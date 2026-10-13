package pw.binom.mail.smtp.server

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.runBlocking
import pw.binom.mail.smtp.SmtpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmtpServerIT {
    @Test
    fun `server accepts and stores a message`() = runBlocking {
        val mailbox = InMemorySmtpMailbox()
        val selector = SelectorManager()
        try {
            val server = SmtpServer.start(selector, port = 0, mailbox = mailbox)
            try {
                val client = SmtpClient.connect(selector, "127.0.0.1", server.localPort)
                try {
                    client.ehlo("client.example.com")
                    client.mail("alice@example.com")
                    client.rcpt("bob@example.com")
                    val body = """
                        From: alice@example.com
                        To: bob@example.com
                        Subject: Test
                        MIME-Version: 1.0
                        Content-Type: text/plain; charset=utf-8

                        Hello, world!
                    """.trimIndent()
                    val data = client.data(body)
                    assertTrue(data.isPositive, "DATA: ${data.code} ${data.text}")
                } finally {
                    client.quit()
                }
            } finally {
                server.close()
            }
            val messages = mailbox.all()
            assertEquals(1, messages.size, "expected one stored message")
            val msg = messages[0]
            assertEquals("alice@example.com", msg.from)
            assertEquals(listOf("bob@example.com"), msg.recipients)
            val text = String(msg.data, Charsets.UTF_8)
            assertTrue(text.contains("Hello, world!"), "body: $text")
        } finally {
            selector.close()
        }
    }

    @Test
    fun `server handles multiple recipients`() = runBlocking {
        val mailbox = InMemorySmtpMailbox()
        val selector = SelectorManager()
        try {
            val server = SmtpServer.start(selector, port = 0, mailbox = mailbox)
            try {
                val client = SmtpClient.connect(selector, "127.0.0.1", server.localPort)
                try {
                    client.ehlo("client.example.com")
                    client.mail("alice@example.com")
                    client.rcpt("bob@example.com")
                    client.rcpt("charlie@example.com")
                    client.data("Hello\r\n".repeat(5))
                } finally {
                    client.quit()
                }
            } finally {
                server.close()
            }
            val msg = mailbox.all().single()
            assertEquals(2, msg.recipients.size)
            assertEquals(listOf("bob@example.com", "charlie@example.com"), msg.recipients)
        } finally {
            selector.close()
        }
    }

    @Test
    fun `server handles dot stuffing`() = runBlocking {
        val mailbox = InMemorySmtpMailbox()
        val selector = SelectorManager()
        try {
            val server = SmtpServer.start(selector, port = 0, mailbox = mailbox)
            try {
                val client = SmtpClient.connect(selector, "127.0.0.1", server.localPort)
                try {
                    client.ehlo("client.example.com")
                    client.mail("a@b")
                    client.rcpt("c@d")
                    // Body has a line starting with "." which is dot-stuffed to ".."
                    val body = "line1\r\n.line2\r\n..line3\r\n"
                    client.data(body)
                } finally {
                    client.quit()
                }
            } finally {
                server.close()
            }
            val msg = mailbox.all().single()
            val text = String(msg.data, Charsets.UTF_8)
            // After dot-unstuffing, the ".." line becomes "."
            assertTrue(text.contains(".line2"), "got: $text")
            assertTrue(text.contains("..line3"), "got: $text")
        } finally {
            selector.close()
        }
    }

    @Test
    fun `rset cancels current transaction`() = runBlocking {
        val mailbox = InMemorySmtpMailbox()
        val selector = SelectorManager()
        try {
            val server = SmtpServer.start(selector, port = 0, mailbox = mailbox)
            try {
                val client = SmtpClient.connect(selector, "127.0.0.1", server.localPort)
                try {
                    client.ehlo("client.example.com")
                    client.mail("a@b")
                    client.rset()
                    client.mail("c@d")
                    client.rcpt("e@f")
                    client.data("body")
                } finally {
                    client.quit()
                }
            } finally {
                server.close()
            }
            val msg = mailbox.all().single()
            assertEquals("c@d", msg.from)
        } finally {
            selector.close()
        }
    }
}
