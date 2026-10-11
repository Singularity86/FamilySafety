package com.example.familysafety.crash

import com.example.familysafety.crash.CrashDetectionMonitor.Companion.magnitudeOf
import com.example.familysafety.crash.ImpactDecider.Companion.SENSITIVITY_HIGH
import com.example.familysafety.crash.ImpactDecider.Companion.SENSITIVITY_LOW
import com.example.familysafety.crash.ImpactDecider.Companion.SENSITIVITY_MEDIUM
import com.example.familysafety.crash.ImpactDecider.Companion.SPEED_GUARD_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The magnitude the monitor hands to [ImpactDecider], plus sanity checks on the settings
 * constants. The trigger rule itself is covered by ImpactDeciderTest, sensor registration and
 * the notification by CrashDetectionMonitorRobolectricTest.
 */
class CrashDetectionMonitorTest {

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
