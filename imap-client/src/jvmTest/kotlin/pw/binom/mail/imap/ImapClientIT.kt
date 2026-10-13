package pw.binom.mail.imap

import pw.binom.mail.test.AbstractITTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImapClientIT : AbstractITTest() {
    @Test
    fun `connect and capability`() = runTest { selector ->
        val client = ImapClient.connect(selector, host, imapPort)
        try {
            val cap = client.capability()
            assertTrue(cap is ImapResponse.Tagged, "got: $cap")
            cap as ImapResponse.Tagged
            assertEquals(ImapResponse.TagStatus.OK, cap.status)
        } finally {
            client.logout()
        }
    }

    @Test
    fun `login and select`() = runTest { selector ->
        val client = ImapClient.connect(selector, host, imapPort)
        try {
            client.capability()
            val login = client.login("it", "it")
            assertTrue(login is ImapResponse.Tagged && login.status == ImapResponse.TagStatus.OK, "login: $login")
            val select = client.select("INBOX")
            assertTrue(select is ImapResponse.Tagged && select.status == ImapResponse.TagStatus.OK, "select: $select")
        } finally {
            client.logout()
        }
    }

    @Test
    fun `bad credentials rejected`() = runTest { selector ->
        val client = ImapClient.connect(selector, host, imapPort)
        try {
            client.capability()
            val login = client.login("it", "wrong")
            assertTrue(login is ImapResponse.Tagged && login.status == ImapResponse.TagStatus.NO, "login: $login")
        } finally {
            client.logout()
        }
    }
}
