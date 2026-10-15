package com.example.familysafety.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.2b: each install keeps its own broker connection, so two phones can be seen at once. */
class ClientIdTest {

    private val member = "0f6a7e0350db21861e2f2aac5571fc7e"
    private val deviceA = "00112233445566778899aabbccddeeff"
    private val deviceB = "ffeeddccbbaa99887766554433221100"

    @Test
    fun `client id carries the member and the full device id`() {
        assertEquals("familysafe_${member}_$deviceA", MqttConfig.generateClientId(member, deviceA))
    }

    @Test
    fun `two installs of one identity get different client ids`() {
        // The same ID made the broker evict one install whenever the other connected.
        assertNotEquals(MqttConfig.generateClientId(member, deviceA), MqttConfig.generateClientId(member, deviceB))
    }

    @Test
    fun `the client id can't be built from the member id alone`() {
        assertNotEquals("familysafe_$member", MqttConfig.generateClientId(member, deviceA))
    }

    @Test
    fun `only a connection lost soon after connecting counts as short-lived`() {
        assertTrue(MqttTransport.isShortLivedConnection(connectedAtMs = 1_000, nowMs = 20_000))
        assertFalse(MqttTransport.isShortLivedConnection(connectedAtMs = 1_000, nowMs = 40_000))
        assertFalse(MqttTransport.isShortLivedConnection(connectedAtMs = 0, nowMs = 5_000))
    }
}
