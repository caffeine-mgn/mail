package pw.binom.mail.smtp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.asByteWriteChannel
import io.ktor.utils.io.asSink
import io.ktor.utils.io.asSource
import io.ktor.utils.io.cancel
import io.ktor.utils.io.close
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.io.buffered
import pw.binom.tls.socket.SSLContext
import pw.binom.tls.socket.TlsConnection
import pw.binom.tls.socket.asSink
import pw.binom.tls.socket.asSource
import pw.binom.tls.socket.tlsClient

/**
 * High-level SMTP client (RFC 5321). Coroutine-friendly.
 *
 * The client speaks plaintext SMTP. [startTls] performs the full STARTTLS
 * upgrade (RFC 3207): it sends `STARTTLS`, and on a positive reply wraps the
 * byte channels in a TLS connection via the `pw.binom.tls` library. The
 * caller must re-issue EHLO afterwards.
 *
 * NOTE: TLS reads/writes are blocking; drive the client from a dispatcher
 * that may block (e.g. `Dispatchers.IO`).
 */
public class SmtpClient internal constructor(
    private val socket: Socket,
    private var inputChannel: ByteReadChannel,
    private var outputChannel: ByteWriteChannel,
    private val ownsSocket: Boolean,
) : AutoCloseable {
    private var closed = false
    private var tlsConnection: TlsConnection? = null

    public val capabilities: EhloCapabilities get() = _capabilities ?: EhloCapabilities.EMPTY
    private var _capabilities: EhloCapabilities? = null

    public companion object {
        public const val MAX_LINE_BYTES: Int = 65536
        private val CRLF_DOT_CRLF = "\r\n.\r\n".encodeToByteArray()

        /**
         * Connects to [host]:[port] and reads the server greeting.
         *
         * @throws SmtpException if the greeting is not a 2xx response.
         */
        public suspend fun connect(
            selectorManager: SelectorManager,
            host: String,
            port: Int,
        ): SmtpClient {
            val socket = aSocket(selectorManager).tcp().connect(host, port)
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = true)
            val client = SmtpClient(socket, input, output, ownsSocket = true)
            client.readGreeting()
            return client
        }

        /**
         * Wraps an existing socket. The caller retains ownership; [close]
         * will only close the channels, not the socket.
         */
        public fun wrap(socket: Socket): SmtpClient {
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = true)
            return SmtpClient(socket, input, output, ownsSocket = false)
        }
    }

    private suspend fun readGreeting() {
        val lines = mutableListOf<Reply>()
        while (true) {
            val line = inputChannel.readUTF8Line(MAX_LINE_BYTES) ?: throw SmtpException("connection closed before greeting")
            val r = SmtpParser.parseLine(line) ?: continue
            lines += r
            if (!r.continuing) break
        }
        val response = SmtpResponse(lines)
        if (!response.isPositive) {
            throw SmtpException("server greeting not 2xx: ${response.code} ${response.text}", response.code)
        }
    }

    /** Sends EHLO and returns the advertised capabilities. */
    public suspend fun ehlo(domain: String): EhloCapabilities {
        sendCommand(SmtpCommand.Ehlo(domain))
        val response = readResponse()
        if (!response.isPositive) {
            sendCommand(SmtpCommand.Helo(domain))
            val heloResponse = readResponse()
            if (!heloResponse.isPositive) {
                throw SmtpException("EHLO/HELO failed: ${response.code} ${response.text}", response.code)
            }
            _capabilities = EhloCapabilities.EMPTY
            return EhloCapabilities.EMPTY
        }
        val caps = EhloCapabilities.parse(response)
        _capabilities = caps
        return caps
    }

    /** Sends MAIL FROM. */
    public suspend fun mail(from: String, size: Long? = null, body: String? = null, smtputf8: Boolean = false): SmtpResponse {
        sendCommand(SmtpCommand.Mail(from, size, body, smtputf8))
        return readResponse()
    }

    /** Sends RCPT TO. May be called multiple times for multiple recipients. */
    public suspend fun rcpt(to: String): SmtpResponse {
        sendCommand(SmtpCommand.Rcpt(to))
        return readResponse()
    }

    /**
     * Sends DATA followed by [data] and reads the server's response.
     *
     * [data] must NOT include the terminating `.\r\n` — it is added by this
     * method. Lines starting with `.` are de-stuffed per RFC 5321 §4.5.2.
     */
    public suspend fun data(data: ByteArray): SmtpResponse {
        sendCommand(SmtpCommand.Data)
        val interim = readResponse()
        if (!interim.isIntermediate) {
            return interim
        }
        writeData(data)
        outputChannel.writeFully(CRLF_DOT_CRLF)
        outputChannel.flush()
        return readResponse()
    }

    public suspend fun data(content: String): SmtpResponse = data(content.encodeToByteArray())

    public suspend fun rset(): SmtpResponse {
        sendCommand(SmtpCommand.Rset)
        return readResponse()
    }

    public suspend fun noop(): SmtpResponse {
        sendCommand(SmtpCommand.Noop)
        return readResponse()
    }

    public suspend fun quit(): SmtpResponse {
        sendCommand(SmtpCommand.Quit)
        val response = try { readResponse() } catch (_: Exception) { SmtpResponse.single(221, "Bye") }
        close()
        return response
    }

    /**
     * Performs a full STARTTLS upgrade (RFC 3207).
     *
     * Sends `STARTTLS`; on a `220` reply wraps the current byte channels in a
     * TLS connection. The caller must send a fresh EHLO afterwards — the
     * capability cache is cleared.
     *
     * @param serverName name sent as SNI and used for certificate verification.
     * @param trustAll when true, accepts any server certificate (testing only).
     * @return the server's reply to `STARTTLS`. If it is not positive, the
     *   connection is left in plaintext and no upgrade happens.
     */
    public suspend fun startTls(serverName: String, trustAll: Boolean = false): SmtpResponse {
        sendCommand(SmtpCommand.StartTls)
        val response = readResponse()
        if (!response.isPositive) return response
        val source = inputChannel.asSource().buffered()
        val sink = outputChannel.asSink().buffered()
        val connection = source.tlsClient(sink, SSLContext.client(trustAll), serverName)
        tlsConnection = connection
        inputChannel = ByteReadChannel(connection.asSource())
        outputChannel = connection.asSink().asByteWriteChannel()
        _capabilities = null
        return response
    }

    public suspend fun auth(mechanism: String, initialResponse: String? = null): SmtpResponse {
        sendCommand(SmtpCommand.Auth(mechanism, initialResponse))
        return readResponse()
    }

    public suspend fun authData(data: String): SmtpResponse {
        sendCommand(SmtpCommand.AuthData(data))
        return readResponse()
    }

    public suspend fun sendCommand(command: SmtpCommand) {
        outputChannel.writeStringUtf8(command.line + "\r\n")
        outputChannel.flush()
    }

    /** Reads a complete server response, possibly spanning multiple lines. */
    public suspend fun readResponse(): SmtpResponse {
        val lines = mutableListOf<Reply>()
        while (true) {
            val line = inputChannel.readUTF8Line(MAX_LINE_BYTES) ?: throw SmtpException("connection closed unexpectedly")
            val r = SmtpParser.parseLine(line)
                ?: throw SmtpException("invalid SMTP response: $line")
            lines += r
            if (!r.continuing) break
        }
        return SmtpResponse(lines)
    }

    private suspend fun writeData(data: ByteArray) {
        var i = 0
        while (i < data.size) {
            val nl = findLineEnd(data, i)
            val atLineStart = i == 0 || data[i - 1] == '\n'.code.toByte()
            if (atLineStart && data[i] == '.'.code.toByte()) {
                outputChannel.writeByte('.'.code.toByte())
            }
            outputChannel.writeFully(data, i, nl)
            i = nl
        }
    }

    private fun findLineEnd(data: ByteArray, from: Int): Int {
        var i = from
        while (i < data.size) {
            if (data[i] == '\n'.code.toByte()) return i + 1
            i++
        }
        return data.size
    }

    override fun close() {
        if (closed) return
        closed = true
        try { tlsConnection?.close() } catch (_: Throwable) {}
        try { outputChannel.close() } catch (_: Throwable) {}
        try { inputChannel.cancel() } catch (_: Throwable) {}
        if (ownsSocket) {
            try { socket.dispose() } catch (_: Throwable) {}
        }
    }
}
