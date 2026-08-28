package com.example.familysafety.main

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Groups member pins that would cover each other up on screen.
 *
 * Overlap is a property of the screen, not of the ground: two people standing ten metres
 * apart hide each other at zoom 15 and are plainly separate at zoom 19. So the distance
 * that matters here is measured in pixels at the current zoom. That also makes the result
 * independent of where the map is panned — only the zoom changes which pins collide —
 * which is what lets the caller rebuild markers on zoom alone instead of on every scroll.
 *
 * Everything in this file is pure arithmetic on doubles, deliberately: it is the part of
 * the feature that can be wrong in ways a screenshot will not show.
 */

/** Metres around the equator; the standard WGS-84 value osmdroid's tile system uses. */
private const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.686
private const val EARTH_RADIUS_METERS = 6_371_008.8

/** osmdroid's default tile size. Passed explicitly so a caller with 512 px tiles is not lying to. */
const val DEFAULT_TILE_SIZE_PX = 256

/** One member's pin, before anything is known about who it collides with. */
data class PinPoint(
    val memberId: String,
    val latitude: Double,
    val longitude: Double
)

/**
 * One or more pins that occupy the same patch of screen.
 *
 * [memberIds] keeps the order the caller supplied, so whatever the caller sorted by —
 * draw order, who was raised — survives clustering.
 */
data class PinCluster(
    val memberIds: List<String>,
    val latitude: Double,
    val longitude: Double
) {
    val size: Int get() = memberIds.size
    val isSingle: Boolean get() = memberIds.size == 1

    /**
     * Stable identity across rebuilds. Sorted, so the same set of people is the same
     * cluster no matter what order they were drawn in — an expanded cluster stays
     * expanded when a location update reshuffles the draw order.
     */
    val key: String get() = memberIds.sorted().joinToString(",")
}

/**
 * Metres per pixel at [latitude] and [zoom].
 *
 * The Mercator projection stretches with latitude, which is why this takes one: the same
 * pixel gap is a smaller distance in Reykjavík than in Quito, and clustering by a fixed
 * metre radius would group families at high latitudes too eagerly.
 */
fun groundResolutionMetersPerPixel(
    latitude: Double,
    zoom: Double,
    tileSizePx: Int = DEFAULT_TILE_SIZE_PX
): Double {
    val clampedLat = latitude.coerceIn(-85.05112878, 85.05112878)
    val mapWidthPx = tileSizePx * Math.pow(2.0, zoom)
    return cos(Math.toRadians(clampedLat)) * EARTH_CIRCUMFERENCE_METERS / mapWidthPx
}

/** Great-circle distance in metres. */
fun haversineMeters(
    lat1: Double, lon1: Double,
    lat2: Double, lon2: Double
): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(normalizeLongitudeDelta(lon2 - lon1))
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
}

/**
 * Groups [points] so that no two clusters are closer than [thresholdPx] on screen.
 *
 * Single-link: A joins B's cluster if it is within the threshold of *any* member of it,
 * which is the right rule for "would these cover each other up" — a chain of overlapping
 * pins is one unreadable blob however long it is. With a family-sized roster the O(n²)
 * pass is not worth improving on.
 *
 * Returns clusters in order of first appearance in [points].
 */
fun clusterPins(
    points: List<PinPoint>,
    zoom: Double,
    thresholdPx: Double,
    tileSizePx: Int = DEFAULT_TILE_SIZE_PX
): List<PinCluster> {
    if (points.isEmpty()) return emptyList()
    if (points.size == 1 || thresholdPx <= 0.0) {
        return points.map { PinCluster(listOf(it.memberId), it.latitude, it.longitude) }
    }

    // Union-find keyed by index, so the grouping does not depend on visit order.
    val parent = IntArray(points.size) { it }
    fun find(i: Int): Int {
        var root = i
        while (parent[root] != root) root = parent[root]
        var walk = i
        while (parent[walk] != root) {
            val next = parent[walk]
            parent[walk] = root
            walk = next
        }
        return root
    }
    fun union(a: Int, b: Int) {
        val ra = find(a); val rb = find(b)
        if (ra != rb) parent[max(ra, rb)] = min(ra, rb)
    }

    for (i in points.indices) {
        for (j in i + 1 until points.size) {
            val a = points[i]
            val b = points[j]
            // Resolution at the midpoint latitude: at the scale where pins collide the
            // difference from either endpoint is far below a pixel, and it keeps the
            // comparison symmetric.
            val metersPerPx = groundResolutionMetersPerPixel(
                (a.latitude + b.latitude) / 2.0, zoom, tileSizePx
            )
            if (metersPerPx <= 0.0) continue
            val pixelGap = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) / metersPerPx
            if (pixelGap < thresholdPx) union(i, j)
        }
    }

    val grouped = LinkedHashMap<Int, MutableList<PinPoint>>()
    for (i in points.indices) {
        grouped.getOrPut(find(i)) { mutableListOf() }.add(points[i])
    }

    return grouped.values.map { group ->
        val centre = centroidOf(group)
        PinCluster(group.map { it.memberId }, centre.first, centre.second)
    }
}

/**
 * Mean position of a group.
 *
 * Longitudes are averaged relative to the first point rather than absolutely, so a cluster
 * straddling the antimeridian does not get a centroid on the opposite side of the planet.
 */
internal fun centroidOf(group: List<PinPoint>): Pair<Double, Double> {
    val anchor = group.first()
    var latSum = 0.0
    var lonDeltaSum = 0.0
    for (p in group) {
        latSum += p.latitude
        lonDeltaSum += normalizeLongitudeDelta(p.longitude - anchor.longitude)
    }
    val lat = latSum / group.size
    val lon = normalizeLongitude(anchor.longitude + lonDeltaSum / group.size)
    return lat to lon
}

/** Shortest signed way round from one longitude to another, in (-180, 180]. */
internal fun normalizeLongitudeDelta(delta: Double): Double {
    var d = delta
    while (d > 180.0) d -= 360.0
    while (d <= -180.0) d += 360.0
    return d
}

/** Wraps a longitude back into (-180, 180]. */
internal fun normalizeLongitude(longitude: Double): Double = normalizeLongitudeDelta(longitude)
