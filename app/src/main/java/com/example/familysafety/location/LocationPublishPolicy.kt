package com.example.familysafety.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides whether a new fix is worth sending to the family.
 *
 * Every publish is one encrypted message per family member (about 1.4 KB each on the wire),
 * so with 12 people one fix costs ~15 KB in and another ~15 KB out of the broker. The
 * passive listener hands us fixes whenever any other app asks for location — often every
 * 30 seconds for a phone sitting on a desk — and each of those used to be published even
 * though the position had not changed. Those repeats carry no information: the receiver
 * already has that spot, and the heartbeat re-stamps it every five minutes anyway.
 *
 * A fix is sent when it moved, when it is markedly more accurate, or when the last one is
 * old enough that freshness itself is news.
 */
object LocationPublishPolicy {

    /** Movement below this is GPS jitter for a phone that is standing still. */
    const val MIN_MOVE_METERS = 30.0

    /** Re-send even an unchanged position once the last one is this old. */
    const val MAX_SILENCE_MS = 5 * 60_000L

    /**
     * True when [next] adds nothing over [previous] and can be skipped.
     *
     * Movement is judged against the new fix's own accuracy as well: a 500 m cell fix that
     * lands 200 m away is noise, not travel.
     */
    fun isRedundant(previous: MemberLocation, next: MemberLocation): Boolean {
        // Older than what we already have — receivers would discard it as stale.
        if (next.timestamp <= previous.timestamp) return true
        if (next.timestamp - previous.timestamp >= MAX_SILENCE_MS) return false
        // A much better fix of the same place is worth sending (cell → GPS).
        if (next.accuracy < previous.accuracy / 2) return false
        val moved = distanceMeters(
            previous.latitude, previous.longitude,
            next.latitude, next.longitude
        )
        return moved < maxOf(MIN_MOVE_METERS, next.accuracy.toDouble())
    }

    /** Great-circle distance. Pure Kotlin so it runs in JVM unit tests. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    private const val EARTH_RADIUS_M = 6_371_000.0
}
