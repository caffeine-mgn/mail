package pw.binom.mail.smtp

/**
 * Parses SMTP server responses.
 *
 * Multi-line responses are returned as a single [SmtpResponse] with multiple
 * [Reply] entries.
 */
public object SmtpParser {
    /**
     * Parses a single server response (which may span multiple lines) from a
     * stream of newline-terminated lines.
     *
     * Returns the parsed [SmtpResponse], or `null` on EOF before any data.
     */
    public fun parse(lines: Sequence<String>): SmtpResponse? {
        val iter = lines.iterator()
        if (!iter.hasNext()) return null
        val first = iter.next()
        val firstReply = parseLine(first) ?: return null
        if (!firstReply.continuing) return SmtpResponse(listOf(firstReply))

        val all = mutableListOf(firstReply)
        while (iter.hasNext()) {
            val r = parseLine(iter.next()) ?: return null
            all += r
            if (!r.continuing) return SmtpResponse(all)
        }
        // Reached EOF without a terminating line — RFC 5321 says this is a protocol error,
        // but we'll be lenient and return what we have.
        return SmtpResponse(all)
    }

    /**
     * Parses one line of server response.
     *
     * Returns `null` if the line is not a valid SMTP response.
     */
    public fun parseLine(line: String): Reply? {
        if (line.length < 4) return null
        val codeStr = line.substring(0, 3)
        val sep = line[3]
        if (sep != ' ' && sep != '-') return null
        val code = codeStr.toIntOrNull() ?: return null
        if (code < 100 || code > 599) return null
        val text = line.substring(4)
        return Reply(code, text, continuing = sep == '-')
    }
}
