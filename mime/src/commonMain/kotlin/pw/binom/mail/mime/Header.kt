package pw.binom.mail.mime

/**
 * A parsed RFC 5322 header field.
 *
 * `name` is the field name (case-insensitive; we store as-is from the wire).
 * `value` is the un-folded value. The value is **raw** — it has NOT been
 * decoded for encoded-words, content-type parameters, or address lists. Use
 * specific parsers ([EncodedWord.decode], [ContentType.parse],
 * [Address.parseList]) on `value` to get a structured form.
 *
 * RFC 5322 §3.6.8 specifies that an obsolete form permits a header value to
 * contain multiple "unstructured" segments. We treat the value as a single
 * un-folded string.
 */
public data class Header(val name: String, val value: String) {
    public val nameLower: String get() = name.lowercase()

    public fun valueDecoded(): String = EncodedWord.decode(value)

    public fun contentType(): ContentType? =
        if (nameLower == "content-type") ContentType.parse(value) else null

    public fun addresses(): List<Address.Mailbox> = when (nameLower) {
        "from", "to", "cc", "bcc", "reply-to", "sender" -> Address.parseList(value)
        else -> emptyList()
    }

    override fun toString(): String = "$name: $value"
}

/**
 * An ordered list of headers with lookup helpers.
 */
public class Headers(public val entries: List<Header>) : List<Header> by entries {
    public fun first(name: String): Header? = entries.firstOrNull { it.nameLower == name.lowercase() }

    public fun all(name: String): List<Header> = entries.filter { it.nameLower == name.lowercase() }

    public fun firstValue(name: String): String? = first(name)?.value

    public fun firstDecodedValue(name: String): String? = first(name)?.valueDecoded()

    public fun contentType(): ContentType? = first("content-type")?.contentType()

    public companion object {
        public val EMPTY: Headers = Headers(emptyList())

        /** @see HeaderParser.parse */
        public fun parse(lines: List<String>): Headers = HeaderParser.parse(lines)
    }
}

/**
 * Index-based header parser that handles obs-fold properly (pushing back lines
 * that don't start with whitespace).
 */
public object HeaderParser {
    public fun parse(lines: List<String>): Headers {
        val out = mutableListOf<Header>()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            if (raw.isEmpty()) { i++; continue }
            val (name, firstValue) = parseHeaderLine(raw)
            val sb = StringBuilder(firstValue)
            i++
            while (i < lines.size) {
                val peek = lines[i]
                if (peek.isEmpty()) break
                if (peek[0] == ' ' || peek[0] == '\t') {
                    if (sb.isNotEmpty() && sb.last() != ' ' && sb.last() != '\t') sb.append(' ')
                    sb.append(peek.trimStart())
                    i++
                } else {
                    break
                }
            }
            out += Header(name, sb.toString())
        }
        return Headers(out)
    }

    private fun parseHeaderLine(line: String): Pair<String, String> {
        val colon = line.indexOf(':')
        if (colon < 0) throw MimeFormatException("header line without ':': $line")
        val name = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trimStart()
        if (name.isEmpty()) throw MimeFormatException("empty header name in: $line")
        if (!name.all { it.isLetterOrDigit() || it == '-' }) {
            throw MimeFormatException("invalid header name: $name")
        }
        return name to value
    }
}
