package com.example.familysafety.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays recorded motion against the crash rule.
 *
 * The fixtures under `src/test/resources/crash-traces` are the point of this suite: each one is a
 * motion pattern with a known right answer, so a change to a threshold or a guard has to justify
 * itself against every one of them. Traces recorded from real drives belong here alongside the
 * synthetic ones — same format, same assertions.
 */
class CrashTraceTest {

    private fun load(name: String): List<CrashTrace.Sample> {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("crash-traces/$name")) {
            "missing fixture crash-traces/$name"
        }
        return stream.bufferedReader().useLines { CrashTrace.parse(it) }
    }

    // --- the fixtures ---------------------------------------------------------------------

    @Test
    fun `a motorway collision alerts exactly once`() {
        val hits = CrashTrace.replay(load("highway-collision.csv"))

        assertEquals("one crash should produce one alert, not one per sample", 1, hits.size)
        val hit = hits.single()
        assertTrue(
            "the alert should land on the impact at ~10s, not on the cruise before it, got ${hit.elapsedMs}ms",
            hit.elapsedMs in 9_900..10_400
        )
        assertTrue("the firing sample should be a real spike", hit.magnitudeMs2 >= ImpactDecider.SENSITIVITY_MEDIUM)
    }

    @Test
    fun `a rough road does not alert at the default sensitivity`() {
        assertEquals(emptyList<CrashTrace.Hit>(), CrashTrace.replay(load("rough-road.csv")))
    }

    @Test
    fun `the rough road is exactly what high sensitivity costs you`() {
        // Documented, not lamented: at HIGH the same washboard road does alert. If this ever
        // stops being true the thresholds have moved and the tradeoff needs re-stating.
        val hits = CrashTrace.replay(load("rough-road.csv"), ImpactDecider.SENSITIVITY_HIGH)
        assertTrue("expected the bumps to trip HIGH sensitivity", hits.isNotEmpty())
    }

    @Test
    fun `a phone dropped after parking never alerts, at any sensitivity`() {
        val trace = load("parked-drop.csv")
        for (threshold in listOf(
            ImpactDecider.SENSITIVITY_LOW,
            ImpactDecider.SENSITIVITY_MEDIUM,
            ImpactDecider.SENSITIVITY_HIGH,
        )) {
            assertEquals(
                "a dropped handset must not call the family (threshold $threshold)",
                emptyList<CrashTrace.Hit>(),
                CrashTrace.replay(trace, threshold),
            )
        }
    }

    @Test
    fun `a knock long after the last GPS fix does not alert`() {
        // The speed column still reads 30 m/s, but the fix behind it is two minutes old.
        val trace = load("stale-speed-spike.csv")
        assertTrue(
            "fixture should carry a highway speed, otherwise it proves nothing about staleness",
            trace.last().speedMs > ImpactDecider.SPEED_GUARD_MS
        )
        assertEquals(emptyList<CrashTrace.Hit>(), CrashTrace.replay(trace))
    }

    // --- the format -----------------------------------------------------------------------

    @Test
    fun `a recorded row round-trips through the parser`() {
        val row = CrashTrace.formatRow(
            elapsedMs = 1234, x = 3f, y = 4f, z = 12f, speedMs = 27.5f, speedElapsedMs = 1200,
        )
        val sample = CrashTrace.parse(sequenceOf(row)).single()

        assertEquals(1234L, sample.elapsedMs)
        assertEquals(13f, sample.magnitudeMs2, 0.001f) // 3-4-12 is a Pythagorean quadruple
        assertEquals(27.5f, sample.speedMs, 0.001f)
        assertEquals(1200L, sample.speedElapsedMs)
    }

    @Test
    fun `a row recorded before any GPS fix round-trips as no speed`() {
        val row = CrashTrace.formatRow(
            elapsedMs = 40, x = 1f, y = 0f, z = 0f, speedMs = 0f, speedElapsedMs = null,
        )
        assertEquals(null, CrashTrace.parse(sequenceOf(row)).single().speedElapsedMs)
    }

    @Test
    fun `a fix from before the recording started keeps its negative timestamp`() {
        val row = CrashTrace.formatRow(
            elapsedMs = 40, x = 1f, y = 0f, z = 0f, speedMs = 20f, speedElapsedMs = -8_000,
        )
        assertEquals(-8_000L, CrashTrace.parse(sequenceOf(row)).single().speedElapsedMs)
    }

    @Test
    fun `comments, blanks and the header are skipped`() {
        val lines = sequenceOf(
            "# familysafety crash trace v1",
            "# recorded somewhere",
            CrashTrace.HEADER,
            "",
            "   ",
            CrashTrace.formatRow(0, 1f, 0f, 0f, 0f, null),
        )
        assertEquals(1, CrashTrace.parse(lines).size)
    }

    @Test
    fun `a truncated row fails loudly rather than being dropped`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            CrashTrace.parse(sequenceOf("0,1.0,2.0"))
        }
        assertTrue(e.message!!.contains("expected 7 columns"))
    }

    @Test
    fun `an unparseable magnitude fails loudly`() {
        assertThrows(IllegalArgumentException::class.java) {
            CrashTrace.parse(sequenceOf("0,1.0,2.0,3.0,not-a-number,0.0,"))
        }
    }

    // --- replay semantics -----------------------------------------------------------------

    @Test
    fun `replay feeds each distinct fix once so the staleness clock is the recorded one`() {
        // Two samples 5 minutes apart sharing one fix: the second must be judged stale even
        // though its row still carries a fast speed.
        val samples = listOf(
            CrashTrace.Sample(elapsedMs = 0, magnitudeMs2 = 1f, speedMs = 30f, speedElapsedMs = 0),
            CrashTrace.Sample(elapsedMs = 300_000, magnitudeMs2 = 60f, speedMs = 30f, speedElapsedMs = 0),
        )
        assertEquals(emptyList<CrashTrace.Hit>(), CrashTrace.replay(samples))
    }

    @Test
    fun `an empty trace replays to nothing`() {
        assertEquals(emptyList<CrashTrace.Hit>(), CrashTrace.replay(emptyList()))
    }
}
