package pw.binom.mail.mime

import java.nio.charset.Charset

internal actual fun ByteArray.decodeString(charset: String): String =
    String(this, Charset.forName(charset))

internal actual fun String.encodeToByteArray(charset: String): ByteArray =
    toByteArray(Charset.forName(charset))
