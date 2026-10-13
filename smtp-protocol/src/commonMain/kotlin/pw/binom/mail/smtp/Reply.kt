package pw.binom.mail.smtp

/**
 * A single-line SMTP reply, possibly the last in a multi-line response.
 *
 * Multi-line replies are encoded as a list of [Reply] entries where all but
 * the last have `continuing = true` (signalled by the `nnn-` form) and the
 * last is terminated by `nnn ` (the `nnn ` form).
 */
public data class Reply(
    val code: Int,
    val text: String,
    val continuing: Boolean = false,
) {
    public val isError: Boolean get() = code >= 500
    public val isTransient: Boolean get() = code in 400..499
    public val isPositive: Boolean get() = code in 200..299
    public val isIntermediate: Boolean get() = code in 300..399
}

/**
 * A complete server response — a sequence of one or more [Reply] lines that
 * share a code.
 */
public class SmtpResponse(public val lines: List<Reply>) {
    init {
        require(lines.isNotEmpty()) { "SmtpResponse must have at least one line" }
    }

    public val code: Int get() = lines.first().code
    public val text: String get() = lines.joinToString("\n") { it.text }
    public val isPositive: Boolean get() = lines.last().isPositive
    public val isError: Boolean get() = lines.last().isError
    public val isTransient: Boolean get() = lines.last().isTransient
    public val isIntermediate: Boolean get() = lines.last().isIntermediate

    public fun textOrNull(): String? = if (isError || isTransient) null else text

    public companion object {
        public fun single(code: Int, text: String): SmtpResponse =
            SmtpResponse(listOf(Reply(code, text, false)))
    }
}
