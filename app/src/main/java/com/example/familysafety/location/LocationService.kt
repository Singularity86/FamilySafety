package com.example.familysafety.location

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.familysafety.AppInitializer
import com.example.familysafety.MainActivity
import com.example.familysafety.R
import com.example.familysafety.core.ErrorHandler
import com.example.familysafety.core.RateLimiters
import com.example.familysafety.core.DataValidator
import com.example.familysafety.core.ValidationResult
import com.example.familysafety.crash.CrashDetectionMonitor
import com.example.familysafety.transport.MqttConfig
import com.example.familysafety.transport.TransportProvider
import com.example.familysafety.transport.MqttTransport
import com.google.android.gms.location.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class LocationService : Service() {

    @Inject
    lateinit var locationRepository: LocationRepository

    @Inject
    lateinit var locationHistoryRepository: com.example.familysafety.storage.LocationHistoryRepository

    @Inject
    lateinit var mqttTransport: com.example.familysafety.transport.MqttTransport

    @Inject
    lateinit var transportProvider: com.example.familysafety.transport.TransportProvider

    @Inject
    lateinit var groupStateManager: com.example.familysafety.group.GroupStateManager

    @Inject
    lateinit var e2eeManager: com.example.familysafety.crypto.E2EEManager

    @Inject
    lateinit var activityRecognitionManager: ActivityRecognitionManager

    @Inject
    lateinit var crashDetectionMonitor: CrashDetectionMonitor

    @Inject
    lateinit var appInitializer: AppInitializer

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var memberId: String? = null
    private var isTracking = false

    private var currentIntervalMs = LOCATION_INTERVAL_NORMAL

    // GPS speed debounce: require this many consecutive "still" GPS readings before
    // trusting the speed signal. Prevents flicker at traffic lights / brief stops.
    private var consecutiveStillCount = 0

    // Last reported movement state so we only call notifyMovementState on changes
    private var lastReportedMoving: Boolean? = null

    private var activityMonitoringJob: Job? = null
    private var vehicleMonitoringJob: Job? = null
    private var reconnectObserverJob: Job? = null
    private var heartbeatJob: Job? = null

    // Updated each time we successfully publish a location.
    @Volatile private var lastPublishedAt = 0L

    private val wakeLock by lazy {
        (getSystemService(POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "FamilySafety::LocationPublish")
    }

    companion object {
        private const val CHANNEL_ID = "location_service_channel"
        private const val NOTIFICATION_ID = 1

        const val LOCATION_INTERVAL_NORMAL = 30_000L
        const val LOCATION_INTERVAL_STATIONARY = 5 * 60_000L
        private const val MOVEMENT_THRESHOLD_MS = 1.0f
        private const val GPS_STILL_DEBOUNCE_COUNT = 3

        // Safety timeout for the publish wakelock. With 3 retries × ~1–4 s backoff,
        // worst-case publish take ~15 s; double it to be safe but still bounded
        // so a runaway coroutine can't pin the CPU forever.
        private const val WAKELOCK_TIMEOUT_MS = 60_000L

        const val PREFS_NAME = "location_service"
        const val PREFS_MEMBER_ID = "member_id"
        const val PREF_SERVICE_ALIVE = "service_alive"

        const val ACTION_START_TRACKING = "com.example.familysafety.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.familysafety.STOP_TRACKING"
        const val EXTRA_MEMBER_ID = "member_id"

        /** True while this service instance is alive; read by ServiceWatchdogReceiver. */
        @Volatile
        var isRunning = false
            private set

        fun startTracking(context: Context, memberId: String) {
            val intent = Intent(context, LocationService::class.java).apply {
                action = ACTION_START_TRACKING
                putExtra(EXTRA_MEMBER_ID, memberId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopTracking(context: Context) {
            val intent = Intent(context, LocationService::class.java).apply {
                action = ACTION_STOP_TRACKING
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("LocationService: onCreate")
        isRunning = true
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        ServiceWatchdogReceiver.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                val id = intent.getStringExtra(EXTRA_MEMBER_ID)
                Timber.i("LocationService: START_TRACKING for member=${id?.take(8)}…")
                memberId = id
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREFS_MEMBER_ID, memberId)
                    .putBoolean(PREF_SERVICE_ALIVE, true)
                    .apply()
                startForeground(NOTIFICATION_ID, createNotification())
                appInitializer.initialize()
                startLocationUpdates()
                ServiceWatchdogWorker.scheduleIfNeeded(this)
            }
            ACTION_STOP_TRACKING -> {
                Timber.i("LocationService: STOP_TRACKING (explicit user action)")
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .remove(PREFS_MEMBER_ID)
                    .putBoolean(PREF_SERVICE_ALIVE, false)
                    .apply()
                ServiceWatchdogWorker.cancel(this)
                ServiceWatchdogReceiver.cancel(this)
                stopLocationUpdates()
                stopSelf()
            }
            else -> {
                val savedId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREFS_MEMBER_ID, null)
                if (savedId != null) {
                    Timber.i("LocationService: restarted by OS — resuming tracking for ${savedId.take(8)}…")
                    memberId = savedId
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(PREF_SERVICE_ALIVE, true).apply()
                    startForeground(NOTIFICATION_ID, createNotification())
                    appInitializer.initialize()
                    startLocationUpdates()
                    ServiceWatchdogWorker.scheduleIfNeeded(this)
                } else {
                    Timber.w("LocationService: restarted by OS but no saved member ID — idle")
                    startForeground(NOTIFICATION_ID, createNotification())
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.w("LocationService: task removed - scheduling service recovery")
        val savedId = memberId ?: getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREFS_MEMBER_ID, null)
        if (savedId != null) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREFS_MEMBER_ID, savedId)
                .apply()
            ServiceWatchdogReceiver.scheduleSoon(this)
            ServiceWatchdogWorker.scheduleIfNeeded(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracks location in background"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FamilySafety — Location active")
            .setContentText("Notification required for location sharing to work. Tap to open.")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startLocationUpdates() {
        if (!LocationPermissionHelper.hasAlwaysOnLocationPrerequisites(this)) {
            Timber.w("LocationService: always-on location prerequisites missing - not starting tracking")
            stopSelf()
            return
        }
        if (isTracking) {
            Timber.d("LocationService: startLocationUpdates called but already tracking — skipping")
            return
        }
        Timber.i("LocationService: starting location updates (interval=${currentIntervalMs}ms)")

        activityRecognitionManager.startMonitoring()

        activityMonitoringJob = scope.launch {
            activityRecognitionManager.isMoving.collect { isMoving ->
                applyMovementState(isMoving)
            }
        }

        val crashPrefs = getSharedPreferences(CrashDetectionMonitor.PREFS_NAME, MODE_PRIVATE)
        val crashEnabled = crashPrefs.getBoolean(CrashDetectionMonitor.PREF_ENABLED, false)
        val crashSensitivity = crashPrefs.getFloat(
            CrashDetectionMonitor.PREF_SENSITIVITY, CrashDetectionMonitor.SENSITIVITY_MEDIUM
        )
        Timber.d("LocationService: crash detection enabled=$crashEnabled threshold=$crashSensitivity")
        crashDetectionMonitor.setThreshold(crashSensitivity)
        crashDetectionMonitor.setEnabled(crashEnabled)
        vehicleMonitoringJob = scope.launch {
            activityRecognitionManager.isInVehicle.collect { inVehicle ->
                crashDetectionMonitor.setArmed(inVehicle)
            }
        }

        reconnectObserverJob = scope.launch {
            var wasConnected = false
            mqttTransport.connectionState.collect { state ->
                val isNowConnected = state is MqttTransport.ConnectionState.Connected
                Timber.d("LocationService: MQTT state → $state")
                if (isNowConnected && !wasConnected) {
                    val lastLocation = locationRepository.myLocation.value
                    if (lastLocation != null) {
                        Timber.i("LocationService: MQTT reconnected — republishing last known location")
                        val heartbeatLocation = lastLocation.copy(timestamp = System.currentTimeMillis())
                        if (publishLocationSnapshot(heartbeatLocation, updateRepository = true)) {
                            lastPublishedAt = System.currentTimeMillis()
                        } else {
                            Timber.w("LocationService: reconnect publish did not reach any peers")
                        }
                    }
                }
                wasConnected = isNowConnected
            }
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    handleLocationUpdate(location)
                }
            }
        }

        // Heartbeat: if GPS goes quiet for a full stationary interval (e.g. Doze mode,
        // OEM battery management), republish the last known location so members don't
        // see a growing stale "Updated Xh ago" with no explanation.
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(LOCATION_INTERVAL_STATIONARY)
                val sinceLastPublish = System.currentTimeMillis() - lastPublishedAt
                if (sinceLastPublish >= LOCATION_INTERVAL_STATIONARY) {
                    val lastLocation = locationRepository.myLocation.value
                    if (lastLocation != null) {
                        Timber.i("LocationService: heartbeat — republishing last known location (${sinceLastPublish / 1000}s since last publish)")
                        val heartbeatLocation = lastLocation.copy(timestamp = System.currentTimeMillis())
                        if (publishLocationSnapshot(heartbeatLocation, updateRepository = true)) {
                            lastPublishedAt = System.currentTimeMillis()
                        } else {
                            Timber.w("LocationService: heartbeat publish did not reach any peers")
                        }
                    }
                }
            }
        }

        requestLocationUpdates()
    }

    private fun requestLocationUpdates() {
        if (!LocationPermissionHelper.hasAlwaysOnLocationPrerequisites(this)) {
            Timber.w("LocationService: missing foreground/background location permission - GPS request skipped")
            return
        }
        val priority = if (currentIntervalMs == LOCATION_INTERVAL_NORMAL) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val locationRequest = LocationRequest.Builder(
            priority,
            currentIntervalMs
        ).apply {
            setMinUpdateIntervalMillis(currentIntervalMs / 2)
            setMaxUpdateDelayMillis(currentIntervalMs * 2)
            setWaitForAccurateLocation(false)
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            isTracking = true
            Timber.i("LocationService: GPS request registered (interval=${currentIntervalMs}ms)")
        } catch (e: SecurityException) {
            Timber.e(e, "LocationService: missing location permission — cannot start GPS")
        }
    }

    private fun stopLocationUpdates() {
        if (!isTracking) {
            Timber.d("LocationService: stopLocationUpdates called but not tracking — skipping")
            return
        }
        Timber.i("LocationService: stopping location updates")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isTracking = false
        activityMonitoringJob?.cancel(); activityMonitoringJob = null
        vehicleMonitoringJob?.cancel(); vehicleMonitoringJob = null
        reconnectObserverJob?.cancel(); reconnectObserverJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        activityRecognitionManager.stopMonitoring()
        crashDetectionMonitor.setEnabled(false)
        if (wakeLock.isHeld) wakeLock.release()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_SERVICE_ALIVE, false).apply()
    }

    private fun applyMovementState(isMoving: Boolean) {
        val newInterval = if (isMoving) LOCATION_INTERVAL_NORMAL else LOCATION_INTERVAL_STATIONARY
        if (newInterval != currentIntervalMs) {
            currentIntervalMs = newInterval
            Timber.i("LocationService: movement=$isMoving → GPS interval=${currentIntervalMs / 1000}s")
            if (isTracking) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                isTracking = false
                requestLocationUpdates()
            }
        }
        if (isMoving != lastReportedMoving) {
            lastReportedMoving = isMoving
            
            scope.launch {
                try {
                    val id = memberId ?: return@launch
                    val topic = MqttConfig.getMovementTopic(id)
                    val payload = if (isMoving) "moving" else "stationary"
                    transportProvider.broadcastMessage(topic, payload.toByteArray(), MqttConfig.QOS_AT_MOST_ONCE, true)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to broadcast movement state")
                }
            }
        }
    }

    private fun handleLocationUpdate(location: Location) {
        val id = memberId ?: return

        val speedMs = if (location.hasSpeed()) location.speed else 0f
        val speedMph = (speedMs * 2.237f).toInt()
        Timber.d(
            "LocationService: GPS fix — lat=%.5f lon=%.5f acc=%.0fm speed=${speedMph}mph".format(
                location.latitude, location.longitude, location.accuracy
            )
        )

        wakeLock.acquire(WAKELOCK_TIMEOUT_MS)

        scope.launch {
            try { ErrorHandler.safely(
                tag = "LocationService",
                operation = "location update"
            ) {
                val memberLocation = MemberLocation(
                    memberId = id,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    timestamp = location.time,
                    speed = if (location.hasSpeed()) location.speed else null,
                    bearing = if (location.hasBearing()) location.bearing else null
                )

                when (val validationResult = DataValidator.validateLocation(memberLocation)) {
                    is ValidationResult.Invalid -> {
                        Timber.w("LocationService: invalid location — ${validationResult.errors.joinToString()}")
                        return@safely
                    }
                    ValidationResult.Valid -> {}
                }

                locationRepository.updateMyLocation(memberLocation)
                crashDetectionMonitor.feedSpeed(speedMs)

                if (!RateLimiters.locationUpdates.allowRequest(id)) {
                    val retryAfter = RateLimiters.locationUpdates.getRetryAfterMs(id)
                    Timber.d("LocationService: rate limited — retry in ${retryAfter}ms")
                    return@safely
                }

                if (publishLocationSnapshot(memberLocation, updateRepository = false)) {
                    lastPublishedAt = System.currentTimeMillis()
                    Timber.d("LocationService: published location")
                } else {
                    Timber.w("LocationService: location publish did not reach any peers")
                }

                adjustIntervalFromGps(location)
            } } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private suspend fun publishLocationToPeers(location: MemberLocation): Boolean {
        val group = groupStateManager.groupDefinition.value ?: run {
            Timber.w("LocationService: no group available for location publish")
            return false
        }
        val myId = memberId ?: run {
            Timber.w("LocationService: no member id available for location publish")
            return false
        }
        // Publish to OUR OWN sender topic — peers subscribe to getLocationTopic(sender).
        val topic = MqttConfig.getLocationTopic(myId)
        val peers = group.members.filter { it.memberId != myId }
        if (peers.isEmpty()) {
            Timber.d("LocationService: no peer recipients for location publish")
            return true
        }
        var anySucceeded = false

        peers.forEach { peer ->
                try {
                    val encryptedPayload = e2eeManager.encryptMessage(
                        plaintext = com.example.familysafety.transport.MessageProtocol.encodeLocationUpdate(location),
                        recipientMemberId = peer.memberId,
                        recipientX25519PublicKey = peer.x25519PublicKey.hexToByteArray()
                    )
                    transportProvider.broadcastMessage(
                        topic = topic,
                        payload = encryptedPayload.toByteArray(),
                        qos = MqttConfig.QOS_AT_LEAST_ONCE,
                        retained = false
                    ).also { delivered ->
                        anySucceeded = anySucceeded || delivered
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to send location to ${peer.memberId.take(8)}")
                }
            }

        if (!anySucceeded) {
            Timber.w("LocationService: no location payload was delivered for ${location.memberId.take(8)}")
        }
        return anySucceeded
    }

    private suspend fun publishLocationSnapshot(
        location: MemberLocation,
        updateRepository: Boolean
    ): Boolean {
        val delivered = publishLocationToPeers(location)
        if (delivered && updateRepository) {
            locationRepository.updateMyLocation(location)
        }
        return delivered
    }

    private fun String.hexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun adjustIntervalFromGps(location: Location) {
        val speed = if (location.hasSpeed()) location.speed else 0f
        val gpsReportsMoving = speed > MOVEMENT_THRESHOLD_MS

        if (gpsReportsMoving) {
            consecutiveStillCount = 0
            if (!activityRecognitionManager.isMoving.value) {
                Timber.d("LocationService: GPS speed override → moving (AR not yet caught up)")
                applyMovementState(true)
            }
        } else {
            consecutiveStillCount++
            if (consecutiveStillCount >= GPS_STILL_DEBOUNCE_COUNT) {
                if (activityRecognitionManager.isMoving.value) {
                    Timber.d("LocationService: GPS debounce complete → stationary")
                    applyMovementState(false)
                }
            }
        }
    }

    override fun onDestroy() {
        Timber.w("LocationService: onDestroy — service is being killed")
        isRunning = false
        super.onDestroy()
        stopLocationUpdates()
        scope.cancel()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_SERVICE_ALIVE, false).apply()
        // Schedule a recovery alarm so the watchdog fires even if WorkManager is deferred
        ServiceWatchdogReceiver.schedule(this)
    }
}
