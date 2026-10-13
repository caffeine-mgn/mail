package pw.binom.mail.mime

/**
 * Lexer for header field values. Handles RFC 5322 tokens, quoted-strings, and
 * RFC 5322 comments (CFWS).
 *
 * Used by [ContentType], address parsers, etc.
 */
internal class HeaderTokenizer(private val input: String) {
    private var pos: Int = 0

    fun skipWhitespaceAndCfws() {
        while (pos < input.length) {
            when (input[pos]) {
                ' ', '\t', '\r', '\n' -> pos++
                '(' -> {
                    val end = findMatchingParen(pos) ?: return
                    pos = end
                }
                else -> return
            }
        }
    }

    private fun findMatchingParen(start: Int): Int? {
        var depth = 0
        var i = start
        var inQuotes = false
        while (i < input.length) {
            when (input[i]) {
                '\\' -> { i += 2; continue }
                '"' -> inQuotes = !inQuotes
                '(' -> if (!inQuotes) depth++
                ')' -> {
                    if (inQuotes) { i++; continue }
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return null
    }

    fun readChar(c: Char): Boolean {
        skipWhitespaceAndCfws()
        if (pos < input.length && input[pos] == c) {
            pos++
            return true
        }
        return false
    }

    fun readToken(): String? {
        skipWhitespaceAndCfws()
        val start = pos
        while (pos < input.length && input[pos].isTokenChar()) pos++
        if (start == pos) return null
        return input.substring(start, pos)
    }

    fun readTokenOrQuotedString(): String? {
        skipWhitespaceAndCfws()
        if (pos >= input.length) return null
        if (input[pos] == '"') {
            pos++
            val sb = StringBuilder()
            while (pos < input.length && input[pos] != '"') {
                if (input[pos] == '\\' && pos + 1 < input.length) {
                    sb.append(input[pos + 1])
                    pos += 2
                } else {
                    sb.append(input[pos])
                    pos++
                }
            }
            if (pos < input.length) pos++ // consume closing "
            return sb.toString()
        }
        return readToken()
    }

    fun readToEnd(): String {
        skipWhitespaceAndCfws()
        val s = input.substring(pos)
        pos = input.length
        return s
    }

    fun position(): Int = pos
}

private fun Char.isTokenChar(): Boolean = this in '!'..'~' && this !in "()<>@,;:\\\"/[]?= \t"
