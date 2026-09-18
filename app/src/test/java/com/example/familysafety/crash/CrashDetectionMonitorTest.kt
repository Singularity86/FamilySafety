package com.example.familysafety.crash

import com.example.familysafety.crash.CrashDetectionMonitor.Companion.ALERT_COOLDOWN_MS
import com.example.familysafety.crash.CrashDetectionMonitor.Companion.SENSITIVITY_HIGH
import com.example.familysafety.crash.CrashDetectionMonitor.Companion.SENSITIVITY_LOW
import com.example.familysafety.crash.CrashDetectionMonitor.Companion.SENSITIVITY_MEDIUM
import com.example.familysafety.crash.CrashDetectionMonitor.Companion.SPEED_GUARD_MS
import com.example.familysafety.crash.CrashDetectionMonitor.Companion.magnitudeOf
import com.example.familysafety.crash.CrashDetectionMonitor.Companion.shouldTriggerAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision, not the plumbing. The monitor itself can't be built here — its constructor
 * resolves SENSOR_SERVICE — and SensorEvent has no public constructor, so the trigger rule
 * is tested directly. Sensor registration and the notification are covered by
 * CrashDetectionMonitorRobolectricTest.
 */
class CrashDetectionMonitorTest {

    // A speed comfortably past the guard, and a time comfortably past the cooldown, so each
    // test varies one axis at a time.
    private val drivingSpeed = 30f
    private val now = 10_000_000L
    private val longAgo = now - ALERT_COOLDOWN_MS - 1

    // --- magnitude ---

    @Test
    fun magnitudeOf_isEuclideanNorm() {
        assertEquals(5f, magnitudeOf(3f, 4f, 0f), 0.0001f)
        assertEquals(0f, magnitudeOf(0f, 0f, 0f), 0.0001f)
    }

    @Test
    fun magnitudeOf_ignoresDirection() {
        // A 3g impact is 3g whichever way the phone is lying.
        assertEquals(magnitudeOf(30f, 0f, 0f), magnitudeOf(-30f, 0f, 0f), 0.0001f)
        assertEquals(magnitudeOf(0f, 0f, 30f), magnitudeOf(0f, -30f, 0f), 0.0001f)
    }

    // --- impact threshold ---

    @Test
    fun belowThreshold_doesNotTrigger() {
        assertFalse(
            shouldTriggerAlert(SENSITIVITY_MEDIUM - 0.1f, SENSITIVITY_MEDIUM, drivingSpeed, now, longAgo)
        )
    }

    @Test
    fun exactlyAtThreshold_triggers() {
        assertTrue(
            shouldTriggerAlert(SENSITIVITY_MEDIUM, SENSITIVITY_MEDIUM, drivingSpeed, now, longAgo)
        )
    }

    @Test
    fun sensitivitySettingMovesTheThreshold() {
        val moderateImpact = 25f
        // Between HIGH (20) and MEDIUM (30): caught on the sensitive setting, missed on default.
        assertTrue(shouldTriggerAlert(moderateImpact, SENSITIVITY_HIGH, drivingSpeed, now, longAgo))
        assertFalse(shouldTriggerAlert(moderateImpact, SENSITIVITY_MEDIUM, drivingSpeed, now, longAgo))
    }

    // --- speed guard: the false-positive defence ---

    @Test
    fun hardImpactWhileStationary_doesNotTrigger() {
        // The phone dropped on a hard floor easily clears 3g. Nobody's family should hear
        // about it.
        assertFalse(shouldTriggerAlert(80f, SENSITIVITY_MEDIUM, 0f, now, longAgo))
    }

    @Test
    fun hardImpactBelowSpeedGuard_doesNotTrigger() {
        // Walking pace, or a phone knocked about in a stationary car.
        assertFalse(
            shouldTriggerAlert(80f, SENSITIVITY_MEDIUM, SPEED_GUARD_MS - 0.1f, now, longAgo)
        )
    }

    @Test
    fun exactlyAtSpeedGuard_triggers() {
        assertTrue(
            shouldTriggerAlert(SENSITIVITY_MEDIUM, SENSITIVITY_MEDIUM, SPEED_GUARD_MS, now, longAgo)
        )
    }

    // --- cooldown ---

    @Test
    fun withinCooldown_doesNotTrigger() {
        val recent = now - (ALERT_COOLDOWN_MS / 2)
        assertFalse(
            shouldTriggerAlert(SENSITIVITY_MEDIUM, SENSITIVITY_MEDIUM, drivingSpeed, now, recent)
        )
    }

    @Test
    fun exactlyAtCooldownBoundary_doesNotTrigger() {
        // The rule is strictly greater than, so the boundary itself is still suppressed.
        val boundary = now - ALERT_COOLDOWN_MS
        assertFalse(
            shouldTriggerAlert(SENSITIVITY_MEDIUM, SENSITIVITY_MEDIUM, drivingSpeed, now, boundary)
        )
    }

    @Test
    fun justPastCooldown_triggers() {
        val past = now - ALERT_COOLDOWN_MS - 1
        assertTrue(
            shouldTriggerAlert(SENSITIVITY_MEDIUM, SENSITIVITY_MEDIUM, drivingSpeed, now, past)
        )
    }

    @Test
    fun firstEverAlert_triggers() {
        // lastAlertMs starts at 0, which must not read as "just alerted".
        assertTrue(
            shouldTriggerAlert(SENSITIVITY_MEDIUM, SENSITIVITY_MEDIUM, drivingSpeed, now, 0L)
        )
    }

    // --- the conjunction ---

    @Test
    fun everyConditionIsNecessary() {
        // Each call below fails exactly one condition and must not trigger.
        assertFalse(shouldTriggerAlert(1f, SENSITIVITY_MEDIUM, drivingSpeed, now, longAgo))
        assertFalse(shouldTriggerAlert(SENSITIVITY_MEDIUM, SENSITIVITY_MEDIUM, 0f, now, longAgo))
        assertFalse(shouldTriggerAlert(SENSITIVITY_MEDIUM, SENSITIVITY_MEDIUM, drivingSpeed, now, now))
        // All three satisfied.
        assertTrue(shouldTriggerAlert(SENSITIVITY_MEDIUM, SENSITIVITY_MEDIUM, drivingSpeed, now, longAgo))
    }

    @Test
    fun realisticCrashSample_triggers() {
        // ~3.5g spread across axes at 40 mph.
        val magnitude = magnitudeOf(20f, 25f, 12f)
        assertTrue(magnitude > SENSITIVITY_MEDIUM)
        assertTrue(shouldTriggerAlert(magnitude, SENSITIVITY_MEDIUM, 17.9f, now, longAgo))
    }

    // --- settings sanity ---

    @Test
    fun sensitivityLevelsAreOrdered() {
        // "High sensitivity" must mean a lower bar, not a higher one.
        assertTrue(SENSITIVITY_HIGH < SENSITIVITY_MEDIUM)
        assertTrue(SENSITIVITY_MEDIUM < SENSITIVITY_LOW)
    }

    @Test
    fun speedGuardIsTwentyFiveMph() {
        // The settings copy promises 25+ mph; keep the constant honest about it. 11.2 m/s is
        // 25.05 mph — rounded, so the tolerance is loose enough for that but tight enough to
        // catch anyone retuning the guard without revisiting the copy.
        assertEquals(25.0, SPEED_GUARD_MS * 2.23694, 0.1)
    }
}
