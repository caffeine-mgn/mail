package pw.binom.mail.mime

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Parses an RFC 5322 / RFC 2045–2049 message from raw bytes.
 *
 * Handles:
 *   - header section with obs-fold joining
 *   - Content-Transfer-Encoding decoding for body (7bit, 8bit, binary,
 *     quoted-printable, base64)
 *   - multipart/... bodies, recursively parsing parts
 *
 * For text bodies the returned [MimeMessage.Body.Single.content] is the raw
 * decoded bytes. Use [MimeMessage.text] to get a String with a charset.
 */
public object MimeParser {
    public fun parse(data: ByteArray): MimeMessage = parse(data, 0, data.size)

    public fun parse(data: ByteArray, offset: Int, length: Int): MimeMessage {
        val end = offset + length
        val headerLines = readHeaderLines(data, offset, end)
        val headers = HeaderParser.parse(headerLines)
        val bodyStart = findBodyStart(data, offset, end, headerLines)
        val ct = headers.contentType() ?: ContentType.TEXT_PLAIN
        val te = TransferEncoding.parse(headers.firstValue("content-transfer-encoding"))
        if (ct.type.equals("multipart", ignoreCase = true)) {
            val boundary = ct.parameter("boundary")
                ?: throw MimeFormatException("multipart Content-Type without boundary: $ct")
            val body = parseMultipart(data, bodyStart, end, boundary, te)
            return MimeMessage(headers, body)
        }
        val decoded = decodeBody(data, bodyStart, end, te)
        return MimeMessage(headers, MimeMessage.Body.Single(decoded))
    }

    private fun readHeaderLines(data: ByteArray, offset: Int, end: Int): List<String> {
        val out = mutableListOf<String>()
        var i = offset
        val sb = StringBuilder()
        while (i < end) {
            val nl = findLineEnd(data, i, end)
            val lineEnd = if (nl > i && data[nl - 1] == '\r'.code.toByte()) nl - 1 else nl
            val line = data.decodeToString(i, lineEnd)
            // Empty line = end of headers
            if (line.isEmpty()) break
            out += line
            i = nl + 1
            if (i < end && (data[i] == '\n'.code.toByte() || data[i] == '\r'.code.toByte())) {
                // CRLF or LF — already skipped
            }
        }
        return out
    }

    private fun findBodyStart(data: ByteArray, offset: Int, end: Int, headerLines: List<String>): Int {
        var i = offset
        repeat(headerLines.size) {
            i = findLineEnd(data, i, end) + 1
        }
        // Skip the empty separator line
        if (i < end) {
            val nl = findLineEnd(data, i, end)
            val lineEnd = if (nl > i && data[nl - 1] == '\r'.code.toByte()) nl - 1 else nl
            if (i == lineEnd) i = nl + 1
        }
        return i
    }

    private fun findLineEnd(data: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i < end) {
            val b = data[i]
            if (b == '\n'.code.toByte()) return i
            if (b == '\r'.code.toByte()) {
                if (i + 1 < end && data[i + 1] == '\n'.code.toByte()) return i + 1
                return i
            }
            i++
        }
        return end
    }

    private fun parseMultipart(
        data: ByteArray,
        offset: Int,
        end: Int,
        boundary: String,
        te: TransferEncoding,
    ): MimeMessage.Body.Multipart {
        // The body of a multipart is decoded per its transfer encoding
        val decoded = decodeBody(data, offset, end, te)
        val parts = splitByBoundary(decoded, boundary)
        return MimeMessage.Body.Multipart(boundary, parts.map { parse(it) })
    }

    private fun decodeBody(data: ByteArray, offset: Int, end: Int, te: TransferEncoding): ByteArray = when (te) {
        TransferEncoding.SEVEN_BIT, TransferEncoding.EIGHT_BIT, TransferEncoding.BINARY -> data.copyOfRange(offset, end)
        TransferEncoding.QUOTED_PRINTABLE -> decodeQuotedPrintable(data, offset, end)
        TransferEncoding.BASE64 -> decodeBase64(data, offset, end)
    }

    private fun decodeQuotedPrintable(data: ByteArray, offset: Int, end: Int): ByteArray {
        val out = ArrayList<Byte>(end - offset)
        var i = offset
        while (i < end) {
            val b = data[i]
            when {
                b == '='.code.toByte() -> {
                    if (i + 2 < end && data[i + 1] == '\r'.code.toByte() && data[i + 2] == '\n'.code.toByte()) {
                        // Soft line break
                        i += 3
                    } else if (i + 1 < end && data[i + 1] == '\n'.code.toByte()) {
                        i += 2
                    } else if (i + 2 < end) {
                        val hi = HEX[data[i + 1].toInt().toChar()] ?: 0
                        val lo = HEX[data[i + 2].toInt().toChar()] ?: 0
                        out += (((hi shl 4) or lo) and 0xFF).toByte()
                        i += 3
                    } else {
                        i++
                    }
                }
                b == '\r'.code.toByte() -> {
                    if (i + 1 < end && data[i + 1] == '\n'.code.toByte()) {
                        out += '\n'.code.toByte()
                        i += 2
                    } else {
                        out += b
                        i++
                    }
                }
                else -> {
                    out += b
                    i++
                }
            }
        }
        return out.toByteArray()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64(data: ByteArray, offset: Int, end: Int): ByteArray {
        // Strip whitespace
        val filtered = ByteArray(end - offset)
        var n = 0
        for (i in offset until end) {
            val b = data[i]
            if (b == ' '.code.toByte() || b == '\t'.code.toByte() ||
                b == '\r'.code.toByte() || b == '\n'.code.toByte()
            ) continue
            filtered[n++] = b
        }
        return Base64.decode(filtered.copyOf(n))
    }

    private fun splitByBoundary(data: ByteArray, boundary: String): List<ByteArray> {
        val delim = "--$boundary".encodeToByteArray()
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < data.size) {
            val nl = findLineEnd(data, i, data.size)
            val lineEnd = if (nl > i && data[nl - 1] == '\r'.code.toByte()) nl - 1 else nl
            val line = data.copyOfRange(i, lineEnd)
            i = nl + 1
            if (matchesPrefix(line, delim)) {
                val rest = line.size - delim.size
                val isFinal = rest >= 2 && line[delim.size] == '-'.code.toByte() && line[delim.size + 1] == '-'.code.toByte()
                if (isFinal) break
                // Find next boundary
                val nextStart = findNextBoundary(data, i, data.size, delim)
                if (nextStart < 0) {
                    out += data.copyOfRange(i, data.size)
                    break
                }
                val partEnd = nextStart
                // trim trailing CRLF before the next boundary
                var actualEnd = partEnd
                if (actualEnd > i && data[actualEnd - 1] == '\n'.code.toByte()) actualEnd--
                if (actualEnd > i && data[actualEnd - 1] == '\r'.code.toByte()) actualEnd--
                out += data.copyOfRange(i, actualEnd)
                i = nextStart
            }
        }
        return out
    }

    private fun findNextBoundary(data: ByteArray, from: Int, end: Int, delim: ByteArray): Int {
        var i = from
        while (i < end) {
            val nl = findLineEnd(data, i, end)
            val lineEnd = if (nl > i && data[nl - 1] == '\r'.code.toByte()) nl - 1 else nl
            val lineStart = i
            if (matchesPrefix(data, lineStart, lineEnd, delim)) return lineStart
            i = nl + 1
        }
        return -1
    }

    private fun matchesPrefix(data: ByteArray, start: Int, end: Int, prefix: ByteArray): Boolean {
        if (end - start < prefix.size) return false
        for (i in prefix.indices) {
            if (data[start + i] != prefix[i]) return false
        }
        return true
    }

    private fun matchesPrefix(line: ByteArray, prefix: ByteArray): Boolean = matchesPrefix(line, 0, line.size, prefix)

    private val HEX = mapOf(
        '0' to 0, '1' to 1, '2' to 2, '3' to 3, '4' to 4, '5' to 5, '6' to 6, '7' to 7,
        '8' to 8, '9' to 9, 'A' to 10, 'B' to 11, 'C' to 12, 'D' to 13, 'E' to 14, 'F' to 15,
        'a' to 10, 'b' to 11, 'c' to 12, 'd' to 13, 'e' to 14, 'f' to 15,
    )
}
