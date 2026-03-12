package com.example.familysafety.location

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.familysafety.MainActivity
import com.example.familysafety.R
import com.example.familysafety.core.ErrorHandler
import com.example.familysafety.core.RateLimiters
import com.example.familysafety.core.DataValidator
import com.example.familysafety.core.ValidationResult
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

    companion object {
        private const val CHANNEL_ID = "location_service_channel"
        private const val NOTIFICATION_ID = 1

        const val LOCATION_INTERVAL_NORMAL = 30_000L
        const val LOCATION_INTERVAL_STATIONARY = 5 * 60_000L
        private const val MOVEMENT_THRESHOLD_MS = 1.0f
        private const val GPS_STILL_DEBOUNCE_COUNT = 3  // consecutive still readings needed

        const val PREFS_NAME = "location_service"
        const val PREFS_MEMBER_ID = "member_id"

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
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                memberId = intent.getStringExtra(EXTRA_MEMBER_ID)
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(PREFS_MEMBER_ID, memberId).apply()
                startForeground(NOTIFICATION_ID, createNotification())
                startLocationUpdates()
            }
            ACTION_STOP_TRACKING -> {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().remove(PREFS_MEMBER_ID).apply()
                stopLocationUpdates()
                stopSelf()
            }
            else -> {
                val savedId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREFS_MEMBER_ID, null)
                if (savedId != null) {
                    memberId = savedId
                    startForeground(NOTIFICATION_ID, createNotification())
                    startLocationUpdates()
                    Timber.i("Location service restarted, resumed tracking for $savedId")
                } else {
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
        if (isTracking) return

        // Start Activity Recognition so we can adapt intervals to movement state
        activityRecognitionManager.startMonitoring()

        // Observe AR movement state and adapt GPS interval + MQTT keepalive
        activityMonitoringJob = scope.launch {
            activityRecognitionManager.isMoving.collect { isMoving ->
                applyMovementState(isMoving)
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
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
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
            Timber.i("Started location updates (interval=${currentIntervalMs}ms)")
        } catch (e: SecurityException) {
            Timber.e(e, "Missing location permission")
        }
    }

    private fun stopLocationUpdates() {
        if (!isTracking) return
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isTracking = false
        activityMonitoringJob?.cancel()
        activityMonitoringJob = null
        activityRecognitionManager.stopMonitoring()
        Timber.i("Stopped location updates")
    }

    /**
     * Called when Activity Recognition reports a movement-state change.
     * Updates the GPS polling interval and MQTT keepalive accordingly.
     */
    private fun applyMovementState(isMoving: Boolean) {
        val newInterval = if (isMoving) LOCATION_INTERVAL_NORMAL else LOCATION_INTERVAL_STATIONARY
        if (newInterval != currentIntervalMs) {
            currentIntervalMs = newInterval
            Timber.d("AR: movement=${isMoving}, new GPS interval=${currentIntervalMs}ms")
            if (isTracking) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                isTracking = false
                requestLocationUpdates()
            }
        }

        // Notify MQTT transport so it adapts keepalive on next reconnect
        if (isMoving != lastReportedMoving) {
            lastReportedMoving = isMoving
            mqttTransport.notifyMovementState(isMoving)
        }
    }

    private fun handleLocationUpdate(location: Location) {
        val id = memberId ?: return

        scope.launch {
            ErrorHandler.safely(
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
                        Timber.w("Invalid location data: ${validationResult.errors.joinToString()}")
                        return@safely
                    }
                    ValidationResult.Valid -> {}
                }

                locationRepository.updateMyLocation(memberLocation)

                if (!RateLimiters.locationUpdates.allowRequest(id)) {
                    val retryAfter = RateLimiters.locationUpdates.getRetryAfterMs(id)
                    Timber.d("Location update rate limited, retry after ${retryAfter}ms")
                    return@safely
                }

                ErrorHandler.withRetry(
                    maxAttempts = 3,
                    initialDelayMs = 1000,
                    onError = { e, attempt ->
                        Timber.w(e, "Failed to publish location (attempt $attempt)")
                    }
                ) {
                    mqttTransport.publishLocation(memberLocation)
                }.onFailure { e ->
                    Timber.e(e, "Failed to publish location after retries")
                }

                // GPS speed debounce: AR is the primary signal, but GPS speed acts as
                // a fallback when AR confidence is low (e.g. first few minutes of tracking).
                adjustIntervalFromGps(location)
            }
        }
    }

    /**
     * Secondary movement signal based on GPS speed. Requires [GPS_STILL_DEBOUNCE_COUNT]
     * consecutive "still" readings before reducing the interval, so brief stops (traffic
     * lights, etc.) don't trigger unnecessary interval changes.
     */
    private fun adjustIntervalFromGps(location: Location) {
        val speed = if (location.hasSpeed()) location.speed else 0f
        val gpsReportsMoving = speed > MOVEMENT_THRESHOLD_MS

        if (gpsReportsMoving) {
            consecutiveStillCount = 0
            // GPS says moving — apply immediately (wake up if AR hasn't caught up yet)
            if (!activityRecognitionManager.isMoving.value) {
                applyMovementState(true)
            }
        } else {
            consecutiveStillCount++
            if (consecutiveStillCount >= GPS_STILL_DEBOUNCE_COUNT) {
                // GPS consistently says still — apply if AR hasn't already done so
                if (activityRecognitionManager.isMoving.value) {
                    applyMovementState(false)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        scope.cancel()
    }
}
