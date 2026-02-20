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

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var memberId: String? = null
    private var isTracking = false

    private var currentIntervalMs = LOCATION_INTERVAL_NORMAL

    companion object {
        private const val CHANNEL_ID = "location_service_channel"
        private const val NOTIFICATION_ID = 1

        private const val LOCATION_INTERVAL_NORMAL = 30_000L
        private const val LOCATION_INTERVAL_STATIONARY = 5 * 60_000L
        private const val MOVEMENT_THRESHOLD_MS = 1.0f

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
                startForeground(NOTIFICATION_ID, createNotification())
                startLocationUpdates()
            }
            ACTION_STOP_TRACKING -> {
                stopLocationUpdates()
                stopSelf()
            }
            else -> {
                // Service was started via startForegroundService() without an explicit
                // action (e.g. from MainActivity.startLocationService()). Android 8+
                // requires startForeground() to be called within 5 seconds of
                // startForegroundService(), or it throws ForegroundServiceDidNotStartInTimeException.
                startForeground(NOTIFICATION_ID, createNotification())
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
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
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

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    handleLocationUpdate(location)
                }
            }
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            currentIntervalMs
        ).apply {
            setMinUpdateIntervalMillis(currentIntervalMs / 2)
            setMaxUpdateDelayMillis(currentIntervalMs * 2)
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            isTracking = true
            Timber.i("Started location updates")
        } catch (e: SecurityException) {
            Timber.e(e, "Missing location permission")
        }
    }

    private fun stopLocationUpdates() {
        if (!isTracking) return

        fusedLocationClient.removeLocationUpdates(locationCallback)
        isTracking = false
        Timber.i("Stopped location updates")
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
                    ValidationResult.Valid -> {
                    }
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

                adjustUpdateInterval(location)
            }
        }
    }

    private fun adjustUpdateInterval(location: Location) {
        val speed = if (location.hasSpeed()) location.speed else 0f

        val newInterval = if (speed > MOVEMENT_THRESHOLD_MS) {
            LOCATION_INTERVAL_NORMAL
        } else {
            LOCATION_INTERVAL_STATIONARY
        }

        if (newInterval != currentIntervalMs) {
            currentIntervalMs = newInterval
            Timber.d("Adjusted location interval to ${currentIntervalMs}ms")

            if (isTracking) {
                stopLocationUpdates()
                startLocationUpdates()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        scope.cancel()
    }
}
