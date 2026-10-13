package pw.binom.mail.mime

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Serializes a [MimeMessage] back into raw bytes.
 *
 * The serialized form is suitable for SMTP DATA submission. Header folding is
 * applied so that no line exceeds 78 octets (RFC 5322 §2.2.3). Body bytes are
 * re-encoded per the Content-Transfer-Encoding header.
 */
public object MimeWriter {
    private const val HEX = "0123456789ABCDEF"

    public fun write(message: MimeMessage): ByteArray {
        val out = ArrayList<Byte>()
        writeHeaders(out, message.headers)
        val body = message.body
        if (body is MimeMessage.Body.Single) {
            val te = message.transferEncoding()
            out += '\r'.code.toByte()
            out += '\n'.code.toByte()
            writeSingleBody(out, body.content, te)
        } else if (body is MimeMessage.Body.Multipart) {
            out += '\r'.code.toByte()
            out += '\n'.code.toByte()
            writeMultipart(out, body)
        }
        return out.toByteArray()
    }

    private fun writeMultipart(out: ArrayList<Byte>, body: MimeMessage.Body.Multipart) {
        val parts = body.parts
        for (idx in parts.indices) {
            val part = parts[idx]
            out += "--${body.boundary}".encodeToByteArray().toList()
            out += '\r'.code.toByte()
            out += '\n'.code.toByte()
            val partBytes = write(part)
            for (b in partBytes) out += b
            val needCrlf = partBytes.isEmpty() || partBytes.last() != '\n'.code.toByte()
            if (needCrlf) {
                out += '\r'.code.toByte()
                out += '\n'.code.toByte()
            }
        }
        out += "--${body.boundary}--".encodeToByteArray().toList()
        out += '\r'.code.toByte()
        out += '\n'.code.toByte()
    }

    private fun writeHeaders(out: ArrayList<Byte>, headers: Headers) {
        for (h in headers) {
            val line = "${h.name}: ${h.value}"
            // Fold lines to <= 78 octets
            val folded = foldLine(line, 78)
            for (f in folded) {
                for (b in f.encodeToByteArray()) out += b
            }
            out += '\r'.code.toByte()
            out += '\n'.code.toByte()
        }
    }

    private fun foldLine(line: String, max: Int): List<String> {
        if (line.length <= max) return listOf(line)
        val out = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            val end = (i + max).coerceAtMost(line.length)
            // Try to break at whitespace
            var breakAt = end
            if (end < line.length) {
                var j = end
                while (j > i && line[j] != ' ' && line[j] != '\t') j--
                if (j > i) breakAt = j
            }
            val chunk = line.substring(i, breakAt)
            out += if (i == 0) chunk else " $chunk".trimStart().let { "\t$it" }
            i = breakAt
            // Skip the whitespace at the break point
            while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        }
        return out
    }

    private fun writeSingleBody(out: ArrayList<Byte>, content: ByteArray, te: TransferEncoding) {
        when (te) {
            TransferEncoding.SEVEN_BIT, TransferEncoding.EIGHT_BIT, TransferEncoding.BINARY -> {
                out += content.toList()
            }
            TransferEncoding.QUOTED_PRINTABLE -> {
                encodeQuotedPrintable(out, content)
            }
            TransferEncoding.BASE64 -> {
                @OptIn(ExperimentalEncodingApi::class)
                val b64 = Base64.encode(content)
                for (i in b64.indices step 76) {
                    val end = (i + 76).coerceAtMost(b64.length)
                    for (k in i until end) out += b64[k].code.toByte()
                    out += '\r'.code.toByte()
                    out += '\n'.code.toByte()
                }
            }
        }
    }

    private fun encodeQuotedPrintable(out: ArrayList<Byte>, data: ByteArray) {
        var lineLength = 0
        for (b in data) {
            val v = b.toInt() and 0xFF
            val needsEncode = v == '='.code || v < 0x20 || v >= 0x7F
            val encoded = if (needsEncode) "=" + HEX[(v ushr 4) and 0xF] + HEX[v and 0xF] else v.toChar().toString()
            if (lineLength + encoded.length > 75) {
                out += '='.code.toByte()
                out += '\r'.code.toByte()
                out += '\n'.code.toByte()
                lineLength = 0
            }
            for (c in encoded) {
                out += c.code.toByte()
                lineLength++
            }
            if (v == '\n'.code || v == '\r'.code) lineLength = 0
        }
    }
}
