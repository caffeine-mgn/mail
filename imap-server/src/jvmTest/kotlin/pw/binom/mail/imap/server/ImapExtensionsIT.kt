package pw.binom.mail.imap.server

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import pw.binom.mail.imap.ImapAuthenticator
import pw.binom.mail.imap.ImapClient
import pw.binom.mail.imap.ImapResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImapExtensionsIT {
    private val auth = ImapAuthenticator.of(mapOf("alice" to "secret"))

    private fun message(
        subject: String,
        from: String = "sender@example.com",
        messageId: String = "<m@example.com>",
        references: String? = null,
        date: String = "Mon, 01 Jan 2024 10:00:00 +0000",
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("From: $from\r\n")
        sb.append("To: alice@localhost\r\n")
        sb.append("Subject: $subject\r\n")
        sb.append("Date: $date\r\n")
        sb.append("Message-ID: $messageId\r\n")
        if (references != null) sb.append("References: $references\r\n")
        sb.append("\r\n")
        sb.append("body of $subject\r\n")
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun `condstore enable and modseq fetch`() = runBlocking {
        val selector = SelectorManager()
        val inbox = InMemoryImapMailbox("INBOX")
        val untagged = mutableListOf<ImapResponse.Untagged>()
        try {
            val server = ImapServer.start(
                selector, port = 0,
                authenticator = auth,
                userMailboxes = { listOf("INBOX") },
                mailboxProvider = { _, _ -> inbox },
            )
            try {
                val client = ImapClient.connect(selector, "127.0.0.1", server.localPort, onUntagged = { untagged += it })
                try {
                    client.login("alice", "secret")
                    val enable = client.execute("X1 ENABLE CONDSTORE QRESYNC")
                    assertTrue(enable is ImapResponse.Tagged && enable.status == ImapResponse.TagStatus.OK)
                    assertTrue(untagged.any { it.text.startsWith("ENABLED") && it.text.contains("CONDSTORE") }, "got: $untagged")

                    client.append("INBOX", message("one", messageId = "<one@x>"))
                    client.append("INBOX", message("two", messageId = "<two@x>"))

                    untagged.clear()
                    val select = client.execute("X2 SELECT INBOX (CONDSTORE)")
                    assertTrue(select is ImapResponse.Tagged && select.status == ImapResponse.TagStatus.OK)
                    assertTrue(untagged.any { it.text.contains("HIGHESTMODSEQ") }, "got: $untagged")

                    untagged.clear()
                    client.execute("X3 FETCH 1 (MODSEQ)")
                    assertTrue(untagged.any { it.text.contains("MODSEQ") }, "got: $untagged")

                    untagged.clear()
                    client.execute("X4 FETCH 1:* (FLAGS) (CHANGEDSINCE 0)")
                    assertTrue(untagged.count { it.text.contains("FETCH") } == 2, "got: $untagged")

                    val store = client.execute("X5 STORE 1 +FLAGS (\\Seen) (UNCHANGEDSINCE 0)")
                    assertTrue(store is ImapResponse.Tagged && store.status == ImapResponse.TagStatus.OK)
                    val code = store.code
                    assertTrue(code is ImapResponse.ResponseCode.Other && code.keyword == "MODIFIED", "got: $code")
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
    fun `qresync reports vanished earlier`() = runBlocking {
        val selector = SelectorManager()
        val inbox = InMemoryImapMailbox("INBOX")
        val untagged = mutableListOf<ImapResponse.Untagged>()
        try {
            val server = ImapServer.start(
                selector, port = 0,
                authenticator = auth,
                userMailboxes = { listOf("INBOX") },
                mailboxProvider = { _, _ -> inbox },
            )
            try {
                val client = ImapClient.connect(selector, "127.0.0.1", server.localPort, onUntagged = { untagged += it })
                try {
                    client.login("alice", "secret")
                    client.execute("X1 ENABLE QRESYNC")
                    val first = inbox.append(message("one", messageId = "<one@x>"), emptyList())
                    inbox.append(message("two", messageId = "<two@x>"), emptyList())

                    untagged.clear()
                    client.execute("X2 SELECT INBOX (CONDSTORE)")
                    val modSeqLine = untagged.first { it.text.contains("HIGHESTMODSEQ") }.text
                    val modSeq = Regex("HIGHESTMODSEQ (\\d+)").find(modSeqLine)!!.groupValues[1].toLong()

                    client.execute("X3 STORE 1 +FLAGS (\\Deleted)")
                    client.execute("X4 EXPUNGE")

                    untagged.clear()
                    client.execute("X5 SELECT INBOX (QRESYNC (1 $modSeq))")
                    val vanished = untagged.filter { it.text.startsWith("VANISHED") }
                    assertTrue(vanished.any { it.text.contains("EARLIER") && it.text.contains(first.toString()) }, "got: $untagged")
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
    fun `sort by subject`() = runBlocking {
        val selector = SelectorManager()
        val inbox = InMemoryImapMailbox("INBOX")
        val untagged = mutableListOf<ImapResponse.Untagged>()
        try {
            val server = ImapServer.start(
                selector, port = 0,
                authenticator = auth,
                userMailboxes = { listOf("INBOX") },
                mailboxProvider = { _, _ -> inbox },
            )
            try {
                val client = ImapClient.connect(selector, "127.0.0.1", server.localPort, onUntagged = { untagged += it })
                try {
                    client.login("alice", "secret")
                    client.append("INBOX", message("banana", messageId = "<b@x>"))
                    client.append("INBOX", message("apple", messageId = "<a@x>"))
                    client.append("INBOX", message("cherry", messageId = "<c@x>"))
                    client.select("INBOX")
                    untagged.clear()
                    val res = client.execute("X1 UID SORT (SUBJECT) UTF-8 ALL")
                    assertTrue(res is ImapResponse.Tagged && res.status == ImapResponse.TagStatus.OK, "got: ${res.raw}")
                    val sortLine = untagged.first { it.text.startsWith("SORT") }.text
                    assertEquals("SORT 2 1 3", sortLine)
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
    fun `thread by ordered subject`() = runBlocking {
        val selector = SelectorManager()
        val inbox = InMemoryImapMailbox("INBOX")
        val untagged = mutableListOf<ImapResponse.Untagged>()
        try {
            val server = ImapServer.start(
                selector, port = 0,
                authenticator = auth,
                userMailboxes = { listOf("INBOX") },
                mailboxProvider = { _, _ -> inbox },
            )
            try {
                val client = ImapClient.connect(selector, "127.0.0.1", server.localPort, onUntagged = { untagged += it })
                try {
                    client.login("alice", "secret")
                    client.append("INBOX", message("topic", messageId = "<t1@x>"))
                    client.append("INBOX", message("Re: topic", messageId = "<t2@x>", references = "<t1@x>"))
                    client.select("INBOX")
                    untagged.clear()
                    val res = client.execute("X1 UID THREAD REFERENCES UTF-8 ALL")
                    assertTrue(res is ImapResponse.Tagged && res.status == ImapResponse.TagStatus.OK, "got: ${res.raw}")
                    val threadLine = untagged.first { it.text.startsWith("THREAD") }.text
                    assertEquals("THREAD (1 (2))", threadLine)
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
    fun `notify pushes exists`() = runBlocking {
        val selector = SelectorManager()
        val inbox = InMemoryImapMailbox("INBOX")
        val untagged = mutableListOf<ImapResponse.Untagged>()
        try {
            val server = ImapServer.start(
                selector, port = 0,
                authenticator = auth,
                userMailboxes = { listOf("INBOX") },
                mailboxProvider = { _, _ -> inbox },
            )
            try {
                val client = ImapClient.connect(selector, "127.0.0.1", server.localPort, onUntagged = { untagged += it })
                try {
                    client.login("alice", "secret")
                    client.execute("X1 SELECT INBOX")
                    untagged.clear()
                    val notify = client.execute("X2 NOTIFY SET (MESSAGE) (INBOX)")
                    assertTrue(notify is ImapResponse.Tagged && notify.status == ImapResponse.TagStatus.OK)

                    inbox.append(message("new", messageId = "<new@x>"), emptyList())
                    val delivered = withTimeoutOrNull(3000) {
                        while (untagged.none { it.text.startsWith("FETCH") }) {
                            client.noop()
                            delay(100)
                        }
                        true
                    }
                    assertTrue(delivered == true && untagged.any { it.text.startsWith("FETCH") }, "got: $untagged")
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
