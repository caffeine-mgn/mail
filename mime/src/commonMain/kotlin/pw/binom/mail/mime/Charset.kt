package pw.binom.mail.mime

/**
 * Decodes the bytes using the named charset.
 *
 * Supports a small set of common charsets (`us-ascii`, `utf-8`, `utf-16`,
 * `utf-16be`, `utf-16le`, `iso-8859-1`) on all platforms. Other names are
 * resolved via the platform's charset facility.
 */
internal expect fun ByteArray.decodeString(charset: String): String

/**
 * Encodes the string using the named charset.
 *
 * @see decodeString
 */
internal expect fun String.encodeToByteArray(charset: String): ByteArray
