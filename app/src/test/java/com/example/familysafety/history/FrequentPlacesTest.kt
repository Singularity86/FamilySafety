package com.example.familysafety.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrequentPlacesTest {

    private val day = 24 * 60 * 60 * 1000L
    private val min = 60_000L
    private val base = 1_700_000_000_000L
    private val utc: (Long) -> Long = { 0L }

    private fun stay(dayIndex: Int, lat: Double = 40.0, lon: Double = -105.0, minutes: Long = 20, place: PlaceRef? = null) =
        Stay(base + dayIndex * day, base + dayIndex * day + minutes * min, lat, lon, 5, place, false)

    @Test
    fun threeDifferentDaysMakeASuggestion() {
        val found = FrequentPlaces.find(listOf(stay(0), stay(1), stay(2)), emptyList(), utc)
        assertEquals(1, found.size)
        assertEquals(3, found.single().dayCount)
    }

    @Test
    fun twoDaysIsNotEnough() {
        assertTrue(FrequentPlaces.find(listOf(stay(0), stay(1)), emptyList(), utc).isEmpty())
    }

    @Test
    fun manyVisitsOnOneDayIsNotEnough() {
        val sameDay = (0..5).map { stay(0).copy(startMs = base + it * 60 * min, endMs = base + it * 60 * min + 20 * min) }
        assertTrue(FrequentPlaces.find(sameDay, emptyList(), utc).isEmpty())
    }

    @Test
    fun tooLittleTotalTimeIsIgnored() {
        val brief = listOf(stay(0, minutes = 5), stay(1, minutes = 5), stay(2, minutes = 5))
        assertTrue(FrequentPlaces.find(brief, emptyList(), utc).isEmpty())
    }

    @Test
    fun namedPlacesAreNeverSuggested() {
        val home = PlaceRef("h", "Home", 40.0, -105.0, 150f)
        val named = listOf(stay(0, place = home), stay(1, place = home), stay(2, place = home))
        assertTrue(FrequentPlaces.find(named, emptyList(), utc).isEmpty())
    }

    @Test
    fun aDismissedPlaceIsNotAskedAgain() {
        val stays = listOf(stay(0), stay(1), stay(2))
        val found = FrequentPlaces.find(stays, listOf(LatLon(40.0004, -105.0)), utc)
        assertTrue(found.isEmpty())
    }

    @Test
    fun nearbyStaysGroupIntoOnePlace() {
        val stays = listOf(stay(0, lat = 40.0000), stay(1, lat = 40.0006), stay(2, lat = 40.0003))
        assertEquals(1, FrequentPlaces.find(stays, emptyList(), utc).size)
    }

    @Test
    fun distantPlacesStaySeparate() {
        val stays = (0..2).flatMap { listOf(stay(it, lat = 40.0), stay(it, lat = 40.05)) }
        assertEquals(2, FrequentPlaces.find(stays, emptyList(), utc).size)
    }
}
