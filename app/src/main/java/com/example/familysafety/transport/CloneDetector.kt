package com.example.familysafety.transport

/**
 * Notices one identity in use on two installs at once (F15).
 *
 * A recovery phrase restores an identity on any phone, and a restored phone is let back into
 * the family without a prompt. So a phrase that someone else learned produced a second,
 * invisible copy of a family member. Each install now signs a random device ID into its
 * presence. A second ID showing up *online* while the first was last seen online, and
 * recently, means two phones running at once.
 *
 * Offline matters. Reinstalling or restoring is the common case, and it looks like this: the
 * old install's last-will "offline" arrives a few minutes after it dies, then the new install
 * comes online. Counting that as an overlap would tell the whole family that someone was
 * hacked every time they reinstalled. So an offline from a device ends its sighting, and an
 * offline from any other device proves nothing and is ignored.
 *
 * Times are this phone's receive times, not the sender's timestamps, so clock skew between
 * phones can't hide or fake an overlap.
 */
class CloneDetector(
    private val windowMs: Long = WINDOW_MS,
    private val cooldownMs: Long = COOLDOWN_MS
) {
    private data class Sighting(val deviceId: String, val at: Long, val online: Boolean)

    private val lastSighting = HashMap<String, Sighting>()
    private val lastAlert = HashMap<String, Long>()

    /** Record a verified sighting; true when it should be announced now. */
    @Synchronized
    fun observe(memberId: String, deviceId: String, isOnline: Boolean, now: Long): Boolean {
        val previous = lastSighting[memberId]
        if (!isOnline) {
            // Only the device we're tracking can sign itself off. Another device's offline
            // says nothing about whether two were ever running together.
            if (previous == null || previous.deviceId == deviceId) {
                lastSighting[memberId] = Sighting(deviceId, now, online = false)
            }
            return false
        }

        lastSighting[memberId] = Sighting(deviceId, now, online = true)
        if (previous == null || previous.deviceId == deviceId) return false
        if (!previous.online) return false                // the old one signed off: a switch
        if (now - previous.at > windowMs) return false    // not heard from lately: a switch
        val alerted = lastAlert[memberId]
        if (alerted != null && now - alerted < cooldownMs) return false
        lastAlert[memberId] = now
        return true
    }

    companion object {
        const val WINDOW_MS = 10 * 60_000L
        const val COOLDOWN_MS = 6 * 60 * 60_000L

        /**
         * Our own presence, seen on our own topic: another install is running with our identity
         * only if it says it's online. Our previous install's retained offline, which is what a
         * restored phone finds there, is not that.
         */
        fun ownPresenceIsAnotherDevice(isOnline: Boolean, deviceId: String, myDeviceId: String): Boolean =
            isOnline && deviceId != myDeviceId
    }
}
