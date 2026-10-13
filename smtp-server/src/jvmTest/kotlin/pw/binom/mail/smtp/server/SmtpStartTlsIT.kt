package pw.binom.mail.smtp.server

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import pw.binom.mail.smtp.SmtpClient
import pw.binom.tls.socket.SSLContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * STARTTLS (RFC 3207) integration tests.
 *
 * TLS reads/writes are blocking, so the whole test runs on [Dispatchers.IO] —
 * both the server sessions and the client then have blocking-capable threads.
 */
class SmtpStartTlsIT {
    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(name)) { "missing resource $name" }.readBytes()

    private fun serverTls(): SSLContext =
        SSLContext.server(listOf(resource("/tls/cert.der")), resource("/tls/key.der"))

    @Test
    fun `starttls upgrades the connection and a message flows over tls`() = runBlocking(Dispatchers.IO) {
        val mailbox = InMemorySmtpMailbox()
        val selector = SelectorManager()
        try {
            val server = SmtpServer.start(selector, port = 0, mailbox = mailbox, tls = serverTls())
            try {
                val client = SmtpClient.connect(selector, "127.0.0.1", server.localPort)
                try {
                    val caps = client.ehlo("client.example.com")
                    assertTrue(caps.has("STARTTLS"), "server must advertise STARTTLS before upgrade")

                    val reply = client.startTls("localhost", trustAll = true)
                    assertTrue(reply.isPositive, "STARTTLS rejected: ${reply.code} ${reply.text}")

                    val capsAfter = client.ehlo("client.example.com")
                    assertFalse(capsAfter.has("STARTTLS"), "STARTTLS must not be re-advertised after upgrade")

                    client.mail("alice@example.com")
                    client.rcpt("bob@example.com")
                    val data = client.data("Subject: over tls\r\n\r\nHello over TLS!")
                    assertTrue(data.isPositive, "DATA rejected: ${data.code} ${data.text}")
                } finally {
                    client.quit()
                }
            } finally {
                server.close()
            }

            val msg = mailbox.all().single()
            assertTrue(
                String(msg.data, Charsets.UTF_8).contains("Hello over TLS!"),
                "body: ${String(msg.data, Charsets.UTF_8)}",
            )
        } finally {
            selector.close()
        }
    }

    @Test
    fun `starttls is rejected when server has no certificate`() = runBlocking(Dispatchers.IO) {
        val selector = SelectorManager()
        try {
            val server = SmtpServer.start(selector, port = 0)
            try {
                val client = SmtpClient.connect(selector, "127.0.0.1", server.localPort)
                try {
                    val caps = client.ehlo("client.example.com")
                    assertFalse(caps.has("STARTTLS"), "server without TLS must not advertise STARTTLS")

                    val reply = client.startTls("localhost", trustAll = true)
                    assertFalse(reply.isPositive, "STARTTLS must be refused: ${reply.code} ${reply.text}")
                } finally {
                    client.quit()
                }
            } finally {
                server.close()
            }
        } finally {
            selector.close()
        }
    }
}
