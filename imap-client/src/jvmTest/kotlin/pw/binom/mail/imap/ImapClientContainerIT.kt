package pw.binom.mail.imap

import pw.binom.mail.testkit.AbstractContainerIT
import pw.binom.mail.testkit.GreenMailContainer
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drives [ImapClient] (commonMain) against a real GreenMail container:
 * login, list, append, select, fetch and store.
 */
class ImapClientContainerIT : AbstractContainerIT() {

    @Test
    fun `append fetch and store on a real imap server`() = runTest { selector ->
        val marker = "imap-container-${System.nanoTime()}"
        val untagged = mutableListOf<ImapResponse.Untagged>()
        val client = ImapClient.connect(selector, host, imapPort) { untagged += it }
        try {
            assertTrue(client.capability() is ImapResponse.Tagged, "CAPABILITY")

            val login = client.login(GreenMailContainer.IMAP_USER, password)
            assertTrue(login is ImapResponse.Tagged && login.status == ImapResponse.TagStatus.OK, "LOGIN: $login")

            val list = client.list("", "*")
            assertTrue(list is ImapResponse.Tagged && list.status == ImapResponse.TagStatus.OK, "LIST: $list")

            val append = client.append("INBOX", "Subject: imap\r\n\r\n$marker\r\n".encodeToByteArray())
            assertTrue(append is ImapResponse.Tagged && append.status == ImapResponse.TagStatus.OK, "APPEND: $append")

            val select = client.select("INBOX")
            assertTrue(select is ImapResponse.Tagged && select.status == ImapResponse.TagStatus.OK, "SELECT: $select")

            val fetch = client.fetch("1:*", listOf("BODY[]", "FLAGS", "UID"))
            assertTrue(fetch is ImapResponse.Tagged && fetch.status == ImapResponse.TagStatus.OK, "FETCH: $fetch")

            val store = client.store("1", "+FLAGS", listOf("\\Seen"))
            assertTrue(store is ImapResponse.Tagged && store.status == ImapResponse.TagStatus.OK, "STORE: $store")

            val bodyFound = untagged.any { it.literals.any { bytes -> bytes.decodeToString().contains(marker) } }
            assertTrue(bodyFound, "FETCH did not return the appended body: ${untagged.map { it.raw }}")
        } finally {
            client.logout()
        }
    }
}
