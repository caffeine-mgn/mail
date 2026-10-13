package pw.binom.mail.pop3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Pop3ParserTest {
    @Test
    fun okSingle() {
        val r = Pop3Parser.parseStatusLine("+OK POP3 server ready")
        assertNotNull(r)
        assertTrue(r.ok)
        assertEquals("POP3 server ready", r.text)
    }

    @Test
    fun okNoText() {
        val r = Pop3Parser.parseStatusLine("+OK")
        assertNotNull(r)
        assertTrue(r.ok)
        assertEquals("", r.text)
    }

    @Test
    fun errSingle() {
        val r = Pop3Parser.parseStatusLine("-ERR invalid password")
        assertNotNull(r)
        assertFalse(r.ok)
        assertEquals("invalid password", r.text)
    }

    @Test
    fun invalidStatus() {
        assertNull(Pop3Parser.parseStatusLine("garbage"))
    }

    @Test
    fun multiLineResponse() {
        val lines = listOf("+OK", "1 100", "2 200", "3 50", ".").iterator()
        val r = Pop3Parser.parse(lines)
        assertNotNull(r)
        assertTrue(r is Pop3Response.Multi)
        r as Pop3Response.Multi
        assertTrue(r.ok)
        assertEquals(3, r.lines.size)
        assertEquals("1 100", r.lines[0])
        assertEquals("2 200", r.lines[1])
        assertEquals("3 50", r.lines[2])
    }

    @Test
    fun retrMessage() {
        val lines = listOf(
            "+OK 500 octets",
            "Return-Path: <a@b>",
            "From: a@b",
            "",
            "Hello",
            ".",
        ).iterator()
        val r = Pop3Parser.parse(lines)
        assertNotNull(r)
        assertTrue(r is Pop3Response.Multi)
        r as Pop3Response.Multi
        assertEquals(4, r.lines.size)
        assertEquals("Return-Path: <a@b>", r.lines[0])
    }

    @Test
    fun dotStuffing() {
        // RETR with a "." line in the body — the body has "..stuff" which
        // the server should de-stuff to ".stuff"
        val lines = listOf(
            "+OK",
            "line 1",
            "..dot line",
            ".",
        ).iterator()
        val r = Pop3Parser.parse(lines)
        r as Pop3Response.Multi
        assertEquals(listOf("line 1", "..dot line"), r.lines)
    }
}

class Pop3CommandTest {
    @Test
    fun userAndPass() {
        assertEquals("USER alice", Pop3Command.User("alice").line)
        assertEquals("PASS secret", Pop3Command.Pass("secret").line)
    }

    @Test
    fun statList() {
        assertEquals("STAT", Pop3Command.Stat.line)
        assertEquals("LIST", Pop3Command.List().line)
        assertEquals("LIST 3", Pop3Command.List(3).line)
    }

    @Test
    fun retrAndDele() {
        assertEquals("RETR 1", Pop3Command.Retr(1).line)
        assertEquals("DELE 2", Pop3Command.Dele(2).line)
    }

    @Test
    fun noopRsetQuit() {
        assertEquals("NOOP", Pop3Command.Noop.line)
        assertEquals("RSET", Pop3Command.Rset.line)
        assertEquals("QUIT", Pop3Command.Quit.line)
    }

    @Test
    fun topAndUidl() {
        assertEquals("TOP 1 10", Pop3Command.Top(1, 10).line)
        assertEquals("UIDL", Pop3Command.Uidl().line)
        assertEquals("UIDL 1", Pop3Command.Uidl(1).line)
    }

    @Test
    fun apop() {
        assertEquals("APOP alice c4f9...", Pop3Command.Apop("alice", "c4f9...").line)
    }
}
