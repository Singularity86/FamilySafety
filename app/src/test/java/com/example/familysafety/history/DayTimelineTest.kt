package com.example.familysafety.history

import com.example.familysafety.location.MemberLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayTimelineTest {

    private val min = 60_000L
    private val t0 = 1_700_000_000_000L

    // About 111 m per 0.001 degrees of latitude.
    private val homeLat = 33.3000
    private val homeLon = -111.6000
    private val workLat = 33.3900 // roughly 10 km north

    private fun fix(minute: Long, lat: Double, lon: Double, acc: Float = 10f, speed: Float? = null) =
        MemberLocation("m", lat, lon, acc, t0 + minute * min, speed)

    private fun build(fixes: List<MemberLocation>, places: List<PlaceRef> = emptyList(), now: Long = t0 + 24 * 60 * min) =
        DayTimelineBuilder.build(fixes, places, now)

    private val home = PlaceRef("h", "Home", homeLat, homeLon, 150f)

    @Test
    fun emptyInputGivesEmptyTimeline() {
        assertTrue(build(emptyList()).entries.isEmpty())
    }

    @Test
    fun heartbeatDuplicatesCollapseToOneStay() {
        val fixes = (0..12).map { fix(it * 5L, homeLat, homeLon) }
        val entries = build(fixes).entries
        assertEquals(1, entries.size)
        val stay = entries.single() as Stay
        assertEquals(60 * min, stay.endMs - stay.startMs)
        assertEquals(13, stay.fixCount)
    }

    @Test
    fun jitterAtRestDoesNotSplitTheStayOrAddDistance() {
        val fixes = (0..12).map { i ->
            val wobble = if (i % 2 == 0) 0.0003 else -0.0003 // about 33 m
            fix(i * 5L, homeLat + wobble, homeLon)
        }
        val timeline = build(fixes)
        assertEquals(1, timeline.entries.size)
        assertEquals(0.0, timeline.summary.distanceMeters, 0.0)
    }

    @Test
    fun commuteBecomesStayTripStay() {
        val morning = (0..6).map { fix(it * 5L, homeLat, homeLon) } // 0..30 min at home
        val drive = (1..5).map { fix(30L + it * 3, homeLat + it * 0.015, homeLon, speed = 20f) }
        val atWork = (0..6).map { fix(48L + it * 5, workLat, homeLon) }
        val entries = build(morning + drive + atWork, listOf(home)).entries

        assertEquals(3, entries.size)
        val first = entries[0] as Stay
        val trip = entries[1] as Trip
        val last = entries[2] as Stay
        assertEquals("Home", first.place?.name)
        assertNull(last.place)
        assertEquals(20f, trip.maxSpeedMs)
        assertTrue("trip should be several km", trip.distanceMeters > 8_000)
        assertEquals(first.endMs, trip.startMs)
        assertEquals(last.startMs, trip.endMs)
    }

    @Test
    fun longSilenceIsAGapNotATrip() {
        val before = (0..6).map { fix(it * 5L, homeLat, homeLon) }
        val after = (0..6).map { fix(200L + it * 5, workLat, homeLon) }
        val entries = build(before + after).entries

        val gap = entries.filterIsInstance<Gap>().single()
        assertEquals(t0 + 30 * min, gap.startMs)
        assertEquals(t0 + 200 * min, gap.endMs)
        assertTrue(entries.none { it is Trip })
    }

    @Test
    fun poorAccuracyFixesAreIgnored() {
        val fixes = (0..12).map { fix(it * 5L, homeLat, homeLon) } +
            fix(32, homeLat + 0.05, homeLon, acc = 900f)
        val entries = build(fixes).entries
        assertEquals(1, entries.size)
        assertTrue(entries.single() is Stay)
    }

    @Test
    fun aWanderBetweenNearbyStaysMergesThem() {
        val a = (0..4).map { fix(it * 3L, homeLat, homeLon) }
        val blip = fix(15, homeLat + 0.0015, homeLon) // about 165 m, too little for a trip
        val b = (0..4).map { fix(18L + it * 3, homeLat + 0.0004, homeLon) }
        val entries = build(a + blip + b).entries
        assertEquals(1, entries.size)
        assertTrue(entries.single() is Stay)
    }

    @Test
    fun lastEntryIsOngoingWhileRecentlyReported() {
        val fixes = (0..6).map { fix(it * 5L, homeLat, homeLon) }
        val live = build(fixes, now = t0 + 32 * min).entries.last() as Stay
        assertTrue(live.ongoing)
        val stale = build(fixes, now = t0 + 300 * min).entries.last() as Stay
        assertFalse(stale.ongoing)
    }

    @Test
    fun aSingleFixIsStillShown() {
        val entries = build(listOf(fix(0, homeLat, homeLon))).entries
        assertEquals(1, entries.size)
        assertNotNull(entries.single() as? Stay)
    }

    @Test
    fun summaryCountsTripsAndPlaces() {
        val morning = (0..6).map { fix(it * 5L, homeLat, homeLon) }
        val drive = (1..5).map { fix(30L + it * 3, homeLat + it * 0.015, homeLon, speed = 20f) }
        val atWork = (0..6).map { fix(48L + it * 5, workLat, homeLon) }
        val summary = build(morning + drive + atWork, listOf(home)).summary
        assertEquals(1, summary.tripCount)
        assertEquals(listOf("Home"), summary.placesVisited)
    }
}
