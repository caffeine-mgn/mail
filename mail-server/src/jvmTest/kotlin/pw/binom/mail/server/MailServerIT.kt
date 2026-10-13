package pw.binom.mail.server

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import pw.binom.mail.imap.ImapClient
import pw.binom.mail.imap.ImapResponse
import pw.binom.mail.pop3.Pop3Response
import pw.binom.mail.pop3.Pop3Client
import pw.binom.mail.smtp.SmtpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MailServerIT {
    private val selector = SelectorManager(Dispatchers.IO)

    @AfterEach
    fun tearDown() {
        selector.close()
    }

    private fun newStore(): MailStore = MailStore().apply { addUser("it", "secret") }

    @Test
    fun delivered_over_smtp_is_visible_over_imap_and_pop3(): Unit = runBlocking {
        val store = newStore()
        val server = MailServer.start(
            selector, store,
            MailServerConfig(host = "127.0.0.1", smtpPort = 0, submissionPort = 0, imapPort = 0, pop3Port = 0),
        )
        try {
            // 1. Deliver a message over SMTP.
            SmtpClient.connect(selector, "127.0.0.1", server.smtpPort).use { smtp ->
                smtp.ehlo("localhost")
                assertEquals(250, smtp.mail("sender@example.com").code)
                assertEquals(250, smtp.rcpt("it@local").code)
                val body = "Subject: hi\r\n\r\nHello MailServer\r\n"
                assertEquals(250, smtp.data(body).code)
                smtp.quit()
            }

            // 2. It is visible over IMAP (same shared store).
            val untagged = mutableListOf<ImapResponse.Untagged>()
            ImapClient.connect(selector, "127.0.0.1", server.imapPort, onUntagged = { untagged += it }).use { imap ->
                val login = imap.login("it", "secret") as? ImapResponse.Tagged
                    ?: error("expected tagged login response")
                assertEquals(ImapResponse.TagStatus.OK, login.status)
                val sel = imap.select("INBOX") as? ImapResponse.Tagged
                    ?: error("expected tagged select response")
                assertEquals(ImapResponse.TagStatus.OK, sel.status)
                imap.fetch("1", listOf("UID", "RFC822.SIZE"))
                imap.fetch("1", listOf("BODY[]"))
                imap.logout()
            }
            val fetched = untagged.joinToString("\n") { it.raw }
            assertTrue(fetched.contains("UID 1"), "IMAP FETCH should expose UID 1, got: $fetched")
            assertTrue(fetched.contains("RFC822.SIZE"), "IMAP FETCH should expose RFC822.SIZE, got: $fetched")
            val literal = untagged.flatMap { it.literals }.firstOrNull { it.isNotEmpty() }
            assertTrue(literal != null, "IMAP FETCH BODY[] should return a literal")
            assertTrue(
                literal.decodeToString().contains("Hello MailServer"),
                "IMAP literal body: ${literal.decodeToString()}",
            )

            // 3. It is visible over POP3 as well.
            Pop3Client.connect(selector, "127.0.0.1", server.pop3Port).use { pop3 ->
                assertTrue(pop3.user("it").ok)
                assertTrue(pop3.pass("secret").ok)
                val stat = pop3.stat()
                assertTrue(stat.ok && stat.text.startsWith("1 "), "POP3 STAT: ${stat.text}")
                val retr = pop3.retr(1)
                assertTrue(retr is Pop3Response.Multi)
                retr as Pop3Response.Multi
                assertTrue(retr.ok)
                assertTrue(retr.lines.any { it.contains("Hello MailServer") }, "POP3 RETR body: ${retr.lines}")
                pop3.quit()
            }
        } finally {
            server.close()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun submission_requires_auth(): Unit = runBlocking {
        val store = newStore()
        val server = MailServer.start(
            selector, store,
            MailServerConfig(host = "127.0.0.1", smtpPort = 0, submissionPort = 0, imapPort = 0, pop3Port = 0),
        )
        try {
            SmtpClient.connect(selector, "127.0.0.1", server.submissionPort).use { smtp ->
                smtp.ehlo("localhost")
                // Without auth, MAIL FROM must be rejected.
                assertEquals(530, smtp.mail("sender@example.com").code)
                // Authenticate (AUTH PLAIN <base64(\0user\0pass)>).
                val token = Base64.encode("\u0000it\u0000secret".encodeToByteArray())
                assertEquals(235, smtp.auth("PLAIN", token).code)
                assertEquals(250, smtp.mail("sender@example.com").code)
                assertEquals(250, smtp.rcpt("it@local").code)
                assertEquals(250, smtp.data("Subject: sub\r\n\r\nbody\r\n").code)
                smtp.quit()
            }
            // The delivered message reached the store.
            assertEquals(1, store.mailbox("it", "INBOX")?.size())
        } finally {
            server.close()
        }
    }
}
