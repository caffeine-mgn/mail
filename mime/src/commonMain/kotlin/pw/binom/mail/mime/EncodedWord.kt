package pw.binom.mail.mime

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * RFC 2047 encoded-word support.
 *
 * Format: `=?charset?encoding?encoded-text?=`
 *   - encoding: `B` (Base64) or `Q` (Quoted-printable, with `_` instead of ` `)
 *   - charset: source charset name (e.g. `utf-8`); the encoded bytes are
 *     converted to this charset on encode, and interpreted as this charset
 *     on decode.
 *
 * Multiple encoded-words may appear in a single header. The two `=?` markers
 * must be separated by whitespace to be treated as separate encoded-words;
 * we follow RFC 2047 §6.2 for joining across whitespace.
 */
public object EncodedWord {
    private val ENCODED_WORD_REGEX = Regex("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=")

    public fun isEncodedWord(value: String): Boolean = value.startsWith("=?") && value.endsWith("?=") && ENCODED_WORD_REGEX.matches(value)

    /**
     * Decodes an encoded-word (or a concatenation of encoded-words separated by
     * whitespace) into a String. If the input is not an encoded-word, returns
     * it unchanged.
     */
    public fun decode(value: String): String {
        if (!value.contains("=?")) return value
        val result = StringBuilder(value.length)
        val matches = ENCODED_WORD_REGEX.findAll(value).toList()
        if (matches.isEmpty()) return value
        var lastEnd = 0
        for ((i, match) in matches.withIndex()) {
            val between = value.substring(lastEnd, match.range.first)
            val isBetween = i > 0
            if (between.isBlank() && isBetween) {
                // Eat whitespace between two adjacent encoded-words
            } else {
                result.append(between)
            }
            val charset = match.groupValues[1]
            val encoding = match.groupValues[2].uppercase()
            val encoded = match.groupValues[3]
            val bytes = when (encoding) {
                "B" -> decodeBase64(encoded)
                "Q" -> decodeQuotedPrintable(encoded)
                else -> throw MimeFormatException("unknown encoded-word encoding: $encoding")
            }
            result.append(bytes.decodeString(charset))
            lastEnd = match.range.last + 1
        }
        if (lastEnd < value.length) result.append(value.substring(lastEnd))
        return result.toString()
    }

    /**
     * Encodes [text] (a String in [charset]) as a single Base64 encoded-word.
     */
    @OptIn(ExperimentalEncodingApi::class)
    public fun encodeB(text: String, charset: String = "utf-8"): String {
        val bytes = text.encodeToByteArray(charset)
        val b64 = Base64.encode(bytes)
        return "=?$charset?B?$b64?="
    }

    /**
     * Encodes [text] (a String in [charset]) as a single QP encoded-word.
     *
     * Per RFC 2047 §4.2, the set of characters that may be used in a Q-encoded
     * word without quoting is: `!#$%&'*+-/^_`{|}~`, space (as `_`), and
     * ASCII alphanumerics. We also encode `?` and `=` to avoid ambiguity.
     */
    public fun encodeQ(text: String, charset: String = "utf-8"): String {
        val bytes = text.encodeToByteArray(charset)
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == 0x20) {
                sb.append('_')
            } else if (v in 0x21..0x3A || v in 0x3C..0x7E && v.toChar() != '=' && v.toChar() != '?') {
                sb.append(v.toChar())
            } else {
                sb.append('=')
                sb.append(((v ushr 4) and 0xF).toString(16).uppercase())
                sb.append((v and 0xF).toString(16).uppercase())
            }
        }
        return "=?$charset?Q?${sb}?="
    }

    private fun decodeBase64(s: String): ByteArray {
        @OptIn(ExperimentalEncodingApi::class)
        return Base64.decode(s)
    }

    private fun decodeQuotedPrintable(s: String): ByteArray {
        // In RFC 2047 Q encoding, '_' represents ASCII space.
        val out = ByteArray(s.length)
        var i = 0
        var o = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=') {
                if (i + 2 >= s.length) {
                    // Soft line break or malformed; stop
                    break
                }
                val hi = HEX[s[i + 1]] ?: throw MimeFormatException("bad QP hex at $i: ${s.substring(i, i + 3)}")
                val lo = HEX[s[i + 2]] ?: throw MimeFormatException("bad QP hex at $i: ${s.substring(i, i + 3)}")
                out[o++] = ((hi shl 4) or lo).toByte()
                i += 3
            } else if (c == '_') {
                out[o++] = 0x20
                i++
            } else {
                out[o++] = c.code.toByte()
                i++
            }
        }
        return out.copyOf(o)
    }

    private val HEX = mapOf(
        '0' to 0, '1' to 1, '2' to 2, '3' to 3, '4' to 4, '5' to 5, '6' to 6, '7' to 7,
        '8' to 8, '9' to 9, 'A' to 10, 'B' to 11, 'C' to 12, 'D' to 13, 'E' to 14, 'F' to 15,
        'a' to 10, 'b' to 11, 'c' to 12, 'd' to 13, 'e' to 14, 'f' to 15,
    )
}
