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
    lateinit var mqttTransport: MqttTransport

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
        // worst-case publish can take ~15 s; double it to be safe but still bounded
        // so a runaway coroutine can't pin the CPU forever.
        private const val WAKELOCK_TIMEOUT_MS = 60_000L

        const val PREFS_NAME = "location_service"
        const val PREFS_MEMBER_ID = "member_id"
        const val PREF_SERVICE_ALIVE = "service_alive"

        const val ACTION_START_TRACKING = "com.example.familysafety.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.familysafety.STOP_TRACKING"
        const val EXTRA_MEMBER_ID = "member_id"

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
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
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
                startLocationUpdates()
                LocationWatchdogWorker.schedule(this)
            }
            ACTION_STOP_TRACKING -> {
                Timber.i("LocationService: STOP_TRACKING (explicit user action)")
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .remove(PREFS_MEMBER_ID)
                    .putBoolean(PREF_SERVICE_ALIVE, false)
                    .apply()
                LocationWatchdogWorker.cancel(this)
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
                    // Process was killed → MqttTransport singleton is fresh and uninitialized.
                    // Re-wire keys + family members + MQTT before any GPS fix arrives so
                    // publishes don't silently no-op against an empty familyMemberKeys map.
                    appInitializer.initialize()
                    startLocationUpdates()
                    LocationWatchdogWorker.schedule(this)
                } else {
                    Timber.w("LocationService: restarted by OS but no saved member ID — idle")
                    startForeground(NOTIFICATION_ID, createNotification())
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            .setContentText("Required for location sharing to work. Tap to open.")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startLocationUpdates() {
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
                        mqttTransport.publishLocation(lastLocation)
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

        requestLocationUpdates()
    }

    private fun requestLocationUpdates() {
        // While the device is in motion, prioritize accuracy so the family-sharing
        // map shows fresh, precise positions; when stationary, drop to balanced
        // power to save battery (the user isn't going anywhere).
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
            mqttTransport.notifyMovementState(isMoving)
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

        // Hold the wakelock with a generous safety timeout, but release it
        // explicitly in the coroutine's finally block so the device can sleep
        // as soon as the publish coroutine actually completes (success or failure).
        // The previous fixed 15s timeout could expire mid-publish on slow links.
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

                ErrorHandler.withRetry(
                    maxAttempts = 3,
                    initialDelayMs = 1000,
                    onError = { e, attempt ->
                        Timber.w(e, "LocationService: publish failed (attempt $attempt/3)")
                    }
                ) {
                    mqttTransport.publishLocation(memberLocation)
                }.onSuccess {
                    Timber.d("LocationService: published location to MQTT")
                }.onFailure { e ->
                    Timber.e(e, "LocationService: all publish attempts failed")
                }

                adjustIntervalFromGps(location)
            } } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

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
        super.onDestroy()
        stopLocationUpdates()
        scope.cancel()
        // Mark as not alive so the watchdog knows to restart on next run
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_SERVICE_ALIVE, false).apply()
    }
}
