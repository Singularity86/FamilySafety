package com.example.familysafety.main

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@Composable
fun MapScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val memberLocations by viewModel.memberLocations.collectAsState()
    val myLocation by viewModel.myLocation.collectAsState()
    val familyMembers by viewModel.familyMembers.collectAsState()

    val context = LocalContext.current

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(37.7749, -122.4194))
        }
    }

    // Honour the OSM tile library's lifecycle requirements.
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // Pan to the user's location when it becomes available.
    LaunchedEffect(myLocation) {
        myLocation?.let {
            mapView.controller.animateTo(GeoPoint(it.latitude, it.longitude))
            mapView.controller.setZoom(15.0)
        }
    }

    // Rebuild markers whenever location data or member list changes.
    LaunchedEffect(memberLocations, myLocation, familyMembers) {
        mapView.overlays.clear()

        // Add other members' markers first (underneath "me")
        memberLocations.forEach { (memberId, location) ->
            val member = familyMembers.find { it.memberId == memberId }
            val marker = Marker(mapView).apply {
                position = GeoPoint(location.latitude, location.longitude)
                title = member?.displayName ?: memberId
                snippet = "Accuracy: ${location.accuracy}m"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(marker)
        }

        // Add a distinct "You" marker on top
        myLocation?.let { loc ->
            val meMarker = Marker(mapView).apply {
                position = GeoPoint(loc.latitude, loc.longitude)
                title = "You"
                snippet = "Accuracy: ${loc.accuracy}m"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(meMarker)
        }

        mapView.invalidate()
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}
