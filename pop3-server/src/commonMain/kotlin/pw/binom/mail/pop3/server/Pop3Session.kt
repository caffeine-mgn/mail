package pw.binom.mail.pop3

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.asByteWriteChannel
import io.ktor.utils.io.asSink
import io.ktor.utils.io.asSource
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.io.buffered
import pw.binom.tls.socket.SSLContext
import pw.binom.tls.socket.TlsConnection
import pw.binom.tls.socket.asSink
import pw.binom.tls.socket.asSource
import pw.binom.tls.socket.tlsServer

/**
 * POP3 mailbox.
 *
 * Message numbering is stable for the lifetime of a session: [messages]
 * returns every message in the mailbox in order, and deletion is a mark that
 * only becomes effective on [commit] (called on `QUIT`). [message] returns
 * `null` for an index that is out of range or has been marked deleted.
 */
public interface Pop3Mailbox {
    public fun messages(): List<Pop3Message>
    public fun message(index: Int): Pop3Message?
    public fun size(index: Int): Int
    public fun uidl(index: Int): String
    public fun delete(index: Int)
    public fun reset()
    public fun commit()

    public data class Pop3Message(
        public val id: String,    // POP3 message id (used for UIDL)
        public val body: ByteArray,
    ) {
        public val size: Int get() = body.size
        override fun equals(other: Any?): Boolean = other is Pop3Message && id == other.id && body.contentEquals(other.body)
        override fun hashCode(): Int = id.hashCode() * 31 + body.contentHashCode()
    }
}

public class InMemoryPop3Mailbox(
    initial: List<Pop3Mailbox.Pop3Message> = emptyList(),
) : Pop3Mailbox {
    private val lock = SynchronizedObject()
    private val messages: MutableList<Pop3Mailbox.Pop3Message> = initial.toMutableList()
    private val deleted: MutableSet<Int> = mutableSetOf()

    override fun messages(): List<Pop3Mailbox.Pop3Message> = synchronized(lock) { messages.toList() }

    private fun messageLocked(index: Int): Pop3Mailbox.Pop3Message? {
        if (index < 1 || index > messages.size) return null
        val real = index - 1
        if (real in deleted) return null
        return messages[real]
    }

    override fun message(index: Int): Pop3Mailbox.Pop3Message? = synchronized(lock) { messageLocked(index) }

    override fun size(index: Int): Int = synchronized(lock) { messageLocked(index)?.body?.size ?: 0 }

    override fun uidl(index: Int): String = synchronized(lock) { messageLocked(index)?.id ?: "" }

    override fun delete(index: Int) {
        synchronized(lock) {
            if (index in 1..messages.size) deleted += index - 1
        }
    }

    override fun reset() {
        synchronized(lock) {
            deleted.clear()
        }
    }

    override fun commit() {
        synchronized(lock) {
            if (deleted.isEmpty()) return
            val remaining = messages.filterIndexed { i, _ -> i !in deleted }
            messages.clear()
            messages.addAll(remaining)
            deleted.clear()
        }
    }

    public companion object {
        public fun of(vararg bodies: String): InMemoryPop3Mailbox {
            val messages = bodies.mapIndexed { i, body ->
                Pop3Mailbox.Pop3Message(id = "msg${i + 1}", body = body.encodeToByteArray())
            }
            return InMemoryPop3Mailbox(messages)
        }
    }
}

/**
 * Authenticates a user against a (user, password) table.
 */
public fun interface Pop3Authenticator {
    public fun authenticate(user: String, password: String): Boolean

    public companion object {
        public fun of(table: Map<String, String>): Pop3Authenticator =
            Pop3Authenticator { user, password -> table[user] == password }
    }
}

/**
 * Single POP3 session (one connection).
 */
public class Pop3Session(
    public val socket: Socket,
    private val greeting: String,
    private val mailboxProvider: (String) -> Pop3Mailbox,
    private val auth: Pop3Authenticator,
    private val tls: SSLContext? = null,
) {
    public companion object {
        public const val MAX_LINE_BYTES: Int = 65536
    }

    private lateinit var mailbox: Pop3Mailbox

    private var input: ByteReadChannel = socket.openReadChannel()
    private var output: ByteWriteChannel = socket.openWriteChannel(autoFlush = true)
    private var tlsConnection: TlsConnection? = null
    private var tlsActive: Boolean = false

    private var user: String? = null
    private var authenticated = false

    public suspend fun run() {
        try {
            sendOk(greeting)
            while (true) {
                val line = input.readUTF8Line(MAX_LINE_BYTES) ?: break
                if (line.isEmpty()) continue
                val parts = line.split(' ', limit = 2)
                val verb = parts[0].uppercase()
                val arg = parts.getOrNull(1) ?: ""
                val shouldQuit = handle(verb, arg)
                if (shouldQuit) break
            }
        } catch (_: Throwable) {
        } finally {
            try { tlsConnection?.close() } catch (_: Throwable) {}
            try { socket.dispose() } catch (_: Throwable) {}
        }
    }

    private fun capaLines(): List<String> =
        buildList {
            add("USER")
            add("TOP")
            add("UIDL")
            if (tls != null && !tlsActive) add("STLS")
        }

    /**
     * Switches the session from plaintext to TLS after a successful `STLS`
     * (RFC 2595). The next read drives the TLS handshake.
     *
     * NOTE: the `pw.binom.tls` implementation performs blocking I/O, so the
     * session must run on a blocking-capable dispatcher (`Dispatchers.IO`).
     */
    private fun upgradeToTls(config: SSLContext) {
        val source = input.asSource().buffered()
        val sink = output.asSink().buffered()
        val connection = tlsServer(source, sink, config)
        tlsConnection = connection
        input = ByteReadChannel(connection.asSource())
        output = connection.asSink().asByteWriteChannel()
        tlsActive = true
    }

    private suspend fun sendOk(text: String = "") {
        output.writeStringUtf8("+OK${if (text.isEmpty()) "" else " $text"}\r\n")
        output.flush()
    }

    private suspend fun sendErr(text: String = "") {
        output.writeStringUtf8("-ERR${if (text.isEmpty()) "" else " $text"}\r\n")
        output.flush()
    }

    private suspend fun sendLine(line: String) {
        output.writeStringUtf8("$line\r\n")
        output.flush()
    }

    private suspend fun handle(verb: String, arg: String): Boolean {
        if (!authenticated) {
            when (verb) {
                "CAPA" -> {
                    sendOk("capability list follows")
                    for (l in capaLines()) sendLine(l)
                    sendLine(".")
                }
                "STLS" -> {
                    if (tlsActive) {
                        sendErr("TLS already active")
                    } else if (tls == null) {
                        sendErr("STLS not available")
                    } else {
                        sendOk("begin TLS negotiation")
                        upgradeToTls(tls)
                    }
                }
                "USER" -> {
                    user = arg.trim()
                    sendOk("send PASS")
                }
                "PASS" -> {
                    val u = user
                    if (u == null) {
                        sendErr("send USER first")
                    } else if (auth.authenticate(u, arg)) {
                        mailbox = mailboxProvider(u)
                        authenticated = true
                        sendOk("mailbox locked and ready")
                    } else {
                        sendErr("invalid credentials")
                    }
                }
                "QUIT" -> {
                    sendOk("bye")
                    return true
                }
                else -> sendErr("login required")
            }
            return false
        }
        // Authenticated
        return when (verb) {
            "STAT" -> {
                var count = 0
                var total = 0
                for (i in 1..mailbox.messages().size) {
                    val m = mailbox.message(i) ?: continue
                    count++
                    total += m.size
                }
                sendOk("$count $total")
                false
            }
            "LIST" -> {
                if (arg.isEmpty()) {
                    var count = 0
                    for (i in 1..mailbox.messages().size) if (mailbox.message(i) != null) count++
                    sendOk("$count messages")
                    for (i in 1..mailbox.messages().size) {
                        val m = mailbox.message(i) ?: continue
                        sendLine("$i ${m.size}")
                    }
                    sendLine(".")
                } else {
                    val n = arg.toIntOrNull()
                    if (n == null) {
                        sendErr("invalid message number")
                    } else {
                        val m = mailbox.message(n)
                        if (m == null) sendErr("no such message") else sendOk("$n ${m.size}")
                    }
                }
                false
            }
            "RETR" -> {
                val n = arg.toIntOrNull()
                if (n == null) {
                    sendErr("invalid message number")
                } else {
                    val m = mailbox.message(n)
                    if (m == null) {
                        sendErr("no such message")
                    } else {
                        sendOk("${m.size} octets")
                        writeMultiline(m.body, Int.MAX_VALUE)
                    }
                }
                false
            }
            "DELE" -> {
                val n = arg.toIntOrNull()
                if (n == null) {
                    sendErr("invalid message number")
                } else {
                    val m = mailbox.message(n)
                    if (m == null) sendErr("no such message") else {
                        mailbox.delete(n)
                        sendOk("message $n deleted")
                    }
                }
                false
            }
            "NOOP" -> { sendOk(); false }
            "RSET" -> { mailbox.reset(); sendOk(); false }
            "QUIT" -> {
                mailbox.commit()
                sendOk("bye")
                return true
            }
            "TOP" -> {
                val parts = arg.split(' ')
                if (parts.size != 2) {
                    sendErr("usage: TOP msg lines")
                } else {
                    val n = parts[0].toIntOrNull()
                    val lines = parts[1].toIntOrNull()
                    if (n == null || lines == null) {
                        sendErr("invalid arguments")
                    } else {
                        val m = mailbox.message(n)
                        if (m == null) {
                            sendErr("no such message")
                        } else {
                            sendOk()
                            writeMultiline(m.body, lines)
                        }
                    }
                }
                false
            }
            "UIDL" -> {
                if (arg.isEmpty()) {
                    sendOk("unique-id listing follows")
                    for (i in 1..mailbox.messages().size) {
                        val m = mailbox.message(i) ?: continue
                        sendLine("$i ${m.id}")
                    }
                    sendLine(".")
                } else {
                    val n = arg.toIntOrNull()
                    if (n == null) {
                        sendErr("invalid message number")
                    } else {
                        val id = mailbox.uidl(n)
                        if (id.isEmpty()) sendErr("no such message") else sendOk("$n $id")
                    }
                }
                false
            }
            "CAPA" -> {
                sendOk("capability list follows")
                for (l in capaLines()) sendLine(l)
                sendLine(".")
                false
            }
            else -> {
                sendErr("unknown command")
                false
            }
        }
    }

    /**
     * Sends a message in POP3 multi-line form: the bytes are dot-stuffed
     * (a leading `.` is doubled) and terminated with `CRLF.CRLF`. When
     * [maxBodyLines] is finite only the headers plus the first
     * [maxBodyLines] body lines are sent (used by TOP).
     */
    private suspend fun writeMultiline(body: ByteArray, maxBodyLines: Int) {
        val payload = if (maxBodyLines == Int.MAX_VALUE) body else topPortion(body, maxBodyLines)
        output.writeFully(dotStuff(payload))
        output.writeStringUtf8(".\r\n")
        output.flush()
    }

    private fun dotStuff(body: ByteArray): ByteArray {
        val out = ArrayList<Byte>(body.size + 16)
        var atLineStart = true
        for (b in body) {
            if (atLineStart && b == '.'.code.toByte()) out += '.'.code.toByte()
            out += b
            atLineStart = b == '\n'.code.toByte()
        }
        if (out.isEmpty() || out.last() != '\n'.code.toByte()) {
            out += '\r'.code.toByte()
            out += '\n'.code.toByte()
        }
        return out.toByteArray()
    }

    /** Headers, the blank separator line, then the first [lines] body lines. */
    private fun topPortion(body: ByteArray, lines: Int): ByteArray {
        val sep = findHeaderEnd(body)
        if (sep < 0) return body
        val out = ArrayList<Byte>(sep + 16)
        for (i in 0 until sep) out += body[i]
        var count = 0
        var i = sep
        while (i < body.size && count < lines) {
            while (i < body.size) {
                val b = body[i]
                out += b
                i++
                if (b == '\n'.code.toByte()) break
            }
            count++
        }
        if (out.isEmpty() || out.last() != '\n'.code.toByte()) {
            out += '\r'.code.toByte()
            out += '\n'.code.toByte()
        }
        return out.toByteArray()
    }

    private fun findHeaderEnd(body: ByteArray): Int {
        var i = 0
        while (i + 3 < body.size) {
            if (body[i] == '\r'.code.toByte() && body[i + 1] == '\n'.code.toByte() &&
                body[i + 2] == '\r'.code.toByte() && body[i + 3] == '\n'.code.toByte()
            ) {
                return i + 4
            }
            i++
        }
        i = 0
        while (i + 1 < body.size) {
            if (body[i] == '\n'.code.toByte() && body[i + 1] == '\n'.code.toByte()) return i + 2
            i++
        }
        return -1
    }
}
