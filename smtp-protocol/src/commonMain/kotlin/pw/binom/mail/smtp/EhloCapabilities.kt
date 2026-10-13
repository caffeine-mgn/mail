package pw.binom.mail.smtp

/**
 * Parsed EHLO response: the list of extension keywords (and optional
 * parameters) advertised by the server.
 */
public class EhloCapabilities(public val items: List<Capability>) : List<EhloCapabilities.Capability> by items {
    public fun has(keyword: String): Boolean = items.any { it.keyword.equals(keyword, ignoreCase = true) }
    public fun parameter(keyword: String): String? = items.firstOrNull { it.keyword.equals(keyword, ignoreCase = true) }?.parameter

    public data class Capability(val keyword: String, val parameter: String? = null)

    public companion object {
        public val EMPTY: EhloCapabilities = EhloCapabilities(emptyList())

        /**
         * Parses the EHLO response — the multi-line reply to EHLO. The first
         * line is treated as the greeting; subsequent lines are the
         * capabilities (one per line).
         */
        public fun parse(response: SmtpResponse): EhloCapabilities {
            val items = mutableListOf<Capability>()
            for (line in response.lines) {
                val t = line.text
                if (line != response.lines.first()) {
                    // Skip the first line (greeting)
                    val space = t.indexOf(' ')
                    if (space < 0) {
                        items += Capability(t.trim())
                    } else {
                        items += Capability(t.substring(0, space).trim(), t.substring(space + 1).trim())
                    }
                }
            }
            return EhloCapabilities(items)
        }
    }
}
