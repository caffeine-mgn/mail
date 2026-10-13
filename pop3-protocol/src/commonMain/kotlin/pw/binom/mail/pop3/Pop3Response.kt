package pw.binom.mail.pop3

/**
 * POP3 server response (RFC 1939 §3).
 *
 * Two response forms:
 *   - positive: `+OK [optional text]`
 *   - negative: `-ERR [optional text]`
 *
 * Some responses are multi-line (LIST, RETR, TOP, UIDL). [lines] holds the
 * raw lines (without the `+OK` prefix) for those; for single-line responses
 * [lines] has exactly one entry.
 */
public sealed class Pop3Response {
    public abstract val ok: Boolean
    public abstract val text: String

    /**
     * Multi-line response. [lines] is the list of body lines (the `+OK` /
     * `-ERR` status line is not included).
     */
    public class Multi(override val ok: Boolean, public val lines: List<String>) : Pop3Response() {
        override val text: String get() = lines.firstOrNull() ?: ""
    }

    /**
     * Single-line response. [text] is the optional human-readable part after
     * `+OK` / `-ERR`.
     */
    public class Single(override val ok: Boolean, override val text: String = "") : Pop3Response() {
        override fun toString(): String = "${if (ok) "+OK" else "-ERR"}${if (text.isEmpty()) "" else " $text"}"
    }

    public companion object {
        public fun ok(text: String = ""): Pop3Response = Single(true, text)
        public fun err(text: String = ""): Pop3Response = Single(false, text)
        public fun multiOk(lines: List<String>): Pop3Response = Multi(true, lines)
    }
}
