package com.example.familysafety.transport

import android.content.Context
import com.example.familysafety.group.GroupKeys

/**
 * The protocol version each member last advertised, and when this phone last heard it, kept
 * across restarts (3.3, 4.2).
 *
 * Switching on a change older builds can't follow needs every member to have upgraded. A lost
 * phone never comes back to say so, so a member silent for [GroupKeys.GATE_WINDOW_MS] stops
 * counting. Times are this phone's receive times, so a sender's clock can't move them.
 */
class PeerSightings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** When this phone started keeping sightings; a member never seen counts until a window has passed. */
    val observingSinceMs: Long
        get() = prefs.getLong(KEY_SINCE, 0L).takeIf { it > 0 }
            ?: System.currentTimeMillis().also { prefs.edit().putLong(KEY_SINCE, it).apply() }

    /** @return true if the member's advertised version changed. */
    fun record(memberId: String, protocolVersion: Int, nowMs: Long): Boolean {
        val previous = prefs.getString(memberId, null)?.substringBefore(':')?.toIntOrNull()
        prefs.edit().putString(memberId, "$protocolVersion:$nowMs").apply()
        return previous != protocolVersion
    }

    fun snapshot(): Map<String, GroupKeys.Sighting> =
        prefs.all.mapNotNull { (k, v) ->
            if (k == KEY_SINCE) return@mapNotNull null
            val parts = (v as? String)?.split(':') ?: return@mapNotNull null
            val version = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val at = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            k to GroupKeys.Sighting(version, at)
        }.toMap()

    private companion object {
        const val PREFS_NAME = "familysafety_peer_sightings"
        const val KEY_SINCE = "_observing_since"
    }
}
