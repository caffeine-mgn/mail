package pw.binom.mail.mime

/**
 * Content-Transfer-Encoding values from RFC 2045 §6.
 */
public enum class TransferEncoding {
    SEVEN_BIT,
    EIGHT_BIT,
    BINARY,
    QUOTED_PRINTABLE,
    BASE64,
    ;

    public companion object {
        public fun parse(value: String?): TransferEncoding = when (value?.lowercase()?.trim()) {
            null, "7bit" -> SEVEN_BIT
            "8bit" -> EIGHT_BIT
            "binary" -> BINARY
            "quoted-printable" -> QUOTED_PRINTABLE
            "base64" -> BASE64
            else -> throw MimeFormatException("unknown Content-Transfer-Encoding: $value")
        }
    }
}
