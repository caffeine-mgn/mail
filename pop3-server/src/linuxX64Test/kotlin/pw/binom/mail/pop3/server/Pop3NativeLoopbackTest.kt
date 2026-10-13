package pw.binom.mail.pop3.server

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import pw.binom.mail.pop3.InMemoryPop3Mailbox
import pw.binom.mail.pop3.Pop3Authenticator
import pw.binom.mail.pop3.Pop3Client
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the real POP3 server and client over a loopback socket on Kotlin/Native,
 * proving the server works on a non-JVM target (plain connection).
 */
class Pop3NativeLoopbackTest {
    @Test
    fun retrieveOverLocalhost() {
        runBlocking(Dispatchers.Default) {
            val selector = SelectorManager(Dispatchers.Default)
            val mailbox = InMemoryPop3Mailbox.of(
                "From: a@b\r\nTo: c@d\r\nSubject: 1\r\n\r\nHello native\r\n",
            )
            val server = Pop3Server.start(
                selector, port = 0, host = "127.0.0.1",
                mailboxProvider = { mailbox },
                auth = Pop3Authenticator.of(mapOf("alice" to "secret")),
            )
            try {
                val client = Pop3Client.connect(selector, "127.0.0.1", server.localPort)
                assertTrue(client.user("alice").ok)
                assertTrue(client.pass("secret").ok)
                val stat = client.stat()
                assertTrue(stat.ok)
                val retr = client.retr(1)
                assertTrue(retr.ok)
                client.quit()
            } finally {
                server.close()
                selector.close()
            }
        }
    }
}
