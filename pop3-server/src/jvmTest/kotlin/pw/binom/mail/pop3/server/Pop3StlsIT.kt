package pw.binom.mail.pop3.server

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import pw.binom.mail.pop3.InMemoryPop3Mailbox
import pw.binom.mail.pop3.Pop3Authenticator
import pw.binom.mail.pop3.Pop3Client
import pw.binom.mail.pop3.Pop3Mailbox
import pw.binom.mail.pop3.Pop3Response
import pw.binom.tls.socket.SSLContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * STLS (RFC 2595) integration tests for the POP3 server.
 *
 * TLS I/O is blocking, so everything runs on [Dispatchers.IO].
 */
class Pop3StlsIT {
    private fun makeMailbox(): Pop3Mailbox = InMemoryPop3Mailbox.of(
        "From: a@b\r\nTo: c@d\r\nSubject: 1\r\n\r\nHello, world!\r\n",
    )

    private fun makeAuth(): Pop3Authenticator = Pop3Authenticator.of(mapOf("alice" to "secret"))

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(name)) { "missing resource $name" }.readBytes()

    private fun serverTls(): SSLContext =
        SSLContext.server(listOf(resource("/tls/cert.der")), resource("/tls/key.der"))

    @Test
    fun `stls upgrades the connection and login works over tls`() = runBlocking(Dispatchers.IO) {
        val mailbox = makeMailbox()
        val auth = makeAuth()
        val selector = SelectorManager()
        try {
            val server = Pop3Server.start(selector, port = 0, mailboxProvider = { mailbox }, auth = auth, tls = serverTls())
            try {
                val client = Pop3Client.connect(selector, "127.0.0.1", server.localPort)
                try {
                    val capa = client.capa()
                    assertTrue(
                        capa is Pop3Response.Multi && capa.lines.any { it.equals("STLS", ignoreCase = true) },
                        "server must advertise STLS before upgrade: $capa",
                    )

                    val reply = client.stls("localhost", trustAll = true)
                    assertTrue(reply.ok, "STLS rejected: ${reply.text}")

                    val user = client.user("alice")
                    assertTrue(user.ok, "USER over TLS failed: ${user.text}")
                    val pass = client.pass("secret")
                    assertTrue(pass.ok, "PASS over TLS failed: ${pass.text}")
                    val stat = client.stat()
                    assertTrue(stat.ok, "STAT over TLS failed: ${stat.text}")
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

    @Test
    fun `stls is rejected when server has no certificate`() = runBlocking(Dispatchers.IO) {
        val mailbox = makeMailbox()
        val auth = makeAuth()
        val selector = SelectorManager()
        try {
            val server = Pop3Server.start(selector, port = 0, mailboxProvider = { mailbox }, auth = auth)
            try {
                val client = Pop3Client.connect(selector, "127.0.0.1", server.localPort)
                try {
                    val capa = client.capa()
                    assertFalse(
                        capa is Pop3Response.Multi && capa.lines.any { it.equals("STLS", ignoreCase = true) },
                        "server without TLS must not advertise STLS: $capa",
                    )
                    val reply = client.stls("localhost", trustAll = true)
                    assertFalse(reply.ok, "STLS must be refused: ${reply.text}")
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
