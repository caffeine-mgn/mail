package pw.binom.mail.mime

/**
 * RFC 2045/2046 Content-Type value, including parameters.
 *
 * For example `text/plain; charset=utf-8` parses to
 *   ContentType("text", "plain", mapOf("charset" to "utf-8")).
 *
 * Type and subtype are lower-cased. Parameter names are lower-cased; values
 * keep their case (except for `charset`, which is lower-cased per RFC 2978).
 */
public data class ContentType(
    val type: String,
    val subtype: String,
    val parameters: Map<String, String> = emptyMap(),
) {
    public val mediaType: String get() = "$type/$subtype"

    public fun parameter(name: String): String? = parameters[name.lowercase()]

    public val charset: String? get() = parameter("charset")

    public fun withParameter(name: String, value: String): ContentType =
        copy(parameters = parameters + (name.lowercase() to value))

    override fun toString(): String {
        if (parameters.isEmpty()) return mediaType
        val sb = StringBuilder(mediaType)
        for ((k, v) in parameters) {
            sb.append("; ")
            sb.append(k)
            sb.append('=')
            if (v.all { it.isTokenChar() } && v.isNotEmpty()) {
                sb.append(v)
            } else {
                sb.append('"')
                sb.append(v.replace("\\", "\\\\").replace("\"", "\\\""))
                sb.append('"')
            }
        }
        return sb.toString()
    }

    public companion object {
        public val TEXT_PLAIN: ContentType = ContentType("text", "plain", mapOf("charset" to "us-ascii"))
        public val MESSAGE_RFC822: ContentType = ContentType("message", "rfc822")
        public val MULTIPART_MIXED: ContentType = ContentType("multipart", "mixed")
        public val MULTIPART_ALTERNATIVE: ContentType = ContentType("multipart", "alternative")
        public val MULTIPART_RELATED: ContentType = ContentType("multipart", "related")
        public val APPLICATION_OCTET_STREAM: ContentType = ContentType("application", "octet-stream")

        /**
         * Parses a Content-Type header value (without the leading "Content-Type: ").
         * Throws [MimeFormatException] on malformed input.
         */
        public fun parse(value: String): ContentType {
            val tokenizer = HeaderTokenizer(value)
            val type = tokenizer.readToken() ?: throw MimeFormatException("missing type in Content-Type: $value")
            if (!tokenizer.readChar('/')) {
                throw MimeFormatException("missing '/' in Content-Type: $value")
            }
            val subtype = tokenizer.readToken() ?: throw MimeFormatException("missing subtype in Content-Type: $value")
            val params = LinkedHashMap<String, String>()
            while (true) {
                tokenizer.skipWhitespaceAndCfws()
                if (!tokenizer.readChar(';')) break
                tokenizer.skipWhitespaceAndCfws()
                val name = tokenizer.readToken()?.lowercase()
                    ?: throw MimeFormatException("missing parameter name in Content-Type: $value")
                tokenizer.skipWhitespaceAndCfws()
                if (!tokenizer.readChar('=')) {
                    throw MimeFormatException("missing '=' after parameter $name in Content-Type: $value")
                }
                tokenizer.skipWhitespaceAndCfws()
                val v = tokenizer.readTokenOrQuotedString()
                    ?: throw MimeFormatException("missing parameter value for $name in Content-Type: $value")
                params[name] = if (name == "charset") v.lowercase() else v
            }
            return ContentType(type.lowercase(), subtype.lowercase(), params)
        }
    }
}

private fun Char.isTokenChar(): Boolean = this in '!'..'~' && this !in "()<>@,;:\\\"/[]?= \t"
