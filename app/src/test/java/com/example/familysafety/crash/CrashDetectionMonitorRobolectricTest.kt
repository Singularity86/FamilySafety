package com.example.familysafety.crash

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

/**
 * The Android plumbing the pure trigger rule can't reach: whether the monitor actually
 * listens when it should, releases the sensor when it shouldn't, and posts an alert the
 * system will surface over a lock screen.
 *
 * Pinned to SDK 34 rather than following compileSdk — nothing under test here has changed
 * since API 26, and pinning avoids fetching a new android-all jar on every SDK bump.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashDetectionMonitorRobolectricTest {

    private lateinit var context: Application
    private lateinit var sensorManager: SensorManager
    private lateinit var shadowSensorManager: ShadowSensorManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var monitor: CrashDetectionMonitor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shadowSensorManager = shadowOf(sensorManager)
        // Robolectric ships no sensors by default. Without this the monitor finds no linear
        // acceleration sensor and quietly never listens, which would make every assertion
        // below pass for the wrong reason.
        shadowSensorManager.addSensor(
            ShadowSensor.newInstance(Sensor.TYPE_LINEAR_ACCELERATION)
        )
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        monitor = CrashDetectionMonitor(context)
    }

    private fun isListening(): Boolean = shadowSensorManager.listeners.isNotEmpty()

    // --- arm / disarm lifecycle ---

    @Test
    fun sensorIsAvailableToTheMonitor() {
        // Guards the guard: if this fails, every lifecycle assertion below is vacuous.
        assertNotNull(sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION))
    }

    @Test
    fun doesNotListenUntilBothEnabledAndInVehicle() {
        assertFalse("should start idle", isListening())

        monitor.setEnabled(true)
        assertFalse("enabled but not in a vehicle — still idle", isListening())

        monitor.setArmed(true)
        assertTrue("enabled and in a vehicle — listening", isListening())
    }

    @Test
    fun armingWhileDisabledDoesNotListen() {
        monitor.setArmed(true)
        assertFalse("feature off — must not listen", isListening())
    }

    @Test
    fun leavingTheVehicleStopsListening() {
        monitor.setEnabled(true)
        monitor.setArmed(true)
        assertTrue(isListening())

        monitor.setArmed(false)
        assertFalse("left the vehicle — must release the sensor", isListening())
    }

    @Test
    fun disablingStopsListening() {
        monitor.setEnabled(true)
        monitor.setArmed(true)
        assertTrue(isListening())

        monitor.setEnabled(false)
        assertFalse("feature switched off — must release the sensor", isListening())
    }

    @Test
    fun reEnablingWhileStillInVehicleResumesListening() {
        monitor.setEnabled(true)
        monitor.setArmed(true)
        monitor.setEnabled(false)
        assertFalse(isListening())

        // Still in the vehicle, so flipping the setting back on should resume rather than
        // wait for the next activity-recognition transition.
        monitor.setEnabled(true)
        assertTrue("still in a vehicle — should resume immediately", isListening())
    }

    // --- the alert ---

    @Test
    fun alertChannelIsHighImportanceAndBypassesDnd() {
        monitor.triggerCrashAlert()

        val channel = notificationManager.getNotificationChannel(CrashDetectionMonitor.CHANNEL_ID)
        assertNotNull("alert channel must exist", channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertTrue("a crash alert has to get through Do Not Disturb", channel.canBypassDnd())
    }

    @Test
    fun alertIsPostedFullScreenAndOngoing() {
        monitor.triggerCrashAlert()

        val posted = shadowOf(notificationManager).allNotifications
        assertEquals(1, posted.size)

        val notification = posted.first()
        assertNotNull(
            "needs a full-screen intent to wake the screen after a crash",
            notification.fullScreenIntent
        )
        assertEquals(
            "must not be swipeable away",
            Notification.FLAG_ONGOING_EVENT,
            notification.flags and Notification.FLAG_ONGOING_EVENT
        )
    }

    @Test
    fun alertUsesTheIdCrashAlertActivityCancels() {
        monitor.triggerCrashAlert()

        // CrashAlertActivity cancels by this id when the user taps "I'm okay". If the two
        // ever disagree, the alert stays stuck in the shade.
        assertNotNull(
            shadowOf(notificationManager).getNotification(CrashDetectionMonitor.NOTIFICATION_ID)
        )
    }
}
