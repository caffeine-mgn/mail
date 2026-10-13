package pw.binom.mail.smtp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SmtpParserTest {
    @Test
    fun singleLine() {
        val r = SmtpParser.parseLine("250 OK")
        assertNotNull(r)
        assertEquals(250, r.code)
        assertEquals("OK", r.text)
        assertEquals(false, r.continuing)
    }

    @Test
    fun multiLine() {
        val response = SmtpParser.parse(
            sequenceOf(
                "250-server.example.com Hello",
                "250-SIZE 10240000",
                "250-8BITMIME",
                "250 HELP",
            ),
        )!!
        assertEquals(250, response.code)
        assertEquals(4, response.lines.size)
        assertTrue(response.isPositive)
        assertEquals("server.example.com Hello", response.lines[0].text)
        assertEquals("SIZE 10240000", response.lines[1].text)
        assertEquals("8BITMIME", response.lines[2].text)
        assertEquals("HELP", response.lines[3].text)
    }

    @Test
    fun errorResponse() {
        val r = SmtpParser.parseLine("550 User not found")
        assertNotNull(r)
        assertTrue(r.isError)
    }

    @Test
    fun greeting() {
        val r = SmtpParser.parseLine("220 smtp.example.com ESMTP ready")
        assertNotNull(r)
        assertEquals(220, r.code)
    }

    @Test
    fun invalidLines() {
        assertNull(SmtpParser.parseLine("OK"))            // no code
        assertNull(SmtpParser.parseLine("25x OK"))         // non-digit
        assertNull(SmtpParser.parseLine("999 OK"))         // out of range
    }

    @Test
    fun eofReturnsNull() {
        assertNull(SmtpParser.parse(sequenceOf()))
    }
}

class SmtpCommandTest {
    @Test
    fun helo() {
        assertEquals("HELO client.example.com", SmtpCommand.Helo("client.example.com").line)
    }

    @Test
    fun ehlo() {
        assertEquals("EHLO client.example.com", SmtpCommand.Ehlo("client.example.com").line)
    }

    @Test
    fun mailFrom() {
        assertEquals("MAIL FROM:<alice@example.com>", SmtpCommand.Mail("alice@example.com").line)
    }

    @Test
    fun mailFromWithSize() {
        assertEquals(
            "MAIL FROM:<alice@example.com> SIZE=1024",
            SmtpCommand.Mail("alice@example.com", size = 1024).line,
        )
    }

    @Test
    fun mailFrom8bitMime() {
        assertEquals(
            "MAIL FROM:<a@b> BODY=8BITMIME",
            SmtpCommand.Mail("a@b", body = "8BITMIME").line,
        )
    }

    @Test
    fun rcptTo() {
        assertEquals("RCPT TO:<bob@example.com>", SmtpCommand.Rcpt("bob@example.com").line)
    }

    @Test
    fun dataAndQuit() {
        assertEquals("DATA", SmtpCommand.Data.line)
        assertEquals("QUIT", SmtpCommand.Quit.line)
        assertEquals("RSET", SmtpCommand.Rset.line)
        assertEquals("NOOP", SmtpCommand.Noop.line)
    }

    @Test
    fun authPlain() {
        assertEquals("AUTH PLAIN", SmtpCommand.Auth("PLAIN").line)
        assertEquals("AUTH PLAIN AGFkbWlu", SmtpCommand.Auth("PLAIN", "AGFkbWlu").line)
    }
}

class EhloCapabilitiesTest {
    @Test
    fun parseEhlo() {
        val response = SmtpParser.parse(
            sequenceOf(
                "250-server.example.com",
                "250-SIZE 10240000",
                "250-8BITMIME",
                "250-AUTH PLAIN LOGIN",
                "250 HELP",
            ),
        )!!
        val caps = EhloCapabilities.parse(response)
        assertTrue(caps.has("SIZE"))
        assertEquals("10240000", caps.parameter("SIZE"))
        assertTrue(caps.has("8BITMIME"))
        assertTrue(caps.has("AUTH"))
        assertEquals("PLAIN LOGIN", caps.parameter("AUTH"))
        assertTrue(caps.has("HELP"))
    }
}
