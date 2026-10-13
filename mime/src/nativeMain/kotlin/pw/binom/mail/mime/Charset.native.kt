package pw.binom.mail.mime

private fun normalizeName(charset: String): String = charset.trim().lowercase()

internal actual fun ByteArray.decodeString(charset: String): String {
    return when (normalizeName(charset)) {
        "us-ascii", "ascii", "ansi_x3.4-1968", "iso646-us" -> {
            val sb = StringBuilder(size)
            for (b in this) sb.append((b.toInt() and 0x7F).toChar())
            sb.toString()
        }

        "utf-8", "utf8" -> decodeToString()

        "iso-8859-1", "latin1", "latin-1", "iso8859-1", "iso_8859-1" -> {
            val sb = StringBuilder(size)
            for (b in this) sb.append((b.toInt() and 0xFF).toChar())
            sb.toString()
        }

        "utf-16", "utf-16le", "utf-16be" -> decodeUtf16(normalizeName(charset).endsWith("be"))

        else -> decodeToString()
    }
}

private fun ByteArray.decodeUtf16(forceBe: Boolean): String {
    if (size < 2) return ""
    var be = forceBe
    var start = 0
    val b0 = this[0].toInt() and 0xFF
    val b1 = this[1].toInt() and 0xFF
    if (b0 == 0xFE && b1 == 0xFF) {
        be = true
        start = 2
    } else if (b0 == 0xFF && b1 == 0xFE) {
        be = false
        start = 2
    }
    val sb = StringBuilder((size - start) / 2)
    var i = start
    while (i + 1 < size) {
        val x = this[i].toInt() and 0xFF
        val y = this[i + 1].toInt() and 0xFF
        val code = if (be) (x shl 8) or y else (y shl 8) or x
        sb.append(code.toChar())
        i += 2
    }
    return sb.toString()
}

internal actual fun String.encodeToByteArray(charset: String): ByteArray {
    return when (normalizeName(charset)) {
        "us-ascii", "ascii", "ansi_x3.4-1968", "iso646-us" ->
            ByteArray(length) { this[it].code.toByte() }

        "utf-8", "utf8" -> this.encodeToByteArray()

        "iso-8859-1", "latin1", "latin-1", "iso8859-1", "iso_8859-1" ->
            ByteArray(length) { this[it].code.toByte() }

        "utf-16", "utf-16le", "utf-16be" -> {
            val name = normalizeName(charset)
            when {
                name == "utf-16be" -> encodeUtf16(be = true, bom = false)
                name == "utf-16le" -> encodeUtf16(be = false, bom = false)
                else -> encodeUtf16(be = true, bom = true)
            }
        }

        else -> this.encodeToByteArray()
    }
}

private fun String.encodeUtf16(be: Boolean, bom: Boolean): ByteArray {
    val out = ArrayList<Byte>(length * 2 + 2)
    if (bom) {
        out.add(0xFE.toByte())
        out.add(0xFF.toByte())
    }
    for (c in this) {
        val code = c.code
        if (be) {
            out.add((code shr 8).toByte())
            out.add(code.toByte())
        } else {
            out.add(code.toByte())
            out.add((code shr 8).toByte())
        }
    }
    return out.toByteArray()
}
