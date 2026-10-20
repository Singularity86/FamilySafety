package com.example.familysafety.security

/**
 * Sequence numbers this phone accepted in the last [WINDOW_MS], per sender, in memory only.
 *
 * A family-wide message travels over both the broker and the LAN (spec §4.1), so its second
 * copy is refused by the replay window as a `replay`. That is expected and not worth a log
 * entry; a replay of something accepted longer ago is (agreed with iOS for the security log).
 */
class RecentSequences(private val maxEntries: Int = 4_096) {

    private val acceptedAt = LinkedHashMap<String, Long>()

    @Synchronized
    fun accepted(senderKey: String, seq: Long, now: Long) {
        acceptedAt["$senderKey|$seq"] = now
        if (acceptedAt.size > maxEntries) prune(now)
        // Still over: drop the oldest; insertion order is acceptance order.
        val over = acceptedAt.size - maxEntries
        if (over > 0) acceptedAt.keys.take(over).forEach(acceptedAt::remove)
    }

    /** Whether [seq] from [senderKey] was accepted within the window, i.e. this copy is a duplicate. */
    @Synchronized
    fun isRecentDuplicate(senderKey: String, seq: Long, now: Long): Boolean {
        val at = acceptedAt["$senderKey|$seq"] ?: return false
        return now - at <= WINDOW_MS
    }

    private fun prune(now: Long) {
        acceptedAt.entries.removeAll { now - it.value > WINDOW_MS }
    }

    companion object {
        const val WINDOW_MS = 10 * 60_000L
    }
}
