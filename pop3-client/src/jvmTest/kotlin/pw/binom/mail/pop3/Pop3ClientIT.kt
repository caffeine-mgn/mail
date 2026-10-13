package pw.binom.mail.pop3

import pw.binom.mail.test.AbstractITTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Pop3ClientIT : AbstractITTest() {
    @Test
    fun `connect and login`() = runTest { selector ->
        val client = Pop3Client.connect(selector, host, pop3Port)
        try {
            val u = client.user("it")
            assertTrue(u.ok, "USER: ${u.text}")
            val p = client.pass("it")
            assertTrue(p.ok, "PASS: ${p.text}")
        } finally {
            client.quit()
        }
    }

    @Test
    fun `stat and list`() = runTest { selector ->
        val client = Pop3Client.connect(selector, host, pop3Port)
        try {
            client.user("it")
            client.pass("it")
            val stat = client.stat()
            assertTrue(stat.ok, "STAT: ${stat.text}")
            // Initial mailbox is empty for fresh user
            val list = client.list()
            assertTrue(list is Pop3Response.Multi, "LIST: $list")
        } finally {
            client.quit()
        }
    }

    @Test
    fun `retr a message`() = runTest { selector ->
        val client = Pop3Client.connect(selector, host, pop3Port)
        try {
            client.user("it")
            client.pass("it")
            // Greenmail starts with 0 messages; let's test no-such-message
            val r = client.retr(1)
            assertTrue(!r.ok, "expected error for non-existent message: ${r.text}")
        } finally {
            client.quit()
        }
    }
}
