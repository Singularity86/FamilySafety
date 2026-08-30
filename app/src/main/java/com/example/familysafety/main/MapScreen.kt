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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
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
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.familysafety.BuildConfig
import com.example.familysafety.geofence.GeofenceZone
import com.example.familysafety.location.MemberLocation
import com.example.familysafety.ui.theme.AmberWarning
import kotlin.math.ceil
import kotlin.math.log2
import kotlinx.coroutines.launch
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
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
import timber.log.Timber

/** Beyond this age, a member marker is dimmed to signal it's no longer fresh. */
private const val STALE_LOCATION_THRESHOLD_MS = 30 * 60_000L

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
    onNavigateToZones: () -> Unit = {},
    onEdgeSwipeNext: () -> Unit = {}
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
                text = "Jibaro Family Safety needs access to your location to show you and your family members on the map.",
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
    var savedMapLatitude by rememberSaveable { mutableStateOf(0.0) }
    var savedMapLongitude by rememberSaveable { mutableStateOf(0.0) }
    var savedMapZoom by rememberSaveable { mutableStateOf(2.0) }
    var hasSavedMapViewport by rememberSaveable { mutableStateOf(false) }
    // Which pins cover each other up is a question about the screen, so it changes with
    // zoom and not with panning — two pins a pixel apart stay a pixel apart however far
    // the map scrolls. Held apart from savedMapZoom so the marker rebuild can key on zoom
    // alone and stay still through a scroll.
    var mapZoom by remember { mutableStateOf(savedMapZoom) }

    // Cancel any in-progress download if the composable is removed from composition.
    DisposableEffect(Unit) {
        onDispose { downloadTask?.cancel(true) }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(MAP_TILES)
            setMultiTouchControls(true)
            confineToSingleWorld(context.resources.displayMetrics)
            controller.setZoom(savedMapZoom)
            controller.setCenter(GeoPoint(savedMapLatitude, savedMapLongitude))
        }
    }
    val cacheManager = remember(mapView) { CacheManager(mapView) }

    fun rememberCurrentMapViewport() {
        val center = mapView.mapCenter
        savedMapLatitude = center.latitude
        savedMapLongitude = center.longitude
        savedMapZoom = mapView.zoomLevelDouble
        mapZoom = mapView.zoomLevelDouble
        hasSavedMapViewport = true
    }

    DisposableEffect(mapView) {
        val mapListener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                rememberCurrentMapViewport()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                rememberCurrentMapViewport()
                return false
            }
        }
        mapView.addMapListener(mapListener)
        onDispose {
            mapView.removeMapListener(mapListener)
        }
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
        if (BuildConfig.DEBUG) {
            Timber.d("Map lifecycle: resume")
        }
        mapView.onResume()
        onDispose {
            if (BuildConfig.DEBUG) {
                Timber.d("Map lifecycle: pause/detach")
            }
            myLocationOverlay.disableMyLocation()
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // Auto-center on the group the first time we have locations.
    val hasCentered = rememberSaveable { mutableStateOf(hasSavedMapViewport) }
    LaunchedEffect(memberLocations) {
        if (hasCentered.value || memberLocations.isEmpty()) return@LaunchedEffect
        hasCentered.value = true
        val points = memberLocations.values.map { GeoPoint(it.latitude, it.longitude) }
        mapView.post {
            when {
                points.size == 1 -> {
                    mapView.controller.animateTo(points.first())
                    mapView.controller.setZoom(15.0)
                    rememberCurrentMapViewport()
                }
                points.size > 1 -> {
                    val box = BoundingBox.fromGeoPoints(points)
                    mapView.zoomToBoundingBox(box, true, 150)
                    rememberCurrentMapViewport()
                }
            }
        }
    }

    // Whose pin should sit above the others. Kept separately from focusedMemberId because
    // that flag is consumed the moment the pan starts (so a second "Show on map" tap
    // re-pans instead of being swallowed as an unchanged key), whereas the pin has to stay
    // on top after the pan finishes — otherwise the member you just asked to see is still
    // hidden underneath whoever happens to be standing next to them.
    var raisedMemberId by remember { mutableStateOf<String?>(null) }

    // Pan to a specific member when selected from the Members tab.
    LaunchedEffect(focusedMemberId) {
        val id = focusedMemberId ?: return@LaunchedEffect
        val loc = memberLocations[id] ?: return@LaunchedEffect
        raisedMemberId = id
        mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
        mapView.controller.setZoom(16.0)
        rememberCurrentMapViewport()
        viewModel.clearFocus()
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

    // How close two pins have to be, in pixels, before one hides the other. A pin is
    // markerSizePx across, so centres nearer than about a pin-width overlap; 0.9 leaves a
    // sliver of daylight rather than waiting for a total eclipse.
    val clusterThresholdPx = markerSizePx * 0.9

    // The people currently sharing a patch of screen, and which of those groups is open.
    var pinClusters by remember { mutableStateOf<List<PinCluster>>(emptyList()) }
    var expandedClusterKey by remember { mutableStateOf<String?>(null) }
    // Shown once, the first time a group ever appears on this device, and never again —
    // a grouped pin is not self-explanatory the first time but is obvious the second.
    var showClusterTutorial by remember { mutableStateOf(false) }
    var clusterTutorialSeen by remember { mutableStateOf(hasSeenClusterTutorial(context)) }
    // Which raised member a group was already opened for. Without this, closing the card
    // while that member is still raised would reopen it on the very next rebuild.
    var autoExpandedFor by remember { mutableStateOf<String?>(null) }

    // Rebuild member markers whenever locations, members, or avatars change — or when a
    // different member is raised, since draw order is decided here — or when the zoom
    // changes, since that is what decides who is covering whom.
    LaunchedEffect(
        memberLocations, familyMembers, memberAvatars, raisedMemberId, mapZoom, expandedClusterKey
    ) {
        mapView.overlays.removeAll { it is Marker }
        // osmdroid draws overlays in list order, so whatever is added last ends up on top.
        // Sorting the raised member to the end is what puts their pin above pins that
        // overlap it; everyone else keeps their existing relative order.
        // Only draw people who are actually in the family. A location whose member is
        // unknown used to render as an anonymous "?" pin at a stale position — the residue of
        // a departed member, a recreated family, or a roster this device has not caught up
        // with. A pin nobody can identify is worse than no pin.
        val drawOrder = memberLocations.entries
            .filter { entry -> familyMembers.any { it.memberId == entry.key } }
            .sortedBy { it.key == raisedMemberId }

        val clusters = clusterPins(
            points = drawOrder.map { (memberId, location) ->
                PinPoint(memberId, location.latitude, location.longitude)
            },
            zoom = mapZoom,
            thresholdPx = clusterThresholdPx.toDouble()
        )
        pinClusters = clusters

        // A group that no longer exists cannot stay open: zooming in until people separate
        // has to put the card away, or it outlives the thing it was describing.
        if (expandedClusterKey != null && clusters.none { it.key == expandedClusterKey }) {
            expandedClusterKey = null
        }
        // First group this device has ever drawn: say what it is, once.
        if (!clusterTutorialSeen && clusters.any { !it.isSingle }) {
            clusterTutorialSeen = true
            showClusterTutorial = true
            markClusterTutorialSeen(context)
        }

        // Asking to see someone who turns out to be in a huddle should say who they are
        // huddled with, rather than drop a bubble on the map and leave them to guess.
        if (raisedMemberId != null && raisedMemberId != autoExpandedFor) {
            autoExpandedFor = raisedMemberId
            clusters.find { raisedMemberId in it.memberIds && !it.isSingle }?.let {
                expandedClusterKey = it.key
            }
        }

        // Dim (not alarm-color) a marker once its location is old enough that it's no
        // longer a good stand-in for "live" — well past the ~5 min stationary heartbeat
        // interval, so a few missed cycles are still shown as fresh.
        fun isStale(location: MemberLocation) =
            System.currentTimeMillis() - location.timestamp > STALE_LOCATION_THRESHOLD_MS

        clusters
            .sortedBy { raisedMemberId != null && raisedMemberId in it.memberIds }
            .forEach { cluster ->
                if (cluster.isSingle) {
                    val memberId = cluster.memberIds.first()
                    val location = memberLocations[memberId] ?: return@forEach
                    val member = familyMembers.find { it.memberId == memberId }
                    val bmp = memberMarkerBitmap(
                        displayName = member?.displayName ?: "?",
                        memberId = memberId,
                        avatar = memberAvatars[memberId],
                        sizePx = markerSizePx,
                        colorHue = member?.colorHue
                    )
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(location.latitude, location.longitude)
                        title = member?.displayName ?: memberId
                        snippet = "Updated ${getTimeAgo(location.timestamp)} · ±${location.accuracy.toInt()}m"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        icon = BitmapDrawable(context.resources, bmp).apply {
                            alpha = if (isStale(location)) 140 else 255
                        }
                        setOnMarkerClickListener { _, _ ->
                            viewModel.requestDriveEstimate(memberId)
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                } else {
                    // The raised member leads the row and keeps their place in it however
                    // many people are here — being asked for is exactly the case where
                    // being the face trimmed off the end would be wrong.
                    val ordered = cluster.memberIds.sortedByDescending { it == raisedMemberId }
                    val faces = ordered.take(CLUSTER_FACES_SHOWN).map { memberId ->
                        val member = familyMembers.find { it.memberId == memberId }
                        ClusterFace(
                            memberId = memberId,
                            displayName = member?.displayName ?: "?",
                            avatar = memberAvatars[memberId],
                            colorHue = member?.colorHue,
                            isStale = memberLocations[memberId]?.let { isStale(it) } ?: true
                        )
                    }
                    val bmp = clusterMarkerBitmap(
                        faces = faces,
                        totalCount = cluster.size,
                        sizePx = markerSizePx,
                        highlightMemberId = raisedMemberId,
                        isOpen = cluster.key == expandedClusterKey
                    )
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(cluster.latitude, cluster.longitude)
                        title = "${cluster.size} people here"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        icon = BitmapDrawable(context.resources, bmp)
                        setOnMarkerClickListener { _, _ ->
                            expandedClusterKey =
                                if (expandedClusterKey == cluster.key) null else cluster.key
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                }
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
                            color = AmberWarning.copy(alpha = 0.16f),
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = "Large download — make sure you're on Wi-Fi.",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberWarning
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

    Box(modifier = modifier.clipToBounds()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().clipToBounds())

        var edgeDragTotal by remember { mutableFloatStateOf(0f) }
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp)
                .width(18.dp)
                .height(72.dp),
            shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowLeft,
                    contentDescription = "Swipe from edge",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(1f / 10f)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { edgeDragTotal = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            edgeDragTotal += dragAmount
                            if (edgeDragTotal < -48f) {
                                edgeDragTotal = 0f
                                if (BuildConfig.DEBUG) {
                                    Timber.d("Map edge swipe triggered family tab")
                                }
                                onEdgeSwipeNext()
                            }
                        },
                        onDragEnd = { edgeDragTotal = 0f },
                        onDragCancel = { edgeDragTotal = 0f }
                    )
                }
        )

        val openCluster = pinClusters.find { it.key == expandedClusterKey }

        // The card naming a group sits at the bottom centre, up to 320 dp wide, and these
        // controls are pinned to the same 80 dp on both edges — they only clear each other
        // above 432 dp of screen width, which is no phone in use. So the map controls stand
        // down while a group is open, and come back when it closes.
        val bottomControlsVisible = openCluster == null

        if (bottomControlsVisible && isDownloading) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 80.dp)
                    .widthIn(min = 180.dp, max = 260.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
        } else if (bottomControlsVisible) {
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

        if (bottomControlsVisible) {
            SmallFloatingActionButton(
                onClick = onNavigateToZones,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 80.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Icon(Icons.Default.LocationCity, contentDescription = "Manage zones")
            }
        }

        if (showClusterTutorial) {
            AlertDialog(
                onDismissRequest = { showClusterTutorial = false },
                title = { Text("Two of you are in the same place") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "When people are close enough together that their pins would " +
                                "cover each other up, the map stacks them into one bubble " +
                                "instead of hiding whoever was drawn second.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Tap the bubble to spread the faces out and see who is there. " +
                                "Zoom in and they separate back into their own pins.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "You'll only see this once.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { showClusterTutorial = false }) { Text("Got it") }
                }
            )
        }

        // The opened group. The bubble on the map says how many people are standing
        // together and shows the first few faces; this is where they get their names back,
        // which is the part the map cannot show at pin size.
        if (openCluster != null && !openCluster.isSingle) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp, start = 16.dp, end = 16.dp)
                    .widthIn(max = 320.dp),
                shape = MaterialTheme.shapes.medium,
                // Half-opaque: the card sits over the map it is describing, and the pins
                // and streets underneath it are the context for the names on top.
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 3.dp
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${openCluster.size} people here",
                            style = MaterialTheme.typography.labelLarge
                        )
                        IconButton(
                            onClick = { expandedClusterKey = null },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        openCluster.memberIds.forEach { memberId ->
                            val member = familyMembers.find { it.memberId == memberId }
                            val location = memberLocations[memberId]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.requestDriveEstimate(memberId)
                                        expandedClusterKey = null
                                    }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                MemberAvatar(
                                    displayName = member?.displayName ?: "?",
                                    memberId = memberId,
                                    bitmap = memberAvatars[memberId],
                                    colorHue = member?.colorHue,
                                    size = 28.dp
                                )
                                Column {
                                    Text(
                                        text = member?.displayName ?: memberId,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (location != null) {
                                        Text(
                                            text = "Updated ${getTimeAgo(location.timestamp)} · ±${location.accuracy.toInt()}m",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
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
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
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

/**
 * Stops the map from drawing repeated copies of the world.
 *
 * osmdroid wraps tiles edge-to-edge in both axes by default, so at low zoom —
 * including the zoom 2 we start at before any member location arrives — the
 * world is narrower/shorter than the screen and gets tiled two or three times
 * across and down it. Turning repetition off leaves grey voids instead, so we
 * also clamp panning to the world bounds and raise the minimum zoom to the
 * smallest level at which a single world still covers the screen.
 */
private fun MapView.confineToSingleWorld(metrics: android.util.DisplayMetrics) {
    setHorizontalMapRepetitionEnabled(false)
    setVerticalMapRepetitionEnabled(false)
    val tileSystem = MapView.getTileSystem()
    setScrollableAreaLimitLatitude(tileSystem.maxLatitude, tileSystem.minLatitude, 0)
    setScrollableAreaLimitLongitude(tileSystem.minLongitude, tileSystem.maxLongitude, 0)
    // Tiles are 256 px and are not DPI-scaled, so the world spans 256 * 2^zoom px.
    val longestEdgePx = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(256)
    val minZoom = ceil(log2(longestEdgePx / 256.0))
    setMinZoomLevel(minZoom.coerceIn(3.0, 6.0))
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
internal fun memberMarkerBitmap(
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

    // 3 & 4 — The face: white ring, then the avatar photo or coloured initials.
    // Shared with the cluster bubble, so the small faces in a bubble are literally the
    // same drawing at a smaller radius rather than a lookalike that can drift from it.
    drawMemberDisc(
        canvas = canvas,
        cx = cx,
        cy = cy,
        radius = r,
        ringWidth = border,
        displayName = displayName,
        hue = hue,
        avatar = avatar,
        drawRing = false
    )

    return bmp
}

/**
 * Draws one member's circular face: an accent ring with their photo or initials inside.
 *
 * [drawRing] is false when the caller has already painted the accent shape underneath —
 * the single pin's balloon body is that ring, so drawing it again would only cost fill.
 */
private fun drawMemberDisc(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    radius: Float,
    ringWidth: Float,
    displayName: String,
    hue: Float,
    avatar: Bitmap?,
    drawRing: Boolean = true,
    highlight: Boolean = false
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val accentColor = ColorUtils.HSLToColor(floatArrayOf(hue, 0.70f, 0.50f))

    if (drawRing) {
        paint.color = accentColor
        canvas.drawCircle(cx, cy, radius, paint)
    }

    // White under the photo, so a transparent avatar reads as a face and not as a hole.
    paint.color = Color.WHITE
    canvas.drawCircle(cx, cy, radius - ringWidth, paint)

    val innerR = radius - ringWidth
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

    if (highlight) {
        // The member someone just asked to see, ringed so they can be picked out of the
        // row without reading names off a bubble that is 20 dp tall.
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ringWidth * 0.9f
        paint.color = ColorUtils.HSLToColor(floatArrayOf(hue, 0.85f, 0.28f))
        canvas.drawCircle(cx, cy, radius - ringWidth * 0.45f, paint)
        paint.style = Paint.Style.FILL
    }
}

/** How many faces a bubble shows before the rest become a count. */
internal const val CLUSTER_FACES_SHOWN = 3

/**
 * Whether the one-time explanation of grouped pins has been shown on this device.
 *
 * Stored in the same preference file the tips use. A flag on disk is the whole point here:
 * the bubble needs explaining exactly once, and an explanation that reappears is worse than
 * one that never came.
 */
private const val CLUSTER_TUTORIAL_PREF = "map_cluster_tutorial_seen"

private fun hasSeenClusterTutorial(context: android.content.Context): Boolean =
    context.getSharedPreferences("familysafety_prefs", android.content.Context.MODE_PRIVATE)
        .getBoolean(CLUSTER_TUTORIAL_PREF, false)

private fun markClusterTutorialSeen(context: android.content.Context) {
    context.getSharedPreferences("familysafety_prefs", android.content.Context.MODE_PRIVATE)
        .edit().putBoolean(CLUSTER_TUTORIAL_PREF, true).apply()
}

/** One face in a cluster bubble. */
internal data class ClusterFace(
    val memberId: String,
    val displayName: String,
    val avatar: Bitmap?,
    val colorHue: Float?,
    val isStale: Boolean
)

/**
 * Draws the bubble shown where several people are standing close enough to cover each
 * other up: a rounded capsule of small faces in a row, with a tail pointing at the spot
 * they share, and a "+N" when there are more of them than fit.
 *
 * The faces are the same drawing as a full pin at a smaller radius — [drawMemberDisc] is
 * shared with [memberMarkerBitmap] — so a bubble reads as the pins it stands in for.
 */
internal fun clusterMarkerBitmap(
    faces: List<ClusterFace>,
    totalCount: Int,
    sizePx: Int,
    highlightMemberId: String?,
    isOpen: Boolean
): Bitmap {
    val discD  = sizePx * 0.60f
    val discR  = discD / 2f
    val pad    = sizePx * 0.09f
    val gap    = sizePx * 0.05f
    val tailH  = sizePx * 0.20f
    val bleed  = sizePx * 0.08f          // room for the shadow to spill into

    // Closed, the faces sit on each other with a third of their width hidden — a stack,
    // which is both smaller on the map and reads as "these people are in one place".
    // Opening spreads them to full width with a gap, so the tap has a visible result on
    // the marker itself and not only in the card.
    val faceAdvance = if (isOpen) discD + gap else discD * 0.67f

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.textSize = discD * 0.44f
    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    val overflow = totalCount - faces.size
    val overflowLabel = if (overflow > 0) "+$overflow" else null
    val overflowW = overflowLabel?.let { paint.measureText(it) } ?: 0f

    val capsuleH = discD + pad * 2
    // The count needs more room at the end than a face does: the cap is a half-circle, so
    // the white behind text at mid-height runs out sooner than the outline suggests.
    val capsuleW = pad * 2 +
        discD + (faces.size - 1).coerceAtLeast(0) * faceAdvance +
        (if (overflowLabel != null) gap + overflowW + pad * 0.5f else 0f)

    val bmp = Bitmap.createBitmap(
        ceil(capsuleW + bleed * 2).toInt(),
        ceil(capsuleH + tailH + bleed * 2).toInt(),
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bmp)

    val left   = bleed
    val top    = bleed
    val right  = left + capsuleW
    val bottom = top + capsuleH
    val tipX   = left + capsuleW / 2f
    val tipY   = bottom + tailH

    // Capsule and tail unioned into a single outline.
    //
    // Adding the two as separate subpaths of one Path was not enough: a stroke outlines
    // every subpath independently, so a line was drawn straight across the top of the tail
    // and the point read as a separate arrow stuck underneath rather than as part of the
    // bubble. Path.op(UNION) merges them into one region with one boundary. The triangle
    // deliberately starts well inside the capsule so the two shapes genuinely overlap —
    // shapes that merely touch can leave a hairline where the union seams.
    fun bubblePath(inset: Float, tip: Float): android.graphics.Path {
        val body = android.graphics.Path().apply {
            val rect = android.graphics.RectF(
                left + inset, top + inset, right - inset, bottom - inset
            )
            val radius = rect.height() / 2f
            addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
        }
        val tail = android.graphics.Path().apply {
            // Wide enough to read as the bubble narrowing to a point. The capsule got
            // shorter when the faces started overlapping; a tail sized for the old one
            // looked like a drip hanging off it rather than part of the same shape.
            val half = sizePx * 0.14f
            val shoulderY = bottom - inset - capsuleH * 0.3f
            moveTo(tipX - half, shoulderY)
            lineTo(tipX + half, shoulderY)
            lineTo(tipX, tip)
            close()
        }
        body.op(tail, android.graphics.Path.Op.UNION)
        return body
    }

    // 1 — Soft drop shadow, matching the single pin's
    paint.color = Color.argb(55, 0, 0, 0)
    paint.maskFilter = android.graphics.BlurMaskFilter(
        sizePx * 0.09f, android.graphics.BlurMaskFilter.Blur.NORMAL
    )
    canvas.save()
    canvas.translate(2f, 3f)
    canvas.drawPath(bubblePath(1f, tipY - 2f), paint)
    canvas.restore()
    paint.maskFilter = null

    // 2 — The bubble itself: white, so the faces inside carry the colour
    paint.color = Color.WHITE
    canvas.drawPath(bubblePath(0f, tipY), paint)

    // 3 — Outline. Darker while the group is open, which is the only feedback a tap on a
    // bitmap marker can give.
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = if (isOpen) sizePx * 0.035f else sizePx * 0.018f
    paint.color = if (isOpen) Color.argb(210, 40, 40, 40) else Color.argb(70, 0, 0, 0)
    canvas.drawPath(bubblePath(paint.strokeWidth / 2f, tipY - paint.strokeWidth / 2f), paint)
    paint.style = Paint.Style.FILL

    // 4 — The faces.
    //
    // Drawn right to left so the leftmost ends up on top of the stack. That matters when
    // they overlap: the caller puts the member who was asked for first in the list, and
    // being asked for is the wrong time to be the face buried under two others.
    val cy = top + pad + discR
    val firstCx = left + pad + discR
    val separator = if (isOpen) 0f else (discR * 0.16f).coerceAtLeast(2f)
    for (index in faces.indices.reversed()) {
        val face = faces[index]
        val cx = firstCx + index * faceAdvance
        val hue = face.colorHue
            ?: ((face.memberId.hashCode().toLong() and 0xFFFFFFFFL) % 360).toFloat()
        // A stale face fades on its own; the bubble around it stays solid, since the
        // group is still there even when one person's fix is old.
        val restore = canvas.saveLayerAlpha(
            0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(),
            if (face.isStale) 140 else 255
        )
        // A white collar under each overlapping face, so a stack reads as separate people
        // rather than as one smeared shape where two accent rings meet.
        if (separator > 0f) {
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, discR + separator, paint)
        }
        drawMemberDisc(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = discR,
            ringWidth = (discR * 0.22f).coerceAtLeast(2f),
            displayName = face.displayName,
            hue = hue,
            avatar = face.avatar,
            drawRing = true,
            highlight = face.memberId == highlightMemberId
        )
        canvas.restoreToCount(restore)
    }

    // 5 — "+N" for whoever did not fit
    if (overflowLabel != null) {
        paint.color = Color.argb(190, 30, 30, 30)
        paint.textAlign = Paint.Align.LEFT
        val lastCx = firstCx + (faces.size - 1).coerceAtLeast(0) * faceAdvance
        val textY = cy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(overflowLabel, lastCx + discR + gap, textY, paint)
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
