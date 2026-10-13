package pw.binom.mail.imap.server

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import pw.binom.mail.imap.ImapAuthenticator
import pw.binom.mail.imap.ImapClient
import pw.binom.mail.imap.ImapResponse
import pw.binom.tls.socket.SSLContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * STARTTLS (RFC 2595) integration tests for the IMAP server.
 *
 * TLS I/O is blocking, so everything runs on [Dispatchers.IO].
 */
class ImapStartTlsIT {
    private val userMailboxes = mapOf("alice" to listOf("INBOX"))
    private val inboxes = mapOf("INBOX" to InMemoryImapMailbox("INBOX"))
    private val userAuth = ImapAuthenticator.of(mapOf("alice" to "secret"))

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(name)) { "missing resource $name" }.readBytes()

    private fun serverTls(): SSLContext =
        SSLContext.server(listOf(resource("/tls/cert.der")), resource("/tls/key.der"))

    @Test
    fun `starttls upgrades the connection and login works over tls`() = runBlocking(Dispatchers.IO) {
        val selector = SelectorManager()
        try {
            val tls = serverTls()
            val server = ImapServer.start(
                selector, port = 0,
                authenticator = userAuth,
                userMailboxes = { userMailboxes[it] ?: emptyList() },
                mailboxProvider = { _, name -> inboxes[name] },
                tls = tls,
            )
            // server started on the IO dispatcher already via runBlocking(Dispatchers.IO)
            try {
                val capaUntagged = mutableListOf<ImapResponse.Untagged>()
                val client = ImapClient.connect(selector, "127.0.0.1", server.localPort, onUntagged = { capaUntagged += it })
                try {
                    client.capability()
                    assertTrue(
                        capaUntagged.any { it.text.contains("STARTTLS") },
                        "server must advertise STARTTLS before upgrade: $capaUntagged",
                    )

                    val reply = client.startTls("localhost", trustAll = true)
                    assertTrue(
                        reply is ImapResponse.Tagged && reply.status == ImapResponse.TagStatus.OK,
                        "STARTTLS rejected: $reply",
                    )

                    capaUntagged.clear()
                    client.capability()
                    assertFalse(
                        capaUntagged.any { it.text.contains("STARTTLS") },
                        "STARTTLS must not be re-advertised after upgrade: $capaUntagged",
                    )

                    val login = client.login("alice", "secret")
                    assertTrue(
                        login is ImapResponse.Tagged && login.status == ImapResponse.TagStatus.OK,
                        "LOGIN over TLS failed: $login",
                    )
                } finally {
                    client.logout()
                }
            } finally {
                server.close()
            }
        } finally {
            selector.close()
        }
    }

    @Test
    fun `starttls is rejected when server has no certificate`() = runBlocking(Dispatchers.IO) {
        val selector = SelectorManager()
        try {
            val server = ImapServer.start(
                selector, port = 0,
                authenticator = userAuth,
                userMailboxes = { userMailboxes[it] ?: emptyList() },
                mailboxProvider = { _, name -> inboxes[name] },
            )
            try {
                val capaUntagged = mutableListOf<ImapResponse.Untagged>()
                val client = ImapClient.connect(selector, "127.0.0.1", server.localPort, onUntagged = { capaUntagged += it })
                try {
                    client.capability()
                    assertFalse(
                        capaUntagged.any { it.text.contains("STARTTLS") },
                        "server without TLS must not advertise STARTTLS: $capaUntagged",
                    )
                    val reply = client.startTls("localhost", trustAll = true)
                    assertTrue(
                        reply is ImapResponse.Tagged && reply.status == ImapResponse.TagStatus.NO,
                        "STARTTLS must be refused: $reply",
                    )
                } finally {
                    client.logout()
                }
            } finally {
                server.close()
            }
        } finally {
            selector.close()
        }
    }
}
