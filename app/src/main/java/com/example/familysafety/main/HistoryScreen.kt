package com.example.familysafety.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.familysafety.history.DayTimeline
import com.example.familysafety.history.FrequentPlaces
import com.example.familysafety.history.Gap
import com.example.familysafety.history.Geo
import com.example.familysafety.history.PlaceSuggestion
import com.example.familysafety.history.Stay
import com.example.familysafety.history.TimelineEntry
import com.example.familysafety.history.Trip
import com.example.familysafety.location.MemberLocation
import com.example.familysafety.ui.theme.UnitPreference
import com.example.familysafety.ui.theme.UnitSystem
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val RETENTION_DAYS = 30
private const val DRIVE_SPEED_MS = 7f

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
    val rawFixes by viewModel.locationHistory.collectAsState()
    val timeline by viewModel.timeline.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val selectedDayStart by viewModel._selectedDayStart.collectAsState()
    val totalRecordCount by viewModel.totalRecordCount.collectAsState()

    val colorHue = member?.colorHue
        ?: ((viewModel.memberId.hashCode().toLong() and 0xFFFFFFFFL) % 360).toFloat()
    val accentColor = ColorUtils.HSLToColor(floatArrayOf(colorHue, 0.70f, 0.50f))

    val dayLabel = remember(selectedDayStart) {
        SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(selectedDayStart))
    }

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var showRaw by rememberSaveable { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<PlaceSuggestion?>(null) }

    LaunchedEffect(selectedDayStart) { selectedIndex = null }

    val lifecycleOwner = LocalLifecycleOwner.current
    var unitSystem by remember { mutableStateOf(UnitPreference.get(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) unitSystem = UnitPreference.get(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val imperial = unitSystem == UnitSystem.IMPERIAL

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
                TextButton(onClick = { showDatePicker = true }) {
                    Text(text = dayLabel, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = "Choose a day",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { viewModel.nextDay() },
                    enabled = !viewModel.isAtToday()
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next day")
                }
            }

            RouteMapView(
                timeline = timeline,
                rawFixes = rawFixes,
                showRaw = showRaw,
                selectedIndex = selectedIndex,
                accentColor = accentColor,
                lastSeenColor = MaterialTheme.colorScheme.tertiary.toArgb(),
                description = "Map of ${member?.displayName ?: "this person"}'s route on $dayLabel",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            )

            SummaryCard(
                timeline = timeline,
                rawCount = rawFixes.size,
                totalRecordCount = totalRecordCount,
                imperial = imperial,
                showRaw = showRaw,
                onShowRawChange = { showRaw = it }
            )

            if (showRaw) {
                RawFixList(rawFixes, imperial)
            } else if (timeline.entries.isEmpty()) {
                EmptyDay(isToday = viewModel.isAtToday())
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    // Newest first, like a feed.
                    val ordered = timeline.entries.withIndex().toList().asReversed()
                    itemsIndexed(ordered, key = { _, item -> "${item.index}-${item.value.startMs}" }) { _, item ->
                        val entry = item.value
                        val isSelected = selectedIndex == item.index
                        val onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedIndex = if (isSelected) null else item.index
                        }
                        when (entry) {
                            is Stay -> StayRow(
                                stay = entry,
                                suggestion = suggestionFor(entry, suggestions),
                                isSelected = isSelected,
                                onClick = onClick,
                                onName = { naming = it },
                                onDismiss = { viewModel.dismissPlace(it) }
                            )
                            is Trip -> TripRow(entry, imperial, isSelected, onClick)
                            is Gap -> GapRow(entry)
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 64.dp))
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val today = viewModel.todayStartMs()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = localMidnightToUtc(selectedDayStart),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val top = localMidnightToUtc(today)
                    return utcTimeMillis <= top &&
                        utcTimeMillis >= top - RETENTION_DAYS * HistoryViewModel.MS_PER_DAY
                }
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { viewModel.setDay(utcToLocalMidnight(it)) }
                    showDatePicker = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }

    naming?.let { suggestion ->
        var text by remember(suggestion) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text("Name this place") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(40) },
                        singleLine = true,
                        label = { Text("Name") },
                        placeholder = { Text("e.g., Grandma's") }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Saved on this phone as a zone with alerts turned off, so nobody is " +
                            "notified. You can change it in Zones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        viewModel.namePlace(suggestion, text)
                        naming = null
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } }
        )
    }
}

private fun suggestionFor(stay: Stay, suggestions: List<PlaceSuggestion>): PlaceSuggestion? {
    if (stay.place != null) return null
    return suggestions.firstOrNull {
        Geo.distanceMeters(it.latitude, it.longitude, stay.latitude, stay.longitude) <=
            FrequentPlaces.CLUSTER_RADIUS_M
    }
}

// ---------------------------------------------------------------------------
// Summary and empty states
// ---------------------------------------------------------------------------

@Composable
private fun SummaryCard(
    timeline: DayTimeline,
    rawCount: Int,
    totalRecordCount: Int,
    imperial: Boolean,
    showRaw: Boolean,
    onShowRawChange: (Boolean) -> Unit
) {
    val s = timeline.summary
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            val headline = when {
                timeline.entries.isEmpty() -> "No location data for this day"
                s.tripCount == 0 -> "Stayed put"
                else -> "${s.tripCount} ${if (s.tripCount == 1) "trip" else "trips"} · " +
                    "${formatDistance(s.distanceMeters, imperial)} · ${formatDuration(s.movingMs)} moving"
            }
            Text(headline, style = MaterialTheme.typography.titleSmall)
            if (s.placesVisited.isNotEmpty()) {
                Text(
                    text = "Visited " + s.placesVisited.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (showRaw) "$rawCount raw fixes · $totalRecordCount stored in all" else "Show raw fixes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(checked = showRaw, onCheckedChange = onShowRawChange)
            }
        }
    }
}

@Composable
private fun ColumnScope.EmptyDay(isToday: Boolean) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isToday) "Nothing recorded yet today" else "Nothing recorded this day",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "History is kept for $RETENTION_DAYS days. A phone that is off, asleep or " +
                "has sharing paused records nothing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// Timeline rows
// ---------------------------------------------------------------------------

@Composable
private fun EntryRow(
    icon: @Composable () -> Unit,
    title: String,
    detail: String,
    isSelected: Boolean,
    onClick: (() -> Unit)?,
    dimmed: Boolean = false,
    extra: @Composable (ColumnScope.() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(modifier = Modifier.width(32.dp), contentAlignment = Alignment.TopCenter) { icon() }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            extra?.invoke(this)
        }
    }
}

@Composable
private fun StayRow(
    stay: Stay,
    suggestion: PlaceSuggestion?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onName: (PlaceSuggestion) -> Unit,
    onDismiss: (PlaceSuggestion) -> Unit
) {
    val named = stay.place != null
    val range = if (stay.ongoing) "since ${clock(stay.startMs)} · now"
    else "${clock(stay.startMs)} – ${clock(stay.endMs)} · ${formatDuration(stay.endMs - stay.startMs)}"
    EntryRow(
        icon = {
            Icon(
                imageVector = if (named) Icons.Default.Home else Icons.Default.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = stay.place?.name ?: "Stopped here",
        detail = range,
        isSelected = isSelected,
        onClick = onClick
    ) {
        if (suggestion != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Seen here on ${suggestion.dayCount} different days. Want to name it?",
                style = MaterialTheme.typography.bodySmall
            )
            Row {
                TextButton(onClick = { onName(suggestion) }) { Text("Name this place") }
                TextButton(onClick = { onDismiss(suggestion) }) { Text("Not now") }
            }
        }
    }
}

@Composable
private fun TripRow(trip: Trip, imperial: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    val driving = (trip.maxSpeedMs ?: 0f) >= DRIVE_SPEED_MS
    val top = trip.maxSpeedMs?.takeIf { it > 1f }?.let { " · top ${formatSpeed(it, imperial)}" }.orEmpty()
    val time = if (trip.ongoing) "since ${clock(trip.startMs)} · now"
    else "${clock(trip.startMs)} – ${clock(trip.endMs)}"
    EntryRow(
        icon = {
            Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        },
        title = (if (driving) "Drive" else "Trip") + " · ${formatDistance(trip.distanceMeters, imperial)}",
        detail = "$time · ${formatDuration(trip.endMs - trip.startMs)}$top",
        isSelected = isSelected,
        onClick = onClick
    )
}

@Composable
private fun GapRow(gap: Gap) {
    EntryRow(
        icon = {
            Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        },
        title = "No updates for ${formatDuration(gap.endMs - gap.startMs)}",
        detail = "${clock(gap.startMs)} – ${clock(gap.endMs)} · phone offline, asleep or sharing paused",
        isSelected = false,
        onClick = null,
        dimmed = true
    )
}

@Composable
private fun ColumnScope.RawFixList(fixes: List<MemberLocation>, imperial: Boolean) {
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
        // The database returns newest first, which is what a feed wants.
        itemsIndexed(fixes, key = { _, f -> f.timestamp }) { _, loc ->
            RawFixRow(loc, imperial)
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
private fun RawFixRow(location: MemberLocation, imperial: Boolean) {
    val speedMs = location.speed ?: 0f
    val moving = speedMs > 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(location.timestamp)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = if (moving) "Moving ${formatSpeed(speedMs, imperial)}" else "Stationary",
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = "±${location.accuracy.toInt()}m",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// Map
// ---------------------------------------------------------------------------

@Composable
private fun RouteMapView(
    timeline: DayTimeline,
    rawFixes: List<MemberLocation>,
    showRaw: Boolean,
    selectedIndex: Int?,
    accentColor: Int,
    lastSeenColor: Int,
    description: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val mapView = remember {
        MapView(context).apply {
            setTileSource(OSM_TILES)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(37.7749, -122.4194))
        }
    }

    LaunchedEffect(timeline, rawFixes, showRaw, selectedIndex) {
        mapView.overlays.clear()
        val bounds = mutableListOf<GeoPoint>()

        if (showRaw) {
            val points = rawFixes.asReversed().map { GeoPoint(it.latitude, it.longitude) }
            if (points.size >= 2) {
                mapView.overlays.add(route(mapView, points, accentColor, 8f, 255))
            }
            bounds.addAll(points)
        } else {
            var stayNumber = 0
            timeline.entries.forEachIndexed { index, entry ->
                val faded = selectedIndex != null && selectedIndex != index
                when (entry) {
                    is Trip -> {
                        val points = entry.points.map { GeoPoint(it.latitude, it.longitude) }
                        mapView.overlays.add(
                            route(mapView, points, accentColor, if (selectedIndex == index) 12f else 8f, if (faded) 70 else 255)
                        )
                        if (selectedIndex == null || selectedIndex == index) bounds.addAll(points)
                    }
                    is Stay -> {
                        stayNumber++
                        val point = GeoPoint(entry.latitude, entry.longitude)
                        mapView.overlays.add(Marker(mapView).apply {
                            position = point
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = entry.place?.name ?: "Stop $stayNumber"
                            icon = numberedDot(
                                context, stayNumber.toString(),
                                if (entry.ongoing) lastSeenColor else accentColor,
                                large = selectedIndex == index,
                                alpha = if (faded) 90 else 255
                            )
                        })
                        if (selectedIndex == null || selectedIndex == index) bounds.add(point)
                    }
                    is Gap -> Unit
                }
            }
        }

        mapView.post {
            when {
                bounds.isEmpty() -> Unit
                bounds.size == 1 || bounds.all { it == bounds.first() } -> {
                    mapView.controller.animateTo(bounds.first())
                    mapView.controller.setZoom(16.0)
                }
                else -> mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(bounds), true, 100)
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
            // Absorb requestLayout() from the MapView so its internal pan/zoom layout requests
            // do not propagate up into Compose and make the surrounding UI blink.
            object : FrameLayout(ctx) {
                override fun requestLayout() {
                    forceLayout()
                }
            }.apply {
                addView(mapView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
        },
        modifier = modifier.semantics { contentDescription = description }
    )
}

private fun route(mapView: MapView, points: List<GeoPoint>, color: Int, width: Float, alpha: Int) =
    Polyline(mapView).apply {
        setPoints(points)
        outlinePaint.color = ColorUtils.setAlphaComponent(color, alpha)
        outlinePaint.strokeWidth = width
        outlinePaint.isAntiAlias = true
    }

private fun numberedDot(context: Context, label: String, fill: Int, large: Boolean, alpha: Int): Drawable {
    val dp = context.resources.displayMetrics.density
    val size = ((if (large) 36 else 28) * dp).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f
    paint.color = ColorUtils.setAlphaComponent(android.graphics.Color.WHITE, alpha)
    canvas.drawCircle(r, r, r, paint)
    paint.color = ColorUtils.setAlphaComponent(fill, alpha)
    canvas.drawCircle(r, r, r - 2 * dp, paint)
    paint.color = android.graphics.Color.WHITE
    paint.textAlign = Paint.Align.CENTER
    paint.isFakeBoldText = true
    paint.textSize = size * 0.48f
    canvas.drawText(label, r, r - (paint.descent() + paint.ascent()) / 2f, paint)
    return BitmapDrawable(context.resources, bmp)
}

// ---------------------------------------------------------------------------
// Formatting and date helpers
// ---------------------------------------------------------------------------

private fun clock(ms: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))

internal fun formatDuration(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L && m == 0L -> "under a minute"
        h == 0L -> "$m min"
        m == 0L -> "$h hr"
        else -> "$h hr $m min"
    }
}

internal fun formatDistance(meters: Double, imperial: Boolean): String =
    if (imperial) {
        val miles = meters / 1609.344
        if (miles < 10) String.format(Locale.US, "%.1f mi", miles) else "${miles.toInt()} mi"
    } else {
        val km = meters / 1000.0
        if (km < 10) String.format(Locale.US, "%.1f km", km) else "${km.toInt()} km"
    }

private fun formatSpeed(ms: Float, imperial: Boolean): String =
    if (imperial) "${(ms * 2.237f).toInt()} mph" else "${(ms * 3.6f).toInt()} km/h"

/** The date picker works in UTC midnights; the screen works in local ones. Same calendar day. */
private fun localMidnightToUtc(localMidnight: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMidnight }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local[Calendar.YEAR], local[Calendar.MONTH], local[Calendar.DAY_OF_MONTH])
    }.timeInMillis
}

private fun utcToLocalMidnight(utc: Long): Long {
    val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
    return Calendar.getInstance().apply {
        clear()
        set(u[Calendar.YEAR], u[Calendar.MONTH], u[Calendar.DAY_OF_MONTH])
    }.timeInMillis
}
