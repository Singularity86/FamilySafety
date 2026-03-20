package com.example.familysafety.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import com.example.familysafety.location.MemberLocation
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

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showTimePicker by remember { mutableStateOf(false) }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            )

            // Stats row
            val pointCount = locationHistory.size
            val distanceKm = distanceMeters / 1000f
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
                               else "$pointCount points · ${"%.1f".format(distanceKm)} km",
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
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(locationHistory.asReversed(), key = { "${it.timestamp}_${it.latitude}_${it.longitude}" }) { loc ->
                    LocationHistoryRow(location = loc)
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
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
                    scope.launch { listState.animateScrollToItem(scrollTo) }
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
                icon = coloredDotDrawable(context, Color.parseColor("#34A853"))
            })

            mapView.overlays.add(Marker(mapView).apply {
                position = points.last()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Last known"
                icon = coloredDotDrawable(context, Color.parseColor("#EA4335"))
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

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun coloredDotDrawable(context: Context, fillColor: Int): Drawable {
    val dp = context.resources.displayMetrics.density
    val sizePx = (24 * dp).toInt()
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = sizePx / 2f
    paint.color = fillColor
    canvas.drawCircle(r, r, r, paint)
    paint.color = Color.WHITE
    canvas.drawCircle(r, r, r * 0.4f, paint)
    return BitmapDrawable(context.resources, bmp)
}

// ---------------------------------------------------------------------------
// Timeline row
// ---------------------------------------------------------------------------

@Composable
private fun LocationHistoryRow(location: MemberLocation) {
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(location.timestamp))
    val speedKmh = (location.speed ?: 0f) * 3.6f
    val isMoving = speedKmh > 1f
    val speedLabel = if (isMoving) "Moving ${speedKmh.toInt()} km/h" else "Stationary"

    Row(
        modifier = Modifier
            .fillMaxWidth()
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
