package com.example.familysafety.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPeerPolicyTest {

    @Test
    fun `retry delays double from 15 seconds and stop at 5 minutes`() {
        assertEquals(15_000L, LanPeerPolicy.retryDelayMs(0))
        assertEquals(30_000L, LanPeerPolicy.retryDelayMs(1))
        assertEquals(60_000L, LanPeerPolicy.retryDelayMs(2))
        assertEquals(240_000L, LanPeerPolicy.retryDelayMs(4))
        assertEquals(LanPeerPolicy.MAX_RETRY_DELAY_MS, LanPeerPolicy.retryDelayMs(5))
        assertEquals(LanPeerPolicy.MAX_RETRY_DELAY_MS, LanPeerPolicy.retryDelayMs(1_000))
    }

    @Test
    fun `a negative attempt count is treated as the first`() {
        assertEquals(LanPeerPolicy.BASE_RETRY_DELAY_MS, LanPeerPolicy.retryDelayMs(-3))
    }

    @Test
    fun `retry delays never decrease`() {
        val delays = (0..30).map { LanPeerPolicy.retryDelayMs(it) }
        assertTrue(delays.zipWithNext().all { (a, b) -> b >= a })
    }
}
