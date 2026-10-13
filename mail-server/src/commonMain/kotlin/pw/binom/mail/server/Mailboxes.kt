package pw.binom.mail.server

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import pw.binom.mail.imap.ImapMailbox
import pw.binom.mail.pop3.Pop3Mailbox
import pw.binom.mail.smtp.server.SmtpMailbox
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
internal fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** SMTP delivery entry point: routes incoming messages into per-user mailboxes. */
public class SmtpDeliveryRouter(private val store: MailStore) : SmtpMailbox {
    private val lock = SynchronizedObject()
    private val delivered: MutableList<SmtpMailbox.StoredMessage> = mutableListOf()

    override fun store(from: String, recipients: List<String>, data: ByteArray) {
        store.deliver(recipients, data)
        synchronized(lock) {
            delivered += SmtpMailbox.StoredMessage(from, recipients, data.copyOf())
        }
    }

    override fun all(): List<SmtpMailbox.StoredMessage> = synchronized(lock) { delivered.toList() }

    override fun clear() {
        synchronized(lock) { delivered.clear() }
    }
}

/** IMAP view over a [MailboxCore]. */
public class ImapMailboxAdapter(private val core: MailboxCore) : ImapMailbox {
    override fun name(): String = core.name
    override fun exists(): Int = core.size()
    override fun recent(): Int = 0
    override fun uidNext(): Long = core.uidNext()
    override fun uidValidity(): Long = core.uidValidity()
    override fun flags(): List<String> = MailboxCore.DEFAULT_FLAGS
    override fun permanentFlags(): List<String> = MailboxCore.DEFAULT_FLAGS + "\\*"
    override fun message(seq: Int): ImapMailbox.ImapMessage? =
        core.get(seq)?.let { toImap(it, seq) }

    override fun append(message: ByteArray, flags: List<String>): Long =
        core.append(message, flags, currentTimeMillis())

    override fun setFlags(seq: Int, action: String, flags: List<String>) = core.setFlags(seq, action, flags)
    override fun expunge(): List<Int> = core.expunge()
    override fun all(): List<ImapMailbox.ImapMessage> = core.snapshot().mapIndexed { i, e -> toImap(e, i + 1) }

    override fun highestModSeq(): Long = core.highestModSeq()
    override fun modSeq(seq: Int): Long = core.modSeq(seq)
    override fun vanishedSince(modSeq: Long): List<Long> = core.vanishedSince(modSeq)
    override fun messageByUid(uid: Long): ImapMailbox.ImapMessage? {
        val all = core.snapshot()
        val i = all.indexOfFirst { it.uid == uid }
        return if (i < 0) null else toImap(all[i], i + 1)
    }

    private fun toImap(e: MailboxCore.Entry, seq: Int) =
        ImapMailbox.ImapMessage(e.uid, seq, e.body, e.flags.toList(), e.date, e.modSeq)
}

/**
 * POP3 view over a [MailboxCore]. POP3 deletion is deferred until `QUIT`
 * (session `commit()`), and only removes the messages marked in this session.
 */
public class Pop3MailboxAdapter(private val core: MailboxCore) : Pop3Mailbox {
    private val lock = SynchronizedObject()
    private val deletedUids: MutableSet<Long> = mutableSetOf()

    override fun messages(): List<Pop3Mailbox.Pop3Message> = synchronized(lock) {
        core.snapshot().map { Pop3Mailbox.Pop3Message(it.uid.toString(), it.body) }
    }

    private fun messageLocked(index: Int): Pop3Mailbox.Pop3Message? {
        val e = core.get(index) ?: return null
        if (e.uid in deletedUids) return null
        return Pop3Mailbox.Pop3Message(e.uid.toString(), e.body)
    }

    override fun message(index: Int): Pop3Mailbox.Pop3Message? = synchronized(lock) { messageLocked(index) }

    override fun size(index: Int): Int = synchronized(lock) { messageLocked(index)?.size ?: 0 }

    override fun uidl(index: Int): String = synchronized(lock) { messageLocked(index)?.id ?: "" }

    override fun delete(index: Int) {
        synchronized(lock) {
            core.get(index)?.let { deletedUids += it.uid }
        }
    }

    override fun reset() {
        synchronized(lock) {
            deletedUids.clear()
        }
    }

    override fun commit() {
        synchronized(lock) {
            core.removeUids(deletedUids.toSet())
            deletedUids.clear()
        }
    }
}
