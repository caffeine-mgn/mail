package pw.binom.mail.submission

import pw.binom.mail.testkit.AbstractContainerIT
import pw.binom.mail.testkit.GreenMailContainer
import pw.binom.mail.testkit.RawMail
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drives [SubmissionClient] (commonMain, RFC 6409 + SASL) against a real
 * GreenMail container: connect, AUTH, submit, then verify via raw POP3.
 */
class SubmissionClientContainerIT : AbstractContainerIT() {

    @Test
    fun `authenticated submission delivers a message`() = runTest { selector ->
        val marker = "submission-container-${System.nanoTime()}"
        val client = SubmissionClient.connect(
            selectorManager = selector,
            host = host,
            port = smtpPort,
            user = GreenMailContainer.SUBMISSION_USER,
            password = password,
            localDomain = "test.local",
            trustAll = true,
        )
        try {
            assertTrue(client.mail("sender@test.local").isPositive, "MAIL FROM")
            assertTrue(client.rcpt(address(GreenMailContainer.SUBMISSION_USER)).isPositive, "RCPT TO")
            assertTrue(client.data("Subject: submission\r\n\r\n$marker\r\n").isPositive, "DATA")
        } finally {
            client.quit()
        }

        val messages = RawMail.pop3RetrieveAll(
            host,
            pop3Port,
            GreenMailContainer.SUBMISSION_USER,
            password,
        )
        assertTrue(messages.any { it.contains(marker) }, "delivered message not found: $messages")
    }
}
