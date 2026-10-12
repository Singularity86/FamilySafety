package com.example.familysafety.transport

/**
 * Notices one identity in use on two installs at once (F15).
 *
 * A recovery phrase restores an identity on any phone, and a restored phone is let back into
 * the family without a prompt. So a phrase that someone else learned produced a second,
 * invisible copy of a family member. Each install now signs a random device ID into its
 * presence. Two different IDs for one member close together in time mean two phones.
 *
 * Times are this phone's receive times, not the sender's timestamps, so clock skew between
 * phones can't hide or fake an overlap.
 */
class CloneDetector(
    private val windowMs: Long = WINDOW_MS,
    private val cooldownMs: Long = COOLDOWN_MS
) {
    private data class Sighting(val deviceId: String, val at: Long)

    private val lastSighting = HashMap<String, Sighting>()
    private val lastAlert = HashMap<String, Long>()

    /** Record a verified sighting; true when it should be announced now. */
    @Synchronized
    fun observe(memberId: String, deviceId: String, now: Long): Boolean {
        val previous = lastSighting[memberId]
        lastSighting[memberId] = Sighting(deviceId, now)
        if (previous == null || previous.deviceId == deviceId) return false
        if (now - previous.at > windowMs) return false   // a switch of phones, not two at once
        val alerted = lastAlert[memberId]
        if (alerted != null && now - alerted < cooldownMs) return false
        lastAlert[memberId] = now
        return true
    }

    companion object {
        const val WINDOW_MS = 10 * 60_000L
        const val COOLDOWN_MS = 6 * 60 * 60_000L
    }
}
