package com.example.familysafety.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttConnectingStaleTest {

    private val now = 10_000_000L

    @Test
    fun neverStarted_isNotStale() {
        assertFalse(MqttTransport.isConnectingStale(0L, now))
    }

    @Test
    fun freshAttempt_isNotStale() {
        assertFalse(MqttTransport.isConnectingStale(now - 1_000L, now))
    }

    @Test
    fun fullRetrySequenceInFlight_isNotStale() {
        // Three bounded attempts plus backoff is a legitimate worst case.
        val worstCase = 3 * MqttTransport.CONNECT_ATTEMPT_TIMEOUT_MS + 6_000L
        assertFalse(MqttTransport.isConnectingStale(now - worstCase, now))
    }

    @Test
    fun outlivingEveryPossibleAttempt_isStale() {
        val tooLong = 3 * MqttTransport.CONNECT_ATTEMPT_TIMEOUT_MS + 10_001L
        assertTrue(MqttTransport.isConnectingStale(now - tooLong, now))
    }
}
