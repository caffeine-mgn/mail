package pw.binom.mail.imap

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.asByteWriteChannel
import io.ktor.utils.io.asSink
import io.ktor.utils.io.asSource
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.io.buffered
import pw.binom.mail.mime.MimeMessage
import pw.binom.mail.mime.MimeParser
import pw.binom.mail.mime.MimeWriter
import pw.binom.tls.socket.SSLContext
import pw.binom.tls.socket.TlsConnection
import pw.binom.tls.socket.asSink
import pw.binom.tls.socket.asSource
import pw.binom.tls.socket.tlsServer

/**
 * IMAP mailbox abstraction implemented by a message store.
 */
public interface ImapMailbox {
    public fun name(): String
    public fun exists(): Int
    public fun recent(): Int
    public fun uidNext(): Long
    public fun uidValidity(): Long
    public fun flags(): kotlin.collections.List<String>
    public fun permanentFlags(): kotlin.collections.List<String>
    public fun message(seq: Int): ImapMessage?
    public fun append(message: ByteArray, flags: kotlin.collections.List<String>): Long
    public fun setFlags(seq: Int, action: String, flags: kotlin.collections.List<String>)
    public fun expunge(): kotlin.collections.List<Int>
    public fun all(): kotlin.collections.List<ImapMessage>

    /** CONDSTORE (RFC 7162): mailbox-wide highest modification sequence. */
    public fun highestModSeq(): Long = 0L

    /** CONDSTORE: modification sequence of the message at [seq], or 0 when unknown. */
    public fun modSeq(seq: Int): Long = 0L

    /** Looks a message up by UID (RFC 7162 / QRESYNC need UID addressing). */
    public fun messageByUid(uid: Long): ImapMessage? = all().firstOrNull { it.uid == uid }

    /**
     * QRESYNC (RFC 7162): UIDs expunged after [modSeq], used for `VANISHED (EARLIER)`.
     */
    public fun vanishedSince(modSeq: Long): kotlin.collections.List<Long> = emptyList()

    public data class ImapMessage(
        val uid: Long,
        val sequence: Int,
        val body: ByteArray,
        val flags: kotlin.collections.List<String>,
        val internalDate: Long = 0L,
        val modSeq: Long = 0L,
    ) {
        val size: Int get() = body.size
        override fun equals(other: Any?): Boolean = other is ImapMessage && uid == other.uid && body.contentEquals(other.body)
        override fun hashCode(): Int = uid.hashCode() * 31 + body.contentHashCode()
    }
}

/**
 * Authenticates a user against a (user, password) table.
 */
public fun interface ImapAuthenticator {
    public fun authenticate(user: String, password: String): Boolean

    public companion object {
        public fun of(table: Map<String, String>): ImapAuthenticator =
            ImapAuthenticator { user, password -> table[user] == password }
    }
}

public enum class ImapState { NOT_AUTHENTICATED, AUTHENTICATED, SELECTED, LOGOUT }

/**
 * Single IMAP session (one connection).
 *
 * Implements RFC 3501 (IMAP4rev1): CAPABILITY, NOOP, LOGOUT, STARTTLS (RFC 2595),
 * LOGIN, AUTHENTICATE (PLAIN/LOGIN), LIST, LSUB, SELECT, EXAMINE, CREATE, DELETE,
 * RENAME, SUBSCRIBE, UNSUBSCRIBE, STATUS, APPEND, CHECK, CLOSE, EXPUNGE, SEARCH,
 * FETCH, STORE, COPY and the `UID` variants of FETCH/STORE/SEARCH/COPY/EXPUNGE;
 * plus IDLE (RFC 2177).
 *
 * FETCH supports UID, FLAGS, RFC822.SIZE, INTERNALDATE, ENVELOPE, BODYSTRUCTURE,
 * BODY / BODY[<section>] / BODY.PEEK[<section>], RFC822 / RFC822.HEADER /
 * RFC822.TEXT, emitted with proper `{n}` literals. Body sections are backed by
 * [MimeParser]/[MimeWriter] from the `mime` module.
 *
 * Extensions: ENABLE (RFC 5161), CONDSTORE / QRESYNC (RFC 7162), SORT / THREAD
 * (RFC 5256) and NOTIFY (RFC 5465).
 */
public class ImapSession(
    public val socket: Socket,
    private val greeting: String,
    private val authenticator: ImapAuthenticator,
    private val getMailbox: (String, String) -> ImapMailbox?,
    private val userMailboxes: (String) -> kotlin.collections.List<String> = { emptyList() },
    private val tls: SSLContext? = null,
) {
    public companion object {
        public const val MAX_LINE_BYTES: Int = 65536
        private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    }

    private var input: ByteReadChannel = socket.openReadChannel()
    private var output: ByteWriteChannel = socket.openWriteChannel(autoFlush = true)
    private var tlsConnection: TlsConnection? = null
    private var tlsActive: Boolean = false
    private var state: ImapState = ImapState.NOT_AUTHENTICATED
    private var user: String? = null
    private var selectedMailbox: ImapMailbox? = null
    private var condstoreEnabled: Boolean = false
    private var qresyncEnabled: Boolean = false
    private var notifySpec: NotifySpec? = null
    private val notifyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notifyJob: Job? = null
    private val outputLock = Mutex()

    public suspend fun run() {
        try {
            sendUntagged("OK", "[CAPABILITY ${capabilityList()}] $greeting")
            while (state != ImapState.LOGOUT) {
                val line = input.readUTF8Line(MAX_LINE_BYTES) ?: break
                if (line.isEmpty()) continue
                handle(line)
            }
        } catch (_: Throwable) {
        } finally {
            notifyJob?.cancel()
            notifyScope.cancel()
            try { tlsConnection?.close() } catch (_: Throwable) {}
            try { socket.dispose() } catch (_: Throwable) {}
        }
    }

    private fun capabilityList(): String = buildString {
        append("IMAP4rev1 IDLE CONDSTORE QRESYNC SORT THREAD=ORDEREDSUBJECT THREAD=REFERENCES ENABLE NOTIFY")
        if (tls != null && !tlsActive && state == ImapState.NOT_AUTHENTICATED) append(" STARTTLS")
        if (state == ImapState.NOT_AUTHENTICATED) append(" AUTH=PLAIN AUTH=LOGIN")
    }

    /**
     * Switches the session from plaintext to TLS after a successful `STARTTLS`
     * (RFC 2595). The next read drives the TLS handshake.
     *
     * NOTE: `pw.binom.tls` performs blocking reads/writes, so the session must
     * run on a dispatcher that may block (`Dispatchers.IO`).
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

    private suspend fun sendUntagged(status: String, text: String) = outputLock.withLock {
        output.writeStringUtf8("* $status $text\r\n")
        output.flush()
    }

    private suspend fun sendTagged(tag: String, status: ImapResponse.TagStatus, text: String, code: String? = null) = outputLock.withLock {
        val codePart = if (code != null) "[$code] " else ""
        output.writeStringUtf8("$tag $status $codePart$text\r\n")
        output.flush()
    }

    private suspend fun sendContinuation(text: String = "") = outputLock.withLock {
        output.writeStringUtf8("+ $text\r\n")
        output.flush()
    }

    private suspend fun handle(line: String) {
        val parts = splitCommand(line)
        val tag = parts[0]
        var verb = parts[1].uppercase()
        var arg = parts[2]
        var uidMode = false
        if (verb == "UID") {
            uidMode = true
            val sp = arg.indexOf(' ')
            verb = (if (sp < 0) arg else arg.substring(0, sp)).uppercase()
            arg = if (sp < 0) "" else arg.substring(sp + 1)
        }

        when (verb) {
            "CAPABILITY" -> {
                sendUntagged("CAPABILITY", capabilityList())
                sendTagged(tag, ImapResponse.TagStatus.OK, "CAPABILITY completed")
            }
            "STARTTLS" -> {
                if (tlsActive) { sendTagged(tag, ImapResponse.TagStatus.NO, "TLS already active"); return }
                if (tls == null) { sendTagged(tag, ImapResponse.TagStatus.NO, "STARTTLS not available"); return }
                if (state != ImapState.NOT_AUTHENTICATED) {
                    sendTagged(tag, ImapResponse.TagStatus.NO, "STARTTLS only allowed before authentication"); return
                }
                sendTagged(tag, ImapResponse.TagStatus.OK, "Begin TLS negotiation now")
                upgradeToTls(tls)
            }
            "LOGIN" -> {
                if (state != ImapState.NOT_AUTHENTICATED) {
                    sendTagged(tag, ImapResponse.TagStatus.NO, "already authenticated"); return
                }
                val creds = parseLogin(arg)
                if (creds == null) {
                    sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid LOGIN syntax"); return
                }
                completeLogin(tag, creds.first, creds.second)
            }
            "AUTHENTICATE" -> handleAuthenticate(tag, arg)
            "LOGOUT" -> {
                sendUntagged("BYE", "server signing off")
                sendTagged(tag, ImapResponse.TagStatus.OK, "LOGOUT completed")
                state = ImapState.LOGOUT
            }
            "NOOP" -> {
                if (state == ImapState.SELECTED) {
                    val mb = selectedMailbox!!
                    sendUntagged("OK", "[CAPABILITY ${capabilityList()}]")
                    sendUntagged("EXISTS", mb.exists().toString())
                    sendUntagged("RECENT", mb.recent().toString())
                }
                sendTagged(tag, ImapResponse.TagStatus.OK, "NOOP completed")
            }
            "LIST", "LSUB" -> {
                if (state == ImapState.NOT_AUTHENTICATED) {
                    sendTagged(tag, ImapResponse.TagStatus.NO, "login required"); return
                }
                val (_, pattern) = parseList(arg) ?: run {
                    sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid $verb syntax"); return
                }
                for (name in listMailboxes(pattern)) {
                    sendUntagged(verb, "(\\HasNoChildren) \"/\" ${q(name)}")
                }
                sendTagged(tag, ImapResponse.TagStatus.OK, "$verb completed")
            }
            "SELECT", "EXAMINE" -> handleSelect(tag, verb, arg)
            "STATUS" -> handleStatus(tag, arg)
            "FETCH" -> handleFetch(tag, arg, uidMode)
            "STORE" -> handleStore(tag, arg, uidMode)
            "COPY" -> handleCopy(tag, arg, uidMode)
            "CHECK" -> {
                if (state != ImapState.SELECTED) { sendTagged(tag, ImapResponse.TagStatus.NO, "no mailbox selected"); return }
                sendTagged(tag, ImapResponse.TagStatus.OK, "CHECK completed")
            }
            "CLOSE" -> {
                if (state == ImapState.SELECTED) selectedMailbox?.expunge()
                state = ImapState.AUTHENTICATED
                selectedMailbox = null
                sendTagged(tag, ImapResponse.TagStatus.OK, "CLOSE completed")
            }
            "EXPUNGE" -> {
                if (state != ImapState.SELECTED) { sendTagged(tag, ImapResponse.TagStatus.NO, "no mailbox selected"); return }
                for (seq in selectedMailbox!!.expunge()) sendUntagged("EXPUNGE", seq.toString())
                sendTagged(tag, ImapResponse.TagStatus.OK, "EXPUNGE completed")
            }
            "SEARCH" -> handleSearch(tag, arg, uidMode)
            "SORT" -> handleSort(tag, arg, uidMode)
            "THREAD" -> handleThread(tag, arg, uidMode)
            "ENABLE" -> handleEnable(tag, arg)
            "NOTIFY" -> handleNotify(tag, arg)
            "IDLE" -> handleIdle(tag)
            "APPEND" -> handleAppend(tag, arg)
            "SUBSCRIBE", "UNSUBSCRIBE" -> {
                if (state == ImapState.NOT_AUTHENTICATED) { sendTagged(tag, ImapResponse.TagStatus.NO, "login required"); return }
                sendTagged(tag, ImapResponse.TagStatus.OK, "$verb completed")
            }
            "CREATE" -> {
                if (state == ImapState.NOT_AUTHENTICATED) { sendTagged(tag, ImapResponse.TagStatus.NO, "login required"); return }
                sendTagged(tag, ImapResponse.TagStatus.OK, "CREATE completed")
            }
            "DELETE" -> {
                if (state == ImapState.NOT_AUTHENTICATED) { sendTagged(tag, ImapResponse.TagStatus.NO, "login required"); return }
                sendTagged(tag, ImapResponse.TagStatus.OK, "DELETE completed")
            }
            "RENAME" -> {
                if (state == ImapState.NOT_AUTHENTICATED) { sendTagged(tag, ImapResponse.TagStatus.NO, "login required"); return }
                sendTagged(tag, ImapResponse.TagStatus.OK, "RENAME completed")
            }
            else -> sendTagged(tag, ImapResponse.TagStatus.BAD, "unknown command: $verb")
        }
    }

    // ---- Authentication -----------------------------------------------------

    private suspend fun completeLogin(tag: String, username: String, password: String) {
        if (authenticator.authenticate(username, password)) {
            user = username
            state = ImapState.AUTHENTICATED
            sendTagged(tag, ImapResponse.TagStatus.OK, "LOGIN completed")
        } else {
            sendTagged(tag, ImapResponse.TagStatus.NO, "authentication failed")
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun handleAuthenticate(tag: String, arg: String) {
        if (state != ImapState.NOT_AUTHENTICATED) {
            sendTagged(tag, ImapResponse.TagStatus.NO, "already authenticated"); return
        }
        val tokens = tokenize(arg)
        if (tokens.isEmpty()) {
            sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid AUTHENTICATE syntax"); return
        }
        val mechanism = tokens[0].uppercase()
        val initial = tokens.getOrNull(1)
        when (mechanism) {
            "PLAIN" -> {
                val encoded = if (initial != null) initial else {
                    sendContinuation("")
                    input.readUTF8Line(MAX_LINE_BYTES) ?: return
                }
                if (encoded == "*") { sendTagged(tag, ImapResponse.TagStatus.BAD, "authentication cancelled"); return }
                val bytes = try { Base64.decode(encoded.trim()) } catch (_: Throwable) {
                    sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid base64"); return
                }
                val f = bytes.decodeToString().split('\u0000')
                if (f.size < 3) { sendTagged(tag, ImapResponse.TagStatus.BAD, "malformed PLAIN response"); return }
                completeLogin(tag, f[1], f[2])
            }
            "LOGIN" -> {
                sendContinuation("VXNlcm5hbWU6")
                val userLine = input.readUTF8Line(MAX_LINE_BYTES) ?: return
                if (userLine == "*") { sendTagged(tag, ImapResponse.TagStatus.BAD, "authentication cancelled"); return }
                sendContinuation("UGFzc3dvcmQ6")
                val passLine = input.readUTF8Line(MAX_LINE_BYTES) ?: return
                if (passLine == "*") { sendTagged(tag, ImapResponse.TagStatus.BAD, "authentication cancelled"); return }
                val u = try { Base64.decode(userLine.trim()).decodeToString() } catch (_: Throwable) {
                    sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid base64"); return
                }
                val p = try { Base64.decode(passLine.trim()).decodeToString() } catch (_: Throwable) {
                    sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid base64"); return
                }
                completeLogin(tag, u, p)
            }
            else -> sendTagged(tag, ImapResponse.TagStatus.NO, "unsupported authentication mechanism")
        }
    }

    // ---- SELECT / EXAMINE / STATUS -----------------------------------------

    private class SelectArgs(
        val name: String,
        val condstore: Boolean,
        val qresync: Boolean,
        val qresyncModeSeq: Long,
        val qresyncKnownUids: kotlin.collections.List<Long>,
    )

    private fun parseSelectArgs(arg: String): SelectArgs? {
        val trimmed = arg.trim()
        if (trimmed.isEmpty()) return null
        val name: String
        var rest: String
        if (trimmed[0] == '"') {
            val sb = StringBuilder()
            var i = 1
            while (i < trimmed.length && trimmed[i] != '"') {
                if (trimmed[i] == '\\' && i + 1 < trimmed.length) { sb.append(trimmed[i + 1]); i += 2 } else { sb.append(trimmed[i]); i++ }
            }
            if (i >= trimmed.length) return null
            name = sb.toString()
            rest = trimmed.substring(i + 1).trim()
        } else {
            val sp = trimmed.indexOf(' ')
            name = if (sp < 0) trimmed else trimmed.substring(0, sp)
            rest = if (sp < 0) "" else trimmed.substring(sp + 1).trim()
        }
        var condstore = false
        var qresync = false
        var qresyncModeSeq = 0L
        var known: kotlin.collections.List<Long> = emptyList()
        if (rest.startsWith("(") && rest.endsWith(")")) {
            val inner = rest.substring(1, rest.length - 1).trim()
            val up = inner.uppercase()
            condstore = Regex("\\bCONDSTORE\\b").containsMatchIn(up)
            val qi = up.indexOf("QRESYNC")
            if (qi >= 0) {
                qresync = true
                val after = inner.substring(qi + "QRESYNC".length).trim()
                val open = after.indexOf('(')
                val close = after.lastIndexOf(')')
                if (open in 0 until close) {
                    val nums = after.substring(open + 1, close).split(' ').filter { it.isNotEmpty() }
                    qresyncModeSeq = nums.getOrNull(1)?.toLongOrNull() ?: 0L
                    known = nums.getOrNull(2)?.let { expandUidSet(it, Long.MAX_VALUE) } ?: emptyList()
                }
            }
        }
        return SelectArgs(name, condstore, qresync, qresyncModeSeq, known)
    }

    private suspend fun handleSelect(tag: String, verb: String, arg: String) {
        if (state == ImapState.NOT_AUTHENTICATED) { sendTagged(tag, ImapResponse.TagStatus.NO, "login required"); return }
        val args = parseSelectArgs(arg) ?: run {
            sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid $verb syntax"); return
        }
        val mb = getMailbox(user!!, args.name) ?: run {
            sendTagged(tag, ImapResponse.TagStatus.NO, "mailbox not found"); return
        }
        if (args.qresync && !qresyncEnabled) {
            sendTagged(tag, ImapResponse.TagStatus.NO, "QRESYNC not enabled"); return
        }
        if (args.condstore || args.qresync) condstoreEnabled = true
        selectedMailbox = mb
        state = ImapState.SELECTED
        val unseen = countUnseen(mb)
        sendUntagged("FLAGS", "(${mb.flags().joinToString(" ")})")
        sendUntagged("OK", "[PERMANENTFLAGS (${mb.permanentFlags().joinToString(" ")})]")
        if (args.qresync) {
            val known = args.qresyncKnownUids
            val vanished = mb.vanishedSince(args.qresyncModeSeq).filter { known.isEmpty() || it in known }
            if (vanished.isNotEmpty()) sendUntagged("VANISHED", "(EARLIER) ${vanished.joinToString(",")}")
        }
        sendUntagged("OK", "[UIDVALIDITY ${mb.uidValidity()}]")
        sendUntagged("OK", "[UIDNEXT ${mb.uidNext()}]")
        sendUntagged("OK", "[UNSEEN $unseen]")
        if (condstoreEnabled) sendUntagged("OK", "[HIGHESTMODSEQ ${mb.highestModSeq()}]")
        if (args.qresync) {
            for (seq in 1..mb.exists()) {
                val m = mb.message(seq) ?: continue
                if (m.modSeq > args.qresyncModeSeq) {
                    sendUntagged("FETCH", "$seq (UID ${m.uid} FLAGS (${m.flags.joinToString(" ")}) MODSEQ (${m.modSeq}))")
                }
            }
        }
        sendUntagged("EXISTS", mb.exists().toString())
        sendUntagged("RECENT", mb.recent().toString())
        val readWrite = if (verb == "SELECT") "READ-WRITE" else "READ-ONLY"
        sendTagged(tag, ImapResponse.TagStatus.OK, "$verb completed", code = readWrite)
    }

    private fun countUnseen(mb: ImapMailbox): Int {
        var n = 0
        for (m in mb.all()) if (m.flags.none { it.equals("\\Seen", ignoreCase = true) }) n++
        return n
    }

    private suspend fun handleStatus(tag: String, arg: String) {
        if (state == ImapState.NOT_AUTHENTICATED) { sendTagged(tag, ImapResponse.TagStatus.NO, "login required"); return }
        val tokens = tokenize(arg)
        if (tokens.size < 2) { sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid STATUS syntax"); return }
        val mailbox = tokens[0]
        val items = tokens.drop(1).joinToString(" ").removePrefix("(").removeSuffix(")")
            .split(' ').filter { it.isNotEmpty() }
        val mb = getMailbox(user!!, mailbox) ?: run {
            sendTagged(tag, ImapResponse.TagStatus.NO, "mailbox not found"); return
        }
        val sb = StringBuilder("(")
        for ((i, item) in items.withIndex()) {
            if (i > 0) sb.append(' ')
            sb.append(item).append(' ').append(
                when (item.uppercase()) {
                    "MESSAGES" -> mb.exists()
                    "RECENT" -> mb.recent()
                    "UIDNEXT" -> mb.uidNext()
                    "UIDVALIDITY" -> mb.uidValidity()
                    "UNSEEN" -> countUnseen(mb)
                    else -> 0
                },
            )
        }
        sb.append(')')
        sendUntagged("STATUS", "${q(mailbox)} $sb")
        sendTagged(tag, ImapResponse.TagStatus.OK, "STATUS completed")
    }

    // ---- FETCH --------------------------------------------------------------

    private class FetchArgs(
        val set: String,
        val items: kotlin.collections.List<String>,
        val changedSince: Long?,
    )

    private suspend fun handleFetch(tag: String, arg: String, uidMode: Boolean) {
        if (state != ImapState.SELECTED) { sendTagged(tag, ImapResponse.TagStatus.NO, "no mailbox selected"); return }
        val parsed = parseFetch(arg) ?: run {
            sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid FETCH syntax"); return
        }
        val mb = selectedMailbox!!
        val seqs = if (uidMode) expandUid(parsed.set, mb) else expandSequence(parsed.set, mb.exists())
        val items = if (parsed.changedSince != null && parsed.items.none { it.equals("MODSEQ", ignoreCase = true) }) {
            parsed.items + "MODSEQ"
        } else {
            parsed.items
        }
        for (seq in seqs) {
            val msg = mb.message(seq) ?: continue
            if (parsed.changedSince != null && msg.modSeq <= parsed.changedSince) continue
            sendFetch(seq, msg, items, uidMode)
        }
        sendTagged(tag, ImapResponse.TagStatus.OK, "FETCH completed")
    }

    private suspend fun sendFetch(seq: Int, msg: ImapMailbox.ImapMessage, items: kotlin.collections.List<String>, uidMode: Boolean) =
        outputLock.withLock {
            output.writeStringUtf8("* $seq FETCH (")
            var first = true
            for (raw in items) {
                if (!first) output.writeStringUtf8(" ")
                first = false
                val item = raw.uppercase()
                when {
                    item == "UID" -> output.writeStringUtf8("UID ${msg.uid}")
                    item == "FLAGS" -> output.writeStringUtf8("FLAGS (${msg.flags.joinToString(" ")})")
                    item == "RFC822.SIZE" -> output.writeStringUtf8("RFC822.SIZE ${msg.size}")
                    item == "INTERNALDATE" -> output.writeStringUtf8("INTERNALDATE \"${formatInternalDate(msg.internalDate)}\"")
                    item == "MODSEQ" -> output.writeStringUtf8("MODSEQ (${msg.modSeq})")
                    item == "ENVELOPE" -> output.writeStringUtf8("ENVELOPE ${envelope(msg)}")
                    item == "BODYSTRUCTURE" -> output.writeStringUtf8("BODYSTRUCTURE ${bodyStructure(msg)}")
                    item.startsWith("BODY") || item.startsWith("RFC822") -> fetchBody(msg, raw, item)
                    else -> output.writeStringUtf8(raw)
                }
            }
            output.writeStringUtf8(")\r\n")
            output.flush()
        }

    private suspend fun fetchBody(msg: ImapMailbox.ImapMessage, raw: String, item: String) {
        // Map the sugar forms onto a section.
        val label: String
        val section: String
        when {
            item == "RFC822" -> { label = "RFC822"; section = "" }
            item == "RFC822.HEADER" -> { label = "RFC822.HEADER"; section = "HEADER" }
            item == "RFC822.TEXT" -> { label = "RFC822.TEXT"; section = "TEXT" }
            else -> {
                val open = item.indexOf('[')
                val close = item.lastIndexOf(']')
                val peek = item.contains("BODY.PEEK")
                section = if (open >= 0 && close > open) raw.substring(open + 1, close) else ""
                label = if (peek) "BODY[${raw.substring(open + 1, close)}]" else "BODY[$section]"
            }
        }
        val bytes = sectionBytes(msg, section)
        output.writeStringUtf8("$label {${bytes.size}}\r\n")
        output.writeFully(bytes)
    }

    private fun sectionBytes(msg: ImapMailbox.ImapMessage, section: String): ByteArray {
        if (section.isEmpty()) return msg.body
        val upper = section.uppercase()
        if (upper == "HEADER") return headerSection(msg.body)
        if (upper == "TEXT") return textSection(msg.body)
        if (upper.startsWith("HEADER.FIELDS")) {
            val not = upper.startsWith("HEADER.FIELDS.NOT")
            val open = section.indexOf('(')
            val close = section.lastIndexOf(')')
            val fields = if (open in 0 until close) {
                section.substring(open + 1, close).split(' ').filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
            return headerFieldsSection(msg.body, fields, not)
        }
        if (upper == "MIME") {
            val m = tryParse(msg.body) ?: return ByteArray(0)
            return mimeHeadersOnly(m)
        }
        // Numeric part path: walk the MIME tree and re-serialize the part body.
        val num = section.substringAfterLast(']').substringBefore('.').toIntOrNull()
            ?: section.substringBefore('<').trim().toIntOrNull()
        if (num != null && num > 0) {
            val m = tryParse(msg.body) ?: return ByteArray(0)
            val part = nthPart(m, num) ?: return ByteArray(0)
            return reencodeBody(part)
        }
        return ByteArray(0)
    }

    private fun nthPart(m: MimeMessage, n: Int): MimeMessage? {
        val body = m.body
        if (body is MimeMessage.Body.Multipart) return body.parts.getOrNull(n - 1)
        return if (n == 1) m else null
    }

    private fun reencodeBody(part: MimeMessage): ByteArray {
        val full = MimeWriter.write(part)
        val sep = indexOfHeaderEnd(full)
        return if (sep < 0) full else full.copyOfRange(sep, full.size)
    }

    private fun mimeHeadersOnly(m: MimeMessage): ByteArray {
        val full = MimeWriter.write(m)
        val sep = indexOfHeaderEnd(full)
        return if (sep < 0) full else full.copyOfRange(0, sep)
    }

    // ---- STORE / COPY -------------------------------------------------------

    private suspend fun handleStore(tag: String, arg: String, uidMode: Boolean) {
        if (state != ImapState.SELECTED) { sendTagged(tag, ImapResponse.TagStatus.NO, "no mailbox selected"); return }
        var rest = arg.trim()
        var unchangedSince: Long? = null
        val ucMatch = Regex("\\(\\s*UNCHANGEDSINCE\\s+(\\d+)\\s*\\)", RegexOption.IGNORE_CASE).find(rest)
        if (ucMatch != null) {
            unchangedSince = ucMatch.groupValues[1].toLongOrNull()
            rest = (rest.substring(0, ucMatch.range.first) + " " + rest.substring(ucMatch.range.last + 1)).trim()
        }
        val tokens = tokenize(rest)
        if (tokens.size < 3) { sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid STORE syntax"); return }
        val setStr = tokens[0]
        var action = tokens[1].uppercase()
        val silent = action.endsWith(".SILENT")
        if (silent) action = action.removeSuffix(".SILENT")
        val flags = tokens.drop(2).joinToString(" ").removePrefix("(").removeSuffix(")")
            .split(' ').filter { it.isNotEmpty() }
        val mb = selectedMailbox!!
        val seqs = if (uidMode) expandUid(setStr, mb) else expandSequence(setStr, mb.exists())
        val modified = mutableListOf<String>()
        for (seq in seqs) {
            val before = mb.message(seq) ?: continue
            if (unchangedSince != null && before.modSeq > unchangedSince) {
                modified += if (uidMode) before.uid.toString() else seq.toString()
                continue
            }
            mb.setFlags(seq, action, flags)
            if (!silent) {
                val msg = mb.message(seq) ?: continue
                val uidPart = if (uidMode) "UID ${msg.uid} " else ""
                val modseqPart = if (condstoreEnabled) " MODSEQ (${msg.modSeq})" else ""
                sendUntagged("FETCH", "$seq (${uidPart}FLAGS (${msg.flags.joinToString(" ")})$modseqPart)")
            }
        }
        val code = if (modified.isNotEmpty()) "MODIFIED ${modified.joinToString(",")}" else null
        sendTagged(tag, ImapResponse.TagStatus.OK, "STORE completed", code = code)
    }

    private suspend fun handleCopy(tag: String, arg: String, uidMode: Boolean) {
        if (state != ImapState.SELECTED) { sendTagged(tag, ImapResponse.TagStatus.NO, "no mailbox selected"); return }
        val tokens = tokenize(arg)
        if (tokens.size < 2) { sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid COPY syntax"); return }
        val setStr = tokens[0]
        val targetName = tokens[1]
        val target = getMailbox(user!!, targetName) ?: run {
            sendTagged(tag, ImapResponse.TagStatus.NO, "[TRYCREATE] mailbox not found"); return
        }
        val mb = selectedMailbox!!
        val seqs = if (uidMode) expandUid(setStr, mb) else expandSequence(setStr, mb.exists())
        for (seq in seqs) {
            val msg = mb.message(seq) ?: continue
            target.append(msg.body, msg.flags)
        }
        sendTagged(tag, ImapResponse.TagStatus.OK, "COPY completed")
    }

    // ---- SEARCH -------------------------------------------------------------

    private suspend fun handleSearch(tag: String, arg: String, uidMode: Boolean) {
        if (state != ImapState.SELECTED) { sendTagged(tag, ImapResponse.TagStatus.NO, "no mailbox selected"); return }
        val mb = selectedMailbox!!
        val tokens = searchTokenize(arg)
        val matched = mutableListOf<String>()
        for (seq in 1..mb.exists()) {
            val msg = mb.message(seq) ?: continue
            if (SearchParser(tokens, msg, seq).evaluate()) {
                matched += if (uidMode) msg.uid.toString() else seq.toString()
            }
        }
        sendUntagged("SEARCH", matched.joinToString(" "))
        sendTagged(tag, ImapResponse.TagStatus.OK, "SEARCH completed")
    }

    private inner class SearchParser(
        private val tokens: kotlin.collections.List<String>,
        private val msg: ImapMailbox.ImapMessage,
        private val seq: Int,
    ) {
        private var pos = 0

        fun evaluate(): Boolean {
            if (tokens.isEmpty()) return true
            return parseAnd(untilParen = false)
        }

        private fun parseAnd(untilParen: Boolean): Boolean {
            var result = true
            while (pos < tokens.size) {
                if (tokens[pos] == ")") {
                    if (untilParen) break
                    pos++
                    continue
                }
                result = parseOne() && result
            }
            return result
        }

        private fun parseOne(): Boolean {
            if (pos >= tokens.size) return true
            val t = tokens[pos++].uppercase()
            return when (t) {
                "ALL" -> true
                "ANSWERED" -> hasFlag("\\Answered")
                "UNANSWERED" -> !hasFlag("\\Answered")
                "DELETED" -> hasFlag("\\Deleted")
                "UNDELETED" -> !hasFlag("\\Deleted")
                "DRAFT" -> hasFlag("\\Draft")
                "UNDRAFT" -> !hasFlag("\\Draft")
                "FLAGGED" -> hasFlag("\\Flagged")
                "UNFLAGGED" -> !hasFlag("\\Flagged")
                "SEEN" -> hasFlag("\\Seen")
                "UNSEEN" -> !hasFlag("\\Seen")
                "RECENT" -> hasFlag("\\Recent")
                "OLD" -> !hasFlag("\\Recent")
                "NEW" -> hasFlag("\\Recent") && !hasFlag("\\Seen")
                "KEYWORD" -> hasFlag(next())
                "UNKEYWORD" -> !hasFlag(next())
                "FROM" -> headerContains("from", next())
                "TO" -> headerContains("to", next())
                "CC" -> headerContains("cc", next())
                "BCC" -> headerContains("bcc", next())
                "SUBJECT" -> headerContains("subject", next())
                "BODY" -> bodyContains(next())
                "TEXT" -> bodyOrHeaderContains(next())
                "HEADER" -> {
                    val name = next()
                    val value = next()
                    headerContains(name, value)
                }
                "LARGER" -> msg.size > (next().toIntOrNull() ?: 0)
                "SMALLER" -> msg.size < (next().toIntOrNull() ?: Int.MAX_VALUE)
                "UID" -> inUidSet(next())
                "MODSEQ" -> msg.modSeq >= (next().toLongOrNull() ?: 0L)
                "SINCE" -> dateCompare(next()) { msgDays, target -> msgDays >= target }
                "BEFORE" -> dateCompare(next()) { msgDays, target -> msgDays < target }
                "ON" -> dateCompare(next()) { msgDays, target -> msgDays == target }
                "SENTSINCE", "SENTBEFORE", "SENTON" -> {
                    val target = parseImapDate(next()) ?: return true
                    val days = msgDays()
                    when (t) {
                        "SENTSINCE" -> days >= target
                        "SENTBEFORE" -> days < target
                        else -> days == target
                    }
                }
                "NOT" -> !parseOne()
                "OR" -> {
                    val a = parseOne()
                    val b = parseOne()
                    a || b
                }
                "(" -> parseAnd(untilParen = true).also { if (pos < tokens.size && tokens[pos] == ")") pos++ }
                else -> {
                    // Bare sequence-set token.
                    val n = t.toIntOrNull()
                    if (n != null) seq == n else true
                }
            }
        }

        private fun next(): String = if (pos < tokens.size) tokens[pos++] else ""

        private fun hasFlag(flag: String) = msg.flags.any { it.equals(flag, ignoreCase = true) }

        private fun headers(): pw.binom.mail.mime.Headers? = try {
            MimeParser.parse(msg.body).headers
        } catch (_: Throwable) {
            null
        }

        private fun headerContains(name: String, value: String): Boolean {
            val hs = headers() ?: return false
            val needle = value.lowercase()
            return hs.all(name).any { it.value.lowercase().contains(needle) || it.valueDecoded().lowercase().contains(needle) }
        }

        private fun bodyContains(value: String): Boolean {
            val m = try { MimeParser.parse(msg.body) } catch (_: Throwable) { return false }
            return m.text()?.lowercase()?.contains(value.lowercase()) == true
        }

        private fun bodyOrHeaderContains(value: String): Boolean {
            val needle = value.lowercase()
            if (msg.body.decodeToString().lowercase().contains(needle)) return true
            return bodyContains(value)
        }

        private fun inUidSet(setStr: String): Boolean {
            val set = expandUidSet(setStr, Long.MAX_VALUE)
            return set.any { it.toLong() == msg.uid }
        }

        private fun dateCompare(value: String, cmp: (Long, Long) -> Boolean): Boolean {
            val target = parseImapDate(value) ?: return true
            return cmp(msgDays(), target)
        }

        private fun msgDays(): Long = daysFromCivil(msg.internalDate)
    }

    // ---- ENABLE (RFC 5161) --------------------------------------------------

    private suspend fun handleEnable(tag: String, arg: String) {
        if (state == ImapState.NOT_AUTHENTICATED) { sendTagged(tag, ImapResponse.TagStatus.NO, "login required"); return }
        val enabled = mutableListOf<String>()
        for (t in tokenize(arg)) {
            when (t.uppercase()) {
                "CONDSTORE" -> { condstoreEnabled = true; enabled += "CONDSTORE" }
                "QRESYNC" -> { qresyncEnabled = true; condstoreEnabled = true; enabled += "QRESYNC" }
            }
        }
        if (enabled.isNotEmpty()) sendUntagged("ENABLED", enabled.joinToString(" "))
        sendTagged(tag, ImapResponse.TagStatus.OK, "ENABLE completed")
    }

    // ---- SORT / THREAD (RFC 5256) -------------------------------------------

    private class SortKey(val name: String, val reverse: Boolean)

    private class SortArgs(
        val keys: kotlin.collections.List<SortKey>,
        val charset: String,
        val search: kotlin.collections.List<String>,
    )

    private fun parseSortArgs(arg: String): SortArgs? {
        val trimmed = arg.trim()
        if (!trimmed.startsWith("(")) return null
        val end = matchingParen(trimmed, 0)
        if (end < 0) return null
        val rawKeys = trimmed.substring(1, end).split(' ').filter { it.isNotEmpty() }
        val keys = mutableListOf<SortKey>()
        var reverse = false
        for (k in rawKeys) {
            if (k.equals("REVERSE", ignoreCase = true)) {
                reverse = true
            } else {
                keys += SortKey(k.uppercase(), reverse)
                reverse = false
            }
        }
        if (keys.isEmpty()) return null
        val rest = trimmed.substring(end + 1).trim()
        val sp = rest.indexOf(' ')
        val charset = if (sp < 0) rest else rest.substring(0, sp)
        val search = if (sp < 0) emptyList() else searchTokenize(rest.substring(sp + 1))
        return SortArgs(keys, charset, search)
    }

    private suspend fun handleSort(tag: String, arg: String, uidMode: Boolean) {
        if (state != ImapState.SELECTED) { sendTagged(tag, ImapResponse.TagStatus.NO, "no mailbox selected"); return }
        val args = parseSortArgs(arg) ?: run {
            sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid SORT syntax"); return
        }
        val mb = selectedMailbox!!
        val matched = mutableListOf<Pair<Int, ImapMailbox.ImapMessage>>()
        for (seq in 1..mb.exists()) {
            val msg = mb.message(seq) ?: continue
            if (args.search.isEmpty() || SearchParser(args.search, msg, seq).evaluate()) matched += seq to msg
        }
        val sorted = matched.sortedWith { a, b -> compareMessages(args.keys, a.second, b.second) }
        val result = sorted.joinToString(" ") { if (uidMode) it.second.uid.toString() else it.first.toString() }
        sendUntagged("SORT", result)
        sendTagged(tag, ImapResponse.TagStatus.OK, "SORT completed")
    }

    private fun compareMessages(keys: kotlin.collections.List<SortKey>, a: ImapMailbox.ImapMessage, b: ImapMailbox.ImapMessage): Int {
        for (k in keys) {
            val c = compareKey(k.name, a, b)
            if (c != 0) return if (k.reverse) -c else c
        }
        return 0
    }

    private fun compareKey(name: String, a: ImapMailbox.ImapMessage, b: ImapMailbox.ImapMessage): Int = when (name) {
        "ARRIVAL" -> a.internalDate.compareTo(b.internalDate)
        "DATE" -> messageDateMillis(a).compareTo(messageDateMillis(b))
        "SIZE" -> a.size.compareTo(b.size)
        "FROM" -> headerValue(a, "from").lowercase().compareTo(headerValue(b, "from").lowercase())
        "TO" -> headerValue(a, "to").lowercase().compareTo(headerValue(b, "to").lowercase())
        "CC" -> headerValue(a, "cc").lowercase().compareTo(headerValue(b, "cc").lowercase())
        "SUBJECT" -> baseSubject(a).lowercase().compareTo(baseSubject(b).lowercase())
        else -> 0
    }

    private suspend fun handleThread(tag: String, arg: String, uidMode: Boolean) {
        if (state != ImapState.SELECTED) { sendTagged(tag, ImapResponse.TagStatus.NO, "no mailbox selected"); return }
        val trimmed = arg.trim()
        val sp = trimmed.indexOf(' ')
        if (sp < 0) { sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid THREAD syntax"); return }
        val algorithm = trimmed.substring(0, sp).uppercase()
        val rest = trimmed.substring(sp + 1).trim()
        val sp2 = rest.indexOf(' ')
        val searchTokens = if (sp2 < 0) emptyList() else searchTokenize(rest.substring(sp2 + 1))
        val mb = selectedMailbox!!
        val matched = mutableListOf<Pair<Int, ImapMailbox.ImapMessage>>()
        for (seq in 1..mb.exists()) {
            val msg = mb.message(seq) ?: continue
            if (searchTokens.isEmpty() || SearchParser(searchTokens, msg, seq).evaluate()) matched += seq to msg
        }
        fun label(p: Pair<Int, ImapMailbox.ImapMessage>): String =
            if (uidMode) p.second.uid.toString() else p.first.toString()

        val threads: kotlin.collections.List<String> = when (algorithm) {
            "ORDEREDSUBJECT" -> orderedSubjectThreads(matched, ::label)
            "REFERENCES" -> referenceThreads(matched, ::label)
            else -> {
                sendTagged(tag, ImapResponse.TagStatus.BAD, "unknown THREAD algorithm $algorithm")
                return
            }
        }
        sendUntagged("THREAD", threads.joinToString(""))
        sendTagged(tag, ImapResponse.TagStatus.OK, "THREAD completed")
    }

    private fun orderedSubjectThreads(
        matched: kotlin.collections.List<Pair<Int, ImapMailbox.ImapMessage>>,
        label: (Pair<Int, ImapMailbox.ImapMessage>) -> String,
    ): kotlin.collections.List<String> {
        val groups = LinkedHashMap<String, MutableList<Pair<Int, ImapMailbox.ImapMessage>>>()
        for (p in matched) groups.getOrPut(baseSubject(p.second).lowercase()) { mutableListOf() } += p
        return groups.values
            .sortedBy { it.first().second.internalDate }
            .map { group -> "(" + group.sortedBy { it.first }.joinToString(" ") { label(it) } + ")" }
    }

    private fun referenceThreads(
        matched: kotlin.collections.List<Pair<Int, ImapMailbox.ImapMessage>>,
        label: (Pair<Int, ImapMailbox.ImapMessage>) -> String,
    ): kotlin.collections.List<String> {
        val byId = HashMap<String, Int>()
        matched.forEachIndexed { i, p -> msgId(p.second)?.let { byId[it] = i } }
        val parent = IntArray(matched.size) { -1 }
        matched.forEachIndexed { i, p ->
            val refs = headerValues(p.second, "references").flatMap { it.split(' ') } +
                listOfNotNull(headerValue(p.second, "in-reply-to"))
            var pid = -1
            for (r in refs) {
                val idx = byId[normalizeId(r)]
                if (idx != null) pid = idx
            }
            parent[i] = if (pid == i) -1 else pid
        }
        val children = HashMap<Int, MutableList<Int>>()
        val roots = mutableListOf<Int>()
        for (i in matched.indices) {
            val p = parent[i]
            if (p >= 0) children.getOrPut(p) { mutableListOf() } += i else roots += i
        }
        fun node(i: Int): String {
            val sb = StringBuilder(label(matched[i]))
            for (c in children[i]?.sorted() ?: emptyList()) sb.append(" (").append(node(c)).append(")")
            return sb.toString()
        }
        return roots.sorted().map { "(" + node(it) + ")" }
    }

    private fun baseSubject(msg: ImapMailbox.ImapMessage): String {
        var s = headerValue(msg, "subject").trim()
        while (true) {
            val m = Regex("^\\s*(re|fw|fwd)\\s*(\\[\\d+])?\\s*:", RegexOption.IGNORE_CASE).find(s) ?: break
            s = s.substring(m.range.last + 1).trim()
        }
        return s
    }

    private fun msgId(msg: ImapMailbox.ImapMessage): String? =
        headerValue(msg, "message-id").takeIf { it.isNotBlank() }?.let { normalizeId(it) }

    private fun normalizeId(s: String): String = s.trim().removePrefix("<").removeSuffix(">").trim()

    private fun headerValue(msg: ImapMailbox.ImapMessage, name: String): String =
        tryParse(msg.body)?.headers?.firstDecodedValue(name) ?: ""

    private fun headerValues(msg: ImapMailbox.ImapMessage, name: String): kotlin.collections.List<String> =
        tryParse(msg.body)?.headers?.all(name)?.map { it.value } ?: emptyList()

    private fun messageDateMillis(msg: ImapMailbox.ImapMessage): Long {
        val raw = headerValue(msg, "date")
        return parseRfc5322Date(raw) ?: msg.internalDate
    }

    // ---- NOTIFY (RFC 5465) --------------------------------------------------

    private class NotifySpec(
        val statusItems: kotlin.collections.List<String>,
        val events: Set<String>,
        val mailboxes: kotlin.collections.List<String>,
    )

    private class Snapshot(
        val exists: Int,
        val recent: Int,
        val uidNext: Long,
        val uidValidity: Long,
        val unseen: Int,
        val uids: kotlin.collections.List<Long>,
        val flagsByUid: Map<Long, kotlin.collections.List<String>>,
        val modSeqByUid: Map<Long, Long>,
    )

    private fun snapshot(mb: ImapMailbox): Snapshot {
        val all = mb.all()
        return Snapshot(
            exists = mb.exists(),
            recent = mb.recent(),
            uidNext = mb.uidNext(),
            uidValidity = mb.uidValidity(),
            unseen = countUnseen(mb),
            uids = all.map { it.uid },
            flagsByUid = all.associate { it.uid to it.flags },
            modSeqByUid = all.associate { it.uid to it.modSeq },
        )
    }

    private suspend fun handleNotify(tag: String, arg: String) {
        if (state == ImapState.NOT_AUTHENTICATED) { sendTagged(tag, ImapResponse.TagStatus.NO, "login required"); return }
        val trimmed = arg.trim()
        if (trimmed.equals("NONE", ignoreCase = true)) {
            notifyJob?.cancel()
            notifyJob = null
            notifySpec = null
            sendTagged(tag, ImapResponse.TagStatus.OK, "NOTIFY completed")
            return
        }
        if (!trimmed.uppercase().startsWith("SET")) {
            sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid NOTIFY syntax"); return
        }
        val afterSet = trimmed.substring(3).trim()
        var events: Set<String> = emptySet()
        var statusItems: kotlin.collections.List<String> = emptyList()
        var rest = afterSet
        if (afterSet.startsWith("(")) {
            val end = matchingParen(afterSet, 0)
            if (end < 0) { sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid NOTIFY syntax"); return }
            val inner = afterSet.substring(1, end).trim()
            val up = inner.uppercase()
            val statusIdx = up.indexOf("STATUS")
            if (statusIdx >= 0) {
                val before = inner.substring(0, statusIdx).split(' ').filter { it.isNotEmpty() }
                events = before.map { it.uppercase() }.toSet()
                val open = inner.indexOf('(', statusIdx)
                val close = inner.lastIndexOf(')')
                if (open in 0 until close) {
                    statusItems = inner.substring(open + 1, close).split(' ').filter { it.isNotEmpty() }.map { it.uppercase() }
                }
            } else {
                events = inner.split(' ').filter { it.isNotEmpty() }.map { it.uppercase() }.toSet()
            }
            rest = afterSet.substring(end + 1).trim()
        }
        val mailboxes = tokenize(rest.removePrefix("(").removeSuffix(")").trim()).map { unquote(it) }
        startNotify(NotifySpec(statusItems, events, mailboxes))
        sendTagged(tag, ImapResponse.TagStatus.OK, "NOTIFY completed")
    }

    private fun startNotify(spec: NotifySpec) {
        notifyJob?.cancel()
        notifySpec = spec
        val u = user ?: return
        notifyJob = notifyScope.launch {
            val last = HashMap<String, Snapshot>()
            for (name in spec.mailboxes) getMailbox(u, name)?.let { last[name] = snapshot(it) }
            while (isActive) {
                delay(300)
                for (name in spec.mailboxes) {
                    val mb = getMailbox(u, name) ?: continue
                    val prev = last[name] ?: continue
                    val cur = snapshot(mb)
                    last[name] = cur
                    emitNotify(name, spec, prev, cur)
                }
            }
        }
    }

    private suspend fun emitNotify(name: String, spec: NotifySpec, prev: Snapshot, cur: Snapshot) {
        val removed = prev.uids.filter { it !in cur.uids }
        if (removed.isNotEmpty() && "EXPUNGE" in spec.events) {
            for (uid in removed) {
                val seq = prev.uids.indexOf(uid) + 1
                sendUntagged("EXPUNGE", seq.toString())
            }
        }
        if (cur.exists != prev.exists && "EXISTS" in spec.events) sendUntagged("EXISTS", cur.exists.toString())
        if (cur.recent != prev.recent && "RECENT" in spec.events) sendUntagged("RECENT", cur.recent.toString())
        if (("MESSAGE" in spec.events || "FETCH" in spec.events)) {
            for (uid in cur.uids) {
                val isNew = uid !in prev.uids
                val flagsChanged = !isNew && prev.flagsByUid[uid] != cur.flagsByUid[uid]
                if (isNew || flagsChanged) {
                    val seq = cur.uids.indexOf(uid) + 1
                    val flags = cur.flagsByUid[uid]?.joinToString(" ") ?: ""
                    val modseq = cur.modSeqByUid[uid] ?: 0L
                    sendUntagged("FETCH", "$seq (UID $uid FLAGS ($flags) MODSEQ ($modseq))")
                }
            }
        }
        if (spec.statusItems.isNotEmpty()) {
            val changed = cur.exists != prev.exists || cur.recent != prev.recent ||
                cur.uidNext != prev.uidNext || cur.unseen != prev.unseen
            if (changed) {
                val sb = StringBuilder("(")
                for ((i, item) in spec.statusItems.withIndex()) {
                    if (i > 0) sb.append(' ')
                    sb.append(item).append(' ').append(
                        when (item) {
                            "MESSAGES" -> cur.exists
                            "RECENT" -> cur.recent
                            "UIDNEXT" -> cur.uidNext
                            "UIDVALIDITY" -> cur.uidValidity
                            "UNSEEN" -> cur.unseen
                            else -> 0
                        },
                    )
                }
                sb.append(')')
                sendUntagged("STATUS", "${q(name)} $sb")
            }
        }
    }

    // ---- IDLE ---------------------------------------------------------------

    private suspend fun handleIdle(tag: String) {
        if (state != ImapState.SELECTED) { sendTagged(tag, ImapResponse.TagStatus.NO, "IDLE requires SELECTED state"); return }
        sendContinuation("idling")
        while (true) {
            val line = input.readUTF8Line(MAX_LINE_BYTES) ?: break
            if (line.equals("DONE", ignoreCase = true)) break
        }
        sendTagged(tag, ImapResponse.TagStatus.OK, "IDLE terminated")
    }

    // ---- APPEND -------------------------------------------------------------

    private suspend fun handleAppend(tag: String, arg: String) {
        if (state == ImapState.NOT_AUTHENTICATED) { sendTagged(tag, ImapResponse.TagStatus.NO, "login required"); return }
        val parsed = parseAppend(arg) ?: run {
            sendTagged(tag, ImapResponse.TagStatus.BAD, "invalid APPEND syntax"); return
        }
        val (mailbox, flags, literal) = parsed
        val mb = getMailbox(user!!, mailbox) ?: run {
            sendTagged(tag, ImapResponse.TagStatus.NO, "mailbox not found"); return
        }
        sendContinuation("ready for literal data")
        val data = ByteArray(literal)
        if (literal > 0) input.readFully(data)
        val uid = mb.append(data, flags)
        sendTagged(tag, ImapResponse.TagStatus.OK, "APPEND completed", code = "APPENDUID ${mb.uidValidity()} $uid")
    }

    // ---- MIME helpers -------------------------------------------------------

    private fun tryParse(raw: ByteArray): MimeMessage? = try {
        MimeParser.parse(raw)
    } catch (_: Throwable) {
        null
    }

    private fun headerSection(raw: ByteArray): ByteArray {
        val sep = indexOfHeaderEnd(raw)
        return if (sep < 0) raw else raw.copyOfRange(0, sep)
    }

    private fun textSection(raw: ByteArray): ByteArray {
        val sep = indexOfHeaderEnd(raw)
        return if (sep < 0) ByteArray(0) else raw.copyOfRange(sep, raw.size)
    }

    private fun headerFieldsSection(raw: ByteArray, fields: kotlin.collections.List<String>, negate: Boolean): ByteArray {
        val headerText = headerSection(raw).decodeToString()
        val lines = headerText.split("\r\n")
        val names = fields.map { it.lowercase() }.toSet()
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isEmpty()) { i++; continue }
            val colon = line.indexOf(':')
            val name = if (colon >= 0) line.substring(0, colon).lowercase() else ""
            val selected = names.contains(name)
            if (selected != negate) {
                out.append(line).append("\r\n")
                var j = i + 1
                while (j < lines.size && lines[j].isNotEmpty() && (lines[j][0] == ' ' || lines[j][0] == '\t')) {
                    out.append(lines[j]).append("\r\n")
                    j++
                }
            }
            i++
        }
        out.append("\r\n")
        return out.toString().encodeToByteArray()
    }

    private fun envelope(msg: ImapMailbox.ImapMessage): String {
        val hs = tryParse(msg.body)?.headers ?: return "NIL"
        fun addrList(name: String): String {
            val all = hs.all(name)
            if (all.isEmpty()) return "NIL"
            val addrs = all.flatMap { it.addresses() }
            if (addrs.isEmpty()) return "NIL"
            return "(" + addrs.joinToString("") { formatAddress(it) } + ")"
        }
        val date = hs.firstValue("date")
        val subject = hs.first("subject")?.valueDecoded()
        val inReplyTo = hs.firstValue("in-reply-to")
        val messageId = hs.firstValue("message-id")
        return "(" +
            q(date) + " " + q(subject) + " " +
            addrList("from") + " " + addrList("sender") + " " + addrList("reply-to") + " " +
            addrList("to") + " " + addrList("cc") + " " + addrList("bcc") + " " +
            q(inReplyTo) + " " + q(messageId) + ")"
    }

    private fun formatAddress(a: pw.binom.mail.mime.Address.Mailbox): String =
        "(${q(a.name)} NIL ${q(a.localPart)} ${q(a.domain)})"

    private fun bodyStructure(msg: ImapMailbox.ImapMessage): String {
        val m = tryParse(msg.body) ?: return "(\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" ${msg.size})"
        return structure(m)
    }

    private fun structure(m: MimeMessage): String {
        return when (val body = m.body) {
            is MimeMessage.Body.Multipart -> {
                val parts = body.parts.joinToString(" ") { structure(it) }
                "($parts \"${m.contentType().subtype.uppercase()}\")"
            }
            is MimeMessage.Body.Single -> {
                val ct = m.contentType()
                val type = ct.type.uppercase()
                val subtype = ct.subtype.uppercase()
                val charset = ct.charset
                val params = if (charset != null) "(\"CHARSET\" \"${charset.uppercase()}\")" else "NIL"
                val enc = (m.headers.firstValue("content-transfer-encoding") ?: "7bit").uppercase()
                val size = body.content.size
                if (type == "TEXT") {
                    val lines = body.content.count { it == '\n'.code.toByte() } + 1
                    "(\"TEXT\" \"$subtype\" $params NIL NIL \"$enc\" $size $lines)"
                } else {
                    "(\"$type\" \"$subtype\" $params NIL NIL \"$enc\" $size)"
                }
            }
        }
    }

    // ---- parsing / misc -----------------------------------------------------

    private suspend fun <T> read(block: () -> T): T = block()

    private fun splitCommand(line: String): kotlin.collections.List<String> {
        val space = line.indexOf(' ')
        if (space < 0) return listOf(line, "", "")
        val tag = line.substring(0, space)
        val rest = line.substring(space + 1)
        val space2 = rest.indexOf(' ')
        if (space2 < 0) return listOf(tag, rest, "")
        return listOf(tag, rest.substring(0, space2), rest.substring(space2 + 1))
    }

    private fun parseLogin(arg: String): Pair<String, String>? {
        val tokens = tokenize(arg)
        if (tokens.size != 2) return null
        return tokens[0] to tokens[1]
    }

    private fun parseList(arg: String): Pair<String, String>? {
        val tokens = tokenize(arg)
        if (tokens.size != 2) return null
        return tokens[0] to tokens[1]
    }

    private fun parseFetch(arg: String): FetchArgs? {
        val trimmed = arg.trim()
        val sp = trimmed.indexOf(' ')
        if (sp < 0) return null
        val set = trimmed.substring(0, sp).trim()
        if (set.isEmpty()) return null
        val remainder = trimmed.substring(sp + 1).trim()
        val items: kotlin.collections.List<String>
        var mods = ""
        if (remainder.startsWith("(")) {
            val end = matchingParen(remainder, 0)
            if (end < 0) return null
            items = splitFetchItems(remainder.substring(0, end + 1))
            mods = remainder.substring(end + 1).trim()
        } else {
            val mi = Regex("\\s*\\(\\s*CHANGEDSINCE", RegexOption.IGNORE_CASE).find(remainder)?.range?.first
            items = splitFetchItems(if (mi == null) remainder else remainder.substring(0, mi))
            mods = if (mi == null) "" else remainder.substring(mi)
        }
        if (items.isEmpty()) return null
        val changedSince = Regex("CHANGEDSINCE\\s+(\\d+)", RegexOption.IGNORE_CASE).find(mods)
            ?.groupValues?.get(1)?.toLongOrNull()
        return FetchArgs(set, items, changedSince)
    }

    private fun matchingParen(s: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    /** Splits the FETCH item list, keeping `BODY[...]` (with nested parens) intact. */
    private fun splitFetchItems(s: String): kotlin.collections.List<String> {
        val items = mutableListOf<String>()
        val inner = s.removePrefix("(").removeSuffix(")")
        val sb = StringBuilder()
        var bracket = 0
        var paren = 0
        for (c in inner) {
            when (c) {
                '[' -> { bracket++; sb.append(c) }
                ']' -> { if (bracket > 0) bracket--; sb.append(c) }
                '(' -> { paren++; sb.append(c) }
                ')' -> { if (paren > 0) paren--; sb.append(c) }
                ' ' -> if (bracket == 0 && paren == 0) {
                    if (sb.isNotEmpty()) { items += sb.toString(); sb.clear() }
                } else sb.append(c)
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) items += sb.toString()
        return items
    }

    private fun parseAppend(arg: String): Triple<String, kotlin.collections.List<String>, Int>? {
        val m = Regex("""(?:"([^"]*)"|(\S+))(?:\s+\(([^)]*)\))?(?:\s+"[^"]*")?\s+\{(\d+)}""").matchEntire(arg.trim())
            ?: return null
        val mailbox = if (m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.groupValues[2]
        val flags = m.groupValues[3].split(' ').filter { it.isNotEmpty() }
        val literal = m.groupValues[4].toIntOrNull() ?: return null
        return Triple(mailbox, flags, literal)
    }

    private fun listMailboxes(pattern: String): kotlin.collections.List<String> {
        val u = user ?: return emptyList()
        val mbs = userMailboxes(u)
        val p = unquote(pattern)
        if (p == "*" || p == "%") return mbs
        return mbs.filter { matchesPattern(p, it) }
    }

    private fun matchesPattern(pattern: String, name: String): Boolean {
        val sb = StringBuilder("^")
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '%' -> sb.append("[^/]*")
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        sb.append('$')
        return Regex(sb.toString()).matches(name)
    }

    private fun expandSequence(seq: String, max: Int): kotlin.collections.List<Int> =
        expandIntSet(seq, max)

    private fun expandIntSet(seq: String, max: Int): kotlin.collections.List<Int> {
        val out = mutableListOf<Int>()
        for (part in seq.split(',')) {
            if (part.isEmpty()) continue
            if (part == "*") { if (max >= 1) out += max; continue }
            if (part.contains(':')) {
                val a = part.substringBefore(':')
                val b = part.substringAfter(':')
                val start = if (a == "*") max else a.toIntOrNull() ?: continue
                val end = if (b == "*") max else b.toIntOrNull() ?: continue
                if (start <= end) for (i in start..end) if (i in 1..max) out += i
                else for (i in start downTo end) if (i in 1..max) out += i
            } else {
                part.toIntOrNull()?.let { if (it in 1..max) out += it }
            }
        }
        return out
    }

    private fun expandUidSet(seq: String, max: Long): kotlin.collections.List<Long> {
        val out = mutableListOf<Long>()
        for (part in seq.split(',')) {
            if (part.isEmpty()) continue
            if (part == "*") { out += max; continue }
            if (part.contains(':')) {
                val a = part.substringBefore(':')
                val b = part.substringAfter(':')
                val start = if (a == "*") max else a.toLongOrNull() ?: continue
                val end = if (b == "*") max else b.toLongOrNull() ?: continue
                if (start <= end) for (i in start..end) out += i
                else for (i in start downTo end) out += i
            } else {
                part.toLongOrNull()?.let { out += it }
            }
        }
        return out
    }

    /** Resolves a UID set to a list of (current) sequence numbers. */
    private fun expandUid(setStr: String, mb: ImapMailbox): kotlin.collections.List<Int> {
        val uids = expandUidSet(setStr, mb.uidNext()).toSet()
        val out = mutableListOf<Int>()
        for (seq in 1..mb.exists()) {
            val msg = mb.message(seq) ?: continue
            if (msg.uid in uids) out += seq
        }
        return out
    }

    private fun tokenize(s: String): kotlin.collections.List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            when {
                s[i] == ' ' || s[i] == '\t' -> i++
                s[i] == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < s.length && s[i] != '"') {
                        if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2 }
                        else { sb.append(s[i]); i++ }
                    }
                    i++
                    out += sb.toString()
                }
                else -> {
                    val sb = StringBuilder()
                    while (i < s.length && s[i] != ' ' && s[i] != '\t') { sb.append(s[i]); i++ }
                    out += sb.toString()
                }
            }
        }
        return out
    }

    /** Tokenizer for SEARCH: `(`/`)` are separate tokens, quoted strings are unquoted. */
    private fun searchTokenize(s: String): kotlin.collections.List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == ' ' || c == '\t' -> i++
                c == '(' || c == ')' -> { out += c.toString(); i++ }
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < s.length && s[i] != '"') {
                        if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2 }
                        else { sb.append(s[i]); i++ }
                    }
                    i++
                    out += sb.toString()
                }
                else -> {
                    val sb = StringBuilder()
                    while (i < s.length && s[i] != ' ' && s[i] != '\t' && s[i] != '(' && s[i] != ')') {
                        sb.append(s[i]); i++
                    }
                    out += sb.toString()
                }
            }
        }
        return out
    }

    private fun unquote(s: String): String {
        if (s.length >= 2 && s[0] == '"' && s[s.length - 1] == '"') {
            return s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return s
    }

    private fun q(s: String?): String =
        if (s == null) "NIL" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Index just past the header/body separator (CRLFCRLF or LFLF), or -1. */
    private fun indexOfHeaderEnd(raw: ByteArray): Int {
        var i = 0
        while (i + 3 < raw.size) {
            if (raw[i] == CR && raw[i + 1] == LF && raw[i + 2] == CR && raw[i + 3] == LF) return i + 4
            i++
        }
        i = 0
        while (i + 1 < raw.size) {
            if (raw[i] == LF && raw[i + 1] == LF) return i + 2
            i++
        }
        return -1
    }

    private fun formatInternalDate(millis: Long): String {
        val ms = if (millis <= 0L) 0L else millis
        val days = ms / 86_400_000L
        var rem = ms % 86_400_000L
        val (year, month, day) = civilFromDays(days)
        val hour = rem / 3_600_000L
        rem %= 3_600_000L
        val min = rem / 60_000L
        val sec = (rem % 60_000L) / 1000L
        return "${pad2(day.toLong())}-${MONTHS[month - 1]}-$year ${pad2(hour)}:${pad2(min)}:${pad2(sec)} +0000"
    }

    private fun pad2(v: Long): String = if (v < 10) "0$v" else v.toString()

    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return Triple(year.toInt(), m.toInt(), d.toInt())
    }

    private fun daysFromCivil(millis: Long): Long {
        val ms = if (millis <= 0L) 0L else millis
        return ms / 86_400_000L
    }

    private fun parseImapDate(s: String): Long? {
        val m = Regex("""(\d{1,2})-([A-Za-z]{3})-(\d{4})""").matchEntire(s.trim()) ?: return null
        val day = m.groupValues[1].toInt()
        val month = MONTHS.indexOfFirst { it.equals(m.groupValues[2], ignoreCase = true) }
        if (month < 0) return null
        val year = m.groupValues[3].toInt()
        return civilFromYmd(year, month + 1, day)
    }

    /** Parses an RFC 5322 `Date:` header into epoch millis, or null. */
    private fun parseRfc5322Date(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val m = Regex("""(?:[A-Za-z]{3},\s*)?(\d{1,2})\s+([A-Za-z]{3})\s+(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s+([+-]\d{4}|[A-Za-z]+)""").find(t)
            ?: return null
        val day = m.groupValues[1].toInt()
        val month = MONTHS.indexOfFirst { it.equals(m.groupValues[2], ignoreCase = true) }
        if (month < 0) return null
        var year = m.groupValues[3].toInt()
        if (year < 100) year += if (year < 50) 2000 else 1900
        val hour = m.groupValues[4].toInt()
        val min = m.groupValues[5].toInt()
        val sec = m.groupValues[6].toIntOrNull() ?: 0
        val zone = m.groupValues[7]
        val offsetMin = when {
            zone.startsWith("+") || zone.startsWith("-") -> {
                val sign = if (zone[0] == '-') -1 else 1
                val h = zone.substring(1, 3).toIntOrNull() ?: 0
                val mm = zone.substring(3, 5).toIntOrNull() ?: 0
                sign * (h * 60 + mm)
            }
            zone.equals("GMT", true) || zone.equals("UT", true) || zone.equals("UTC", true) -> 0
            zone.equals("EST", true) -> -300
            zone.equals("EDT", true) -> -240
            zone.equals("CST", true) -> -360
            zone.equals("CDT", true) -> -300
            zone.equals("MST", true) -> -420
            zone.equals("MDT", true) -> -360
            zone.equals("PST", true) -> -480
            zone.equals("PDT", true) -> -420
            else -> 0
        }
        val days = civilFromYmd(year, month + 1, day)
        return ((days * 86_400L) + hour * 3_600L + min * 60L + sec - offsetMin * 60L) * 1000L
    }

    private fun civilFromYmd(y0: Int, m: Int, d: Int): Long {
        var y = y0.toLong()
        if (m <= 2) y -= 1
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private val CR: Byte = '\r'.code.toByte()
    private val LF: Byte = '\n'.code.toByte()
}
