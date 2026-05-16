package com.example.familysafety.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import android.widget.FrameLayout
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import com.example.familysafety.location.MemberLocation
import com.example.familysafety.ui.theme.UnitPreference
import com.example.familysafety.ui.theme.UnitSystem
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Tile sources
// ---------------------------------------------------------------------------

private val HISTORY_TILES = object : OnlineTileSourceBase(
    "OsmStandardHistory", 0, 19, 256, ".png",
    arrayOf(
        "https://a.tile.openstreetmap.org/",
        "https://b.tile.openstreetmap.org/",
        "https://c.tile.openstreetmap.org/"
    ),
    "© OpenStreetMap contributors"
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl +
            MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit
) {
    val member by viewModel.member.collectAsState()
    val memberAvatar by viewModel.memberAvatar.collectAsState()
    val locationHistory by viewModel.locationHistory.collectAsState()
    val distanceMeters by viewModel.distanceMeters.collectAsState()
    val selectedDayStart by viewModel._selectedDayStart.collectAsState()
    val totalRecordCount by viewModel.totalRecordCount.collectAsState()

    val colorHue = member?.colorHue
        ?: ((viewModel.memberId.hashCode().toLong() and 0xFFFFFFFFL) % 360).toFloat()
    val accentColor = ColorUtils.HSLToColor(floatArrayOf(colorHue, 0.70f, 0.50f))

    val dayLabel = remember(selectedDayStart) {
        SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(selectedDayStart))
    }

    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val rowStepPx = with(LocalDensity.current) { 60.dp.roundToPx() }
    val scope = rememberCoroutineScope()
    var showTimePicker by remember { mutableStateOf(false) }
    var selectedLocation by remember { mutableStateOf<MemberLocation?>(null) }
    val haptic = LocalHapticFeedback.current

    // Clear selection when the day changes
    LaunchedEffect(selectedDayStart) { selectedLocation = null }

    // Re-read units when user returns from Settings
    val lifecycleOwner = LocalLifecycleOwner.current
    var unitSystem by remember { mutableStateOf(UnitPreference.get(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) unitSystem = UnitPreference.get(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val timePickerState = rememberTimePickerState(
        initialHour = 12,
        initialMinute = 0,
        is24Hour = false
    )

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(member?.displayName ?: "History") },
                actions = {
                    MemberAvatar(
                        displayName = member?.displayName ?: "?",
                        memberId = viewModel.memberId,
                        bitmap = memberAvatar,
                        colorHue = member?.colorHue,
                        size = 40.dp,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Day navigation row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { viewModel.previousDay() }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous day")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = dayLabel, style = MaterialTheme.typography.titleSmall)
                    if (locationHistory.isNotEmpty()) {
                        IconButton(onClick = { showTimePicker = true }) {
                            Icon(
                                Icons.Default.AccessTime,
                                contentDescription = "Jump to time",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { viewModel.nextDay() },
                    enabled = !viewModel.isAtToday()
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next day")
                }
            }

            // Map (fixed height)
            RouteMapView(
                locationHistory = locationHistory,
                accentColor = accentColor,
                focusedLocation = selectedLocation,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            )

            // Stats row
            val pointCount = locationHistory.size
            val imperial = unitSystem == UnitSystem.IMPERIAL
            val distanceDisplay = if (imperial) {
                "${"%.1f".format(distanceMeters / 1609.34f)} mi"
            } else {
                "${"%.1f".format(distanceMeters / 1000f)} km"
            }
            val estimatedKb = (totalRecordCount * 120L) / 1024
            val storageLabel = when {
                estimatedKb < 1 -> "<1 KB"
                estimatedKb < 1024 -> "$estimatedKb KB"
                else -> "${"%.1f".format(estimatedKb / 1024f)} MB"
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (pointCount == 0) "No location data for this day"
                               else "$pointCount points · $distanceDisplay",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (pointCount == 0) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface
                    )
                    if (totalRecordCount > 0) {
                        Text(
                            text = "$totalRecordCount total · $storageLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Timeline — newest first
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Top
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(bottom = 16.dp)
                ) {
                    locationHistory.asReversed().forEach { loc ->
                        LocationHistoryRow(
                            location = loc,
                            imperial = imperial,
                            isSelected = loc == selectedLocation,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedLocation = if (selectedLocation == loc) null else loc
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }

    // Time-jump dialog
    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Jump to time") },
            text = {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTimePicker = false
                    // Build target timestamp from selected hour:minute on the displayed day
                    val targetMs = selectedDayStart +
                        timePickerState.hour * 3_600_000L +
                        timePickerState.minute * 60_000L
                    // locationHistory is DESC (newest first); asReversed() gives ASC (oldest first)
                    val ascending = locationHistory.asReversed()
                    // Find the first entry at or after the target time
                    val idx = ascending.indexOfFirst { it.timestamp >= targetMs }
                    val scrollTo = when {
                        idx >= 0 -> idx
                        ascending.isNotEmpty() -> ascending.lastIndex // target is after all entries
                        else -> return@TextButton
                    }
                    scope.launch { scrollState.animateScrollTo(scrollTo * rowStepPx) }
                }) { Text("Go") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Map composable
// ---------------------------------------------------------------------------

@Composable
private fun RouteMapView(
    locationHistory: List<MemberLocation>,
    accentColor: Int,
    focusedLocation: MemberLocation? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val mapView = remember {
        MapView(context).apply {
            setTileSource(HISTORY_TILES)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(37.7749, -122.4194))
        }
    }

    // Track the focus marker separately so we can replace it without redrawing the route
    val focusMarker = remember { mutableStateOf<Marker?>(null) }

    LaunchedEffect(focusedLocation) {
        // Remove previous focus marker
        focusMarker.value?.let { mapView.overlays.remove(it) }
        focusMarker.value = null

        if (focusedLocation != null) {
            val point = GeoPoint(focusedLocation.latitude, focusedLocation.longitude)
            val marker = Marker(mapView).apply {
                position = point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = coloredDotDrawable(context, AndroidColor.parseColor("#1A73E8"), large = true)
                title = SimpleDateFormat("h:mm a", Locale.getDefault())
                    .format(Date(focusedLocation.timestamp))
            }
            mapView.overlays.add(marker)
            focusMarker.value = marker
            mapView.post {
                mapView.controller.animateTo(point)
                mapView.controller.setZoom(16.0)
            }
            mapView.invalidate()
        }
    }

    LaunchedEffect(locationHistory) {
        mapView.overlays.clear()

        if (locationHistory.isNotEmpty()) {
            val points = locationHistory.map { GeoPoint(it.latitude, it.longitude) }

            val polyline = Polyline(mapView).apply {
                setPoints(points)
                outlinePaint.color = accentColor
                outlinePaint.strokeWidth = 8f
                outlinePaint.isAntiAlias = true
            }
            mapView.overlays.add(polyline)

            mapView.overlays.add(Marker(mapView).apply {
                position = points.first()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Start"
                icon = coloredDotDrawable(context, AndroidColor.parseColor("#34A853"))
            })

            mapView.overlays.add(Marker(mapView).apply {
                position = points.last()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Last known"
                icon = coloredDotDrawable(context, AndroidColor.parseColor("#EA4335"))
            })

            mapView.post {
                if (points.size == 1) {
                    mapView.controller.animateTo(points.first())
                    mapView.controller.setZoom(15.0)
                } else {
                    val box = BoundingBox.fromGeoPoints(points)
                    // Guard against zero-area box (all points identical) which can
                    // cause a divide-by-zero inside zoomToBoundingBox on some devices.
                    if (box.latNorth != box.latSouth || box.lonEast != box.lonWest) {
                        mapView.zoomToBoundingBox(box, true, 100)
                    } else {
                        mapView.controller.animateTo(points.first())
                        mapView.controller.setZoom(15.0)
                    }
                }
            }
        }
        mapView.invalidate()
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { ctx ->
            // Wrap in a FrameLayout that absorbs requestLayout() calls from the MapView
            // so osmdroid's internal pan/zoom layout requests don't propagate up into
            // the Compose hierarchy and cause the surrounding UI to blink/re-layout.
            object : FrameLayout(ctx) {
                override fun requestLayout() {
                    // forceLayout() remeasures our own children without telling our
                    // Compose parent that a layout pass is needed.
                    forceLayout()
                }
            }.apply {
                addView(mapView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
        },
        modifier = modifier
    )
}

private fun coloredDotDrawable(context: Context, fillColor: Int, large: Boolean = false): Drawable {
    val dp = context.resources.displayMetrics.density
    val sizePx = ((if (large) 32 else 24) * dp).toInt()
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = sizePx / 2f
    paint.color = fillColor
    canvas.drawCircle(r, r, r, paint)
    paint.color = AndroidColor.WHITE
    canvas.drawCircle(r, r, r * 0.4f, paint)
    return BitmapDrawable(context.resources, bmp)
}

// ---------------------------------------------------------------------------
// Timeline row
// ---------------------------------------------------------------------------

@Composable
private fun LocationHistoryRow(
    location: MemberLocation,
    imperial: Boolean = true,
    isSelected: Boolean = false,
    onClick: () -> Unit = {}
) {
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(location.timestamp))
    val speedMs = location.speed ?: 0f
    val isMoving = speedMs > 0.5f
    val speedLabel = if (isMoving) {
        if (imperial) "Moving ${(speedMs * 2.237f).toInt()} mph"
        else "Moving ${(speedMs * 3.6f).toInt()} km/h"
    } else "Stationary"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(72.dp)
        )
        Surface(
            color = if (isMoving) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.extraSmall
        ) {
            Text(
                text = speedLabel,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(
            text = "±${location.accuracy.toInt()}m",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
