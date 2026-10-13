package pw.binom.mail.mime

/**
 * RFC 5322 mailbox (an email address with optional display name).
 *
 * Parsed forms:
 *   - `Name <local@domain>`            — display name + address
 *   - `"Quoted Name" <local@domain>`
 *   - `local@domain`                    — address only
 *   - `Group: a@x, b@y;`                — group of addresses (less common in mail envelopes)
 *
 * Group syntax is parsed but exposed as a list of [Address] entries.
 */
public sealed class Address {
    public data class Mailbox(val name: String?, val localPart: String, val domain: String) : Address() {
        public val address: String get() = if (domain.isEmpty()) localPart else "$localPart@$domain"

        override fun toString(): String {
            if (name == null) return address
            val needsQuoting = name.any { c -> c in "()<>@,;:\\\"\t." || c.isWhitespace() }
            return if (needsQuoting) {
                "\"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\" <$address>"
            } else {
                "$name <$address>"
            }
        }
    }

    public data class Group(val name: String, val mailboxes: List<Mailbox>) : Address()

    public companion object {
        /**
         * Parses a single address (or group). Use [parseList] for header values
         * that may contain multiple comma-separated addresses.
         */
        public fun parse(value: String): Address {
            val v = value.trim()
            if (v.isEmpty()) throw MimeFormatException("empty address")
            val (display, addr) = splitDisplay(v)
            return parseInner(display, addr)
        }

        /**
         * Parses a comma-separated list of addresses (header style).
         * Whitespace inside a quoted-string or comment is preserved.
         */
        public fun parseList(value: String): List<Mailbox> {
            val out = mutableListOf<Mailbox>()
            var depth = 0
            var inQuotes = false
            var start = 0
            var i = 0
            while (i < value.length) {
                when {
                    value[i] == '\\' && inQuotes -> i += 2
                    value[i] == '"' -> { inQuotes = !inQuotes; i++ }
                    value[i] == '(' && !inQuotes -> { depth++; i++ }
                    value[i] == ')' && !inQuotes -> { depth--; i++ }
                    value[i] == '<' && !inQuotes -> { depth++; i++ }
                    value[i] == '>' && !inQuotes -> { depth--; i++ }
                    value[i] == ',' && depth == 0 && !inQuotes -> {
                        val part = value.substring(start, i).trim()
                        if (part.isNotEmpty()) out += parseOne(part)
                        start = i + 1
                        i++
                    }
                    else -> i++
                }
            }
            val tail = value.substring(start).trim()
            if (tail.isNotEmpty()) out += parseOne(tail)
            return out
        }

        private fun parseOne(s: String): Mailbox {
            when (val a = parse(s)) {
                is Mailbox -> return a
                is Group -> if (a.mailboxes.size == 1) return a.mailboxes[0]
                    else throw MimeFormatException("group in single-address position: $s")
            }
        }

        private fun parseInner(display: String?, addr: String): Address {
            if (addr.startsWith(":")) {
                // Group: addr starts with ":" which means the form was `Name: addr1, addr2;`
                return Group(display ?: "", emptyList())
            }
            val at = addr.lastIndexOf('@')
            return if (at < 0) {
                // local-part only (e.g. postmaster)
                Mailbox(display, addr, "")
            } else {
                Mailbox(display, addr.substring(0, at), addr.substring(at + 1))
            }
        }

        private fun splitDisplay(v: String): Pair<String?, String> {
            val lt = v.lastIndexOf('<')
            if (lt >= 0) {
                val gt = v.indexOf('>', lt)
                if (gt < 0) throw MimeFormatException("unclosed '<' in address: $v")
                val addr = v.substring(lt + 1, gt)
                val display = v.substring(0, lt).trim().trimEnd(',')
                val displayClean = if (display.startsWith("\"") && display.endsWith("\"")) {
                    unescapeQuotes(display.substring(1, display.length - 1))
                } else display
                return displayClean.ifEmpty { null } to addr
            }
            return null to v
        }

        private fun unescapeQuotes(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2 }
                else { sb.append(s[i]); i++ }
            }
            return sb.toString()
        }
    }
}
