package pw.binom.mail.imap

/**
 * Parses IMAP response lines (without CRLF).
 *
 * Three line types:
 *   - `* ...` — untagged
 *   - `tag OK|NO|BAD ...` — tagged
 *   - `+ ...` — continuation
 */
public object ImapParser {
    public fun parseLine(line: String): ImapResponse? {
        if (line.isEmpty()) return null
        return when (line[0]) {
            '*' -> {
                val text = line.substring(1).trim()
                ImapResponse.Untagged(line, text)
            }
            '+' -> {
                val text = line.substring(1).trim()
                ImapResponse.Continuation(text)
            }
            else -> {
                val firstSpace = line.indexOf(' ')
                if (firstSpace < 0) return null
                val tag = line.substring(0, firstSpace)
                val rest = line.substring(firstSpace + 1)
                val secondSpace = rest.indexOf(' ')
                if (secondSpace < 0) return null
                val statusStr = rest.substring(0, secondSpace)
                val status = when (statusStr) {
                    "OK" -> ImapResponse.TagStatus.OK
                    "NO" -> ImapResponse.TagStatus.NO
                    "BAD" -> ImapResponse.TagStatus.BAD
                    else -> return null
                }
                val text = rest.substring(secondSpace + 1)
                val (code, body) = parseResponseCodeAtStart(text)
                ImapResponse.Tagged(tag, status, body, code)
            }
        }
    }

    /**
     * Parses a line that may start with optional response code `[KEYWORD value]`
     * followed by human-readable text. Returns the code and the body.
     */
    public fun parseResponseCodeAtStart(text: String): Pair<ImapResponse.ResponseCode?, String> {
        if (text.isEmpty() || text[0] != '[') return null to text
        var i = 1
        // Find matching ] respecting nested parens
        var depth = 0
        var end = -1
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                ']' -> if (depth == 0) { end = i; break }
            }
            i++
        }
        if (end < 0) return null to text
        val inner = text.substring(1, end)
        val space = inner.indexOf(' ')
        val keyword = if (space < 0) inner else inner.substring(0, space)
        val value = if (space < 0) null else inner.substring(space + 1)
        val code = parseCode(keyword, value)
        val rest = text.substring(end + 1).trimStart()
        return code to rest
    }

    /**
     * Parses the response code (if any) at the start of [text] and returns
     * the code + remaining text. The response code is bracketed
     * `[KEYWORD value]` or `[KEYWORD]`.
     */
    public fun parseResponseCode(text: String): Pair<ImapResponse.ResponseCode?, String> {
        if (text.isEmpty() || text[0] != '[') return null to text
        var i = 1
        val end = text.indexOf(']', i)
        if (end < 0) return null to text
        val inner = text.substring(i, end)
        val space = inner.indexOf(' ')
        val keyword = if (space < 0) inner else inner.substring(0, space)
        val value = if (space < 0) null else inner.substring(space + 1)
        val code = parseCode(keyword, value)
        val rest = text.substring(end + 1).trimStart()
        return code to rest
    }

    private fun parseCode(keyword: String, value: String?): ImapResponse.ResponseCode = when (keyword.uppercase()) {
        "CAPABILITY" -> ImapResponse.ResponseCode.Capability(value?.split(' ') ?: emptyList())
        "READ-WRITE" -> ImapResponse.ResponseCode.ReadWrite()
        "READ-ONLY" -> ImapResponse.ResponseCode.ReadOnly()
        "ALERT" -> ImapResponse.ResponseCode.Alert()
        "PERMANENTFLAGS" -> {
            val v = value ?: "()"
            val inner = v.trim().removePrefix("(").removeSuffix(")")
            ImapResponse.ResponseCode.PermanentFlags(if (inner.isEmpty()) emptyList() else inner.split(' '))
        }
        "UIDVALIDITY" -> ImapResponse.ResponseCode.UidValidity(value?.toLongOrNull() ?: 0L)
        "UIDNEXT" -> ImapResponse.ResponseCode.UidNext(value?.toLongOrNull() ?: 0L)
        else -> ImapResponse.ResponseCode.Other(keyword, value)
    }
}
