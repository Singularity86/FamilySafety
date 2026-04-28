package com.example.familysafety.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
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
import androidx.core.graphics.ColorUtils
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import com.example.familysafety.geofence.GeofenceZone
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

private val MAP_TILES = object : OnlineTileSourceBase(
    "OsmStandard", 0, 19, 256, ".png",
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

@Composable
fun MapScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    geofences: List<GeofenceZone> = emptyList(),
    onLongPressMap: (GeoPoint) -> Unit = {}
) {
    val context = LocalContext.current

    // --- Permission state ---
    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    var permissionGranted by remember { mutableStateOf(hasLocationPermission()) }
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
    val memberAvatars by viewModel.memberAvatars.collectAsState()

    val density = context.resources.displayMetrics.density
    val markerSizePx = (52 * density).toInt()
    val dotSizePx = (18 * density).toInt()

    val scope = rememberCoroutineScope()
    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadTask by remember { mutableStateOf<CacheManager.CacheManagerTask?>(null) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloadTotal by remember { mutableIntStateOf(0) }
    var downloadErrorMessage by remember { mutableStateOf<String?>(null) }
    val isDownloading = downloadTask != null

    // Cancel any in-progress download if the composable is removed from composition.
    DisposableEffect(Unit) {
        onDispose { downloadTask?.cancel(true) }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(MAP_TILES)
            setMultiTouchControls(true)
            controller.setZoom(2.0)
            controller.setCenter(GeoPoint(0.0, 0.0))
        }
    }
    val cacheManager = remember(mapView) { CacheManager(mapView) }

    // Blue dot for own location — replaces the default person icon.
    val myLocationOverlay = remember {
        val dot = blueDotBitmap(dotSizePx)
        MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
            setPersonIcon(dot)
            setPersonHotspot(dot.width / 2f, dot.height / 2f)
        }
    }

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
        hasCentered.value = true
        val points = memberLocations.values.map { GeoPoint(it.latitude, it.longitude) }
        mapView.post {
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
        }
    }

    // Pan to a specific member when selected from the Members tab.
    LaunchedEffect(focusedMemberId) {
        val id = focusedMemberId ?: return@LaunchedEffect
        val loc = memberLocations[id] ?: return@LaunchedEffect
        mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
        mapView.controller.setZoom(16.0)
        viewModel.clearFocus()
    }

    // Add long-press overlay once (stable reference via remember)
    val longPressOverlay = remember(onLongPressMap) {
        LongPressOverlay(onLongPressMap)
    }
    LaunchedEffect(longPressOverlay) {
        mapView.overlays.removeAll { it is LongPressOverlay }
        mapView.overlays.add(0, longPressOverlay)
    }

    // Draw geofence zone circles
    LaunchedEffect(geofences) {
        mapView.overlays.removeAll { it is Polygon }
        for (zone in geofences) {
            val center = GeoPoint(zone.latitude, zone.longitude)
            val circlePoints = Polygon.pointsAsCircle(center, zone.radiusMeters.toDouble())
            val color = androidx.core.graphics.ColorUtils.HSLToColor(
                floatArrayOf(zone.colorHue, 0.6f, 0.5f)
            )
            val fillColor = (color and 0x00FFFFFF) or (0x33 shl 24) // ~20% alpha
            val polygon = Polygon(mapView).apply {
                points = circlePoints
                fillPaint.color = fillColor
                outlinePaint.color = color
                outlinePaint.strokeWidth = 3f
            }
            mapView.overlays.add(polygon)
        }
        mapView.invalidate()
    }

    // Rebuild member markers whenever locations, members, or avatars change.
    LaunchedEffect(memberLocations, familyMembers, memberAvatars) {
        mapView.overlays.removeAll { it is Marker }
        memberLocations.forEach { (memberId, location) ->
            val member = familyMembers.find { it.memberId == memberId }
            val avatar = memberAvatars[memberId]
            val bmp = memberMarkerBitmap(
                displayName = member?.displayName ?: "?",
                memberId = memberId,
                avatar = avatar,
                sizePx = markerSizePx,
                colorHue = member?.colorHue
            )
            val marker = Marker(mapView).apply {
                position = GeoPoint(location.latitude, location.longitude)
                title = member?.displayName ?: memberId
                snippet = "Updated ${getTimeAgo(location.timestamp)} · ±${location.accuracy.toInt()}m"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = BitmapDrawable(context.resources, bmp)
            }
            mapView.overlays.add(marker)
        }
        mapView.invalidate()
    }

    // Download-confirmation dialog
    if (showDownloadDialog) {
        val bbox = mapView.boundingBox
        // Start 3 zoom levels above the current view (enough context to pan around)
        // down to full street detail. Anchoring to the current zoom instead of 0
        // keeps tile counts manageable — zoom 0–current adds millions of useless
        // global tiles the user will never need offline.
        val zoomMin = (mapView.zoomLevel - 3).coerceAtLeast(5)
        val zoomMax = 17
        val tileCount = cacheManager.possibleTilesInArea(bbox, zoomMin, zoomMax)
        // Rough estimate: OSM tiles average ~15 KB each.
        val estimatedMb = tileCount * 15 / 1024
        val isLarge  = estimatedMb > 80   // warn but still allow
        val tooMany  = tileCount > 100_000 // block — unreasonably large

        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text("Download for offline") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Cache all zoom levels for this area so the map works without internet.")
                    Text(
                        text = when {
                            tooMany  -> "~$tileCount tiles (~${estimatedMb} MB) — zoom in to a smaller area first."
                            isLarge  -> "~$tileCount tiles · ~${estimatedMb} MB · zoom $zoomMin–$zoomMax"
                            else     -> "~$tileCount tiles · ~${estimatedMb} MB · zoom $zoomMin–$zoomMax"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            tooMany -> MaterialTheme.colorScheme.error
                            isLarge -> MaterialTheme.colorScheme.onSurfaceVariant
                            else    -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (isLarge && !tooMany) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = "Large download — make sure you're on Wi-Fi.",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !tooMany,
                    onClick = {
                        showDownloadDialog = false
                        downloadProgress = 0
                        downloadTotal = 0
                        downloadTask = cacheManager.downloadAreaAsyncNoUI(
                            context, bbox, zoomMin, zoomMax,
                            object : CacheManager.CacheManagerCallback {
                                override fun onTaskComplete() {
                                    scope.launch {
                                        // Clear the in-memory tile LRU so the map re-fetches
                                        // from the SQL cache rather than serving stale nulls.
                                        mapView.tileProvider.clearTileCache()
                                        mapView.invalidate()
                                        downloadTask = null
                                    }
                                }
                                override fun onTaskFailed(errors: Int) {
                                    scope.launch {
                                        mapView.tileProvider.clearTileCache()
                                        mapView.invalidate()
                                        if (errors > 0) {
                                            downloadErrorMessage = "$errors tile(s) failed to download"
                                        }
                                        downloadTask = null
                                    }
                                }
                                override fun updateProgress(p: Int, cz: Int, zm: Int, zx: Int) {
                                    scope.launch { downloadProgress = p }
                                }
                                override fun downloadStarted() {}
                                override fun setPossibleTilesInArea(t: Int) {
                                    scope.launch { downloadTotal = t }
                                }
                            }
                        )
                    }
                ) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Auto-dismiss error banner after 4 s. Must be unconditional (outside Box) so
    // the composable call order stays stable regardless of whether there's an error.
    LaunchedEffect(downloadErrorMessage) {
        if (downloadErrorMessage != null) {
            kotlinx.coroutines.delay(4_000)
            downloadErrorMessage = null
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (isDownloading) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 80.dp)
                    .widthIn(min = 180.dp, max = 260.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 4.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (downloadTotal > 0)
                                "Downloading… $downloadProgress/$downloadTotal"
                            else
                                "Downloading tiles…",
                            style = MaterialTheme.typography.bodySmall
                        )
                        IconButton(
                            onClick = {
                                downloadTask?.cancel(true)
                                downloadTask = null
                            },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel download",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    val fraction = if (downloadTotal > 0) downloadProgress.toFloat() / downloadTotal else 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        } else {
            SmallFloatingActionButton(
                onClick = { showDownloadDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 80.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = "Download for offline")
            }
        }

        // Brief error banner shown when some tiles fail to download
        if (downloadErrorMessage != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp, start = 16.dp, end = 16.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = 4.dp
            ) {
                Text(
                    text = downloadErrorMessage!!,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/** Small blue dot used for the device's own GPS location. */
private fun blueDotBitmap(sizePx: Int): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = sizePx / 2f
    // White ring
    paint.color = Color.WHITE
    canvas.drawCircle(r, r, r, paint)
    // Blue fill
    paint.color = Color.parseColor("#4A8FE7")
    canvas.drawCircle(r, r, r * 0.62f, paint)
    return bmp
}

/**
 * Teardrop-style map pin matching the Life360 aesthetic:
 * coloured accent ring → white inner ring → photo or initials,
 * with a downward-pointing tail and a soft drop shadow.
 *
 * The bitmap is (sizePx) wide and (sizePx + tailHeight) tall.
 * The geographical anchor point is the very bottom tip of the tail.
 */
private fun memberMarkerBitmap(
    displayName: String,
    memberId: String,
    avatar: Bitmap?,
    sizePx: Int,
    colorHue: Float? = null
): Bitmap {
    val tailH  = (sizePx * 0.34f).toInt()
    val totalH = sizePx + tailH + 4          // +4 px headroom for shadow bleed

    val bmp    = Bitmap.createBitmap(sizePx, totalH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val r      = sizePx / 2f
    val cx     = r
    val cy     = r                            // centre of the circle within the bitmap
    val border = (sizePx * 0.10f).coerceAtLeast(3f)
    val tipY   = cy + r + tailH.toFloat()    // tip of the pin tail

    val hue         = colorHue ?: ((memberId.hashCode().toLong() and 0xFFFFFFFFL) % 360).toFloat()
    val accentColor = ColorUtils.HSLToColor(floatArrayOf(hue, 0.70f, 0.50f))

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Build the balloon-pin outline path:
    // arc from 120° → 60° (300° sweep, covering the top) then lines to pin tip.
    fun pinPath(radius: Float, tip: Float) = android.graphics.Path().apply {
        val x0 = cx + radius * Math.cos(Math.toRadians(120.0)).toFloat()
        val y0 = cy + radius * Math.sin(Math.toRadians(120.0)).toFloat()
        moveTo(x0, y0)
        arcTo(
            android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius),
            120f, 300f, false
        )
        lineTo(cx, tip)
        close()
    }

    // 1 — Soft drop shadow (blur behind the pin)
    paint.color = Color.argb(55, 0, 0, 0)
    paint.maskFilter = android.graphics.BlurMaskFilter(
        sizePx * 0.09f, android.graphics.BlurMaskFilter.Blur.NORMAL
    )
    canvas.save()
    canvas.translate(2f, 3f)
    canvas.drawPath(pinPath(r * 0.97f, tipY - 2f), paint)
    canvas.restore()
    paint.maskFilter = null

    // 2 — Accent-coloured outer pin
    paint.color = accentColor
    canvas.drawPath(pinPath(r, tipY), paint)

    // 3 — White inner circle (creates the border ring effect)
    paint.color = Color.WHITE
    canvas.drawCircle(cx, cy, r - border, paint)

    // 4 — Avatar photo or coloured initials
    val innerR = r - border
    if (avatar != null) {
        val sz     = (innerR * 2).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(avatar, sz, sz, true)
        val shader = android.graphics.BitmapShader(
            scaled,
            android.graphics.Shader.TileMode.CLAMP,
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.shader = shader
        canvas.save()
        canvas.translate(cx - innerR, cy - innerR)
        canvas.drawCircle(innerR, innerR, innerR, paint)
        canvas.restore()
        paint.shader = null
    } else {
        val initials = displayName.trim()
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifEmpty { "?" }

        // Fill slightly lighter than the accent ring so it reads well
        paint.color = ColorUtils.HSLToColor(floatArrayOf(hue, 0.65f, 0.58f))
        canvas.drawCircle(cx, cy, innerR, paint)

        paint.color = Color.WHITE
        paint.textSize = innerR * 0.74f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val textY = cy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initials, cx, textY, paint)
    }

    return bmp
}

/** Detects a 600 ms long-press on the map and calls [onLongPress] with the tapped GeoPoint. */
private class LongPressOverlay(
    private val onLongPress: (GeoPoint) -> Unit
) : Overlay() {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val geoPoint = mapView.projection.fromPixels(
                    event.x.toInt(), event.y.toInt()
                ) as GeoPoint
                pendingRunnable = Runnable { onLongPress(geoPoint) }
                handler.postDelayed(pendingRunnable!!, 600)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_MOVE -> {
                pendingRunnable?.let { handler.removeCallbacks(it) }
                pendingRunnable = null
            }
        }
        return false // don't consume — allow normal map panning
    }
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
