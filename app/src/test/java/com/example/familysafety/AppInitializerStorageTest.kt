package com.example.familysafety

import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.LocalMemberId
import com.example.familysafety.storage.StorageArea
import com.example.familysafety.storage.StorageHealth
import com.example.familysafety.transport.LocalTransport
import com.example.familysafety.transport.MqttTransport
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Startup with an unreadable encrypted store (A7): nothing starts, so nothing writes over the
 * store or connects with half an identity, and a later call after the store recovers still runs.
 */
class AppInitializerStorageTest {

    private val groupStateManager = mockk<GroupStateManager>(relaxed = true)
    private val mqtt = mockk<MqttTransport>(relaxed = true)
    private val lan = mockk<LocalTransport>(relaxed = true)

    private val initializer = AppInitializer(
        mockk(relaxed = true), groupStateManager, LocalMemberId("a".repeat(32)), mockk(relaxed = true),
        mqtt, lan, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
    )

    @Before
    fun setUp() = mockkObject(StorageHealth)

    @After
    fun tearDown() = unmockkObject(StorageHealth)

    @Test
    fun `an unreadable store starts nothing, and a later call after it recovers starts`() {
        coEvery { StorageHealth.unreadableStores(any()) } returns listOf(StorageArea.DATABASE)
        initializer.initialize()
        coVerify(timeout = 2_000) { StorageHealth.unreadableStores(any()) }
        Thread.sleep(200)
        coVerify(exactly = 0) { groupStateManager.initialize() }
        coVerify(exactly = 0) { mqtt.initialize(any(), any(), any()) }
        verify(exactly = 0) { lan.start(any()) }

        // `started` was released, so this call isn't swallowed as a duplicate.
        coEvery { StorageHealth.unreadableStores(any()) } returns emptyList()
        initializer.initialize()
        coVerify(timeout = 2_000) { groupStateManager.initialize() }
    }
}
