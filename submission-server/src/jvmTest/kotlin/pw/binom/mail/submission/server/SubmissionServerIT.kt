package pw.binom.mail.submission.server

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.runBlocking
import pw.binom.mail.submission.SubmissionClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubmissionServerIT {

    @Test
    fun `authenticated submission is stored in the mailbox`() = runBlocking {
        val mailbox = pw.binom.mail.smtp.server.InMemorySmtpMailbox()
        val server = SubmissionServer.start(
            selectorManager = SelectorManager(),
            port = 0,
            authenticator = SubmissionAuthenticator.of(mapOf("sub" to "secret")),
            mailbox = mailbox,
        )
        try {
            val client = SubmissionClient.connect(
                selectorManager = SelectorManager(),
                host = "127.0.0.1",
                port = server.localPort,
                user = "sub",
                password = "secret",
                localDomain = "test.local",
            )
            try {
                assertTrue(client.mail("sender@test.local").isPositive)
                assertTrue(client.rcpt("sub@localhost").isPositive)
                assertTrue(client.data("Subject: x\r\n\r\nbody\r\n").isPositive)
            } finally {
                client.quit()
            }
            assertEquals(1, mailbox.all().size)
            assertTrue(mailbox.all().first().data.decodeToString().contains("body"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `submission rejects a bad password`() = runBlocking {
        val server = SubmissionServer.start(
            selectorManager = SelectorManager(),
            port = 0,
            authenticator = SubmissionAuthenticator.of(mapOf("sub" to "secret")),
        )
        try {
            assertFailsWith<Throwable> {
                SubmissionClient.connect(
                    selectorManager = SelectorManager(),
                    host = "127.0.0.1",
                    port = server.localPort,
                    user = "sub",
                    password = "wrong",
                    localDomain = "test.local",
                )
            }
        } finally {
            server.close()
        }
    }
}
