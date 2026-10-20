package com.example.familysafety.transport

import java.util.concurrent.ConcurrentHashMap

/**
 * Which presence updates a receiver accepts by timestamp, including a sudden disconnect.
 *
 * The last-will is signed at connect (T0), so it is older than every "online" the same
 * connection publishes afterwards (T1). A plain "drop anything older than the newest" rule
 * therefore threw away the will on every phone that was already listening, and an abrupt
 * death only ever showed as "seen X ago".
 *
 * Online presence now names its connection's start, [MessageProtocol.PresenceUpdate.sessionStart]:
 * the exact timestamp its will was signed with. An older "offline" is accepted only when it is
 * that will: same member, timestamp equal to the session start the member last announced. An
 * earlier session's will (a replay, or the late will of a phone that has since reconnected)
 * never matches, so it can't mark a live member offline.
 *
 * Pure apart from its maps, so the rule is tested without a broker.
 */
class PresenceSessions {

    enum class Verdict {
        /** Not older than the newest accepted update: the normal case. */
        CURRENT,
        /** Older, but it is the will of the session in progress: the member died abruptly. */
        SESSION_WILL,
        /** Older and not that will: a replay. */
        STALE
    }

    private val newest = ConcurrentHashMap<String, Long>()
    private val sessionStarts = ConcurrentHashMap<String, Long>()

    fun verdict(memberId: String, isOnline: Boolean, timestamp: Long): Verdict {
        val last = newest[memberId] ?: return Verdict.CURRENT
        // Equal is allowed: the broker redelivers retained copies on every resubscribe.
        if (timestamp >= last) return Verdict.CURRENT
        return if (!isOnline && sessionStarts[memberId] == timestamp) Verdict.SESSION_WILL else Verdict.STALE
    }

    /**
     * Records an update that passed every check. [verifiedSessionStart] is the update's session
     * start if its session signature verified, else null.
     */
    fun accepted(memberId: String, isOnline: Boolean, timestamp: Long, verdict: Verdict, verifiedSessionStart: Long?) {
        if (verdict == Verdict.SESSION_WILL) {
            // The newest timestamp stays where it was, so older updates are still replays; the
            // session is over, so its will can't be accepted twice.
            sessionStarts.remove(memberId)
            return
        }
        newest[memberId] = timestamp
        when {
            !isOnline -> sessionStarts.remove(memberId)
            // A session can't start after the update that announces it.
            verifiedSessionStart != null && verifiedSessionStart <= timestamp ->
                sessionStarts[memberId] = verifiedSessionStart
            else -> sessionStarts.remove(memberId)
        }
    }
}
