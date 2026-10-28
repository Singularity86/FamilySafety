package com.example.familysafety.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the default network moves away from the one our connection was made on, reconnect at
 * once rather than wait out the keep-alive (wireless Android Auto, 2026-10-27).
 */
class NetworkSwitchReconnectTest {

    private val homeWifi = "wifi-home"
    private val mobile = "cellular"

    @Test
    fun movingToAnotherNetworkWhileConnected_reconnects() {
        assertTrue(MqttTransport.movedOffConnectionNetwork(homeWifi, mobile, connected = true))
    }

    @Test
    fun theSameNetworkReportedAgain_doesNot() {
        assertFalse(MqttTransport.movedOffConnectionNetwork(homeWifi, homeWifi, connected = true))
    }

    @Test
    fun noDefaultNetwork_leavesItToTheNetworkRestoredPath() {
        assertFalse(MqttTransport.movedOffConnectionNetwork(homeWifi, null, connected = true))
    }

    @Test
    fun notConnected_isTheReconnectLoopsJob() {
        assertFalse(MqttTransport.movedOffConnectionNetwork(homeWifi, mobile, connected = false))
    }

    @Test
    fun aConnectionWithNoRecordedNetwork_isLeftAlone() {
        assertFalse(MqttTransport.movedOffConnectionNetwork(null, mobile, connected = true))
    }
}
