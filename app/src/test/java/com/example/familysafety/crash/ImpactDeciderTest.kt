package com.example.familysafety.crash

import com.example.familysafety.crash.ImpactDecider.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactDeciderTest {

    /** An arbitrary wall-clock base so the tests read like real timestamps. */
    private val t0 = 1_700_000_000_000L

    /** A decider that is on, in a vehicle, and has just seen a highway-speed GPS fix. */
    private fun driving(
        threshold: Float = ImpactDecider.SENSITIVITY_MEDIUM,
        speedMs: Float = 30f,
    ) = ImpactDecider(threshold).apply {
        setEnabled(true)
        setArmed(true)
        feedSpeed(speedMs, t0)
    }

    // --- the happy path -------------------------------------------------------------------

    @Test
    fun `impact above threshold at speed fires`() {
        val decider = driving()
        assertEquals(Decision.FIRE, decider.onSample(45f, t0))
    }

    @Test
    fun `sample exactly at the threshold fires`() {
        val decider = driving(threshold = ImpactDecider.SENSITIVITY_MEDIUM)
        assertEquals(Decision.FIRE, decider.onSample(ImpactDecider.SENSITIVITY_MEDIUM, t0))
    }

    @Test
    fun `sample just under the threshold does not fire`() {
        val decider = driving(threshold = ImpactDecider.SENSITIVITY_MEDIUM)
        assertEquals(
            Decision.BELOW_THRESHOLD,
            decider.onSample(ImpactDecider.SENSITIVITY_MEDIUM - 0.1f, t0)
        )
    }

    @Test
    fun `speed exactly at the guard fires`() {
        val decider = driving(speedMs = ImpactDecider.SPEED_GUARD_MS)
        assertEquals(Decision.FIRE, decider.onSample(45f, t0))
    }

    // --- sensitivity ----------------------------------------------------------------------

    @Test
    fun `a moderate impact fires on high sensitivity but not on low`() {
        val moderate = 25f // between SENSITIVITY_HIGH (20) and SENSITIVITY_LOW (40)

        val sensitive = driving(threshold = ImpactDecider.SENSITIVITY_HIGH)
        assertEquals(Decision.FIRE, sensitive.onSample(moderate, t0))

        val severeOnly = driving(threshold = ImpactDecider.SENSITIVITY_LOW)
        assertEquals(Decision.BELOW_THRESHOLD, severeOnly.onSample(moderate, t0))
    }

    @Test
    fun `changing sensitivity takes effect on the next sample`() {
        val decider = driving(threshold = ImpactDecider.SENSITIVITY_LOW)
        assertEquals(Decision.BELOW_THRESHOLD, decider.onSample(25f, t0))

        decider.setThreshold(ImpactDecider.SENSITIVITY_HIGH)
        assertEquals(Decision.FIRE, decider.onSample(25f, t0 + 1_000))
    }

    // --- the speed guard: this is what keeps a dropped phone from calling the family -------

    @Test
    fun `a hard impact while stationary does not fire`() {
        // Phone slides off the dash onto the footwell at a red light: real spike, no speed.
        val decider = driving(speedMs = 0f)
        assertEquals(Decision.TOO_SLOW, decider.onSample(60f, t0))
    }

    @Test
    fun `walking pace is below the guard`() {
        val decider = driving(speedMs = 1.5f)
        assertEquals(Decision.TOO_SLOW, decider.onSample(60f, t0))
    }

    @Test
    fun `speed just under the guard does not fire`() {
        val decider = driving(speedMs = ImpactDecider.SPEED_GUARD_MS - 0.1f)
        assertEquals(Decision.TOO_SLOW, decider.onSample(60f, t0))
    }

    @Test
    fun `an impact with no GPS speed at all does not fire`() {
        val decider = ImpactDecider().apply {
            setEnabled(true)
            setArmed(true)
            // no feedSpeed — the service has only just started
        }
        assertEquals(Decision.SPEED_TOO_STALE, decider.onSample(60f, t0))
    }

    @Test
    fun `a stale highway speed does not satisfy the guard`() {
        // Drove home, parked, put the phone down hard. The last fix still says 65 mph, but
        // it is minutes old and must not be trusted.
        val decider = driving(speedMs = 30f)
        val wellAfterParking = t0 + ImpactDecider.SPEED_MAX_AGE_MS + 1
        assertEquals(Decision.SPEED_TOO_STALE, decider.onSample(60f, wellAfterParking))
    }

    @Test
    fun `a speed at the age limit is still trusted`() {
        val decider = driving(speedMs = 30f)
        assertEquals(Decision.FIRE, decider.onSample(60f, t0 + ImpactDecider.SPEED_MAX_AGE_MS))
    }

    @Test
    fun `a fresh fix revives a decider whose speed had gone stale`() {
        val decider = driving(speedMs = 30f)
        val late = t0 + ImpactDecider.SPEED_MAX_AGE_MS + 1
        assertEquals(Decision.SPEED_TOO_STALE, decider.onSample(60f, late))

        decider.feedSpeed(30f, late)
        assertEquals(Decision.FIRE, decider.onSample(60f, late))
    }

    // --- enable / arm ---------------------------------------------------------------------

    @Test
    fun `a decider that was never enabled does not fire`() {
        val decider = ImpactDecider().apply {
            setArmed(true)
            feedSpeed(30f, t0)
        }
        assertEquals(Decision.NOT_ENABLED, decider.onSample(60f, t0))
    }

    @Test
    fun `an impact outside a vehicle does not fire`() {
        val decider = ImpactDecider().apply {
            setEnabled(true)
            feedSpeed(30f, t0)
        }
        assertEquals(Decision.NOT_ARMED, decider.onSample(60f, t0))
    }

    @Test
    fun `disabling stops firing and re-enabling resumes it`() {
        val decider = driving()

        decider.setEnabled(false)
        assertEquals(Decision.NOT_ENABLED, decider.onSample(60f, t0))

        decider.setEnabled(true)
        assertEquals(Decision.FIRE, decider.onSample(60f, t0))
    }

    @Test
    fun `leaving the vehicle disarms`() {
        val decider = driving()
        decider.setArmed(false)
        assertEquals(Decision.NOT_ARMED, decider.onSample(60f, t0))
    }

    @Test
    fun `shouldListen requires both enabled and armed`() {
        val decider = ImpactDecider()
        assertFalse(decider.shouldListen)

        decider.setEnabled(true)
        assertFalse("enabled but not in a vehicle", decider.shouldListen)

        decider.setArmed(true)
        assertTrue(decider.shouldListen)

        decider.setEnabled(false)
        assertFalse("still in a vehicle but switched off", decider.shouldListen)
    }

    // --- cooldown -------------------------------------------------------------------------

    @Test
    fun `a crash produces one alert, not one per sample`() {
        val decider = driving()
        // A real collision spikes the accelerometer for many consecutive samples.
        assertEquals(Decision.FIRE, decider.onSample(60f, t0))
        for (i in 1..20) {
            assertEquals(Decision.IN_COOLDOWN, decider.onSample(60f, t0 + i * 20L))
        }
    }

    @Test
    fun `the cooldown expires`() {
        val decider = driving()
        assertEquals(Decision.FIRE, decider.onSample(60f, t0))

        val justInside = t0 + ImpactDecider.ALERT_COOLDOWN_MS
        decider.feedSpeed(30f, justInside) // still driving, so only the cooldown can reject this
        assertEquals(Decision.IN_COOLDOWN, decider.onSample(60f, justInside))

        val justOutside = t0 + ImpactDecider.ALERT_COOLDOWN_MS + 1
        decider.feedSpeed(30f, justOutside) // still driving
        assertEquals(Decision.FIRE, decider.onSample(60f, justOutside))
    }

    @Test
    fun `the cooldown restarts from the sample that fired`() {
        val decider = driving()
        assertEquals(Decision.FIRE, decider.onSample(60f, t0))

        val second = t0 + ImpactDecider.ALERT_COOLDOWN_MS + 1
        decider.feedSpeed(30f, second)
        assertEquals(Decision.FIRE, decider.onSample(60f, second))

        // Measured from the first alert this is outside the window; from the second it is not.
        val between = second + ImpactDecider.ALERT_COOLDOWN_MS - 1
        decider.feedSpeed(30f, between)
        assertEquals(Decision.IN_COOLDOWN, decider.onSample(60f, between))
    }

    @Test
    fun `a sample rejected by a guard does not consume the cooldown`() {
        val decider = driving(speedMs = 0f)
        assertEquals(Decision.TOO_SLOW, decider.onSample(60f, t0))

        // A genuine crash a moment later must still get through.
        decider.feedSpeed(30f, t0 + 500)
        assertEquals(Decision.FIRE, decider.onSample(60f, t0 + 500))
    }

    // --- guard precedence -----------------------------------------------------------------

    @Test
    fun `guards are reported cheapest-first so a quiet sample is not blamed on speed`() {
        val decider = driving(speedMs = 0f)
        // Below threshold AND too slow — the threshold is the honest reason.
        assertEquals(Decision.BELOW_THRESHOLD, decider.onSample(1f, t0))
    }
}
