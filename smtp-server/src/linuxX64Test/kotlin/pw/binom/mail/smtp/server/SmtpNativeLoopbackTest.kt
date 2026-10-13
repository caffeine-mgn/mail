package pw.binom.mail.smtp.server

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import pw.binom.mail.smtp.SmtpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real SMTP server and client over a loopback socket on Kotlin/Native,
 * proving the server works on a non-JVM target (no TLS, plain connection).
 */
class SmtpNativeLoopbackTest {
    @Test
    fun deliversMessageOverLocalhost() {
        runBlocking(Dispatchers.Default) {
            val selector = SelectorManager(Dispatchers.Default)
            val mailbox = InMemorySmtpMailbox()
            val server = SmtpServer.start(selector, port = 0, host = "127.0.0.1", mailbox = mailbox)
            try {
                val client = SmtpClient.connect(selector, "127.0.0.1", server.localPort)
                client.ehlo("test.local")
                assertTrue(client.mail("sender@test.local").isPositive)
                assertTrue(client.rcpt("rcpt@test.local").isPositive)
                assertTrue(
                    client.data("Subject: hi\r\n\r\nHello native\r\n".encodeToByteArray()).isPositive,
                )
                client.quit()
                val stored = mailbox.all()
                assertEquals(1, stored.size)
                assertTrue(stored[0].data.decodeToString().contains("Hello native"))
            } finally {
                server.close()
                selector.close()
            }
        }
    }
}
