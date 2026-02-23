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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.compose.material.icons.filled.FileDownload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

private fun cartoTileSource(style: String, name: String) =
    object : OnlineTileSourceBase(
        name, 0, 19, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/$style/",
            "https://b.basemaps.cartocdn.com/$style/",
            "https://c.basemaps.cartocdn.com/$style/",
            "https://d.basemaps.cartocdn.com/$style/"
        ),
        "© OpenStreetMap contributors © CARTO"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl +
                MapTileIndex.getZoom(pMapTileIndex) + "/" +
                MapTileIndex.getX(pMapTileIndex) + "/" +
                MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
    }

/** Light tiles — similar to Google Maps. */
private val CARTO_VOYAGER = cartoTileSource("rastertiles/voyager", "CartoVoyager")

/** Dark tiles — for dark mode. */
private val CARTO_DARK = cartoTileSource("dark_matter", "CartoDark")

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
    val isDark = isSystemInDarkTheme()
    val memberLocations by viewModel.memberLocations.collectAsState()
    val familyMembers by viewModel.familyMembers.collectAsState()
    val focusedMemberId by viewModel.focusedMemberId.collectAsState()
    val memberAvatars by viewModel.memberAvatars.collectAsState()

    val density = context.resources.displayMetrics.density
    val markerSizePx = (44 * density).toInt()
    val dotSizePx = (18 * density).toInt()

    val scope = rememberCoroutineScope()
    var showDownloadDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(if (isDark) CARTO_DARK else CARTO_VOYAGER)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(37.7749, -122.4194))
        }
    }
    val cacheManager = remember(mapView) { CacheManager(mapView) }

    // Switch tile source live when system theme changes
    LaunchedEffect(isDark) {
        mapView.setTileSource(if (isDark) CARTO_DARK else CARTO_VOYAGER)
        mapView.invalidate()
    }

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
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = BitmapDrawable(context.resources, bmp)
            }
            mapView.overlays.add(marker)
        }
        mapView.invalidate()
    }

    // Download-confirmation dialog
    if (showDownloadDialog) {
        val bbox = mapView.boundingBox
        val zoomCur = mapView.zoomLevelDouble.toInt()
        val zoomMin = zoomCur.coerceAtLeast(8)
        val zoomMax = (zoomCur + 2).coerceAtMost(17)
        val tileCount = cacheManager.possibleTilesInArea(bbox, zoomMin, zoomMax)
        val tooMany = tileCount > 5_000

        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text("Download for offline") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cache map tiles for the current view so they're available without internet.")
                    Text(
                        text = if (tooMany)
                            "~$tileCount tiles needed — zoom in for a smaller area."
                        else
                            "~$tileCount tiles · zoom $zoomMin–$zoomMax",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tooMany)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !tooMany,
                    onClick = {
                        showDownloadDialog = false
                        isDownloading = true
                        scope.launch {
                            val done = CompletableDeferred<Unit>()
                            cacheManager.downloadAreaAsync(
                                context, bbox, zoomMin, zoomMax,
                                object : CacheManager.CacheManagerCallback {
                                    override fun onTaskComplete() { done.complete(Unit) }
                                    override fun onTaskFailed(errors: Int) { done.complete(Unit) }
                                    override fun updateProgress(p: Int, cz: Int, zm: Int, zx: Int) {}
                                    override fun downloadStarted() {}
                                    override fun setPossibleTilesInArea(t: Int) {}
                                }
                            )
                            done.await()
                            isDownloading = false
                        }
                    }
                ) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDialog = false }) { Text("Cancel") }
            }
        )
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (isDownloading) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 80.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Downloading tiles…", style = MaterialTheme.typography.bodySmall)
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
 * Circular marker bitmap matching the Members tab avatar style:
 * photo if available, otherwise coloured initials circle.
 */
private fun memberMarkerBitmap(
    displayName: String,
    memberId: String,
    avatar: Bitmap?,
    sizePx: Int,
    colorHue: Float? = null
): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val r = sizePx / 2f
    val border = (sizePx * 0.09f).coerceAtLeast(3f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    if (avatar != null) {
        // White border
        paint.color = Color.WHITE
        canvas.drawCircle(r, r, r, paint)
        // Scale avatar into inner circle
        val inner = (r - border).toInt()
        val scaled = Bitmap.createScaledBitmap(avatar, inner * 2, inner * 2, true)
        val shader = android.graphics.BitmapShader(
            scaled,
            android.graphics.Shader.TileMode.CLAMP,
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.shader = shader
        canvas.save()
        canvas.translate(border, border)
        canvas.drawCircle(inner.toFloat(), inner.toFloat(), inner.toFloat(), paint)
        canvas.restore()
        paint.shader = null
    } else {
        val initials = displayName.trim()
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifEmpty { "?" }

        // Use member's chosen hue, or derive from memberId hash (same as MemberAvatar).
        val hue = colorHue ?: ((memberId.hashCode().toLong() and 0xFFFFFFFFL) % 360).toFloat()
        val bgColor = ColorUtils.HSLToColor(floatArrayOf(hue, 0.55f, 0.45f))

        // White border
        paint.color = Color.WHITE
        canvas.drawCircle(r, r, r, paint)
        // Coloured fill
        paint.color = bgColor
        canvas.drawCircle(r, r, r - border, paint)
        // Initials text
        paint.color = Color.WHITE
        paint.textSize = sizePx * 0.36f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val textY = r - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initials, r, textY, paint)
    }
    return bmp
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
