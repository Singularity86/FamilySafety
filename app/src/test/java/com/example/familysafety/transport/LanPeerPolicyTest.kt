package com.example.familysafety.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPeerPolicyTest {

    private val prefix = "55abf73c6fd7b670"

    @Test
    fun `reads the member prefix from our service name`() {
        assertEquals(prefix, LanPeerPolicy.peerPrefixFromServiceName("familysafety-$prefix"))
    }

    @Test
    fun `a name NSD renamed after a conflict still names the same member`() {
        // Re-registering while neighbours still cache the old registration produces these.
        assertEquals(prefix, LanPeerPolicy.peerPrefixFromServiceName("familysafety-$prefix (2)"))
        assertEquals(prefix, LanPeerPolicy.peerPrefixFromServiceName("familysafety-$prefix (13)"))
    }

    @Test
    fun `other services and malformed names are ignored`() {
        assertNull(LanPeerPolicy.peerPrefixFromServiceName("printer-$prefix"))
        assertNull(LanPeerPolicy.peerPrefixFromServiceName("familysafety-"))
        assertNull(LanPeerPolicy.peerPrefixFromServiceName("familysafety-55abf73c"))
        assertNull(LanPeerPolicy.peerPrefixFromServiceName("familysafety-NOT-HEX-AT-ALL!!"))
    }

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
