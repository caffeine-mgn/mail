package pw.binom.mail.smtp

/** Thrown when the SMTP server returns an error response or the wire format is invalid. */
public class SmtpException(message: String, public val code: Int? = null) : RuntimeException(message)
