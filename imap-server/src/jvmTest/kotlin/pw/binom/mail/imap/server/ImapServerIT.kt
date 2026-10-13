package pw.binom.mail.imap.server

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.runBlocking
import pw.binom.mail.imap.ImapAuthenticator
import pw.binom.mail.imap.ImapClient
import pw.binom.mail.imap.ImapResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImapServerIT {
    private val userMailboxes = mapOf("alice" to listOf("INBOX"))
    private val inboxes = mapOf(
        "INBOX" to InMemoryImapMailbox("INBOX"),
    )

    private val userAuth = ImapAuthenticator.of(mapOf("alice" to "secret"))

    @Test
    fun `login and select`() = runBlocking {
        val selector = SelectorManager()
        try {
            val server = ImapServer.start(
                selector, port = 0,
                authenticator = userAuth,
                userMailboxes = { userMailboxes[it] ?: emptyList() },
                mailboxProvider = { _, name -> inboxes[name] },
            )
            try {
                val client = ImapClient.connect(selector, "127.0.0.1", server.localPort)
                try {
                    val cap = client.capability()
                    assertTrue(cap is ImapResponse.Tagged && cap.status == ImapResponse.TagStatus.OK)
                    val login = client.login("alice", "secret")
                    assertTrue(login is ImapResponse.Tagged && login.status == ImapResponse.TagStatus.OK)
                    val select = client.select("INBOX")
                    assertTrue(select is ImapResponse.Tagged && select.status == ImapResponse.TagStatus.OK)
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
    fun `login rejected with bad password`() = runBlocking {
        val selector = SelectorManager()
        try {
            val server = ImapServer.start(
                selector, port = 0,
                authenticator = userAuth,
                userMailboxes = { userMailboxes[it] ?: emptyList() },
                mailboxProvider = { _, name -> inboxes[name] },
            )
            try {
                val client = ImapClient.connect(selector, "127.0.0.1", server.localPort)
                try {
                    val cap = client.capability()
                    val login = client.login("alice", "wrong")
                    assertTrue(login is ImapResponse.Tagged && login.status == ImapResponse.TagStatus.NO)
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
    fun `list mailboxes`() = runBlocking {
        val selector = SelectorManager()
        try {
            val server = ImapServer.start(
                selector, port = 0,
                authenticator = userAuth,
                userMailboxes = { userMailboxes[it] ?: emptyList() },
                mailboxProvider = { _, name -> inboxes[name] },
            )
            try {
                val client = ImapClient.connect(selector, "127.0.0.1", server.localPort)
                try {
                    client.capability()
                    client.login("alice", "secret")
                    val untagged = mutableListOf<ImapResponse.Untagged>()
                    val client2 = ImapClient.connect(selector, "127.0.0.1", server.localPort, onUntagged = { untagged += it })
                    try {
                        client2.capability()
                        client2.login("alice", "secret")
                        client2.list("", "*")
                    } finally {
                        client2.logout()
                    }
                    assertTrue(untagged.any { it.text.contains("INBOX") }, "got: $untagged")
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
