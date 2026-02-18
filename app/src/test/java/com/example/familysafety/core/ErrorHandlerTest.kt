package com.example.familysafety.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ErrorHandlerTest {

    // ── withRetry ────────────────────────────────────────────────

    @Test
    fun `withRetry succeeds on first attempt`() = runTest {
        var callCount = 0
        val result = ErrorHandler.withRetry(maxAttempts = 3) {
            callCount++
            "success"
        }
        assertTrue(result.isSuccess)
        assertEquals("success", result.getOrNull())
        assertEquals(1, callCount)
    }

    @Test
    fun `withRetry succeeds after initial failures`() = runTest {
        var callCount = 0
        val result = ErrorHandler.withRetry(
            maxAttempts = 3,
            initialDelayMs = 0
        ) {
            callCount++
            if (callCount < 3) throw RuntimeException("not yet")
            "done"
        }
        assertTrue(result.isSuccess)
        assertEquals("done", result.getOrNull())
        assertEquals(3, callCount)
    }

    @Test
    fun `withRetry returns failure after exhausting all attempts`() = runTest {
        var callCount = 0
        val result = ErrorHandler.withRetry(
            maxAttempts = 3,
            initialDelayMs = 0
        ) {
            callCount++
            throw RuntimeException("always fails")
        }
        assertFalse(result.isSuccess)
        assertEquals(3, callCount)
        assertTrue(result.exceptionOrNull()?.message == "always fails")
    }

    @Test
    fun `withRetry invokes onError callback with attempt number`() = runTest {
        val errors = mutableListOf<Pair<Exception, Int>>()
        ErrorHandler.withRetry(
            maxAttempts = 3,
            initialDelayMs = 0,
            onError = { ex, attempt -> errors.add(ex to attempt) }
        ) {
            throw RuntimeException("fail")
        }
        assertEquals(3, errors.size)
        assertEquals(1, errors[0].second)
        assertEquals(2, errors[1].second)
        assertEquals(3, errors[2].second)
    }

    @Test
    fun `withRetry does not delay after last attempt`() = runTest {
        // If maxAttempts=1 there is never a delay, so this must complete quickly
        val result = ErrorHandler.withRetry(
            maxAttempts = 1,
            initialDelayMs = 60_000
        ) {
            throw RuntimeException("fail")
        }
        assertFalse(result.isSuccess)
    }

    // ── withLinearRetry ──────────────────────────────────────────

    @Test
    fun `withLinearRetry succeeds on first attempt`() = runTest {
        val result = ErrorHandler.withLinearRetry(maxAttempts = 3) { "ok" }
        assertTrue(result.isSuccess)
        assertEquals("ok", result.getOrNull())
    }

    @Test
    fun `withLinearRetry returns failure after max attempts`() = runTest {
        var callCount = 0
        val result = ErrorHandler.withLinearRetry(
            maxAttempts = 2,
            delayMs = 0
        ) {
            callCount++
            throw RuntimeException("linear fail")
        }
        assertFalse(result.isSuccess)
        assertEquals(2, callCount)
    }

    @Test
    fun `withLinearRetry invokes onError with correct attempt numbers`() = runTest {
        val attempts = mutableListOf<Int>()
        ErrorHandler.withLinearRetry(
            maxAttempts = 3,
            delayMs = 0,
            onError = { _, attempt -> attempts.add(attempt) }
        ) {
            throw RuntimeException("fail")
        }
        assertEquals(listOf(1, 2, 3), attempts)
    }

    // ── withTimeout ──────────────────────────────────────────────

    @Test
    fun `withTimeout returns success when operation completes in time`() = runTest {
        val result = ErrorHandler.withTimeout(timeoutMs = 5_000) { "fast" }
        assertTrue(result.isSuccess)
        assertEquals("fast", result.getOrNull())
    }

    @Test
    fun `withTimeout returns failure when operation exceeds time limit`() = runTest {
        var callbackInvoked = false
        val result = ErrorHandler.withTimeout(
            timeoutMs = 1,
            onTimeout = { callbackInvoked = true }
        ) {
            kotlinx.coroutines.delay(10_000)
            "slow"
        }
        assertFalse(result.isSuccess)
        assertTrue(callbackInvoked)
        assertTrue(result.exceptionOrNull() is TimeoutException)
    }

    @Test
    fun `withTimeout invokes onTimeout callback`() = runTest {
        var notified = false
        ErrorHandler.withTimeout(timeoutMs = 1, onTimeout = { notified = true }) {
            kotlinx.coroutines.delay(10_000)
        }
        assertTrue(notified)
    }

    // ── safely ───────────────────────────────────────────────────

    @Test
    fun `safely returns block result on success`() = runTest {
        val result = ErrorHandler.safely("tag", "op") { 42 }
        assertEquals(42, result)
    }

    // Note: the error cases for safely() call android.util.Log.e() inside the catch block,
    // which is an Android stub in JVM unit tests. Those paths require instrumented tests.
}
