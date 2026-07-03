package com.example.familysafety.routing

import com.example.familysafety.main.formatDriveDistance
import com.example.familysafety.main.formatDriveDuration
import com.example.familysafety.main.selectDriveEstimateOrigin
import com.example.familysafety.location.MemberLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RoutingServiceTest {
    @Test
    fun parseDriveEstimate_readsFirstRoute() {
        val estimate = RoutingService.parseDriveEstimate(
            """{"code":"Ok","routes":[{"distance":11909.3,"duration":1080.5}]}"""
        )

        assertEquals(11909.3, estimate.distanceMeters, 0.01)
        assertEquals(1080.5, estimate.durationSeconds, 0.01)
    }

    @Test
    fun parseDriveEstimate_rejectsMissingRoute() {
        assertThrows(RoutingException::class.java) {
            RoutingService.parseDriveEstimate("""{"code":"NoRoute","routes":[]}""")
        }
    }

    @Test
    fun driveEstimateFormatting_usesReadableUSUnits() {
        assertEquals("18 min", formatDriveDuration(1080.5))
        assertEquals("7.4 mi", formatDriveDistance(11909.3))
        assertEquals("1 hr 5 min", formatDriveDuration(3900.0))
    }

    @Test
    fun driveEstimateOrigin_prefersLiveLocationAndFallsBackToCachedLocalLocation() {
        val live = memberLocation(latitude = 33.1)
        val cached = memberLocation(latitude = 33.2)

        assertEquals(live, selectDriveEstimateOrigin(live, cached))
        assertEquals(cached, selectDriveEstimateOrigin(null, cached))
    }

    private fun memberLocation(latitude: Double) = MemberLocation(
        memberId = "local-member",
        latitude = latitude,
        longitude = -112.0,
        accuracy = 10f,
        timestamp = 1_700_000_000_000L
    )
}
