package com.example.familysafety.transport

/**
 * The parts of [LocalTransport]'s peer handling that don't need Android, so they can be unit
 * tested: reading a peer out of an NSD service name, and how long to wait before trying again.
 */
object LanPeerPolicy {

    const val SERVICE_NAME_PREFIX = "familysafety-"

    /** Member IDs are 32 hex characters; peers are identified by the first 16. */
    const val PEER_PREFIX_LENGTH = 16

    /** First retry after a failure; doubles each time up to [MAX_RETRY_DELAY_MS]. */
    const val BASE_RETRY_DELAY_MS = 15_000L
    const val MAX_RETRY_DELAY_MS = 5 * 60_000L

    /**
     * The member-ID prefix advertised in [serviceName], or null if it is not one of ours.
     *
     * NSD renames a service whose name is already taken on the network — "familysafety-abcd…"
     * becomes "familysafety-abcd… (2)". That happens when a phone re-registers while the old
     * registration is still cached by its neighbours, after an app restart or a Wi-Fi change.
     * Taking the whole remainder as the prefix turned that peer into a stranger that matched no
     * member, so only the first [PEER_PREFIX_LENGTH] characters count.
     */
    fun peerPrefixFromServiceName(serviceName: String): String? {
        if (!serviceName.startsWith(SERVICE_NAME_PREFIX)) return null
        val prefix = serviceName.removePrefix(SERVICE_NAME_PREFIX).take(PEER_PREFIX_LENGTH)
        if (prefix.length != PEER_PREFIX_LENGTH) return null
        if (!prefix.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return prefix
    }

    /** Delay before retry number [attempt] (0-based): 15 s, 30 s, 60 s … capped at 5 min. */
    fun retryDelayMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 20)
        return (BASE_RETRY_DELAY_MS shl shift).coerceAtMost(MAX_RETRY_DELAY_MS)
    }
}
