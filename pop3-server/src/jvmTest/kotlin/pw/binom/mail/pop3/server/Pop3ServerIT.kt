package pw.binom.mail.pop3.server

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.runBlocking
import pw.binom.mail.pop3.InMemoryPop3Mailbox
import pw.binom.mail.pop3.Pop3Authenticator
import pw.binom.mail.pop3.Pop3Client
import pw.binom.mail.pop3.Pop3Mailbox
import pw.binom.mail.pop3.Pop3Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Pop3ServerIT {
    private fun makeMailbox(): Pop3Mailbox = InMemoryPop3Mailbox.of(
        "From: a@b\r\nTo: c@d\r\nSubject: 1\r\n\r\nHello, world!\r\n",
        "From: a@b\r\nTo: c@d\r\nSubject: 2\r\n\r\nBye.\r\n",
    )

    private fun makeAuth(): Pop3Authenticator = Pop3Authenticator.of(mapOf("alice" to "secret"))

    @Test
    fun `login with USER and PASS`() = runBlocking {
        val mailbox = makeMailbox()
        val auth = makeAuth()
        val selector = SelectorManager()
        try {
            val server = Pop3Server.start(selector, port = 0, mailboxProvider = { mailbox }, auth = auth)
            try {
                val client = Pop3Client.connect(selector, "127.0.0.1", server.localPort)
                try {
                    val u = client.user("alice")
                    assertTrue(u.ok)
                    val p = client.pass("secret")
                    assertTrue(p.ok)
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
    fun `stat returns count and total size`() = runBlocking {
        val mailbox = makeMailbox()
        val auth = makeAuth()
        val selector = SelectorManager()
        try {
            val server = Pop3Server.start(selector, port = 0, mailboxProvider = { mailbox }, auth = auth)
            try {
                val client = Pop3Client.connect(selector, "127.0.0.1", server.localPort)
                try {
                    client.user("alice")
                    client.pass("secret")
                    val s = client.stat()
                    assertTrue(s.ok)
                    val parts = s.text.split(' ')
                    assertEquals("2", parts[0], "expected 2 messages")
                    val totalSize = parts[1].toInt()
                    assertTrue(totalSize > 0, "total size should be positive")
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
    fun `list all messages`() = runBlocking {
        val mailbox = makeMailbox()
        val auth = makeAuth()
        val selector = SelectorManager()
        try {
            val server = Pop3Server.start(selector, port = 0, mailboxProvider = { mailbox }, auth = auth)
            try {
                val client = Pop3Client.connect(selector, "127.0.0.1", server.localPort)
                try {
                    client.user("alice")
                    client.pass("secret")
                    val l = client.list()
                    assertTrue(l is Pop3Response.Multi)
                    l as Pop3Response.Multi
                    assertEquals(2, l.lines.size)
                    assertTrue(l.lines[0].startsWith("1 "))
                    assertTrue(l.lines[1].startsWith("2 "))
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
    fun `retr returns message content`() = runBlocking {
        val mailbox = makeMailbox()
        val auth = makeAuth()
        val selector = SelectorManager()
        try {
            val server = Pop3Server.start(selector, port = 0, mailboxProvider = { mailbox }, auth = auth)
            try {
                val client = Pop3Client.connect(selector, "127.0.0.1", server.localPort)
                try {
                    client.user("alice")
                    client.pass("secret")
                    val r = client.retr(1)
                    assertTrue(r is Pop3Response.Multi)
                    r as Pop3Response.Multi
                    val text = r.lines.joinToString("\n")
                    assertTrue(text.contains("Subject: 1"), "got: $text")
                    assertTrue(text.contains("Hello, world!"), "got: $text")
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
    fun `uidl returns message ids`() = runBlocking {
        val mailbox = makeMailbox()
        val auth = makeAuth()
        val selector = SelectorManager()
        try {
            val server = Pop3Server.start(selector, port = 0, mailboxProvider = { mailbox }, auth = auth)
            try {
                val client = Pop3Client.connect(selector, "127.0.0.1", server.localPort)
                try {
                    client.user("alice")
                    client.pass("secret")
                    val u = client.uidl()
                    assertTrue(u is Pop3Response.Multi)
                    u as Pop3Response.Multi
                    assertEquals(2, u.lines.size)
                    assertTrue(u.lines[0].startsWith("1 msg1"))
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
    fun `dele marks message as deleted`() = runBlocking {
        val mailbox = makeMailbox()
        val auth = makeAuth()
        val selector = SelectorManager()
        try {
            val server = Pop3Server.start(selector, port = 0, mailboxProvider = { mailbox }, auth = auth)
            try {
                val client = Pop3Client.connect(selector, "127.0.0.1", server.localPort)
                try {
                    client.user("alice")
                    client.pass("secret")
                    val d = client.dele(1)
                    assertTrue(d.ok)
                    // After deletion, msg 2 is still there but msg 1 is gone
                    val s = client.stat()
                    assertEquals("1", s.text.split(' ')[0])
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
    fun `bad password rejected`() = runBlocking {
        val mailbox = makeMailbox()
        val auth = makeAuth()
        val selector = SelectorManager()
        try {
            val server = Pop3Server.start(selector, port = 0, mailboxProvider = { mailbox }, auth = auth)
            try {
                val client = Pop3Client.connect(selector, "127.0.0.1", server.localPort)
                try {
                    client.user("alice")
                    val p = client.pass("wrong")
                    assertTrue(!p.ok, "PASS should fail with wrong password")
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
