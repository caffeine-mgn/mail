package pw.binom.mail.imap

/**
 * An IMAP response line (RFC 3501 §2.2.2 / §7).
 *
 * Three flavors:
 *   - [Untagged] — starts with `*` (e.g. `* OK [CAPABILITY IMAP4rev1] ready`)
 *   - [Tagged] — starts with a client-provided tag (e.g. `A001 OK LOGIN completed`)
 *   - [Continuation] — `+` (server ready for literal data)
 */
public sealed class ImapResponse {
    public abstract val raw: String
    public abstract val text: String

    /**
     * An untagged response. [raw] holds the textual form; if the response carried
     * literals (`{n}`), their raw octets are in [literals] (in order).
     */
    public data class Untagged(
        override val raw: String,
        override val text: String,
        val literals: List<ByteArray> = emptyList(),
    ) : ImapResponse()

    public data class Tagged(
        val tag: String,
        val status: TagStatus,
        public override val text: String,
        val code: ResponseCode? = null,
    ) : ImapResponse() {
        override val raw: String get() = "$tag $status $text"
    }

    public data class Continuation(public override val text: String) : ImapResponse() {
        override val raw: String get() = "+ $text"
    }

    public enum class TagStatus { OK, NO, BAD }

    /**
     * RFC 3501 response codes (e.g. `[CAPABILITY ...]`, `[READ-WRITE]`,
     * `[ALERT]`, `[PERMANENTFLAGS (\\*)]`).
     */
    public sealed class ResponseCode {
        public abstract val keyword: String
        public abstract val value: String?

        public data class Capability(val extensions: List<String>) : ResponseCode() {
            override val keyword: String get() = "CAPABILITY"
            override val value: String? get() = extensions.joinToString(" ")
        }

        public class ReadWrite : ResponseCode() {
            override val keyword: String get() = "READ-WRITE"
            override val value: String? get() = null
        }

        public class ReadOnly : ResponseCode() {
            override val keyword: String get() = "READ-ONLY"
            override val value: String? get() = null
        }

        public class Alert : ResponseCode() {
            override val keyword: String get() = "ALERT"
            override val value: String? get() = null
        }

        public data class PermanentFlags(val flags: List<String>) : ResponseCode() {
            override val keyword: String get() = "PERMANENTFLAGS"
            override val value: String? get() = flags.joinToString(" ")
        }

        public data class UidValidity(val uid: Long) : ResponseCode() {
            override val keyword: String get() = "UIDVALIDITY"
            override val value: String? get() = uid.toString()
        }

        public data class UidNext(val uid: Long) : ResponseCode() {
            override val keyword: String get() = "UIDNEXT"
            override val value: String? get() = uid.toString()
        }

        public data class Other(override val keyword: String, override val value: String?) : ResponseCode()
    }
}
