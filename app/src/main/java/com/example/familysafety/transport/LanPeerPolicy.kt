package com.example.familysafety.transport

/**
 * The parts of [LocalTransport]'s peer handling that don't need Android, so they can be unit
 * tested: how long to wait before trying again. Service names are in [LanProtocol].
 */
object LanPeerPolicy {

    /** First retry after a failure; doubles each time up to [MAX_RETRY_DELAY_MS]. */
    const val BASE_RETRY_DELAY_MS = 15_000L
    const val MAX_RETRY_DELAY_MS = 5 * 60_000L

    /** Delay before retry number [attempt] (0-based): 15 s, 30 s, 60 s … capped at 5 min. */
    fun retryDelayMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 20)
        return (BASE_RETRY_DELAY_MS shl shift).coerceAtMost(MAX_RETRY_DELAY_MS)
    }
}
