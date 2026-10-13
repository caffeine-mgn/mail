package pw.binom.mail.server

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Shared, in-memory mail store backing every protocol server in [MailServer].
 *
 * A user owns a set of named mailboxes (the `INBOX` is created automatically).
 * SMTP delivery routes an incoming message to the mailboxes of its recipients,
 * and the IMAP/POP3 servers read the very same [MailboxCore] instances, so a
 * message received over SMTP is immediately visible to IMAP/POP3 clients.
 */
public class MailStore {
    private val lock = SynchronizedObject()
    private val passwords: MutableMap<String, String> = mutableMapOf()
    private val mailboxes: MutableMap<String, MutableMap<String, MailboxCore>> = mutableMapOf()

    /** Registers (or updates) a user together with their password. */
    public fun addUser(user: String, password: String) {
        synchronized(lock) {
            passwords[user] = password
            mailboxes.getOrPut(user) { mutableMapOf() }.getOrPut("INBOX") { MailboxCore("INBOX") }
        }
    }

    public fun authenticate(user: String, password: String): Boolean =
        synchronized(lock) { passwords[user] == password }

    public fun users(): List<String> = synchronized(lock) { passwords.keys.toList() }

    public fun mailboxesOf(user: String): List<String> =
        synchronized(lock) { mailboxes[user]?.keys?.toList() ?: emptyList() }

    /** Returns an existing mailbox or `null`, without creating it. */
    public fun mailbox(user: String, name: String): MailboxCore? =
        synchronized(lock) { mailboxes[user]?.get(name) }

    /** Returns an existing mailbox or creates it on demand (used for delivery). */
    public fun ensureMailbox(user: String, name: String): MailboxCore = synchronized(lock) {
        mailboxes.getOrPut(user) { mutableMapOf() }.getOrPut(name) { MailboxCore(name) }
    }

    /** Delivers [data] to every local recipient; returns how many were accepted. */
    public fun deliver(recipients: List<String>, data: ByteArray, receivedAt: Long = nowMillis()): Int =
        synchronized(lock) {
            var accepted = 0
            for (recipient in recipients) {
                val local = localPart(recipient)
                if (local != null && passwords.containsKey(local)) {
                    mailboxes.getOrPut(local) { mutableMapOf() }
                        .getOrPut("INBOX") { MailboxCore("INBOX") }
                        .append(data, emptyList(), receivedAt)
                    accepted++
                }
            }
            accepted
        }

    public companion object {
        /** Extracts `user` from `user@domain` / `<user@domain>`. */
        public fun localPart(address: String): String? {
            val trimmed = address.trim().removePrefix("<").removeSuffix(">").trim()
            if (trimmed.isEmpty()) return null
            val at = trimmed.indexOf('@')
            return if (at < 0) trimmed else trimmed.substring(0, at)
        }
    }
}

/**
 * One mailbox: the single source of truth for a user's messages. Protocol
 * adapters ([SmtpDeliveryRouter], [ImapMailboxAdapter], [Pop3MailboxAdapter])
 * wrap this so SMTP, IMAP and POP3 observe the same data.
 */
public class MailboxCore(public val name: String) {
    internal class Entry(
        val uid: Long,
        val body: ByteArray,
        val flags: MutableSet<String>,
        val date: Long,
        var modSeq: Long,
    )

    private val lock = SynchronizedObject()
    private val entries: MutableList<Entry> = mutableListOf()
    private var nextUid: Long = 1L
    private var highestModSeq: Long = 1L
    private val expunged: MutableList<Pair<Long, Long>> = mutableListOf()

    public fun size(): Int = synchronized(lock) { entries.size }

    public fun uidNext(): Long = synchronized(lock) { nextUid }

    public fun uidValidity(): Long = 1L

    public fun highestModSeq(): Long = synchronized(lock) { highestModSeq }

    public fun modSeq(seq: Int): Long = synchronized(lock) { if (seq in 1..entries.size) entries[seq - 1].modSeq else 0L }

    internal fun byUid(uid: Long): Entry? = synchronized(lock) { entries.firstOrNull { it.uid == uid } }

    public fun vanishedSince(modSeq: Long): List<Long> =
        synchronized(lock) { expunged.filter { it.second > modSeq }.map { it.first } }

    public fun append(body: ByteArray, flags: List<String>, date: Long): Long = synchronized(lock) {
        val uid = nextUid++
        entries += Entry(uid, body.copyOf(), flags.toMutableSet(), date, ++highestModSeq)
        uid
    }

    internal fun get(seq: Int): Entry? =
        synchronized(lock) { if (seq in 1..entries.size) entries[seq - 1] else null }

    internal fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    public fun setFlags(seq: Int, action: String, flags: List<String>) {
        synchronized(lock) {
            if (seq !in 1..entries.size) return
            val e = entries[seq - 1]
            val before = e.flags.toSet()
            when (action.uppercase()) {
                "FLAGS" -> {
                    e.flags.clear()
                    e.flags += flags
                }

                "+FLAGS" -> e.flags += flags
                "-FLAGS" -> e.flags -= flags.toSet()
            }
            if (e.flags.toSet() != before) e.modSeq = ++highestModSeq
        }
    }

    public fun expunge(): List<Int> = synchronized(lock) {
        val removed = mutableListOf<Int>()
        val kept = mutableListOf<Entry>()
        val vanishedModSeq = ++highestModSeq
        entries.forEachIndexed { i, e ->
            if ("\\Deleted" in e.flags) {
                removed += i + 1
                expunged += e.uid to vanishedModSeq
            } else {
                kept += e
            }
        }
        entries.clear()
        entries += kept
        removed
    }

    /** Removes messages by UID (POP3 `QUIT` commit). */
    public fun removeUids(uids: Set<Long>) {
        synchronized(lock) {
            entries.removeAll { it.uid in uids }
        }
    }

    public companion object {
        public val DEFAULT_FLAGS: List<String> =
            listOf("\\Answered", "\\Flagged", "\\Deleted", "\\Seen", "\\Draft")
    }
}
