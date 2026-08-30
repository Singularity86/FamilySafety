package com.example.familysafety.location

import com.example.familysafety.geofence.GeofenceMonitor
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.storage.LocationHistoryRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for LocationRepository's synchronous in-memory cache operations.
 *
 * Async persistence (scope.launch { locationHistoryRepository.saveLocation(...) })
 * is covered by instrumented tests because it runs on Dispatchers.IO.
 */
class LocationRepositoryTest {

    private lateinit var mockLocationHistoryRepository: LocationHistoryRepository
    private lateinit var mockGeofenceMonitor: GeofenceMonitor
    private lateinit var mockGroupStateManager: GroupStateManager
    private lateinit var locationRepo: LocationRepository

    private fun makeLocation(
        memberId: String = "member_001",
        lat: Double = 37.7749,
        lon: Double = -122.4194,
        timestamp: Long = 1_700_000_000_000L
    ) = MemberLocation(
        memberId = memberId,
        latitude = lat,
        longitude = lon,
        accuracy = 10.0f,
        timestamp = timestamp
    )

    @Before
    fun setup() {
        mockLocationHistoryRepository = mockk(relaxed = true)
        mockGeofenceMonitor = mockk(relaxed = true)
        mockGroupStateManager = mockk(relaxed = true)
        every { mockGroupStateManager.groupDefinition } returns MutableStateFlow(null)
        locationRepo = LocationRepository(
            mockLocationHistoryRepository,
            mockGeofenceMonitor,
            mockGroupStateManager
        )
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `memberLocations initial value is empty`() {
        assertTrue(locationRepo.memberLocations.value.isEmpty())
    }

    @Test
    fun `myLocation initial value is null`() {
        assertNull(locationRepo.myLocation.value)
    }

    // ── updateMemberLocation ──────────────────────────────────────────────────

    @Test
    fun `updateMemberLocation adds location to in-memory cache`() {
        val loc = makeLocation()
        locationRepo.updateMemberLocation(loc)

        assertEquals(loc, locationRepo.memberLocations.value["member_001"])
    }

    @Test
    fun `updateMemberLocation replaces existing entry with a newer one`() {
        val loc1 = makeLocation(lat = 1.0, timestamp = 1_700_000_000_000L)
        val loc2 = makeLocation(lat = 2.0, timestamp = 1_700_000_001_000L)

        locationRepo.updateMemberLocation(loc1)
        locationRepo.updateMemberLocation(loc2)

        assertEquals(2.0, locationRepo.memberLocations.value["member_001"]?.latitude ?: 0.0, 0.0001)
    }

    @Test
    fun `updateMemberLocation rejects older or equal timestamps (replay guard)`() {
        val newer = makeLocation(lat = 2.0, timestamp = 1_700_000_001_000L)
        val replayedOlder = makeLocation(lat = 1.0, timestamp = 1_700_000_000_000L)
        val sameTimestamp = makeLocation(lat = 3.0, timestamp = 1_700_000_001_000L)

        locationRepo.updateMemberLocation(newer)
        locationRepo.updateMemberLocation(replayedOlder)
        locationRepo.updateMemberLocation(sameTimestamp)

        // The pin must stay at the newest position; replays cannot move it back.
        assertEquals(2.0, locationRepo.memberLocations.value["member_001"]?.latitude ?: 0.0, 0.0001)
    }

    @Test
    fun `updateMemberLocation stores entries for multiple members`() {
        locationRepo.updateMemberLocation(makeLocation("alice"))
        locationRepo.updateMemberLocation(makeLocation("bob"))

        assertEquals(2, locationRepo.memberLocations.value.size)
        assertNotNull(locationRepo.memberLocations.value["alice"])
        assertNotNull(locationRepo.memberLocations.value["bob"])
    }

    // ── updateMyLocation ──────────────────────────────────────────────────────

    @Test
    fun `updateMyLocation sets myLocation flow`() {
        val loc = makeLocation()
        locationRepo.updateMyLocation(loc)

        assertEquals(loc, locationRepo.myLocation.value)
    }

    @Test
    fun `updateMyLocation also adds location to memberLocations`() {
        val loc = makeLocation()
        locationRepo.updateMyLocation(loc)

        assertNotNull(locationRepo.memberLocations.value[loc.memberId])
    }

    @Test
    fun `updateMyLocation overwrites previous myLocation`() {
        locationRepo.updateMyLocation(makeLocation(lat = 10.0))
        locationRepo.updateMyLocation(makeLocation(lat = 20.0))

        assertEquals(20.0, locationRepo.myLocation.value?.latitude ?: 0.0, 0.0001)
    }

    // ── getLocationForMember ──────────────────────────────────────────────────

    @Test
    fun `getLocationForMember returns null for unknown member`() {
        assertNull(locationRepo.getLocationForMember("nonexistent"))
    }

    @Test
    fun `getLocationForMember returns cached location`() {
        val loc = makeLocation("alice")
        locationRepo.updateMemberLocation(loc)

        assertEquals(loc, locationRepo.getLocationForMember("alice"))
    }

    // ── updateMemberLocations ─────────────────────────────────────────────────

    @Test
    fun `updateMemberLocations adds all locations`() {
        val locations = listOf(
            makeLocation("alice", timestamp = 1000L),
            makeLocation("bob", timestamp = 2000L)
        )

        locationRepo.updateMemberLocations(locations)

        assertEquals(2, locationRepo.memberLocations.value.size)
    }

    @Test
    fun `updateMemberLocations uses newer-wins strategy`() {
        val old = makeLocation("alice", timestamp = 1000L)
        locationRepo.updateMemberLocation(old)

        // Batch update with older timestamp → should NOT replace
        val olderBatch = listOf(makeLocation("alice", timestamp = 500L))
        locationRepo.updateMemberLocations(olderBatch)

        assertEquals(1000L, locationRepo.memberLocations.value["alice"]?.timestamp)
    }

    @Test
    fun `updateMemberLocations replaces when batch timestamp is newer`() {
        val old = makeLocation("alice", timestamp = 1000L)
        locationRepo.updateMemberLocation(old)

        val newerBatch = listOf(makeLocation("alice", timestamp = 5000L))
        locationRepo.updateMemberLocations(newerBatch)

        assertEquals(5000L, locationRepo.memberLocations.value["alice"]?.timestamp)
    }

    @Test
    fun `updateMemberLocations with empty list does nothing`() {
        locationRepo.updateMemberLocations(emptyList())
        assertTrue(locationRepo.memberLocations.value.isEmpty())
    }

    // ── removeStaleLocations ──────────────────────────────────────────────────

    @Test
    fun `removeStaleLocations removes locations older than threshold`() {
        val now = System.currentTimeMillis()
        val old = makeLocation("alice", timestamp = now - 2 * 24 * 60 * 60 * 1000L) // 2 days old
        locationRepo.updateMemberLocation(old)

        // Remove anything older than 1 day
        locationRepo.removeStaleLocations(thresholdMs = 24 * 60 * 60 * 1000L)

        assertNull(locationRepo.memberLocations.value["alice"])
    }

    @Test
    fun `removeStaleLocations keeps recent locations`() {
        val now = System.currentTimeMillis()
        val recent = makeLocation("bob", timestamp = now - 1000L) // 1 second old
        locationRepo.updateMemberLocation(recent)

        locationRepo.removeStaleLocations(thresholdMs = 24 * 60 * 60 * 1000L)

        assertNotNull(locationRepo.memberLocations.value["bob"])
    }

    // ── clearAllLocations ─────────────────────────────────────────────────────

    @Test
    fun `clearAllLocations empties memberLocations`() {
        locationRepo.updateMemberLocation(makeLocation("alice"))
        locationRepo.clearAllLocations()

        assertTrue(locationRepo.memberLocations.value.isEmpty())
    }

    @Test
    fun `clearAllLocations sets myLocation to null`() {
        locationRepo.updateMyLocation(makeLocation())
        locationRepo.clearAllLocations()

        assertNull(locationRepo.myLocation.value)
    }

    // ── deleteHistoryForMember ────────────────────────────────────────────────

    @Test
    fun `deleteHistoryForMember removes member from in-memory cache`() = kotlinx.coroutines.test.runTest {
        locationRepo.updateMemberLocation(makeLocation("alice"))
        locationRepo.updateMemberLocation(makeLocation("bob"))

        locationRepo.deleteHistoryForMember("alice")

        assertNull(locationRepo.memberLocations.value["alice"])
        assertNotNull(locationRepo.memberLocations.value["bob"])
    }

    // ── recordLocationUpdate ──────────────────────────────────────────────────
    // The diagnostics report's "last location" line reads a timestamp that only
    // GroupStateManager.recordLocationUpdate writes, and for a long time nothing called
    // it — so the line read "never" for everybody, on every device, whether or not
    // locations were arriving. These two say when it is stamped and when it is not.
    // The stamping runs on Dispatchers.IO, hence the verification timeout.

    @Test
    fun `accepting a location stamps when we last heard from that member`() {
        locationRepo.updateMemberLocation(makeLocation("alice"))

        coVerify(timeout = 2_000) { mockGroupStateManager.recordLocationUpdate("alice") }
    }

    @Test
    fun `a location older than the one held does not stamp`() {
        locationRepo.updateMemberLocation(makeLocation("alice", timestamp = 2_000L))
        locationRepo.updateMemberLocation(makeLocation("alice", timestamp = 1_000L))

        // Exactly once — from the first, accepted update. The replayed older one is
        // dropped before it reaches the stamp, so it cannot make a member look present.
        coVerify(exactly = 1, timeout = 2_000) {
            mockGroupStateManager.recordLocationUpdate("alice")
        }
    }
}
