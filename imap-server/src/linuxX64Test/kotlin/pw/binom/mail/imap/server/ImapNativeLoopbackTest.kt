package pw.binom.mail.imap.server

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import pw.binom.mail.imap.ImapAuthenticator
import pw.binom.mail.imap.ImapClient
import pw.binom.mail.imap.ImapResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real IMAP server and client over a loopback socket on Kotlin/Native,
 * proving the server works on a non-JVM target (plain connection).
 */
class ImapNativeLoopbackTest {
    @Test
    fun fetchOverLocalhost() {
        runBlocking(Dispatchers.Default) {
            val selector = SelectorManager(Dispatchers.Default)
            val inbox = InMemoryImapMailbox("INBOX")
            val server = ImapServer.start(
                selector, port = 0, host = "127.0.0.1",
                authenticator = ImapAuthenticator.of(mapOf("alice" to "secret")),
                userMailboxes = { listOf("INBOX") },
                mailboxProvider = { _, _ -> inbox },
            )
            try {
                val client = ImapClient.connect(selector, "127.0.0.1", server.localPort)
                client.login("alice", "secret")
                client.append("INBOX", "From: a@b\r\nSubject: hi\r\n\r\nHello native\r\n".encodeToByteArray())
                val sel = client.select("INBOX")
                assertTrue(sel is ImapResponse.Tagged && sel.status == ImapResponse.TagStatus.OK)
                val fetch = client.fetch("1", listOf("BODY[]"))
                assertTrue(fetch is ImapResponse.Tagged && fetch.status == ImapResponse.TagStatus.OK)
                assertEquals(1, inbox.exists())
                assertTrue(inbox.message(1)!!.body.decodeToString().contains("Hello native"))
            } finally {
                server.close()
                selector.close()
            }
        }
    }
}
