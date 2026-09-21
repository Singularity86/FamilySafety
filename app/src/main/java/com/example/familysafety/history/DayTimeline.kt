package com.example.familysafety.history

import com.example.familysafety.location.MemberLocation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val latitude: Double, val longitude: Double)

/** A named place, taken from the family's own zones. No address lookup is ever involved. */
data class PlaceRef(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float
)

sealed class TimelineEntry {
    abstract val startMs: Long
    abstract val endMs: Long
}

/** Time spent in one place. [place] is set when it falls inside one of the family's zones. */
data class Stay(
    override val startMs: Long,
    override val endMs: Long,
    val latitude: Double,
    val longitude: Double,
    val fixCount: Int,
    val place: PlaceRef?,
    val ongoing: Boolean
) : TimelineEntry()

data class Trip(
    override val startMs: Long,
    override val endMs: Long,
    val distanceMeters: Double,
    val maxSpeedMs: Float?,
    val points: List<LatLon>,
    val ongoing: Boolean
) : TimelineEntry()

/** No reports for a long stretch: the phone was offline, asleep, or had sharing paused. */
data class Gap(
    override val startMs: Long,
    override val endMs: Long
) : TimelineEntry()

data class DaySummary(
    val tripCount: Int,
    val distanceMeters: Double,
    val movingMs: Long,
    val placesVisited: List<String>
)

data class DayTimeline(
    val entries: List<TimelineEntry>,
    val summary: DaySummary
) {
    companion object {
        val EMPTY = DayTimeline(emptyList(), DaySummary(0, 0.0, 0L, emptyList()))
    }
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

/**
 * Turns a day of raw GPS fixes into stays, trips and gaps.
 *
 * Raw fixes are a poor thing to show a person: at the moving cadence there are thousands a day,
 * and while someone is still the 5-minute heartbeat re-stamps the same coordinates. Everything
 * here is derived on the device from data already stored; nothing is sent anywhere and no
 * address lookup is made.
 *
 * Pure and Android-free on purpose so the rules can be tested with synthetic days.
 */
object DayTimelineBuilder {

    /** Fixes within this distance of a running centroid belong to the same stay. */
    const val STAY_RADIUS_M = 100.0

    /** A cluster must last this long to count as a stay rather than a slow moment. */
    const val STAY_MIN_MS = 5 * 60_000L

    /**
     * Silence longer than this is reported as a gap. The stationary cadence is 5 minutes, so 30
     * leaves room for a missed heartbeat or two without calling it an outage.
     */
    const val GAP_MS = 30 * 60_000L

    /** Fixes worse than this are dropped; they jump around and would split real stays. */
    const val MAX_ACCURACY_M = 150f

    /** Movement shorter than this between two stays is treated as GPS wander, not a trip. */
    const val MIN_TRIP_M = 100.0

    /** Segments shorter than this add jitter to the distance without being movement. */
    const val MIN_SEGMENT_M = 10.0

    fun build(
        fixes: List<MemberLocation>,
        places: List<PlaceRef>,
        nowMs: Long
    ): DayTimeline {
        if (fixes.isEmpty()) return DayTimeline.EMPTY

        val good = fixes.filter { it.accuracy <= MAX_ACCURACY_M }
        val pts = (if (good.isEmpty()) fixes else good)
            .sortedBy { it.timestamp }
            .distinctBy { it.timestamp }

        val runs = mutableListOf<MutableList<MemberLocation>>()
        for (p in pts) {
            val last = runs.lastOrNull()?.last()
            if (last == null || p.timestamp - last.timestamp > GAP_MS) runs.add(mutableListOf(p))
            else runs.last().add(p)
        }

        val entries = mutableListOf<TimelineEntry>()
        runs.forEachIndexed { index, run ->
            if (index > 0) {
                entries.add(Gap(runs[index - 1].last().timestamp, run.first().timestamp))
            }
            entries.addAll(entriesForRun(run, places))
        }

        val lastFixMs = pts.last().timestamp
        val stillReporting = nowMs - lastFixMs <= GAP_MS
        if (stillReporting && entries.isNotEmpty()) {
            when (val tail = entries.last()) {
                is Stay -> entries[entries.lastIndex] = tail.copy(ongoing = true)
                is Trip -> entries[entries.lastIndex] = tail.copy(ongoing = true)
                is Gap -> Unit
            }
        }

        return DayTimeline(entries, summarise(entries))
    }

    private fun entriesForRun(run: List<MemberLocation>, places: List<PlaceRef>): List<TimelineEntry> {
        val items = mutableListOf<Item>()
        var i = 0
        while (i < run.size) {
            var sumLat = run[i].latitude
            var sumLon = run[i].longitude
            var j = i
            while (j + 1 < run.size) {
                val n = j - i + 1
                val d = Geo.distanceMeters(
                    run[j + 1].latitude, run[j + 1].longitude, sumLat / n, sumLon / n
                )
                if (d > STAY_RADIUS_M) break
                j++
                sumLat += run[j].latitude
                sumLon += run[j].longitude
            }
            if (run[j].timestamp - run[i].timestamp >= STAY_MIN_MS) {
                val n = j - i + 1
                items.add(Item.Cluster(i, j, sumLat / n, sumLon / n))
                i = j + 1
            } else {
                items.add(Item.Point(i))
                i++
            }
        }

        val result = mutableListOf<TimelineEntry>()
        var idx = 0
        while (idx < items.size) {
            when (val item = items[idx]) {
                is Item.Cluster -> {
                    result.add(stay(run, item, places))
                    idx++
                }
                is Item.Point -> {
                    val block = mutableListOf<Int>()
                    while (idx < items.size && items[idx] is Item.Point) {
                        block.add((items[idx] as Item.Point).index)
                        idx++
                    }
                    val before = (items.getOrNull(idx - block.size - 1) as? Item.Cluster)
                    val after = items.getOrNull(idx) as? Item.Cluster
                    tripFor(run, block, before, after)?.let { result.add(it) }
                }
            }
        }

        val merged = mergeAdjacentStays(result, places)
        if (merged.isEmpty() && run.isNotEmpty()) {
            val lat = run.map { it.latitude }.average()
            val lon = run.map { it.longitude }.average()
            return listOf(
                Stay(
                    run.first().timestamp, run.last().timestamp, lat, lon, run.size,
                    placeAt(lat, lon, places), false
                )
            )
        }
        return merged
    }

    private fun stay(run: List<MemberLocation>, c: Item.Cluster, places: List<PlaceRef>) = Stay(
        startMs = run[c.from].timestamp,
        endMs = run[c.to].timestamp,
        latitude = c.lat,
        longitude = c.lon,
        fixCount = c.to - c.from + 1,
        place = placeAt(c.lat, c.lon, places),
        ongoing = false
    )

    private fun tripFor(
        run: List<MemberLocation>,
        block: List<Int>,
        before: Item.Cluster?,
        after: Item.Cluster?
    ): Trip? {
        val fixes = mutableListOf<MemberLocation>()
        before?.let { fixes.add(run[it.to]) }
        block.forEach { fixes.add(run[it]) }
        after?.let { fixes.add(run[it.from]) }
        if (fixes.size < 2) return null

        // One or two stray fixes between stays that end up in the same place are GPS wander.
        // Summing out-and-back would call that a trip; a real outing has more fixes than this.
        if (before != null && after != null && block.size <= 2 &&
            Geo.distanceMeters(before.lat, before.lon, after.lat, after.lon) <= STAY_RADIUS_M * 1.5
        ) return null

        var distance = 0.0
        for (k in 0 until fixes.size - 1) {
            val d = Geo.distanceMeters(
                fixes[k].latitude, fixes[k].longitude, fixes[k + 1].latitude, fixes[k + 1].longitude
            )
            if (d >= MIN_SEGMENT_M) distance += d
        }
        if (distance < MIN_TRIP_M) return null

        val moving = block.map { run[it] }
        return Trip(
            startMs = before?.let { run[it.to].timestamp } ?: moving.first().timestamp,
            endMs = after?.let { run[it.from].timestamp } ?: moving.last().timestamp,
            distanceMeters = distance,
            maxSpeedMs = moving.mapNotNull { it.speed }.maxOrNull(),
            points = fixes.map { LatLon(it.latitude, it.longitude) },
            ongoing = false
        )
    }

    /** A discarded wander between two nearby stays leaves them adjacent; they are one stay. */
    private fun mergeAdjacentStays(entries: List<TimelineEntry>, places: List<PlaceRef>): List<TimelineEntry> {
        val out = mutableListOf<TimelineEntry>()
        for (e in entries) {
            val prev = out.lastOrNull()
            if (e is Stay && prev is Stay &&
                Geo.distanceMeters(prev.latitude, prev.longitude, e.latitude, e.longitude) <=
                STAY_RADIUS_M * 1.5
            ) {
                val total = prev.fixCount + e.fixCount
                val lat = (prev.latitude * prev.fixCount + e.latitude * e.fixCount) / total
                val lon = (prev.longitude * prev.fixCount + e.longitude * e.fixCount) / total
                out[out.lastIndex] = Stay(
                    min(prev.startMs, e.startMs), max(prev.endMs, e.endMs),
                    lat, lon, total, placeAt(lat, lon, places), false
                )
            } else {
                out.add(e)
            }
        }
        return out
    }

    private fun placeAt(lat: Double, lon: Double, places: List<PlaceRef>): PlaceRef? =
        places
            .map { it to Geo.distanceMeters(lat, lon, it.latitude, it.longitude) }
            .filter { (place, d) -> d <= place.radiusMeters }
            .minByOrNull { (_, d) -> d }
            ?.first

    private fun summarise(entries: List<TimelineEntry>): DaySummary {
        val trips = entries.filterIsInstance<Trip>()
        return DaySummary(
            tripCount = trips.size,
            distanceMeters = trips.sumOf { it.distanceMeters },
            movingMs = trips.sumOf { it.endMs - it.startMs },
            placesVisited = entries.filterIsInstance<Stay>().mapNotNull { it.place?.name }.distinct()
        )
    }

    private sealed class Item {
        data class Point(val index: Int) : Item()
        data class Cluster(val from: Int, val to: Int, val lat: Double, val lon: Double) : Item()
    }
}
