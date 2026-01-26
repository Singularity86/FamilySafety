package com.example.familysafety.location

import com.example.familysafety.storage.LocationHistoryRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocationRepositoryTest {

    private lateinit var locationRepository: LocationRepository
    private lateinit var mockHistoryRepository: LocationHistoryRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockHistoryRepository = mockk(relaxed = true)
        locationRepository = LocationRepository(mockHistoryRepository)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun createTestLocation(
        memberId: String = "test_member",
        latitude: Double = 37.7749,
        longitude: Double = -122.4194,
        accuracy: Float = 10f,
        timestamp: Long = System.currentTimeMillis()
    ): MemberLocation {
        return MemberLocation(
            memberId = memberId,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            timestamp = timestamp
        )
    }

    @Test
    fun `initializes with locations from history`() = runTest {
        val existingLocations = mapOf(
            "member1" to createTestLocation(memberId = "member1"),
            "member2" to createTestLocation(memberId = "member2")
        )

        coEvery { mockHistoryRepository.getLatestLocationsForAllMembers() } returns existingLocations

        locationRepository.initialize()
        advanceUntilIdle()

        val locations = locationRepository.memberLocations.first()
        assertEquals(2, locations.size)
        assertTrue(locations.containsKey("member1"))
        assertTrue(locations.containsKey("member2"))
    }

    @Test
    fun `initializes only once`() = runTest {
        coEvery { mockHistoryRepository.getLatestLocationsForAllMembers() } returns emptyMap()

        locationRepository.initialize()
        advanceUntilIdle()

        locationRepository.initialize()
        advanceUntilIdle()

        // Should only be called once
        coVerify(exactly = 1) { mockHistoryRepository.getLatestLocationsForAllMembers() }
    }

    @Test
    fun `updates member location in cache`() = runTest {
        val location = createTestLocation(memberId = "test_member")

        locationRepository.updateMemberLocation(location)

        val locations = locationRepository.memberLocations.first()
        assertEquals(1, locations.size)
        assertEquals(location, locations["test_member"])
    }

    @Test
    fun `persists location to history repository`() = runTest {
        val location = createTestLocation(memberId = "test_member")

        locationRepository.updateMemberLocation(location)
        advanceUntilIdle()

        coVerify {
            mockHistoryRepository.saveLocation(
                location = location,
                isReplicated = false,
                replicatedFrom = null
            )
        }
    }

    @Test
    fun `updateMyLocation updates both myLocation and memberLocations`() = runTest {
        val location = createTestLocation(memberId = "my_member_id")

        locationRepository.updateMyLocation(location)

        val myLocation = locationRepository.myLocation.first()
        val memberLocations = locationRepository.memberLocations.first()

        assertEquals(location, myLocation)
        assertEquals(location, memberLocations["my_member_id"])
    }

    @Test
    fun `updateMemberLocations updates cache with batch`() = runTest {
        val locations = listOf(
            createTestLocation(memberId = "member1", timestamp = 1000L),
            createTestLocation(memberId = "member2", timestamp = 2000L),
            createTestLocation(memberId = "member3", timestamp = 3000L)
        )

        locationRepository.updateMemberLocations(locations, isReplicated = true, replicatedFrom = "peer1")
        advanceUntilIdle()

        val cachedLocations = locationRepository.memberLocations.first()
        assertEquals(3, cachedLocations.size)

        coVerify {
            mockHistoryRepository.saveLocations(
                locations = locations,
                isReplicated = true,
                replicatedFrom = "peer1"
            )
        }
    }

    @Test
    fun `updateMemberLocations only updates if newer`() = runTest {
        // First add an existing location
        val oldLocation = createTestLocation(memberId = "member1", timestamp = 5000L)
        locationRepository.updateMemberLocation(oldLocation)

        // Now batch update with both older and newer locations
        val batchLocations = listOf(
            createTestLocation(memberId = "member1", timestamp = 3000L), // Older - should NOT update
            createTestLocation(memberId = "member2", timestamp = 4000L)  // New member - should add
        )

        locationRepository.updateMemberLocations(batchLocations)

        val cachedLocations = locationRepository.memberLocations.first()

        // member1 should still have the newer timestamp (5000)
        assertEquals(5000L, cachedLocations["member1"]?.timestamp)
        // member2 should be added
        assertEquals(4000L, cachedLocations["member2"]?.timestamp)
    }

    @Test
    fun `updateMemberLocations ignores empty list`() = runTest {
        locationRepository.updateMemberLocations(emptyList())
        advanceUntilIdle()

        coVerify(exactly = 0) { mockHistoryRepository.saveLocations(any(), any(), any()) }
    }

    @Test
    fun `removeStaleLocations removes old entries`() = runTest {
        val now = System.currentTimeMillis()
        val recentLocation = createTestLocation(memberId = "recent", timestamp = now - 1000)
        val staleLocation = createTestLocation(memberId = "stale", timestamp = now - 48 * 60 * 60 * 1000) // 48 hours old

        locationRepository.updateMemberLocation(recentLocation)
        locationRepository.updateMemberLocation(staleLocation)

        // Verify both are in cache
        var locations = locationRepository.memberLocations.first()
        assertEquals(2, locations.size)

        // Remove stale (default threshold is 24 hours)
        locationRepository.removeStaleLocations()

        locations = locationRepository.memberLocations.first()
        assertEquals(1, locations.size)
        assertTrue(locations.containsKey("recent"))
        assertFalse(locations.containsKey("stale"))
    }

    @Test
    fun `getLocationForMember returns correct location`() = runTest {
        val location = createTestLocation(memberId = "test_member")
        locationRepository.updateMemberLocation(location)

        val retrieved = locationRepository.getLocationForMember("test_member")
        assertEquals(location, retrieved)

        val notFound = locationRepository.getLocationForMember("nonexistent")
        assertNull(notFound)
    }

    @Test
    fun `getLocationHistory delegates to history repository`() = runTest {
        val expectedHistory = listOf(
            createTestLocation(timestamp = 1000L),
            createTestLocation(timestamp = 2000L)
        )

        coEvery {
            mockHistoryRepository.getLocationHistory("member1", 0L, 3000L)
        } returns expectedHistory

        val history = locationRepository.getLocationHistory("member1", 0L, 3000L)

        assertEquals(expectedHistory, history)
    }

    @Test
    fun `getRecentLocations delegates to history repository`() = runTest {
        val recentLocations = listOf(
            createTestLocation(timestamp = 3000L),
            createTestLocation(timestamp = 2000L)
        )

        coEvery {
            mockHistoryRepository.getRecentLocations("member1", 50)
        } returns recentLocations

        val result = locationRepository.getRecentLocations("member1", 50)

        assertEquals(recentLocations, result)
    }

    @Test
    fun `clearAllLocations empties cache`() = runTest {
        locationRepository.updateMemberLocation(createTestLocation(memberId = "member1"))
        locationRepository.updateMyLocation(createTestLocation(memberId = "my_id"))

        locationRepository.clearAllLocations()

        assertTrue(locationRepository.memberLocations.first().isEmpty())
        assertNull(locationRepository.myLocation.first())
    }

    @Test
    fun `deleteHistoryForMember removes from cache and calls repository`() = runTest {
        locationRepository.updateMemberLocation(createTestLocation(memberId = "member1"))
        locationRepository.updateMemberLocation(createTestLocation(memberId = "member2"))

        locationRepository.deleteHistoryForMember("member1")
        advanceUntilIdle()

        val locations = locationRepository.memberLocations.first()
        assertEquals(1, locations.size)
        assertFalse(locations.containsKey("member1"))
        assertTrue(locations.containsKey("member2"))

        coVerify { mockHistoryRepository.deleteDataForMember("member1") }
    }

    @Test
    fun `applyRetentionPolicy delegates to history repository`() = runTest {
        locationRepository.applyRetentionPolicy(60L)
        advanceUntilIdle()

        coVerify { mockHistoryRepository.applyRetentionPolicy(60L) }
    }
}
