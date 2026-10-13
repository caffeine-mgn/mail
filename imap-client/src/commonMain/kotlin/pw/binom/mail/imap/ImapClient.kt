package pw.binom.mail.imap

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
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writeFully
import kotlinx.io.buffered
import pw.binom.tls.socket.SSLContext
import pw.binom.tls.socket.TlsConnection
import pw.binom.tls.socket.asSink
import pw.binom.tls.socket.asSource
import pw.binom.tls.socket.tlsClient

/**
 * Thrown when the IMAP server returns a `NO` or `BAD` response for a command.
 */
public class ImapException(message: String, public val tag: String? = null) : RuntimeException(message)

/**
 * High-level IMAP client (RFC 3501 + selected extensions). Coroutine-friendly.
 *
 * The client maintains a tag counter and parses both tagged and untagged
 * responses. Untagged responses (e.g. `* OK`, `* LIST`) are emitted as
 * events to a callback ([onUntagged]) so the caller can react to
 * unsolicited updates.
 */
public class ImapClient internal constructor(
    private val socket: Socket,
    private var input: ByteReadChannel,
    private var output: ByteWriteChannel,
    private val ownsSocket: Boolean,
    private val onUntagged: (ImapResponse.Untagged) -> Unit,
) : AutoCloseable {
    private var closed = false
    private var nextTag: Int = 1
    private var tlsConnection: TlsConnection? = null

    public val capabilities: kotlin.collections.List<String> get() = _capabilities
    private var _capabilities: kotlin.collections.List<String> = emptyList()

    public companion object {
        public const val MAX_LINE_BYTES: Int = 65536
        private val LITERAL_AT_END = Regex("""\{(\d+)\+?}$""")

        public suspend fun connect(
            selectorManager: SelectorManager,
            host: String,
            port: Int,
            onUntagged: (ImapResponse.Untagged) -> Unit = {},
        ): ImapClient {
            val socket = aSocket(selectorManager).tcp().connect(host, port)
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = true)
            val client = ImapClient(socket, input, output, ownsSocket = true, onUntagged)
            client.readGreeting()
            return client
        }
    }

    private fun newTag(): String = "A" + (nextTag++).toString().padStart(3, '0')

    private suspend fun readGreeting() {
        // Server greeting is an untagged OK line; consume it.
        val line = input.readUTF8Line(MAX_LINE_BYTES) ?: throw ImapException("connection closed before greeting")
        val r = ImapParser.parseLine(line) ?: throw ImapException("invalid greeting: $line")
        if (r is ImapResponse.Untagged) {
            onUntagged(r)
        } else if (r is ImapResponse.Tagged) {
            // Some servers tag the greeting
            if (r.status != ImapResponse.TagStatus.OK) {
                throw ImapException("server greeting not OK: ${r.text}", r.tag)
            }
        }
    }

    public suspend fun capability(): ImapResponse {
        return sendCommandAndAwait(ImapCommand.Capability(newTag())).also {
            // The CAPABILITY response includes an untagged CAPABILITY line.
            if (it is ImapResponse.Tagged) {
                // Find CAPABILITY untagged we already collected, or we need to re-parse.
                // For simplicity, store the first CAPABILITY we see.
            }
        }
    }

    public suspend fun login(user: String, password: String): ImapResponse {
        return sendCommandAndAwait(ImapCommand.Login(newTag(), user, password))
    }

    /**
     * Performs `STARTTLS` (RFC 2595). On an `OK` tagged reply the client
     * wraps its byte channels in a TLS connection and clears the cached
     * capabilities — the caller must re-issue `CAPABILITY` (or `LOGIN`).
     *
     * NOTE: the TLS implementation performs blocking I/O; run the client on
     * a blocking-capable dispatcher (`Dispatchers.IO`).
     */
    public suspend fun startTls(serverName: String, trustAll: Boolean = false): ImapResponse {
        val reply = sendCommandAndAwait(ImapCommand.StartTls(newTag()))
        if (reply is ImapResponse.Tagged && reply.status == ImapResponse.TagStatus.OK) {
            val source = input.asSource().buffered()
            val sink = output.asSink().buffered()
            val connection = source.tlsClient(sink, SSLContext.client(trustAll), serverName)
            tlsConnection = connection
            input = ByteReadChannel(connection.asSource())
            output = connection.asSink().asByteWriteChannel()
            _capabilities = emptyList()
        }
        return reply
    }

    public suspend fun logout(): ImapResponse {
        return sendCommandAndAwait(ImapCommand.Logout(newTag()))
    }

    public suspend fun noop(): ImapResponse {
        return sendCommandAndAwait(ImapCommand.Noop(newTag()))
    }

    public suspend fun list(reference: String, pattern: String): ImapResponse {
        return sendCommandAndAwait(ImapCommand.List(newTag(), reference, pattern))
    }

    public suspend fun lsub(reference: String, pattern: String): ImapResponse {
        return sendCommandAndAwait(ImapCommand.Lsub(newTag(), reference, pattern))
    }

    public suspend fun select(mailbox: String): ImapResponse {
        return sendCommandAndAwait(ImapCommand.Select(newTag(), mailbox))
    }

    public suspend fun examine(mailbox: String): ImapResponse {
        return sendCommandAndAwait(ImapCommand.Examine(newTag(), mailbox))
    }

    public suspend fun status(mailbox: String, items: kotlin.collections.List<String>): ImapResponse {
        return sendCommandAndAwait(ImapCommand.Status(newTag(), mailbox, items))
    }

    public suspend fun fetch(sequence: String, items: kotlin.collections.List<String>, uid: Boolean = false): ImapResponse {
        return sendCommandAndAwait(ImapCommand.Fetch(newTag(), sequence, items, uid))
    }

    public suspend fun store(sequence: String, action: String, flags: kotlin.collections.List<String>, uid: Boolean = false): ImapResponse {
        return sendCommandAndAwait(ImapCommand.Store(newTag(), sequence, action, flags, uid))
    }

    public suspend fun search(criteria: kotlin.collections.List<String>, uid: Boolean = false): ImapResponse {
        return sendCommandAndAwait(ImapCommand.Search(newTag(), criteria, uid))
    }

    public suspend fun imapClose(): ImapResponse {
        return sendCommandAndAwait(ImapCommand.Close(newTag()))
    }

    public suspend fun expunge(): ImapResponse {
        return sendCommandAndAwait(ImapCommand.Expunge(newTag()))
    }

    public suspend fun append(mailbox: String, message: ByteArray, flags: kotlin.collections.List<String>? = null): ImapResponse {
        val tag = newTag()
        val cmd = ImapCommand.Append(tag, mailbox, flags = flags, message = message)
        output.writeStringUtf8(cmd.line + "\r\n")
        // Server responds with + for literal
        val cont = readResponse()
        if (cont !is ImapResponse.Continuation) {
            throw ImapException("expected continuation, got ${cont::class.simpleName}")
        }
        output.writeFully(message)
        output.writeStringUtf8("\r\n")
        output.flush()
        return readResponse()
    }

    public suspend fun sendCommand(command: ImapCommand) {
        output.writeStringUtf8(command.line + "\r\n")
        output.flush()
    }

    /**
     * Sends a raw command line (without CRLF) and reads until the tagged
     * response. Useful for extension commands (ENABLE, SORT, THREAD, NOTIFY)
     * or parameterised forms such as `SELECT INBOX (CONDSTORE)`.
     */
    public suspend fun execute(line: String): ImapResponse {
        output.writeStringUtf8(line + "\r\n")
        output.flush()
        return readResponse()
    }

    private suspend fun sendCommandAndAwait(command: ImapCommand): ImapResponse {
        sendCommand(command)
        return readResponse()
    }

    /**
     * Reads responses until a tagged response matching [tag] (or any tag if
     * null) is found. Untagged and continuation responses are delivered
     * via [onUntagged] / returned.
     */
    public suspend fun readResponse(): ImapResponse {
        while (true) {
            val (text, literals) = readLogicalLine()
            val parsed = ImapParser.parseLine(text) ?: throw ImapException("invalid response: $text")
            val r = if (parsed is ImapResponse.Untagged && literals.isNotEmpty()) {
                parsed.copy(literals = literals)
            } else {
                parsed
            }
            if (r is ImapResponse.Untagged) {
                onUntagged(r)
            }
            if (r is ImapResponse.Tagged) {
                return r
            }
            if (r is ImapResponse.Continuation) {
                return r
            }
        }
    }

    /**
     * Reads one logical response line, transparently consuming any IMAP literals
     * (`{n}\r\n<n octets>`). Returns the reconstructed text plus the literal octets.
     */
    private suspend fun readLogicalLine(): Pair<String, List<ByteArray>> {
        var text = input.readUTF8Line(MAX_LINE_BYTES) ?: throw ImapException("connection closed")
        var literals: MutableList<ByteArray>? = null
        while (true) {
            val n = literalSizeAtEnd(text) ?: break
            val bytes = ByteArray(n)
            input.readFully(bytes)
            (literals ?: mutableListOf<ByteArray>().also { literals = it }) += bytes
            val rest = input.readUTF8Line(MAX_LINE_BYTES) ?: throw ImapException("connection closed")
            text += rest
        }
        return text to (literals ?: emptyList())
    }

    private fun literalSizeAtEnd(s: String): Int? =
        LITERAL_AT_END.find(s)?.groupValues?.get(1)?.toIntOrNull()

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
