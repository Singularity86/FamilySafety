package com.example.familysafety.geofence

import android.app.Activity
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.exp
import kotlin.math.ln

private val PRESET_HUES = listOf(0f, 30f, 60f, 90f, 140f, 180f, 210f, 240f, 270f, 300f, 330f)

// Log-scale slider helpers: radius range 100m..50000m
private val LN_MIN = ln(100f)
private val LN_MAX = ln(50_000f)

private fun sliderToRadius(t: Float): Float = exp(LN_MIN + t * (LN_MAX - LN_MIN))
private fun radiusToSlider(r: Float): Float = ((ln(r) - LN_MIN) / (LN_MAX - LN_MIN)).coerceIn(0f, 1f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceEditorScreen(
    geofenceId: String,
    viewModel: GeofenceViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigateToZones: () -> Unit = onBack
) {
    val geofences by viewModel.geofences.collectAsState()
    val familyMembers by viewModel.familyMembers.collectAsState()
    val pendingGeoPoint by viewModel.pendingGeoPoint.collectAsState()
    val myLocation by viewModel.myLocation.collectAsState()

    val isNew = geofenceId == "new"
    val existing = if (!isNew) geofences.find { it.id == geofenceId } else null

    // Form state — initialised from existing zone or defaults
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var latitude by remember(existing) { mutableStateOf(existing?.latitude ?: 0.0) }
    var longitude by remember(existing) { mutableStateOf(existing?.longitude ?: 0.0) }
    var radiusSlider by remember(existing) {
        mutableFloatStateOf(radiusToSlider(existing?.radiusMeters ?: 500f))
    }
    var alertOnEnter by remember(existing) { mutableStateOf(existing?.alertOnEnter ?: true) }
    var alertOnExit by remember(existing) { mutableStateOf(existing?.alertOnExit ?: true) }
    var enterSoundUri by remember(existing) { mutableStateOf(existing?.enterSoundUri) }
    var exitSoundUri by remember(existing) { mutableStateOf(existing?.exitSoundUri) }
    var colorHue by remember(existing) { mutableFloatStateOf(existing?.colorHue ?: 120f) }
    val watchedMemberIds = remember(existing) {
        mutableStateOf(existing?.watchedMemberIds ?: emptySet())
    }
    val hasLocation = latitude != 0.0 || longitude != 0.0

    // Pre-fill location when navigated from map long-press
    LaunchedEffect(pendingGeoPoint) {
        if (isNew && pendingGeoPoint != null) {
            latitude = pendingGeoPoint!!.first
            longitude = pendingGeoPoint!!.second
        }
    }

    LaunchedEffect(isNew, myLocation) {
        val loc = myLocation
        if (isNew && !hasLocation && loc != null) {
            latitude = loc.latitude
            longitude = loc.longitude
        }
    }

    // Sound pickers
    val enterSoundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            enterSoundUri = uri?.toString()
        }
    }
    val exitSoundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            exitSoundUri = uri?.toString()
        }
    }

    fun openRingtonePicker(currentUri: String?, launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>) {
        val intent = android.content.Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Notification Sound")
            if (currentUri != null) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentUri))
            }
        }
        launcher.launch(intent)
    }

    val radiusMeters = sliderToRadius(radiusSlider)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New Zone" else "Edit Zone") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToZones) {
                        Icon(Icons.Default.List, contentDescription = "All zones")
                    }
                    if (!isNew) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete zone",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            val zone = if (isNew) {
                                GeofenceZone(
                                    name = name.trim().ifBlank { "Zone" },
                                    latitude = latitude,
                                    longitude = longitude,
                                    radiusMeters = radiusMeters,
                                    watchedMemberIds = watchedMemberIds.value,
                                    alertOnEnter = alertOnEnter,
                                    alertOnExit = alertOnExit,
                                    enterSoundUri = enterSoundUri,
                                    exitSoundUri = exitSoundUri,
                                    isEnabled = true,
                                    colorHue = colorHue
                                )
                            } else {
                                existing!!.copy(
                                    name = name.trim().ifBlank { "Zone" },
                                    latitude = latitude,
                                    longitude = longitude,
                                    radiusMeters = radiusMeters,
                                    watchedMemberIds = watchedMemberIds.value,
                                    alertOnEnter = alertOnEnter,
                                    alertOnExit = alertOnExit,
                                    enterSoundUri = enterSoundUri,
                                    exitSoundUri = exitSoundUri,
                                    colorHue = colorHue
                                )
                            }
                            if (isNew) viewModel.addGeofence(zone)
                            else viewModel.updateGeofence(zone)
                            viewModel.clearPendingGeoPoint()
                            onBack()
                        }
                    ) { Text("Save", fontWeight = FontWeight.Bold) }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Name
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 40) name = it },
                label = { Text("Zone name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Location display
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Center", style = MaterialTheme.typography.labelMedium)
                    if (hasLocation) {
                        Text(
                            "%.5f, %.5f".format(latitude, longitude),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            "No location yet. Use your current location when GPS is available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            myLocation?.let {
                                latitude = it.latitude
                                longitude = it.longitude
                            }
                        },
                        enabled = myLocation != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Use current location")
                    }
                }
            }

            // Radius slider
            Column {
                Text(
                    "Radius: ${formatRadius(radiusMeters)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = radiusSlider,
                    onValueChange = { radiusSlider = it },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("100 m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("50 km", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Color hue picker
            Column {
                Text("Zone color", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PRESET_HUES.forEach { hue ->
                        val color = Color.hsl(hue, 0.55f, 0.45f)
                        val isSelected = kotlin.math.abs(hue - colorHue) < 5f
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(if (isSelected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                                .clickable { colorHue = hue }
                        )
                    }
                }
            }

            // Enter/Exit alert toggles + sound pickers
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Alerts", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Alert when entering")
                        Switch(checked = alertOnEnter, onCheckedChange = { alertOnEnter = it })
                    }
                    if (alertOnEnter) {
                        TextButton(
                            onClick = { openRingtonePicker(enterSoundUri, enterSoundLauncher) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (enterSoundUri != null) "Enter sound: custom" else "Enter sound: default",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Alert when exiting")
                        Switch(checked = alertOnExit, onCheckedChange = { alertOnExit = it })
                    }
                    if (alertOnExit) {
                        TextButton(
                            onClick = { openRingtonePicker(exitSoundUri, exitSoundLauncher) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (exitSoundUri != null) "Exit sound: custom" else "Exit sound: default",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // Member filter
            if (familyMembers.isNotEmpty()) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Watch members", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Empty selection = all members",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        familyMembers.forEach { member ->
                            val isChecked = member.memberId in watchedMemberIds.value
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        watchedMemberIds.value = if (checked) {
                                            watchedMemberIds.value + member.memberId
                                        } else {
                                            watchedMemberIds.value - member.memberId
                                        }
                                    }
                                )
                                Text(member.displayName, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Zone?") },
            text = { Text("\"$name\" will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteGeofence(geofenceId)
                        onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
