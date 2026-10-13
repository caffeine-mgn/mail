package pw.binom.mail.smtp

import pw.binom.mail.testkit.AbstractContainerIT
import pw.binom.mail.testkit.GreenMailContainer
import pw.binom.mail.testkit.RawMail
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drives [SmtpClient] (commonMain) against a real GreenMail container, then
 * verifies delivery with an independent raw POP3 oracle.
 */
class SmtpClientContainerIT : AbstractContainerIT() {

    @Test
    fun `deliver a message to a real smtp server`() = runTest { selector ->
        val marker = "smtp-container-${System.nanoTime()}"
        val client = SmtpClient.connect(selector, host, smtpPort)
        try {
            val caps = client.ehlo("test.local")
            assertTrue(caps.has("AUTH"), "EHLO should advertise AUTH: $caps")

            val mail = client.mail("sender@test.local")
            assertTrue(mail.isPositive, "MAIL FROM: $mail")

            val rcpt = client.rcpt(address(GreenMailContainer.SMTP_USER))
            assertTrue(rcpt.isPositive, "RCPT TO: $rcpt")

            val data = client.data("Subject: container\r\n\r\n$marker\r\n")
            assertTrue(data.isPositive, "DATA: $data")
        } finally {
            client.quit()
        }

        val messages = RawMail.pop3RetrieveAll(
            host,
            pop3Port,
            GreenMailContainer.SMTP_USER,
            password,
        )
        assertTrue(messages.any { it.contains(marker) }, "delivered message not found: $messages")
    }

    @Test
    fun `reset clears the transaction`() = runTest { selector ->
        val client = SmtpClient.connect(selector, host, smtpPort)
        try {
            client.ehlo("test.local")
            assertTrue(client.mail("a@test.local").isPositive)
            assertTrue(client.rcpt(address(GreenMailContainer.SMTP_USER)).isPositive)
            assertTrue(client.rset().isPositive, "RSET should be accepted")
            assertTrue(client.noop().isPositive, "NOOP should be accepted")
        } finally {
            client.quit()
        }
    }
}
