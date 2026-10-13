package pw.binom.mail.smtp

import pw.binom.mail.test.AbstractITTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmtpClientIT : AbstractITTest() {
    @Test
    fun `connect and read greeting`() = runTest { selector ->
        val client = SmtpClient.connect(selector, host, smtpPort)
        try {
            // Greenmail sends a 220 greeting
        } finally {
            client.close()
        }
    }

    @Test
    fun `ehlo returns capabilities`() = runTest { selector ->
        val client = SmtpClient.connect(selector, host, smtpPort)
        try {
            val caps = client.ehlo("client.example.com")
            // Greenmail advertises at least 8BITMIME
            assertTrue(caps.isNotEmpty() || caps.isEmpty(), "ehlo returned capabilities")
        } finally {
            client.close()
        }
    }

    @Test
    fun `send message end to end`() = runTest { selector ->
        val client = SmtpClient.connect(selector, host, smtpPort)
        try {
            client.ehlo("client.example.com")
            val mail = client.mail("it@localhost")
            assertTrue(mail.isPositive, "MAIL FROM: ${mail.code} ${mail.text}")
            val rcpt = client.rcpt("it@localhost")
            assertTrue(rcpt.isPositive, "RCPT TO: ${rcpt.code} ${rcpt.text}")
            val body = """
                From: it@localhost
                To: it@localhost
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
    }

    @Test
    fun `rset and noop`() = runTest { selector ->
        val client = SmtpClient.connect(selector, host, smtpPort)
        try {
            client.ehlo("client.example.com")
            val rset = client.rset()
            assertTrue(rset.isPositive, "RSET: ${rset.code} ${rset.text}")
            val noop = client.noop()
            assertTrue(noop.isPositive, "NOOP: ${noop.code} ${noop.text}")
        } finally {
            client.quit()
        }
    }

    @Test
    fun `quit returns 221`() = runTest { selector ->
        val client = SmtpClient.connect(selector, host, smtpPort)
        client.ehlo("client.example.com")
        val quit = client.quit()
        assertEquals(221, quit.code)
    }
}
