package pw.binom.mail.imap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImapParserTest {
    @Test
    fun untaggedOk() {
        val r = ImapParser.parseLine("* OK [CAPABILITY IMAP4rev1] ready")
        assertNotNull(r)
        assertTrue(r is ImapResponse.Untagged)
        assertEquals("OK [CAPABILITY IMAP4rev1] ready", r.text)
    }

    @Test
    fun taggedOk() {
        val r = ImapParser.parseLine("A001 OK LOGIN completed")
        assertNotNull(r)
        assertTrue(r is ImapResponse.Tagged)
        r as ImapResponse.Tagged
        assertEquals("A001", r.tag)
        assertEquals(ImapResponse.TagStatus.OK, r.status)
        assertEquals("LOGIN completed", r.text)
        assertNull(r.code)
    }

    @Test
    fun taggedNo() {
        val r = ImapParser.parseLine("A002 NO [ALERT] mailbox not found")
        assertNotNull(r)
        r as ImapResponse.Tagged
        assertEquals(ImapResponse.TagStatus.NO, r.status)
        assertNotNull(r.code)
        assertTrue(r.code is ImapResponse.ResponseCode.Alert)
    }

    @Test
    fun continuation() {
        val r = ImapParser.parseLine("+ ready for literal")
        assertNotNull(r)
        assertTrue(r is ImapResponse.Continuation)
        assertEquals("ready for literal", r.text)
    }

    @Test
    fun capabilityResponseCode() {
        val r = ImapParser.parseLine("A001 OK [CAPABILITY IMAP4rev1 IDLE CONDSTORE] ready")
        r as ImapResponse.Tagged
        assertNotNull(r.code)
        assertTrue(r.code is ImapResponse.ResponseCode.Capability)
        r.code as ImapResponse.ResponseCode.Capability
        assertEquals(listOf("IMAP4rev1", "IDLE", "CONDSTORE"), r.code.extensions)
    }

    @Test
    fun permanentFlagsResponseCode() {
        val r = ImapParser.parseLine("A001 OK [PERMANENTFLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft \\*)]")
        r as ImapResponse.Tagged
        assertTrue(r.code is ImapResponse.ResponseCode.PermanentFlags)
        r.code as ImapResponse.ResponseCode.PermanentFlags
        assertEquals(listOf("\\Answered", "\\Flagged", "\\Deleted", "\\Seen", "\\Draft", "\\*"), r.code.flags)
    }
}

class ImapCommandTest {
    @Test
    fun capability() {
        assertEquals("A001 CAPABILITY", ImapCommand.Capability("A001").line)
    }

    @Test
    fun login() {
        assertEquals("A001 LOGIN alice secret", ImapCommand.Login("A001", "alice", "secret").line)
    }

    @Test
    fun loginQuotedUser() {
        assertEquals("A001 LOGIN \"alice@example.com\" secret", ImapCommand.Login("A001", "alice@example.com", "secret").line)
    }

    @Test
    fun list() {
        assertEquals("A001 LIST \"\" *", ImapCommand.List("A001", "", "*").line)
    }

    @Test
    fun select() {
        assertEquals("A001 SELECT INBOX", ImapCommand.Select("A001", "INBOX").line)
    }

    @Test
    fun fetch() {
        assertEquals("A001 FETCH 1:5 (UID FLAGS)", ImapCommand.Fetch("A001", "1:5", listOf("UID", "FLAGS")).line)
    }

    @Test
    fun uidFetch() {
        assertEquals("A001 UID FETCH 1 (BODY[TEXT])", ImapCommand.Fetch("A001", "1", listOf("BODY[TEXT]"), uid = true).line)
    }

    @Test
    fun store() {
        assertEquals("A001 STORE 1:5 +FLAGS (\\Seen)", ImapCommand.Store("A001", "1:5", "+FLAGS", listOf("\\Seen")).line)
    }

    @Test
    fun append() {
        val cmd = ImapCommand.Append("A001", "INBOX", flags = listOf("\\Seen"), message = byteArrayOf(1, 2, 3))
        assertEquals("A001 APPEND INBOX (\\Seen) {3}", cmd.line)
    }
}
