package com.example.familysafety.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPublishPolicyTest {

    private val base = MemberLocation(
        memberId = "m",
        latitude = 40.7128,
        longitude = -74.0060,
        accuracy = 10f,
        timestamp = 1_000_000L
    )

    /** ~1.11 m per 0.00001° of latitude. */
    private fun movedNorth(meters: Double, afterMs: Long, accuracy: Float = 10f) = base.copy(
        latitude = base.latitude + meters / 111_195.0,
        timestamp = base.timestamp + afterMs,
        accuracy = accuracy
    )

    @Test
    fun `stationary jitter within a few minutes is redundant`() {
        assertTrue(LocationPublishPolicy.isRedundant(base, movedNorth(8.0, 30_000)))
    }

    @Test
    fun `real movement is published`() {
        assertFalse(LocationPublishPolicy.isRedundant(base, movedNorth(120.0, 30_000)))
    }

    @Test
    fun `walking pace over a 30s interval is published`() {
        // 1.4 m/s for 30 s
        assertFalse(LocationPublishPolicy.isRedundant(base, movedNorth(42.0, 30_000)))
    }

    @Test
    fun `an unchanged position is re-sent once it is old enough`() {
        assertFalse(
            LocationPublishPolicy.isRedundant(base, movedNorth(0.0, LocationPublishPolicy.MAX_SILENCE_MS))
        )
    }

    @Test
    fun `older or same-time fixes are redundant`() {
        assertTrue(LocationPublishPolicy.isRedundant(base, base))
        assertTrue(LocationPublishPolicy.isRedundant(base, base.copy(timestamp = base.timestamp - 1)))
    }

    @Test
    fun `movement inside a coarse fix's own error is noise`() {
        assertTrue(LocationPublishPolicy.isRedundant(base.copy(accuracy = 500f), movedNorth(200.0, 30_000, 500f)))
    }

    @Test
    fun `a much more accurate fix of the same place is published`() {
        val coarse = base.copy(accuracy = 800f)
        assertFalse(LocationPublishPolicy.isRedundant(coarse, movedNorth(5.0, 30_000, 12f)))
    }

    @Test
    fun `distance matches a known reference`() {
        // One degree of latitude is ~111.2 km.
        val d = LocationPublishPolicy.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, d, 50.0)
    }
}
