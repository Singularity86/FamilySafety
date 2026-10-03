package com.example.familysafety.history

import java.util.TimeZone

/** An unnamed place this person keeps coming back to, offered to the user to be named. */
data class PlaceSuggestion(
    val latitude: Double,
    val longitude: Double,
    val stayCount: Int,
    val dayCount: Int,
    val totalMs: Long,
    val lastVisitMs: Long
)

/**
 * Finds places worth naming from a longer run of stays.
 *
 * Only stays that are not already inside one of the family's zones are considered, and a place
 * qualifies only if it recurs on several different days. That keeps a one-off stop, or a long
 * afternoon somewhere, from ever being asked about.
 */
object FrequentPlaces {

    const val CLUSTER_RADIUS_M = 150.0
    const val MIN_DAYS = 3
    const val MIN_TOTAL_MS = 30 * 60_000L
    private const val MS_PER_DAY = 24 * 60 * 60 * 1000L

    fun find(
        stays: List<Stay>,
        dismissed: List<LatLon>,
        tzOffsetMs: (Long) -> Long = { TimeZone.getDefault().getOffset(it).toLong() }
    ): List<PlaceSuggestion> {
        class Cluster(var lat: Double, var lon: Double) {
            val stays = mutableListOf<Stay>()
        }

        val clusters = mutableListOf<Cluster>()
        for (s in stays.filter { it.place == null }.sortedBy { it.startMs }) {
            val home = clusters.firstOrNull {
                Geo.distanceMeters(it.lat, it.lon, s.latitude, s.longitude) <= CLUSTER_RADIUS_M
            } ?: Cluster(s.latitude, s.longitude).also { clusters.add(it) }
            home.stays.add(s)
            home.lat = home.stays.map { it.latitude }.average()
            home.lon = home.stays.map { it.longitude }.average()
        }

        return clusters
            .map { c ->
                val days = c.stays.map { (it.startMs + tzOffsetMs(it.startMs)) / MS_PER_DAY }.toSet()
                PlaceSuggestion(
                    latitude = c.lat,
                    longitude = c.lon,
                    stayCount = c.stays.size,
                    dayCount = days.size,
                    totalMs = c.stays.sumOf { it.endMs - it.startMs },
                    lastVisitMs = c.stays.maxOf { it.endMs }
                )
            }
            .filter { it.dayCount >= MIN_DAYS && it.totalMs >= MIN_TOTAL_MS }
            .filter { s ->
                dismissed.none {
                    Geo.distanceMeters(it.latitude, it.longitude, s.latitude, s.longitude) <=
                        CLUSTER_RADIUS_M
                }
            }
            .sortedByDescending { it.totalMs }
    }
}
