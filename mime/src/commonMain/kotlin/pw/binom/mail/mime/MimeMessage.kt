package pw.binom.mail.mime

/**
 * A parsed MIME message: a header block and a body.
 *
 * For `multipart/...` bodies, [body] is a [Body.Multipart] with the parts
 * recursively parsed.
 *
 * Body bytes are always the **decoded** form (after Content-Transfer-Encoding
 * has been undone). To re-serialize, call [MimeWriter.write] which will apply
 * the transfer encoding based on the Content-Transfer-Encoding header.
 */
public class MimeMessage(
    public val headers: Headers,
    public val body: Body,
) {
    public sealed class Body {
        /**
         * A single (possibly text or binary) body part.
         *
         * [content] is the decoded bytes. The content's interpretation is
         * governed by the Content-Type header (charset, format, etc.).
         */
        public class Single(
            public val content: ByteArray,
        ) : Body() {
            override fun equals(other: Any?): Boolean = other is Single && content.contentEquals(other.content)
            override fun hashCode(): Int = content.contentHashCode()
        }

        /**
         * A multipart body, with [parts] in order and [boundary] used on the wire.
         */
        public class Multipart(
            public val boundary: String,
            public val parts: List<MimeMessage>,
        ) : Body()
    }

    public fun contentType(): ContentType = headers.contentType() ?: ContentType.TEXT_PLAIN

    public fun transferEncoding(): TransferEncoding =
        TransferEncoding.parse(headers.firstValue("content-transfer-encoding"))

    public fun text(overrideCharset: String? = null): String? {
        val ct = contentType()
        if (!ct.type.equals("text", ignoreCase = true)) return null
        val cs = overrideCharset ?: ct.charset ?: "us-ascii"
        return body.toStringContent(cs)
    }

    private fun Body.toStringContent(charset: String): String = when (this) {
        is Body.Single -> content.decodeString(charset)
        is Body.Multipart -> parts.joinToString("\n") { it.toString() }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MimeMessage) return false
        if (headers != other.headers) return false
        if (body != other.body) return false
        return true
    }

    override fun hashCode(): Int = headers.hashCode() * 31 + body.hashCode()

    override fun toString(): String = "MimeMessage(headers=$headers, body=$body)"

    public companion object {
        /** A simple plain-text message. */
        public fun text(
            from: String,
            to: String,
            subject: String,
            body: String,
            charset: String = "utf-8",
        ): MimeMessage {
            val headers = Headers(
                listOf(
                    Header("From", from),
                    Header("To", to),
                    Header("Subject", EncodedWord.encodeQ(subject, charset)),
                    Header("MIME-Version", "1.0"),
                    Header(
                        "Content-Type",
                        ContentType("text", "plain", mapOf("charset" to charset)).toString(),
                    ),
                    Header("Content-Transfer-Encoding", "8bit"),
                ),
            )
            return MimeMessage(headers, Body.Single(body.encodeToByteArray(charset)))
        }
    }
}
