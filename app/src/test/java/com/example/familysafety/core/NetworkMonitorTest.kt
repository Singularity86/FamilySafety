package com.example.familysafety.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NetworkMonitorTest {

    private lateinit var mockContext: Context
    private lateinit var mockCM: ConnectivityManager
    private lateinit var mockNetwork: Network
    private lateinit var mockCapabilities: NetworkCapabilities
    private lateinit var monitor: NetworkMonitor

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockCM = mockk(relaxed = true)
        mockNetwork = mockk(relaxed = true)
        mockCapabilities = mockk(relaxed = true)

        every { mockContext.getSystemService(any<String>()) } returns mockCM
    }

    // ── isCurrentlyConnected ────────────────────────────────────

    @Test
    fun `isCurrentlyConnected returns true when active network has internet capability`() {
        every { mockCM.activeNetwork } returns mockNetwork
        every { mockCM.getNetworkCapabilities(mockNetwork) } returns mockCapabilities
        every { mockCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true

        monitor = NetworkMonitor(mockContext)
        assertTrue(monitor.isCurrentlyConnected())
    }

    @Test
    fun `isCurrentlyConnected returns false when no active network`() {
        every { mockCM.activeNetwork } returns null

        monitor = NetworkMonitor(mockContext)
        assertFalse(monitor.isCurrentlyConnected())
    }

    @Test
    fun `isCurrentlyConnected returns false when capabilities are null`() {
        every { mockCM.activeNetwork } returns mockNetwork
        every { mockCM.getNetworkCapabilities(mockNetwork) } returns null

        monitor = NetworkMonitor(mockContext)
        assertFalse(monitor.isCurrentlyConnected())
    }

    @Test
    fun `isCurrentlyConnected returns false when internet capability is absent`() {
        every { mockCM.activeNetwork } returns mockNetwork
        every { mockCM.getNetworkCapabilities(mockNetwork) } returns mockCapabilities
        every { mockCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns false

        monitor = NetworkMonitor(mockContext)
        assertFalse(monitor.isCurrentlyConnected())
    }

    // ── isWifiConnected ─────────────────────────────────────────

    @Test
    fun `isWifiConnected returns true when on wifi transport`() {
        every { mockCM.activeNetwork } returns mockNetwork
        every { mockCM.getNetworkCapabilities(mockNetwork) } returns mockCapabilities
        every { mockCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true

        monitor = NetworkMonitor(mockContext)
        assertTrue(monitor.isWifiConnected())
    }

    @Test
    fun `isWifiConnected returns false when on cellular transport`() {
        every { mockCM.activeNetwork } returns mockNetwork
        every { mockCM.getNetworkCapabilities(mockNetwork) } returns mockCapabilities
        every { mockCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false

        monitor = NetworkMonitor(mockContext)
        assertFalse(monitor.isWifiConnected())
    }

    @Test
    fun `isWifiConnected returns false when no active network`() {
        every { mockCM.activeNetwork } returns null

        monitor = NetworkMonitor(mockContext)
        assertFalse(monitor.isWifiConnected())
    }

    // ── isMeteredConnection ──────────────────────────────────────

    @Test
    fun `isMeteredConnection returns true when network is metered`() {
        every { mockCM.isActiveNetworkMetered } returns true

        monitor = NetworkMonitor(mockContext)
        assertTrue(monitor.isMeteredConnection())
    }

    @Test
    fun `isMeteredConnection returns false when network is unmetered`() {
        every { mockCM.isActiveNetworkMetered } returns false

        monitor = NetworkMonitor(mockContext)
        assertFalse(monitor.isMeteredConnection())
    }
}
