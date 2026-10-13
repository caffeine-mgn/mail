package pw.binom.mail.smtp.server

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.asByteWriteChannel
import io.ktor.utils.io.asSink
import io.ktor.utils.io.asSource
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.io.buffered
import pw.binom.mail.smtp.Reply
import pw.binom.mail.smtp.SmtpCommand
import pw.binom.mail.smtp.SmtpException
import pw.binom.tls.socket.SSLContext
import pw.binom.tls.socket.TlsConnection
import pw.binom.tls.socket.asSink
import pw.binom.tls.socket.asSource
import pw.binom.tls.socket.tlsServer
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.coroutines.CoroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Verifies SMTP AUTH credentials (RFC 4954).
 */
public fun interface SmtpAuthenticator {
    public fun authenticate(user: String, password: String): Boolean

    public companion object {
        public fun of(table: Map<String, String>): SmtpAuthenticator =
            SmtpAuthenticator { user, password -> table[user] == password }
    }
}

/**
 * In-memory mailbox for the SMTP server.
 *
 * Messages are accepted as raw bytes (the DATA section, after de-dotstuffing)
 * and stored verbatim. A real implementation would parse them, but for
 * testing purposes we keep them as bytes.
 */
public interface SmtpMailbox {
    public fun store(from: String, recipients: List<String>, data: ByteArray)
    public fun all(): List<StoredMessage>
    public fun clear()

    public data class StoredMessage(
        val from: String,
        val recipients: List<String>,
        val data: ByteArray,
        val receivedAt: Long = nowMillis(),
    ) {
        override fun equals(other: Any?): Boolean = other is StoredMessage && data.contentEquals(other.data) && from == other.from && recipients == other.recipients
        override fun hashCode(): Int = data.contentHashCode() * 31 + from.hashCode()
    }
}

public class InMemorySmtpMailbox : SmtpMailbox {
    private val lock = SynchronizedObject()
    private val messages = mutableListOf<SmtpMailbox.StoredMessage>()

    override fun store(from: String, recipients: List<String>, data: ByteArray) {
        synchronized(lock) {
            messages += SmtpMailbox.StoredMessage(from, recipients, data.copyOf())
        }
    }

    override fun all(): List<SmtpMailbox.StoredMessage> = synchronized(lock) { messages.toList() }

    override fun clear() {
        synchronized(lock) {
            messages.clear()
        }
    }
}

/**
 * Single SMTP session (one connection). Created and run by [SmtpServer] for
 * each accepted socket.
 *
 * The session implements the state machine from RFC 5321 §4.1:
 *   - initial greeting on connect
 *   - MAIL command starts a transaction (resets from/recipients)
 *   - RCPT adds a recipient
 *   - DATA streams the message
 *   - RSET cancels the transaction
 *   - QUIT closes
 *
 * RFC 3207 STARTTLS is supported when the server was created with a
 * non-null [SSLContext]. After the `220` reply the session wraps its
 * byte channels in a TLS connection (via the `pw.binom.tls` library) and
 * continues over the encrypted transport; the client is then expected to
 * issue a fresh EHLO.
 *
 * NOTE: the underlying TLS implementation performs blocking reads/writes.
 * The session must therefore be run on a dispatcher that may block (e.g.
 * `Dispatchers.IO`), not on a single-threaded event-loop dispatcher.
 *
 * RFC 4954 AUTH is supported with the PLAIN and LOGIN mechanisms when the
 * server was created with a non-null [SmtpAuthenticator]. When [requireAuth]
 * is set, MAIL is rejected with `530` until the client authenticates.
 */
public class SmtpSession(
    public val socket: Socket,
    private val serverGreeting: String,
    private val localDomain: String,
    private val mailbox: SmtpMailbox,
    private val auth: SmtpAuthenticator? = null,
    private val requireAuth: Boolean = false,
    private val tls: SSLContext? = null,
    private val coroutineContext: CoroutineContext,
) {
    public companion object {
        public const val MAX_LINE_BYTES: Int = 65536
        public const val MAX_MESSAGE_BYTES: Int = 50 * 1024 * 1024 // 50 MB
    }

    private var input: ByteReadChannel = socket.openReadChannel()
    private var output: ByteWriteChannel = socket.openWriteChannel(autoFlush = true)
    private var tlsConnection: TlsConnection? = null
    private var tlsActive: Boolean = false

    public suspend fun run() {
        try {
            sendReply(220, "$serverGreeting $localDomain ESMTP ready")
            while (true) {
                val line = input.readUTF8Line(MAX_LINE_BYTES) ?: break
                if (line.isEmpty()) continue
                val command = parseCommand(line) ?: run {
                    sendReply(500, "unrecognized command")
                    continue
                }
                val shouldQuit = handleCommand(command)
                if (shouldQuit) break
            }
        } catch (e: SmtpException) {
            sendReply(421, "service not available: ${e.message}")
        } catch (e: Throwable) {
            try { sendReply(451, "internal error: ${e.message ?: e::class.simpleName}") } catch (_: Throwable) {}
        } finally {
            try { tlsConnection?.close() } catch (_: Throwable) {}
            try { socket.dispose() } catch (_: Throwable) {}
        }
    }

    /**
     * Switches the session from plaintext to TLS after a successful
     * `STARTTLS` (RFC 3207). The next read drives the TLS handshake.
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

    private suspend fun sendReply(code: Int, text: String) {
        output.writeStringUtf8("$code $text\r\n")
        output.flush()
    }

    private suspend fun sendReply(reply: Reply) {
        output.writeStringUtf8("${reply.code}${if (reply.continuing) '-' else ' '}${reply.text}\r\n")
        output.flush()
    }

    private suspend fun sendMultiLineReplies(code: Int, lines: List<String>) {
        for (i in lines.indices) {
            val sep = if (i == lines.lastIndex) ' ' else '-'
            output.writeStringUtf8("$code$sep${lines[i]}\r\n")
        }
        output.flush()
    }

    private fun parseCommand(line: String): SmtpCommand? {
        val parts = line.split(' ', limit = 2)
        val verb = parts[0].uppercase()
        val arg = parts.getOrNull(1) ?: ""
        return when (verb) {
            "HELO" -> SmtpCommand.Helo(arg)
            "EHLO" -> SmtpCommand.Ehlo(arg)
            "MAIL" -> parseMailFrom(arg)
            "RCPT" -> parseRcptTo(arg)
            "DATA" -> SmtpCommand.Data
            "RSET" -> SmtpCommand.Rset
            "QUIT" -> SmtpCommand.Quit
            "NOOP" -> SmtpCommand.Noop
            "VRFY" -> SmtpCommand.Vrfy(arg)
            "EXPN" -> SmtpCommand.Expn(arg)
            "HELP" -> SmtpCommand.Help(arg.ifEmpty { null })
            "STARTTLS" -> SmtpCommand.StartTls
            "AUTH" -> parseAuth(arg)
            else -> null
        }
    }

    private fun parseMailFrom(arg: String): SmtpCommand? {
        if (!arg.startsWith("FROM:", ignoreCase = true)) return null
        val after = arg.substring(5).trim()
        // Extract <address>
        val lt = after.indexOf('<')
        val gt = after.indexOf('>')
        if (lt < 0 || gt < 0 || gt < lt) return null
        val from = after.substring(lt + 1, gt)
        // Parse optional parameters (SIZE=, BODY=, SMTPUTF8)
        var size: Long? = null
        var body: String? = null
        var smtputf8 = false
        val rest = after.substring(gt + 1)
        for (param in rest.split(' ')) {
            if (param.isBlank()) continue
            val eq = param.indexOf('=')
            if (eq < 0) {
                if (param.equals("SMTPUTF8", ignoreCase = true)) smtputf8 = true
                continue
            }
            val key = param.substring(0, eq).uppercase()
            val value = param.substring(eq + 1)
            when (key) {
                "SIZE" -> size = value.toLongOrNull()
                "BODY" -> body = value.uppercase()
                "SMTPUTF8" -> smtputf8 = true
            }
        }
        return SmtpCommand.Mail(from, size, body, smtputf8)
    }

    private fun parseRcptTo(arg: String): SmtpCommand? {
        if (!arg.startsWith("TO:", ignoreCase = true)) return null
        val after = arg.substring(3).trim()
        val lt = after.indexOf('<')
        val gt = after.indexOf('>')
        if (lt < 0 || gt < 0 || gt < lt) return null
        return SmtpCommand.Rcpt(after.substring(lt + 1, gt))
    }

    private fun parseAuth(arg: String): SmtpCommand? {
        val parts = arg.split(' ', limit = 2)
        return SmtpCommand.Auth(parts[0], parts.getOrNull(1))
    }

    // Session state
    private var from: String? = null
    private var recipients: MutableList<String> = mutableListOf()
    private var inDataMode = false
    private var authenticated = false

    private suspend fun handleCommand(command: SmtpCommand): Boolean {
        if (inDataMode && command !is SmtpCommand.Data) {
            // We're collecting DATA; only DATA itself ends the message
            // (this path shouldn't normally trigger because parseCommand reads
            // lines and DATA handling reads content directly)
        }
        return when (command) {
            is SmtpCommand.Helo -> {
                sendReply(250, "$localDomain Hello ${command.domain}")
                from = null
                recipients.clear()
                false
            }
            is SmtpCommand.Ehlo -> {
                val capabilities = mutableListOf(
                    "$localDomain Hello ${command.domain}",
                    "SIZE ${Int.MAX_VALUE}",
                    "8BITMIME",
                    "HELP",
                )
                if (auth != null) capabilities += "AUTH PLAIN LOGIN"
                if (tls != null && !tlsActive) capabilities += "STARTTLS"
                sendMultiLineReplies(250, capabilities)
                from = null
                recipients.clear()
                false
            }
            is SmtpCommand.Mail -> {
                if (requireAuth && !authenticated) {
                    sendReply(530, "5.7.0 Authentication required")
                } else if (from != null) {
                    sendReply(503, "MAIL already in progress; send RSET first")
                } else {
                    from = command.from
                    recipients.clear()
                    sendReply(250, "sender OK")
                }
                false
            }
            is SmtpCommand.Rcpt -> {
                if (from == null) {
                    sendReply(503, "need MAIL before RCPT")
                } else {
                    recipients += command.to
                    sendReply(250, "recipient OK")
                }
                false
            }
            is SmtpCommand.Data -> {
                if (from == null) {
                    sendReply(503, "need MAIL before DATA")
                    return false
                }
                if (recipients.isEmpty()) {
                    sendReply(503, "need RCPT before DATA")
                    return false
                }
                sendReply(354, "end data with <CR><LF>.<CR><LF>")
                val data = readData()
                mailbox.store(from!!, recipients.toList(), data)
                from = null
                recipients.clear()
                sendReply(250, "message accepted")
                false
            }
            is SmtpCommand.Rset -> {
                from = null
                recipients.clear()
                sendReply(250, "reset OK")
                false
            }
            is SmtpCommand.Quit -> {
                sendReply(221, "$localDomain closing")
                true
            }
            is SmtpCommand.Noop -> {
                sendReply(250, "OK")
                false
            }
            is SmtpCommand.Vrfy -> {
                sendReply(252, "cannot VRFY user")
                false
            }
            is SmtpCommand.Expn -> {
                sendReply(252, "cannot EXPN list")
                false
            }
            is SmtpCommand.Help -> {
                sendReply(214, "HELO EHLO MAIL RCPT DATA RSET NOOP QUIT")
                false
            }
            is SmtpCommand.StartTls -> {
                val config = tls
                when {
                    config == null -> sendReply(454, "TLS not available")
                    tlsActive -> sendReply(503, "TLS already active")
                    else -> {
                        sendReply(220, "ready to start TLS")
                        upgradeToTls(config)
                        from = null
                        recipients.clear()
                        authenticated = false
                    }
                }
                false
            }
            is SmtpCommand.Auth -> {
                val authImpl = auth
                if (authImpl == null) {
                    sendReply(502, "AUTH not implemented")
                } else {
                    when (command.mechanism.uppercase()) {
                        "PLAIN" -> handleAuthPlain(authImpl, command.initialResponse)
                        "LOGIN" -> handleAuthLogin(authImpl, command.initialResponse)
                        else -> sendReply(504, "unsupported authentication mechanism")
                    }
                }
                false
            }
            is SmtpCommand.AuthData -> {
                sendReply(503, "no authentication in progress")
                false
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64OrNull(s: String): ByteArray? =
        try {
            Base64.decode(s.trim())
        } catch (_: Throwable) {
            null
        }

    private suspend fun handleAuthPlain(authImpl: SmtpAuthenticator, initial: String?) {
        val encoded = if (!initial.isNullOrEmpty()) {
            initial
        } else {
            sendReply(334, "")
            input.readUTF8Line(MAX_LINE_BYTES) ?: return
        }
        val bytes = decodeBase64OrNull(encoded) ?: run {
            sendReply(501, "5.5.2 invalid base64 data")
            return
        }
        val decoded = bytes.decodeToString()
        val parts = decoded.split('\u0000')
        if (parts.size < 3) {
            sendReply(501, "5.5.2 malformed AUTH PLAIN response")
            return
        }
        finishAuth(authImpl, parts[1], parts[2])
    }

    private suspend fun handleAuthLogin(authImpl: SmtpAuthenticator, initial: String?) {
        val user: String? = if (!initial.isNullOrEmpty()) {
            decodeBase64OrNull(initial)?.decodeToString()
        } else {
            sendReply(334, "VXNlcm5hbWU6") // "Username:"
            input.readUTF8Line(MAX_LINE_BYTES)?.let { decodeBase64OrNull(it)?.decodeToString() }
        }
        if (user == null) {
            sendReply(501, "5.5.2 invalid username")
            return
        }
        sendReply(334, "UGFzc3dvcmQ6") // "Password:"
        val passLine = input.readUTF8Line(MAX_LINE_BYTES) ?: return
        val pass = decodeBase64OrNull(passLine)?.decodeToString()
        if (pass == null) {
            sendReply(501, "5.5.2 invalid password")
            return
        }
        finishAuth(authImpl, user, pass)
    }

    private suspend fun finishAuth(authImpl: SmtpAuthenticator, user: String, password: String) {
        if (authImpl.authenticate(user, password)) {
            authenticated = true
            sendReply(235, "2.7.0 Authentication successful")
        } else {
            sendReply(535, "5.7.8 Authentication credentials invalid")
        }
    }

    private suspend fun readData(): ByteArray {
        val out = ArrayList<Byte>()
        var totalBytes = 0
        var atLineStart = true
        while (true) {
            val line = input.readUTF8Line(MAX_LINE_BYTES) ?: break
            if (atLineStart && line == ".") {
                break
            }
            if (atLineStart && line.startsWith("..")) {
                out.addAll(line.substring(1).encodeToByteArray().toList())
            } else {
                out.addAll(line.encodeToByteArray().toList())
            }
            out += '\r'.code.toByte()
            out += '\n'.code.toByte()
            totalBytes += line.length + 2
            if (totalBytes > MAX_MESSAGE_BYTES) {
                throw SmtpException("message exceeds maximum size")
            }
            atLineStart = true
        }
        return out.toByteArray()
    }
}
