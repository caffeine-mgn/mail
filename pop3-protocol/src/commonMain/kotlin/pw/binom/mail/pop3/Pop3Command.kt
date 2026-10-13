package pw.binom.mail.pop3

/**
 * POP3 commands from client to server (RFC 1939 + extensions).
 */
public sealed class Pop3Command {
    public abstract val line: String

    public data class User(val name: String) : Pop3Command() {
        override val line: String get() = "USER $name"
    }

    public data class Pass(val password: String) : Pop3Command() {
        override val line: String get() = "PASS $password"
    }

    public object Stat : Pop3Command() {
        override val line: String = "STAT"
    }

    /** LIST [msg]. */
    public data class List(val msg: Int? = null) : Pop3Command() {
        override val line: String get() = if (msg == null) "LIST" else "LIST $msg"
    }

    public data class Retr(val msg: Int) : Pop3Command() {
        override val line: String get() = "RETR $msg"
    }

    public data class Dele(val msg: Int) : Pop3Command() {
        override val line: String get() = "DELE $msg"
    }

    public object Noop : Pop3Command() {
        override val line: String = "NOOP"
    }

    public object Rset : Pop3Command() {
        override val line: String = "RSET"
    }

    public object Quit : Pop3Command() {
        override val line: String = "QUIT"
    }

    public data class Top(val msg: Int, val lines: Int) : Pop3Command() {
        override val line: String get() = "TOP $msg $lines"
    }

    public data class Uidl(val msg: Int? = null) : Pop3Command() {
        override val line: String get() = if (msg == null) "UIDL" else "UIDL $msg"
    }

    public data class Apop(val name: String, val digest: String) : Pop3Command() {
        override val line: String get() = "APOP $name $digest"
    }

    public object Capa : Pop3Command() {
        override val line: String = "CAPA"
    }

    public object Stls : Pop3Command() {
        override val line: String = "STLS"
    }
}
