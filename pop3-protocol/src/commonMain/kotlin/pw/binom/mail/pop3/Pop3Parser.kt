package pw.binom.mail.pop3

/**
 * Parses POP3 server responses.
 *
 * A response begins with `+OK` or `-ERR` followed by an optional space and
 * human-readable text. For multi-line responses (LIST, RETR, TOP, UIDL),
 * the body follows the status line, terminated by a single `.` line.
 */
public object Pop3Parser {
    public const val TERMINATOR: String = "."

    /**
     * Parses a single status line. Returns `null` if the line is not a valid
     * POP3 status line.
     */
    public fun parseStatusLine(line: String): Pop3Response.Single? {
        if (line.length < 3) return null
        return when {
            line.startsWith("+OK") -> {
                val text = if (line.length > 3 && line[3] == ' ') line.substring(4) else ""
                Pop3Response.Single(true, text)
            }
            line.startsWith("-ERR") -> {
                val text = if (line.length > 4 && line[4] == ' ') line.substring(5) else ""
                Pop3Response.Single(false, text)
            }
            else -> null
        }
    }

    /**
     * Parses a complete response, reading lines from [lines]. The iterator
     * is consumed; on a multi-line response, the terminator `.` line is
     * consumed and the body lines (before the terminator) are returned.
     */
    public fun parse(lines: Iterator<String>): Pop3Response? {
        if (!lines.hasNext()) return null
        val status = parseStatusLine(lines.next()) ?: return null
        if (!status.ok) return status
        // Multi-line? Read until we see a "." line on its own.
        val body = mutableListOf<String>()
        while (lines.hasNext()) {
            val line = lines.next()
            if (line == TERMINATOR) {
                return Pop3Response.Multi(true, body)
            }
            body += line
        }
        // EOF before terminator: treat as single-line if body is empty,
        // else multi-line.
        return if (body.isEmpty()) status else Pop3Response.Multi(true, body)
    }
}
