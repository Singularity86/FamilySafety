package com.example.familysafety.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate limiter to prevent excessive API/network calls
 */
class RateLimiter(
    private val maxRequests: Int,
    private val windowMs: Long
) {
    private data class RequestWindow(
        val requests: MutableList<Long> = mutableListOf(),
        val mutex: Mutex = Mutex()
    )
    
    private val windows = ConcurrentHashMap<String, RequestWindow>()
    
    /**
     * Check if request is allowed
     */
    suspend fun allowRequest(key: String): Boolean {
        val window = windows.getOrPut(key) { RequestWindow() }
        
        return window.mutex.withLock {
            val now = System.currentTimeMillis()
            
            // Remove old requests outside the window
            window.requests.removeAll { it < now - windowMs }
            
            // Check if under limit
            if (window.requests.size < maxRequests) {
                window.requests.add(now)
                true
            } else {
                Timber.w("Rate limit exceeded for key: $key")
                false
            }
        }
    }
    
    /**
     * Get time until next request is allowed
     */
    suspend fun getRetryAfterMs(key: String): Long {
        val window = windows[key] ?: return 0
        
        return window.mutex.withLock {
            val now = System.currentTimeMillis()
            window.requests.removeAll { it < now - windowMs }
            
            if (window.requests.size < maxRequests) {
                0
            } else {
                val oldestRequest = window.requests.minOrNull() ?: return@withLock 0
                (oldestRequest + windowMs) - now
            }
        }
    }
    
    /**
     * Reset rate limiter for a key
     */
    suspend fun reset(key: String) {
        windows[key]?.mutex?.withLock {
            windows[key]?.requests?.clear()
        }
    }
    
    /**
     * Clear all rate limits
     */
    fun clearAll() {
        windows.clear()
    }
}

/**
 * Pre-configured rate limiters for different use cases
 */
object RateLimiters {
    // Location updates: max 120 per hour (one every 30 seconds)
    val locationUpdates = RateLimiter(
        maxRequests = 120,
        windowMs = 60 * 60 * 1000
    )
    
    // MQTT publishes: max 300 per minute
    val mqttPublish = RateLimiter(
        maxRequests = 300,
        windowMs = 60 * 1000
    )
    
    // Group sync broadcasts: max 10 per minute
    val groupSync = RateLimiter(
        maxRequests = 10,
        windowMs = 60 * 1000
    )
    
    // Join requests: max 5 per hour
    val joinRequests = RateLimiter(
        maxRequests = 5,
        windowMs = 60 * 60 * 1000
    )
}
