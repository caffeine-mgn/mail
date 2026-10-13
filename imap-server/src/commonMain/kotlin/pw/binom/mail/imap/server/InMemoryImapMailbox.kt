package pw.binom.mail.imap.server

import pw.binom.mail.imap.ImapMailbox
import pw.binom.mail.imap.ImapMailbox.ImapMessage
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Mutable in-memory IMAP mailbox.
 *
 * Per RFC 3501 a message marked `\Deleted` still counts towards `EXISTS` and
 * keeps its sequence number until it is removed by `EXPUNGE`, so [exists]
 * reports every message currently stored and [expunge] renumbers what remains.
 *
 * CONDSTORE (RFC 7162) is supported: every mutation bumps a monotonic
 * `highestModSeq`, expunged UIDs are remembered so QRESYNC `VANISHED (EARLIER)`
 * can be produced.
 */
public class InMemoryImapMailbox(
    public val mailboxName: String,
    initialMessages: List<ImapMessage> = emptyList(),
    private val now: () -> Long = { 0L },
) : ImapMailbox {
    private val lock = SynchronizedObject()
    private val messages: MutableList<ImapMessage> = initialMessages.toMutableList()
    private var nextUid: Long = (messages.maxOfOrNull { it.uid } ?: 0L) + 1L
    private var highestModSeq: Long = messages.maxOfOrNull { it.modSeq } ?: 1L

    /** uids expunged, paired with the modseq at which the expunge happened. */
    private val expunged: MutableList<Pair<Long, Long>> = mutableListOf()

    override fun name(): String = mailboxName

    override fun exists(): Int = synchronized(lock) { messages.size }

    override fun recent(): Int = 0

    override fun uidNext(): Long = synchronized(lock) { nextUid }

    override fun uidValidity(): Long = 1L

    override fun flags(): List<String> = DEFAULT_FLAGS

    override fun permanentFlags(): List<String> = DEFAULT_FLAGS + "\\*"

    override fun highestModSeq(): Long = synchronized(lock) { highestModSeq }

    override fun modSeq(seq: Int): Long = synchronized(lock) { messageLocked(seq)?.modSeq ?: 0L }

    override fun messageByUid(uid: Long): ImapMessage? =
        synchronized(lock) { messages.firstOrNull { it.uid == uid } }

    override fun vanishedSince(modSeq: Long): List<Long> =
        synchronized(lock) { expunged.filter { it.second > modSeq }.map { it.first } }

    private fun messageLocked(seq: Int): ImapMessage? =
        if (seq in 1..messages.size) messages[seq - 1] else null

    override fun message(seq: Int): ImapMessage? = synchronized(lock) { messageLocked(seq) }

    override fun append(message: ByteArray, flags: List<String>): Long = synchronized(lock) {
        val uid = nextUid++
        val modSeq = ++highestModSeq
        val msg = ImapMessage(
            uid = uid,
            sequence = messages.size + 1,
            body = message.copyOf(),
            flags = if (flags.isEmpty()) emptyList() else flags,
            internalDate = now(),
            modSeq = modSeq,
        )
        messages += msg
        uid
    }

    override fun setFlags(seq: Int, action: String, flags: List<String>) {
        synchronized(lock) {
            val m = messageLocked(seq) ?: return
            val current = m.flags.toMutableList()
            val newFlags = when (action.uppercase()) {
                "FLAGS" -> flags.toList()
                "+FLAGS" -> (current + flags).distinct()
                "-FLAGS" -> current.filterNot { it in flags }
                else -> return
            }
            if (newFlags == m.flags) return
            messages[seq - 1] = m.copy(flags = newFlags, modSeq = ++highestModSeq)
        }
    }

    override fun expunge(): List<Int> = synchronized(lock) {
        val removed = mutableListOf<Int>()
        val kept = mutableListOf<ImapMessage>()
        val vanishedModSeq = ++highestModSeq
        messages.forEachIndexed { i, m ->
            if ("\\Deleted" in m.flags) {
                removed += i + 1
                expunged += m.uid to vanishedModSeq
            } else {
                kept += m
            }
        }
        messages.clear()
        kept.forEachIndexed { i, m -> messages += m.copy(sequence = i + 1) }
        removed
    }

    override fun all(): List<ImapMessage> = synchronized(lock) { messages.toList() }

    public companion object {
        public val DEFAULT_FLAGS: List<String> =
            listOf("\\Answered", "\\Flagged", "\\Deleted", "\\Seen", "\\Draft")
    }
}
