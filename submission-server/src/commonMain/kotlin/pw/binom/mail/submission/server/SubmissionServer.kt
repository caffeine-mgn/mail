package pw.binom.mail.submission.server

import io.ktor.network.selector.SelectorManager
import pw.binom.mail.smtp.server.SmtpAuthenticator
import pw.binom.mail.smtp.server.SmtpMailbox
import pw.binom.mail.smtp.server.SmtpServer
import pw.binom.tls.socket.SSLContext

/**
 * Authenticates a user against a (user, password) table for the Submission service.
 */
public fun interface SubmissionAuthenticator {
    public fun authenticate(user: String, password: String): Boolean

    public companion object {
        public fun of(table: Map<String, String>): SubmissionAuthenticator =
            SubmissionAuthenticator { user, password -> table[user] == password }
    }
}

/**
 * Submission server (RFC 6409): an SMTP server that always requires authentication
 * before accepting a message. Thin, reusable wrapper over [SmtpServer] so callers can
 * stand up a submission-only endpoint without pulling in the whole composed server.
 *
 * Usage:
 *   val server = SubmissionServer.start(selectorManager, authenticator = SubmissionAuthenticator.of(users), mailbox = mailbox)
 *   // ... clients submit to server.localPort ...
 *   server.close()
 */
public class SubmissionServer internal constructor(
    private val delegate: SmtpServer,
) : AutoCloseable {
    public val localPort: Int get() = delegate.localPort
    public val mailbox: SmtpMailbox get() = delegate.mailbox
    public val isRunning: Boolean get() = delegate.isRunning

    public suspend fun await(): Unit = delegate.await()

    override fun close(): Unit = delegate.close()

    public companion object {
        public suspend fun start(
            selectorManager: SelectorManager,
            port: Int = 587,
            host: String = "127.0.0.1",
            authenticator: SubmissionAuthenticator,
            mailbox: SmtpMailbox = pw.binom.mail.smtp.server.InMemorySmtpMailbox(),
            localDomain: String = "localhost",
            greeting: String = "mail",
            tls: SSLContext? = null,
        ): SubmissionServer {
            val auth = SmtpAuthenticator { user, password -> authenticator.authenticate(user, password) }
            val server = SmtpServer.start(
                selectorManager = selectorManager,
                port = port,
                host = host,
                mailbox = mailbox,
                localDomain = localDomain,
                greeting = greeting,
                auth = auth,
                requireAuth = true,
                tls = tls,
            )
            return SubmissionServer(server)
        }
    }
}
