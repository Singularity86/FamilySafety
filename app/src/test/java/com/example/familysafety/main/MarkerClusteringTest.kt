package com.example.familysafety.main

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clustering is the part of the overlapping-pins feature that a screenshot cannot
 * check: whether two people are grouped depends on arithmetic that is invisible once it
 * has been turned into a bitmap.
 *
 * Distances used below: 0.0009° of latitude is ~100 m anywhere. At zoom 15 a pixel is
 * ~4.8 m at the equator, so 100 m is ~21 px; at zoom 18 a pixel is ~0.6 m, so the same
 * 100 m is ~167 px. A 47 px threshold therefore separates them at 18 and groups them at 15,
 * which is the whole point — the grouping is a fact about the screen, not about the ground.
 */
class MarkerClusteringTest {

    private val threshold = 47.0

    private fun pin(id: String, lat: Double, lon: Double = 0.0) = PinPoint(id, lat, lon)

    @Test
    fun clusterPins_returnsNothingForNoPins() {
        assertTrue(clusterPins(emptyList(), zoom = 15.0, thresholdPx = threshold).isEmpty())
    }

    @Test
    fun clusterPins_singlePinIsItsOwnCluster() {
        val clusters = clusterPins(listOf(pin("a", 0.0)), zoom = 15.0, thresholdPx = threshold)

        assertEquals(1, clusters.size)
        assertTrue(clusters.first().isSingle)
        assertEquals(listOf("a"), clusters.first().memberIds)
    }

    @Test
    fun clusterPins_separatesPinsThatAreFarApartOnScreen() {
        val clusters = clusterPins(
            listOf(pin("a", 0.0), pin("b", 0.0009)),
            zoom = 18.0,
            thresholdPx = threshold
        )

        assertEquals(2, clusters.size)
        assertTrue(clusters.all { it.isSingle })
    }

    @Test
    fun clusterPins_groupsTheSamePinsOnceZoomedOut() {
        val clusters = clusterPins(
            listOf(pin("a", 0.0), pin("b", 0.0009)),
            zoom = 15.0,
            thresholdPx = threshold
        )

        assertEquals(1, clusters.size)
        assertEquals(2, clusters.first().size)
        assertEquals(listOf("a", "b"), clusters.first().memberIds)
    }

    @Test
    fun clusterPins_groupsIdenticalPositionsAtEveryZoom() {
        for (zoom in listOf(3.0, 12.0, 19.0, 22.0)) {
            val clusters = clusterPins(
                listOf(pin("a", 40.4406, -79.9959), pin("b", 40.4406, -79.9959)),
                zoom = zoom,
                thresholdPx = threshold
            )
            assertEquals("zoom $zoom", 1, clusters.size)
        }
    }

    @Test
    fun clusterPins_chainsThroughAMiddlePin() {
        // a↔b and b↔c are each within the threshold; a↔c is not. All three still form one
        // group, because a chain of overlapping pins is one unreadable blob however long.
        val clusters = clusterPins(
            listOf(pin("a", 0.0), pin("b", 0.0009), pin("c", 0.0018)),
            zoom = 16.0,
            thresholdPx = threshold
        )

        assertEquals(1, clusters.size)
        assertEquals(listOf("a", "b", "c"), clusters.first().memberIds)
    }

    @Test
    fun clusterPins_keepsInputOrderWithinACluster() {
        // The caller sorts the raised member last so their pin draws on top; that order
        // has to survive, since the bubble decides which faces to show from it.
        val clusters = clusterPins(
            listOf(pin("c", 0.0), pin("a", 0.0001), pin("b", 0.0002)),
            zoom = 15.0,
            thresholdPx = threshold
        )

        assertEquals(listOf("c", "a", "b"), clusters.first().memberIds)
    }

    @Test
    fun clusterKey_isTheSameForTheSamePeopleInAnyOrder() {
        val one = PinCluster(listOf("c", "a", "b"), 0.0, 0.0)
        val other = PinCluster(listOf("b", "c", "a"), 1.0, 1.0)

        assertEquals(one.key, other.key)
    }

    @Test
    fun clusterPins_treatsANonPositiveThresholdAsNoClustering() {
        val clusters = clusterPins(
            listOf(pin("a", 0.0), pin("b", 0.0)),
            zoom = 15.0,
            thresholdPx = 0.0
        )

        assertEquals(2, clusters.size)
    }

    @Test
    fun clusterPins_putsTheBubbleBetweenThePeopleInIt() {
        val clusters = clusterPins(
            listOf(pin("a", 0.0), pin("b", 0.0010)),
            zoom = 14.0,
            thresholdPx = threshold
        )

        assertEquals(1, clusters.size)
        assertEquals(0.0005, clusters.first().latitude, 1e-9)
    }

    @Test
    fun clusterPins_keepsTheCentroidOnTheRightSideOfTheAntimeridian() {
        // Averaging longitudes directly would put this bubble at 0° — the far side of the
        // planet from both people in it.
        val clusters = clusterPins(
            listOf(PinPoint("a", 10.0, 179.999), PinPoint("b", 10.0, -179.999)),
            zoom = 12.0,
            thresholdPx = threshold
        )

        assertEquals(1, clusters.size)
        assertTrue(
            "centroid longitude was ${clusters.first().longitude}",
            abs(clusters.first().longitude) > 179.99
        )
    }

    @Test
    fun groundResolution_halvesWithEachZoomLevel() {
        val atFifteen = groundResolutionMetersPerPixel(0.0, 15.0)
        val atSixteen = groundResolutionMetersPerPixel(0.0, 16.0)

        assertEquals(4.7773, atFifteen, 0.001)
        assertEquals(atFifteen / 2, atSixteen, 1e-9)
    }

    @Test
    fun groundResolution_shrinksTowardThePoles() {
        // Mercator stretches with latitude, so a pixel covers less ground the further from
        // the equator it is. Clustering by a fixed distance in metres would group families
        // at high latitudes more eagerly than families near the equator.
        val equator = groundResolutionMetersPerPixel(0.0, 15.0)
        val sixty = groundResolutionMetersPerPixel(60.0, 15.0)

        assertEquals(equator / 2, sixty, 0.001)
    }

    @Test
    fun haversine_matchesAKnownDistance() {
        // One thousandth of a degree of latitude is ~111.3 m anywhere on the globe.
        assertEquals(111.3, haversineMeters(0.0, 0.0, 0.001, 0.0), 0.5)
        assertEquals(111.3, haversineMeters(59.0, 17.0, 59.001, 17.0), 0.5)
    }

    @Test
    fun haversine_measuresAcrossTheAntimeridianTheShortWay() {
        val meters = haversineMeters(10.0, 179.999, 10.0, -179.999)

        // ~0.002° of longitude at 10° N, not most of the way around the world.
        assertEquals(219.0, meters, 5.0)
    }
}
