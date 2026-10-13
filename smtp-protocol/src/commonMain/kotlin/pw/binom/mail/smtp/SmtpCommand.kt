package pw.binom.mail.smtp

/**
 * SMTP commands from client to server (RFC 5321 + extensions).
 *
 * Each command has a wire representation. Some are sent with arguments,
 * others are keywords only.
 */
public sealed class SmtpCommand {
    public abstract val line: String

    /** HELO domain. */
    public data class Helo(val domain: String) : SmtpCommand() {
        override val line: String get() = "HELO $domain"
    }

    /** EHLO domain. */
    public data class Ehlo(val domain: String) : SmtpCommand() {
        override val line: String get() = "EHLO $domain"
    }

    /** AUTH mechanism [mechanism] with optional [initialResponse] (Base64). */
    public data class Auth(val mechanism: String, val initialResponse: String? = null) : SmtpCommand() {
        override val line: String get() = if (initialResponse == null) "AUTH $mechanism"
        else "AUTH $mechanism $initialResponse"
    }

    /** Continuation of an AUTH exchange (Base64-encoded response). */
    public data class AuthData(val data: String) : SmtpCommand() {
        override val line: String get() = data
    }

    /** MAIL FROM:address [SIZE=n] [BODY=8BITMIME] ... */
    public data class Mail(
        val from: String,
        val size: Long? = null,
        val body: String? = null,
        val smtputf8: Boolean = false,
    ) : SmtpCommand() {
        override val line: String = buildString {
            append("MAIL FROM:<")
            append(from)
            append('>')
            size?.let { append(" SIZE=$it") }
            body?.let { append(" BODY=$it") }
            if (smtputf8) append(" SMTPUTF8")
        }
    }

    /** RCPT TO:address. */
    public data class Rcpt(val to: String) : SmtpCommand() {
        override val line: String get() = "RCPT TO:<$to>"
    }

    /** DATA. Followed by message bytes terminated by CRLF.CRLF. */
    public object Data : SmtpCommand() {
        override val line: String = "DATA"
    }

    /** RSET. */
    public object Rset : SmtpCommand() {
        override val line: String = "RSET"
    }

    /** QUIT. */
    public object Quit : SmtpCommand() {
        override val line: String = "QUIT"
    }

    /** NOOP. */
    public object Noop : SmtpCommand() {
        override val line: String = "NOOP"
    }

    /** VRFY user. */
    public data class Vrfy(val user: String) : SmtpCommand() {
        override val line: String get() = "VRFY $user"
    }

    /** EXPN list. */
    public data class Expn(val list: String) : SmtpCommand() {
        override val line: String get() = "EXPN $list"
    }

    /** HELP [command]. */
    public data class Help(val command: String? = null) : SmtpCommand() {
        override val line: String get() = if (command == null) "HELP" else "HELP $command"
    }

    /** STARTTLS. */
    public object StartTls : SmtpCommand() {
        override val line: String = "STARTTLS"
    }
}
