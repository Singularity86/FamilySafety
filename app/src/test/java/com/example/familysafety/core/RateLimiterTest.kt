package com.example.familysafety.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class RateLimiterTest {
    
    private lateinit var rateLimiter: RateLimiter
    
    @Before
    fun setup() {
        rateLimiter = RateLimiter(maxRequests = 3, windowMs = 1000)
    }
    
    @Test
    fun `allows requests under limit`() = runTest {
        assertTrue(rateLimiter.allowRequest("test_key"))
        assertTrue(rateLimiter.allowRequest("test_key"))
        assertTrue(rateLimiter.allowRequest("test_key"))
    }
    
    @Test
    fun `blocks requests over limit`() = runTest {
        repeat(3) {
            assertTrue(rateLimiter.allowRequest("test_key"))
        }
        
        assertFalse(rateLimiter.allowRequest("test_key"))
    }
    
    @Test
    fun `separate keys have separate limits`() = runTest {
        repeat(3) {
            assertTrue(rateLimiter.allowRequest("key1"))
        }
        assertFalse(rateLimiter.allowRequest("key1"))
        
        assertTrue(rateLimiter.allowRequest("key2"))
    }
    
    @Test
    fun `allows requests after window expires`() = runTest {
        repeat(3) {
            assertTrue(rateLimiter.allowRequest("test_key"))
        }
        assertFalse(rateLimiter.allowRequest("test_key"))
        
        delay(1100)
        
        assertTrue(rateLimiter.allowRequest("test_key"))
    }
    
    @Test
    fun `getRetryAfterMs returns correct time`() = runTest {
        val startTime = System.currentTimeMillis()
        repeat(3) {
            rateLimiter.allowRequest("test_key")
        }
        
        val retryAfter = rateLimiter.getRetryAfterMs("test_key")
        
        assertTrue(retryAfter in 900..1100)
    }
    
    @Test
    fun `reset clears rate limit for key`() = runTest {
        repeat(3) {
            assertTrue(rateLimiter.allowRequest("test_key"))
        }
        assertFalse(rateLimiter.allowRequest("test_key"))
        
        rateLimiter.reset("test_key")
        
        assertTrue(rateLimiter.allowRequest("test_key"))
    }
}
