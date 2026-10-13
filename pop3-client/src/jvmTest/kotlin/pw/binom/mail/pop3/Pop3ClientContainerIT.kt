package pw.binom.mail.pop3

import pw.binom.mail.testkit.AbstractContainerIT
import pw.binom.mail.testkit.GreenMailContainer
import pw.binom.mail.testkit.RawMail
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drives [Pop3Client] (commonMain) against a real GreenMail container; the
 * message is seeded with an independent raw SMTP oracle.
 */
class Pop3ClientContainerIT : AbstractContainerIT() {

    @Test
    fun `retrieve a message from a real pop3 server`() = runTest { selector ->
        val marker = "pop3-container-${System.nanoTime()}"
        RawMail.deliver(
            host,
            smtpPort,
            "sender@test.local",
            address(GreenMailContainer.POP3_USER),
            "Subject: pop3\r\n\r\n$marker\r\n",
        )

        val client = Pop3Client.connect(selector, host, pop3Port)
        try {
            assertTrue(client.user(GreenMailContainer.POP3_USER).ok, "USER")
            assertTrue(client.pass(password).ok, "PASS")
            assertTrue(client.stat().ok, "STAT")
            assertTrue(client.list().ok, "LIST")
            assertTrue(client.uidl().ok, "UIDL")

            val retr = client.retr(1)
            assertTrue(retr.ok, "RETR: $retr")
            val body = (retr as? Pop3Response.Multi)?.lines?.joinToString("\n").orEmpty()
            assertTrue(body.contains(marker), "RETR body does not contain marker: $body")

            assertTrue(client.top(1, 0).ok, "TOP")
            assertTrue(client.noop().ok, "NOOP")
        } finally {
            client.quit()
        }
    }

    @Test
    fun `capa advertises capabilities`() = runTest { selector ->
        val client = Pop3Client.connect(selector, host, pop3Port)
        try {
            val capa = client.capa()
            assertTrue(capa.ok, "CAPA: $capa")
        } finally {
            client.quit()
        }
    }
}
