package com.example.familysafety.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

@Composable
fun MapScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val memberLocations by viewModel.memberLocations.collectAsState()
    val myLocation by viewModel.myLocation.collectAsState()
    val familyMembers by viewModel.familyMembers.collectAsState()

    val mapCenter = myLocation?.let {
        LatLng(it.latitude, it.longitude)
    } ?: LatLng(37.7749, -122.4194)

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(mapCenter, 12f)
    }

    LaunchedEffect(myLocation) {
        myLocation?.let {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(
                LatLng(it.latitude, it.longitude),
                15f
            )
        }
    }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(isMyLocationEnabled = false)
    ) {
        memberLocations.forEach { (memberId, location) ->
            val member = familyMembers.find { it.memberId == memberId }
            
            Marker(
                state = MarkerState(position = LatLng(location.latitude, location.longitude)),
                title = member?.displayName ?: memberId,
                snippet = "Accuracy: ${location.accuracy}m"
            )
        }
    }
}
