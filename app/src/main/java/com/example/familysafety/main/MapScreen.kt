package com.example.familysafety.main

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@Composable
fun MapScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val memberLocations by viewModel.memberLocations.collectAsState()
    val familyMembers by viewModel.familyMembers.collectAsState()
    val focusedMemberId by viewModel.focusedMemberId.collectAsState()

    val context = LocalContext.current

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(37.7749, -122.4194))
        }
    }

    // osmdroid "my location" overlay — shows the standard blue dot + accuracy circle.
    val myLocationOverlay = remember {
        MyLocationNewOverlay(GpsMyLocationProvider(context), mapView)
    }

    // Honour the OSM tile library's lifecycle requirements.
    DisposableEffect(Unit) {
        mapView.overlays.add(myLocationOverlay)
        myLocationOverlay.enableMyLocation()
        mapView.onResume()
        onDispose {
            myLocationOverlay.disableMyLocation()
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // Auto-center on the group the first time we have locations.
    val hasCentered = remember { mutableStateOf(false) }
    LaunchedEffect(memberLocations) {
        if (hasCentered.value || memberLocations.isEmpty()) return@LaunchedEffect
        val points = memberLocations.values.map { GeoPoint(it.latitude, it.longitude) }
        when {
            points.size == 1 -> {
                mapView.controller.animateTo(points.first())
                mapView.controller.setZoom(15.0)
            }
            points.size > 1 -> {
                val box = BoundingBox.fromGeoPoints(points)
                mapView.zoomToBoundingBox(box, true, 150)
            }
        }
        hasCentered.value = true
    }

    // Pan to a specific member when selected from the Members tab.
    LaunchedEffect(focusedMemberId) {
        val id = focusedMemberId ?: return@LaunchedEffect
        val loc = memberLocations[id] ?: return@LaunchedEffect
        mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
        mapView.controller.setZoom(16.0)
        viewModel.clearFocus()
    }

    // Rebuild member markers whenever locations or member list changes.
    LaunchedEffect(memberLocations, familyMembers) {
        // Remove old member markers but keep the MyLocationOverlay
        mapView.overlays.removeAll { it is Marker }
        memberLocations.forEach { (memberId, location) ->
            val member = familyMembers.find { it.memberId == memberId }
            val marker = Marker(mapView).apply {
                position = GeoPoint(location.latitude, location.longitude)
                title = member?.displayName ?: memberId
                snippet = "Updated ${getTimeAgo(location.timestamp)} · ±${location.accuracy.toInt()}m"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(marker)
        }
        mapView.invalidate()
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}

private fun getTimeAgo(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> "${diff / 86_400_000}d ago"
    }
}
