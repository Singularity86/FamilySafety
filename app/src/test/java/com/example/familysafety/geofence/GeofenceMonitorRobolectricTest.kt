package com.example.familysafety.geofence

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.familysafety.location.MemberLocation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Who is inside which place survives a relaunch, as on iOS. Held in memory only, a relaunch
 * announced everyone already inside a place as arriving again.
 *
 * Robolectric for `Location.distanceBetween`. Each "relaunch" is a new monitor on the same
 * context, so only what was saved carries over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeofenceMonitorRobolectricTest {

    private lateinit var context: Application
    private val notifications = mockk<GeofenceNotificationHelper>(relaxed = true)
    private val home = GeofenceZone(id = "home", name = "Home", latitude = 40.0, longitude = -75.0, radiusMeters = 200f)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun launch(): GeofenceMonitor {
        val repository = mockk<GeofenceRepository>()
        every { repository.geofences } returns MutableStateFlow(listOf(home))
        return GeofenceMonitor(context, repository, notifications).also(::awaitZones)
    }

    /** The monitor copies the zones on a background dispatcher; wait for that copy. */
    @Suppress("UNCHECKED_CAST")
    private fun awaitZones(monitor: GeofenceMonitor) {
        val field = GeofenceMonitor::class.java.getDeclaredField("geofences").apply { isAccessible = true }
        val zones = field.get(monitor) as StateFlow<List<GeofenceZone>>
        val deadline = System.currentTimeMillis() + 5_000
        while (zones.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("zones never loaded", zones.value.isNotEmpty())
    }

    private fun at(lat: Double, lon: Double) =
        MemberLocation(memberId = "dana", latitude = lat, longitude = lon, accuracy = 5f, timestamp = 0L)

    private val inside = at(40.0, -75.0)
    private val wellOutside = at(40.01, -75.0) // about 1.1 km north

    @Test
    fun `someone already inside is not announced again after a relaunch`() {
        launch().checkLocation(inside, "Dana")
        verify(exactly = 1) { notifications.notifyGeofence("Dana", home, isEnter = true) }

        launch().checkLocation(inside, "Dana")

        verify(exactly = 1) { notifications.notifyGeofence("Dana", home, isEnter = true) }
    }

    @Test
    fun `leaving after a relaunch is still announced`() {
        launch().checkLocation(inside, "Dana")

        launch().checkLocation(wellOutside, "Dana")

        verify(exactly = 1) { notifications.notifyGeofence("Dana", home, isEnter = false) }
    }

    @Test
    fun `someone who left before the relaunch is announced when they come back`() {
        // Each step is a relaunch, so the 2-minute transition cooldown never applies here.
        launch().checkLocation(inside, "Dana")
        launch().checkLocation(wellOutside, "Dana")

        launch().checkLocation(inside, "Dana")

        verify(exactly = 2) { notifications.notifyGeofence("Dana", home, isEnter = true) }
    }
}
