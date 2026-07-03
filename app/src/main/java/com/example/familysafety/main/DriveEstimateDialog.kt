package com.example.familysafety.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DriveEstimateDialog(
    state: MainViewModel.DriveEstimateState,
    onDismiss: () -> Unit,
    onShowOnMap: (String) -> Unit
) {
    if (state is MainViewModel.DriveEstimateState.Hidden) return

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.DirectionsCar, contentDescription = null)
        },
        title = {
            Text(
                when (state) {
                    is MainViewModel.DriveEstimateState.Loading -> state.memberName
                    is MainViewModel.DriveEstimateState.Ready -> state.memberName
                    is MainViewModel.DriveEstimateState.Error -> state.memberName
                    MainViewModel.DriveEstimateState.Hidden -> ""
                }
            )
        },
        text = {
            when (state) {
                is MainViewModel.DriveEstimateState.Loading -> {
                    Column {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Calculating drive time...")
                    }
                }
                is MainViewModel.DriveEstimateState.Ready -> {
                    Column {
                        Text(
                            text = "${formatDriveDuration(state.durationSeconds)} drive",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = formatDriveDistance(state.distanceMeters),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Location updated ${formatLocationAge(state.locationTimestamp)}. " +
                                "Estimate does not include live traffic.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Route by OSRM/OpenStreetMap. Your location and the member's " +
                                "location are sent to the routing service.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is MainViewModel.DriveEstimateState.Error -> {
                    Text(state.message)
                }
                MainViewModel.DriveEstimateState.Hidden -> Unit
            }
        },
        confirmButton = {
            val memberId = when (state) {
                is MainViewModel.DriveEstimateState.Loading -> state.memberId
                is MainViewModel.DriveEstimateState.Ready -> state.memberId
                is MainViewModel.DriveEstimateState.Error -> state.memberId
                MainViewModel.DriveEstimateState.Hidden -> null
            }
            if (memberId != null) {
                TextButton(onClick = { onShowOnMap(memberId) }) {
                    Text("Show on map")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

internal fun formatDriveDuration(durationSeconds: Double): String {
    val totalMinutes = (durationSeconds / 60.0).roundToInt().coerceAtLeast(1)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> "$minutes min"
        minutes == 0 -> "$hours hr"
        else -> "$hours hr $minutes min"
    }
}

internal fun formatDriveDistance(distanceMeters: Double): String {
    val miles = distanceMeters / 1609.344
    return if (miles < 10.0) {
        String.format(Locale.US, "%.1f mi", miles)
    } else {
        "${miles.roundToInt()} mi"
    }
}

private fun formatLocationAge(timestamp: Long): String {
    val ageMs = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    return when {
        ageMs < 60_000 -> "just now"
        ageMs < 3_600_000 -> "${ageMs / 60_000} min ago"
        ageMs < 86_400_000 -> "${ageMs / 3_600_000} hr ago"
        else -> "${ageMs / 86_400_000} days ago"
    }
}
