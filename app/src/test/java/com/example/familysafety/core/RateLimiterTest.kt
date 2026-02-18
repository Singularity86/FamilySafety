package com.example.familysafety.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RateLimiterTest {

    // Use a fresh limiter for each test to avoid cross-test contamination.
    private lateinit var limiter: RateLimiter

    @Before
    fun setup() {
        limiter = RateLimiter(maxRequests = 3, windowMs = 60_000)
    }

    // ── allowRequest ─────────────────────────────────────────────────────────

    @Test
    fun `allowRequest permits the first request`() = runTest {
        assertTrue(limiter.allowRequest("key1"))
    }

    @Test
    fun `allowRequest permits requests up to the limit`() = runTest {
        assertTrue(limiter.allowRequest("key1"))
        assertTrue(limiter.allowRequest("key1"))
        assertTrue(limiter.allowRequest("key1"))
    }

    @Test
    fun `allowRequest blocks the request exceeding the limit`() = runTest {
        repeat(3) { limiter.allowRequest("key1") }
        assertFalse(limiter.allowRequest("key1"))
    }

    @Test
    fun `allowRequest tracks different keys independently`() = runTest {
        repeat(3) { limiter.allowRequest("key_a") }
        // key_a is exhausted, key_b should still be allowed
        assertTrue(limiter.allowRequest("key_b"))
    }

    @Test
    fun `allowRequest after reset allows requests again`() = runTest {
        repeat(3) { limiter.allowRequest("key1") }
        assertFalse(limiter.allowRequest("key1"))

        limiter.reset("key1")

        assertTrue(limiter.allowRequest("key1"))
    }

    // ── getRetryAfterMs ───────────────────────────────────────────────────────

    @Test
    fun `getRetryAfterMs returns 0 when under limit`() = runTest {
        limiter.allowRequest("key1")
        assertEquals(0L, limiter.getRetryAfterMs("key1"))
    }

    @Test
    fun `getRetryAfterMs returns 0 for unknown key`() = runTest {
        assertEquals(0L, limiter.getRetryAfterMs("nonexistent"))
    }

    @Test
    fun `getRetryAfterMs returns positive when at limit`() = runTest {
        repeat(3) { limiter.allowRequest("key1") }
        assertTrue(limiter.getRetryAfterMs("key1") > 0)
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    fun `reset clears the window for one key without affecting others`() = runTest {
        repeat(3) { limiter.allowRequest("key_a") }
        repeat(3) { limiter.allowRequest("key_b") }

        limiter.reset("key_a")

        assertTrue(limiter.allowRequest("key_a"))   // key_a is clear
        assertFalse(limiter.allowRequest("key_b"))  // key_b still exhausted
    }

    @Test
    fun `reset on unknown key does not throw`() = runTest {
        limiter.reset("does_not_exist")
        // No exception expected
    }

    // ── clearAll ──────────────────────────────────────────────────────────────

    @Test
    fun `clearAll removes all windows`() = runTest {
        repeat(3) { limiter.allowRequest("key_x") }
        repeat(3) { limiter.allowRequest("key_y") }

        limiter.clearAll()

        assertTrue(limiter.allowRequest("key_x"))
        assertTrue(limiter.allowRequest("key_y"))
    }

    // ── window expiry ─────────────────────────────────────────────────────────

    @Test
    fun `allowRequest permits requests after window expires`() = runTest {
        // Use a very short window so Thread.sleep can advance real time past it.
        val shortWindowLimiter = RateLimiter(maxRequests = 2, windowMs = 20)

        shortWindowLimiter.allowRequest("key1")
        shortWindowLimiter.allowRequest("key1")
        assertFalse(shortWindowLimiter.allowRequest("key1")) // exhausted

        Thread.sleep(50)  // wait for real-clock window to expire (20 ms + margin)

        // Old requests fall outside the window; new one should be allowed.
        assertTrue(shortWindowLimiter.allowRequest("key1"))
    }

    // ── RateLimiters singleton ────────────────────────────────────────────────

    @Test
    fun `RateLimiters singleton instances are not null`() {
        assertNotNull(RateLimiters.locationUpdates)
        assertNotNull(RateLimiters.mqttPublish)
        assertNotNull(RateLimiters.groupSync)
        assertNotNull(RateLimiters.joinRequests)
    }
}
