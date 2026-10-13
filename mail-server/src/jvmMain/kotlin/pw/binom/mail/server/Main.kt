package pw.binom.mail.server

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import pw.binom.tls.socket.SSLContext
import kotlin.io.path.Path
import kotlin.io.path.readBytes

/**
 * Runs the all-in-one in-memory mail server.
 *
 * Environment variables:
 *  - MAIL_HOST (default 0.0.0.0), MAIL_HOSTNAME (default mail.local)
 *  - MAIL_SMTP_PORT (25), MAIL_SUBMISSION_PORT (587), MAIL_IMAP_PORT (143), MAIL_POP3_PORT (110)
 *  - MAIL_USER / MAIL_PASS — a demo account (default user / pass)
 *  - MAIL_TLS_CERT / MAIL_TLS_KEY — DER cert chain + PKCS#8 key, enables STARTTLS/STLS
 */
public fun main(args: Array<String>): Unit = runBlocking {
    val env = System.getenv()
    fun envInt(key: String, default: Int): Int = env[key]?.toIntOrNull() ?: default

    val tls: SSLContext? = run {
        val cert = env["MAIL_TLS_CERT"]
        val key = env["MAIL_TLS_KEY"]
        if (cert != null && key != null) {
            SSLContext.server(listOf(Path(cert).readBytes()), Path(key).readBytes())
        } else {
            null
        }
    }

    val config = MailServerConfig(
        host = env["MAIL_HOST"] ?: "0.0.0.0",
        smtpPort = envInt("MAIL_SMTP_PORT", 25),
        submissionPort = envInt("MAIL_SUBMISSION_PORT", 587),
        imapPort = envInt("MAIL_IMAP_PORT", 143),
        pop3Port = envInt("MAIL_POP3_PORT", 110),
        hostname = env["MAIL_HOSTNAME"] ?: "mail.local",
        tls = tls,
    )

    val store = MailStore()
    store.addUser(env["MAIL_USER"] ?: "user", env["MAIL_PASS"] ?: "pass")

    val selector = SelectorManager(Dispatchers.IO)
    val server = MailServer.start(selector, store, config)

    println("mail server '${config.hostname}' ready")
    println("  SMTP       ${config.host}:${server.smtpPort}")
    println("  Submission ${config.host}:${server.submissionPort} (AUTH required)")
    println("  IMAP       ${config.host}:${server.imapPort}")
    println("  POP3       ${config.host}:${server.pop3Port}")
    println("  STARTTLS/STLS: ${if (tls != null) "enabled" else "disabled (no MAIL_TLS_CERT/KEY)"}")

    server.smtp.await()
}
