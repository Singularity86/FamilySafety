package com.example.familysafety.geofence

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceListScreen(
    viewModel: GeofenceViewModel = hiltViewModel(),
    onNavigateToEditor: (String) -> Unit
) {
    val geofences by viewModel.geofences.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Zones") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNavigateToEditor("new") }) {
                Icon(Icons.Default.Add, contentDescription = "Add zone")
            }
        }
    ) { paddingValues ->
        if (geofences.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.LocationCity,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "No zones yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Long-press the map or tap + to create a zone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(geofences, key = { it.id }) { zone ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                viewModel.deleteGeofence(zone.id)
                                true
                            } else false
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.errorContainer)
                                    .padding(end = 20.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete zone",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        },
                        enableDismissFromStartToEnd = false
                    ) {
                        GeofenceRow(
                            zone = zone,
                            onToggleEnabled = {
                                viewModel.updateGeofence(zone.copy(isEnabled = !zone.isEnabled))
                            },
                            onTap = { onNavigateToEditor(zone.id) }
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun GeofenceRow(
    zone: GeofenceZone,
    onToggleEnabled: () -> Unit,
    onTap: () -> Unit
) {
    Surface(
        onClick = onTap,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color.hsl(zone.colorHue, 0.6f, 0.5f))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(zone.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = formatRadius(zone.radiusMeters),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (zone.alertOnEnter) {
                Icon(
                    Icons.Default.Login,
                    contentDescription = "Alert on enter",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            if (zone.alertOnExit) {
                Icon(
                    Icons.Default.Logout,
                    contentDescription = "Alert on exit",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
            Switch(
                checked = zone.isEnabled,
                onCheckedChange = { onToggleEnabled() }
            )
        }
    }
}

internal fun formatRadius(radiusMeters: Float): String =
    if (radiusMeters >= 1000f) "%.1f km".format(radiusMeters / 1000f)
    else "${radiusMeters.toInt()} m"
