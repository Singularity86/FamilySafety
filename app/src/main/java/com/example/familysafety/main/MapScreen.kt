package com.example.familysafety.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
    val context = LocalContext.current

    // --- Permission state ---
    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    var permissionGranted by remember { mutableStateOf(hasLocationPermission()) }
    // Track whether the user has permanently denied (so we can send to Settings)
    var permanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        permissionGranted = granted
        if (!granted) {
            permanentlyDenied = true
        }
    }

    if (!permissionGranted) {
        // Permission not granted — show explanation and request button
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Location Permission Required",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "FamilySafety needs access to your location to show you and your family members on the map.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            if (permanentlyDenied) {
                Button(onClick = {
                    // Send user to app settings so they can grant manually
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    )
                }) {
                    Text("Open App Settings")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { permissionGranted = hasLocationPermission() }) {
                    Text("I've granted it, refresh")
                }
            } else {
                Button(onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }) {
                    Text("Grant Location Permission")
                }
            }
        }
        return
    }

    // --- Map (permission granted) ---
    val memberLocations by viewModel.memberLocations.collectAsState()
    val familyMembers by viewModel.familyMembers.collectAsState()
    val focusedMemberId by viewModel.focusedMemberId.collectAsState()

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(37.7749, -122.4194))
        }
    }

    // Standard osmdroid "my location" overlay — blue dot + accuracy circle.
    val myLocationOverlay = remember {
        MyLocationNewOverlay(GpsMyLocationProvider(context), mapView)
    }

    // Honour osmdroid lifecycle requirements.
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
