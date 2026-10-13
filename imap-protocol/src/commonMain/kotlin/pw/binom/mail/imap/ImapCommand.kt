package pw.binom.mail.imap

/**
 * IMAP commands. Each command carries a tag, the command name, and the
 * arguments. [ImapCommand.line] produces the wire representation (without
 * the trailing CRLF, which the client appends).
 */
public sealed class ImapCommand {
    public abstract val tag: String
    public abstract val line: String

    public data class Capability(override val tag: String) : ImapCommand() {
        override val line: String get() = "$tag CAPABILITY"
    }

    public data class StartTls(override val tag: String) : ImapCommand() {
        override val line: String get() = "$tag STARTTLS"
    }

    public data class Login(override val tag: String, val user: String, val password: String) : ImapCommand() {
        override val line: String get() = "$tag LOGIN ${quoteIfNeeded(user)} ${quoteIfNeeded(password)}"
    }

    public data class Authenticate(override val tag: String, val mechanism: String, val initialResponse: String? = null) :
        ImapCommand() {
        override val line: String get() = if (initialResponse == null) "$tag AUTHENTICATE $mechanism"
        else "$tag AUTHENTICATE $mechanism $initialResponse"
    }

    public data class Logout(override val tag: String) : ImapCommand() {
        override val line: String get() = "$tag LOGOUT"
    }

    public data class Noop(override val tag: String) : ImapCommand() {
        override val line: String get() = "$tag NOOP"
    }

    public data class List(override val tag: String, val reference: String, val pattern: String) : ImapCommand() {
        override val line: String get() = "$tag LIST ${quoteIfNeeded(reference)} ${quoteIfNeeded(pattern)}"
    }

    public data class Lsub(override val tag: String, val reference: String, val pattern: String) : ImapCommand() {
        override val line: String get() = "$tag LSUB ${quoteIfNeeded(reference)} ${quoteIfNeeded(pattern)}"
    }

    public data class Select(override val tag: String, val mailbox: String) : ImapCommand() {
        override val line: String get() = "$tag SELECT ${quoteIfNeeded(mailbox)}"
    }

    public data class Examine(override val tag: String, val mailbox: String) : ImapCommand() {
        override val line: String get() = "$tag EXAMINE ${quoteIfNeeded(mailbox)}"
    }

    public data class Status(override val tag: String, val mailbox: String, val items: kotlin.collections.List<String>) : ImapCommand() {
        override val line: String get() = "$tag STATUS ${quoteIfNeeded(mailbox)} (${items.joinToString(" ")})"
    }

    public data class Fetch(override val tag: String, val sequence: String, val items: kotlin.collections.List<String>, val uid: Boolean = false) : ImapCommand() {
        override val line: String get() =
            if (uid) "$tag UID FETCH $sequence (${items.joinToString(" ")})"
            else "$tag FETCH $sequence (${items.joinToString(" ")})"
    }

    public data class Store(override val tag: String, val sequence: String, val action: String, val flags: kotlin.collections.List<String>, val uid: Boolean = false) : ImapCommand() {
        override val line: String get() = if (uid) "$tag UID STORE $sequence $action (${flags.joinToString(" ")})"
        else "$tag STORE $sequence $action (${flags.joinToString(" ")})"
    }

    public data class Search(override val tag: String, val criteria: kotlin.collections.List<String>, val uid: Boolean = false) : ImapCommand() {
        override val line: String get() = if (uid) "$tag UID SEARCH ${criteria.joinToString(" ")}"
        else "$tag SEARCH ${criteria.joinToString(" ")}"
    }

    public data class Copy(override val tag: String, val sequence: String, val mailbox: String, val uid: Boolean = false) : ImapCommand() {
        override val line: String get() = if (uid) "$tag UID COPY $sequence ${quoteIfNeeded(mailbox)}"
        else "$tag COPY $sequence ${quoteIfNeeded(mailbox)}"
    }

    public data class Append(
        override val tag: String,
        val mailbox: String,
        val flags: kotlin.collections.List<String>? = null,
        val dateTime: String? = null,
        val message: ByteArray,
    ) : ImapCommand() {
        override val line: String = buildString {
            append(tag)
            append(" APPEND ")
            append(quoteIfNeeded(mailbox))
            if (flags != null) append(" (${flags.joinToString(" ")})")
            if (dateTime != null) append(" \"$dateTime\"")
            append(" {${message.size}}")
        }
        override fun equals(other: Any?): Boolean = other is Append && tag == other.tag && line == other.line
        override fun hashCode(): Int = line.hashCode()
    }

    public data class Close(override val tag: String) : ImapCommand() {
        override val line: String get() = "$tag CLOSE"
    }

    public data class Expunge(override val tag: String) : ImapCommand() {
        override val line: String get() = "$tag EXPUNGE"
    }

    public data class Check(override val tag: String) : ImapCommand() {
        override val line: String get() = "$tag CHECK"
    }

    public data class Idle(override val tag: String) : ImapCommand() {
        override val line: String get() = "$tag IDLE"
    }
}

internal fun quoteIfNeeded(s: String): String {
    if (s.isEmpty()) return "\"\""
    // Atom chars per RFC 3501: A-Z a-z 0-9 ! # $ % & ' * + - / = ^ _ ` { | } ~
    val atomChars = "!#$%&'*+-/=^_`{|}~"
    val isAtom = s.all { it.isLetterOrDigit() || it in atomChars }
    if (isAtom) return s
    val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}
