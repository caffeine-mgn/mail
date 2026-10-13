package pw.binom.mail.server

import io.ktor.network.selector.SelectorManager
import pw.binom.mail.imap.ImapAuthenticator
import pw.binom.mail.imap.server.ImapServer
import pw.binom.mail.pop3.Pop3Authenticator
import pw.binom.mail.pop3.server.Pop3Server
import pw.binom.mail.smtp.server.SmtpAuthenticator
import pw.binom.mail.smtp.server.SmtpServer
import pw.binom.tls.socket.SSLContext

/** Ports, hostname and TLS settings for [MailServer]. Port `0` = ephemeral. */
public class MailServerConfig(
    public val host: String = "0.0.0.0",
    public val smtpPort: Int = 25,
    public val submissionPort: Int = 587,
    public val imapPort: Int = 143,
    public val pop3Port: Int = 110,
    public val hostname: String = "mail.local",
    public val tls: SSLContext? = null,
    public val requireSmtpAuth: Boolean = false,
)

/**
 * All-in-one in-memory mail server: SMTP (25), Submission (587), IMAP (143)
 * and POP3 (110) over a single shared [MailStore]. A message delivered over
 * SMTP becomes immediately visible over IMAP/POP3.
 */
public class MailServer internal constructor(
    public val store: MailStore,
    public val smtp: SmtpServer,
    public val submission: SmtpServer,
    public val imap: ImapServer,
    public val pop3: Pop3Server,
) : AutoCloseable {
    public val smtpPort: Int get() = smtp.localPort
    public val submissionPort: Int get() = submission.localPort
    public val imapPort: Int get() = imap.localPort
    public val pop3Port: Int get() = pop3.localPort

    override fun close() {
        try { smtp.close() } catch (_: Throwable) {}
        try { submission.close() } catch (_: Throwable) {}
        try { imap.close() } catch (_: Throwable) {}
        try { pop3.close() } catch (_: Throwable) {}
    }

    public companion object {
        public suspend fun start(
            selectorManager: SelectorManager,
            store: MailStore,
            config: MailServerConfig = MailServerConfig(),
        ): MailServer {
            val smtpAuth = SmtpAuthenticator { user, password -> store.authenticate(user, password) }
            val smtpRouter = SmtpDeliveryRouter(store)

            val smtp = SmtpServer.start(
                selectorManager = selectorManager,
                port = config.smtpPort,
                host = config.host,
                mailbox = smtpRouter,
                localDomain = config.hostname,
                greeting = config.hostname,
                auth = smtpAuth,
                requireAuth = config.requireSmtpAuth,
                tls = config.tls,
            )
            val submission = SmtpServer.start(
                selectorManager = selectorManager,
                port = config.submissionPort,
                host = config.host,
                mailbox = smtpRouter,
                localDomain = config.hostname,
                greeting = config.hostname,
                auth = smtpAuth,
                requireAuth = true,
                tls = config.tls,
            )
            val imap = ImapServer.start(
                selectorManager = selectorManager,
                port = config.imapPort,
                host = config.host,
                authenticator = ImapAuthenticator { user, password -> store.authenticate(user, password) },
                userMailboxes = { user -> store.mailboxesOf(user) },
                mailboxProvider = { user, name -> store.mailbox(user, name)?.let { ImapMailboxAdapter(it) } },
                tls = config.tls,
            )
            val pop3 = Pop3Server.start(
                selectorManager = selectorManager,
                port = config.pop3Port,
                host = config.host,
                mailboxProvider = { user -> Pop3MailboxAdapter(store.ensureMailbox(user, "INBOX")) },
                auth = Pop3Authenticator { user, password -> store.authenticate(user, password) },
                tls = config.tls,
            )
            return MailServer(store, smtp, submission, imap, pop3)
        }
    }
}
