package pw.binom.mail.pop3

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
import io.ktor.utils.io.writeStringUtf8
import kotlinx.io.buffered
import pw.binom.tls.socket.SSLContext
import pw.binom.tls.socket.TlsConnection
import pw.binom.tls.socket.asSink
import pw.binom.tls.socket.asSource
import pw.binom.tls.socket.tlsClient

/**
 * Thrown when the POP3 server returns a `-ERR` response.
 */
public class Pop3Exception(message: String) : RuntimeException(message)

/**
 * High-level POP3 client (RFC 1939). Coroutine-friendly.
 */
public class Pop3Client internal constructor(
    private val socket: Socket,
    private var input: ByteReadChannel,
    private var output: ByteWriteChannel,
    private val ownsSocket: Boolean,
) : AutoCloseable {
    private var closed = false
    private var tlsConnection: TlsConnection? = null

    public companion object {
        public const val MAX_LINE_BYTES: Int = 65536

        public suspend fun connect(
            selectorManager: SelectorManager,
            host: String,
            port: Int,
        ): Pop3Client {
            val socket = aSocket(selectorManager).tcp().connect(host, port)
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = true)
            val client = Pop3Client(socket, input, output, ownsSocket = true)
            client.readGreeting()
            return client
        }
    }

    private suspend fun readGreeting() {
        val first = readLineBlocking() ?: throw Pop3Exception("connection closed before greeting")
        val r = Pop3Parser.parseStatusLine(first)
            ?: throw Pop3Exception("invalid greeting: $first")
        if (!r.ok) throw Pop3Exception("server rejected connection: ${r.text}")
    }

    public suspend fun user(name: String): Pop3Response {
        sendCommand(Pop3Command.User(name))
        return readSingleResponse()
    }

    public suspend fun pass(password: String): Pop3Response {
        sendCommand(Pop3Command.Pass(password))
        return readSingleResponse()
    }

    public suspend fun stat(): Pop3Response {
        sendCommand(Pop3Command.Stat)
        return readSingleResponse()
    }

    /** Returns the response — for LIST without args, the body has one "n size" per line. */
    public suspend fun list(msg: Int? = null): Pop3Response {
        sendCommand(Pop3Command.List(msg))
        return if (msg == null) readMultiResponse() else readSingleResponse()
    }

    public suspend fun retr(msg: Int): Pop3Response {
        sendCommand(Pop3Command.Retr(msg))
        return readMultiResponse()
    }

    public suspend fun dele(msg: Int): Pop3Response {
        sendCommand(Pop3Command.Dele(msg))
        return readSingleResponse()
    }

    public suspend fun noop(): Pop3Response {
        sendCommand(Pop3Command.Noop)
        return readSingleResponse()
    }

    public suspend fun rset(): Pop3Response {
        sendCommand(Pop3Command.Rset)
        return readSingleResponse()
    }

    public suspend fun quit(): Pop3Response {
        sendCommand(Pop3Command.Quit)
        val r = try { readSingleResponse() } catch (_: Exception) { Pop3Response.ok("Bye") }
        close()
        return r
    }

    public suspend fun top(msg: Int, lines: Int): Pop3Response {
        sendCommand(Pop3Command.Top(msg, lines))
        return readMultiResponse()
    }

    public suspend fun uidl(msg: Int? = null): Pop3Response {
        sendCommand(Pop3Command.Uidl(msg))
        return if (msg == null) readMultiResponse() else readSingleResponse()
    }

    public suspend fun capa(): Pop3Response {
        sendCommand(Pop3Command.Capa)
        return readMultiResponse()
    }

    /**
     * Performs `STLS` (RFC 2595). On `+OK` the client wraps its byte channels
     * in a TLS connection; the caller must then authenticate (USER/PASS) over
     * the encrypted channel.
     *
     * NOTE: TLS I/O is blocking — run the client on `Dispatchers.IO`.
     */
    public suspend fun stls(serverName: String, trustAll: Boolean = false): Pop3Response {
        sendCommand(Pop3Command.Stls)
        val response = readSingleResponse()
        if (response is Pop3Response.Single && response.ok) {
            val source = input.asSource().buffered()
            val sink = output.asSink().buffered()
            val connection = source.tlsClient(sink, SSLContext.client(trustAll), serverName)
            tlsConnection = connection
            input = ByteReadChannel(connection.asSource())
            output = connection.asSink().asByteWriteChannel()
        }
        return response
    }

    public suspend fun sendCommand(command: Pop3Command) {
        output.writeStringUtf8(command.line + "\r\n")
        output.flush()
    }

    private suspend fun readSingleResponse(): Pop3Response {
        val line = readLineBlocking() ?: throw Pop3Exception("connection closed")
        return Pop3Parser.parseStatusLine(line)
            ?: throw Pop3Exception("invalid response: $line")
    }

    private suspend fun readMultiResponse(): Pop3Response {
        val status = readSingleResponse()
        if (status !is Pop3Response.Single) return status
        if (!status.ok) return status
        val body = mutableListOf<String>()
        while (true) {
            val line = readLineBlocking() ?: throw Pop3Exception("connection closed in multi-line response")
            if (line == ".") return Pop3Response.Multi(true, body)
            // POP3 byte-stuffing: ".." -> "."
            val unescaped = if (line.startsWith("..")) line.substring(1) else line
            body += unescaped
        }
    }

    private suspend fun readLineBlocking(): String? = input.readUTF8Line(MAX_LINE_BYTES)

    override fun close() {
        if (closed) return
        closed = true
        try { tlsConnection?.close() } catch (_: Throwable) {}
        try { output.close() } catch (_: Throwable) {}
        try { input.cancel() } catch (_: Throwable) {}
        if (ownsSocket) {
            try { socket.dispose() } catch (_: Throwable) {}
        }
    }
}
