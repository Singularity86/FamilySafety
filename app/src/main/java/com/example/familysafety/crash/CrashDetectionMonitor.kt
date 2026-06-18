package com.example.familysafety.crash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.familysafety.R
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class CrashDetectionMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private var isEnabled = false
    private var isArmed = false

    @Volatile private var lastSpeedMs = 0f
    private val lastAlertTime = java.util.concurrent.atomic.AtomicLong(0L)

    private var thresholdMs2 = SENSITIVITY_MEDIUM

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!isEnabled || !isArmed) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = sqrt(x * x + y * y + z * z)
            if (magnitude >= thresholdMs2) {
                val now = System.currentTimeMillis()
                val last = lastAlertTime.get()
                if (lastSpeedMs >= SPEED_GUARD_MS &&
                    (now - last) > ALERT_COOLDOWN_MS &&
                    lastAlertTime.compareAndSet(last, now)) {
                    Timber.w("CrashDetection: impact detected! accel=${magnitude}m/s², speed=${lastSpeedMs}m/s")
                    triggerCrashAlert()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            sensorManager.unregisterListener(sensorListener)
        } else if (isArmed) {
            startListening()
        }
    }

    /** Called by LocationService when ActivityRecognition reports IN_VEHICLE state changes. */
    fun setArmed(inVehicle: Boolean) {
        isArmed = inVehicle
        if (isEnabled) {
            if (inVehicle) startListening() else stopListening()
        }
    }

    /** Called from LocationService on every GPS fix so the speed guard is always current. */
    fun feedSpeed(speedMs: Float) {
        lastSpeedMs = speedMs
    }

    fun setThreshold(thresholdMs2: Float) {
        this.thresholdMs2 = thresholdMs2
    }

    private fun startListening() {
        linearAccelSensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
            Timber.d("CrashDetection: armed and listening")
        } ?: Timber.w("CrashDetection: no linear acceleration sensor available on this device")
    }

    private fun stopListening() {
        sensorManager.unregisterListener(sensorListener)
        Timber.d("CrashDetection: disarmed")
    }

    private fun triggerCrashAlert() {
        createNotificationChannel()
        val alertIntent = Intent(context, CrashAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPi = PendingIntent.getActivity(
            context, NOTIFICATION_ID, alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Are you okay?")
            .setContentText("A possible crash was detected. Tap to respond.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPi, true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Crash Detection Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Emergency alerts when a vehicle crash is detected"
                setBypassDnd(true)
                enableVibration(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "crash_detection_channel"
        const val NOTIFICATION_ID = 9999

        /** Minimum GPS speed in m/s before impact before arming the alert (25 mph = 11.2 m/s). */
        private const val SPEED_GUARD_MS = 11.2f

        /** Don't fire again within 10 minutes of a previous alert. */
        private const val ALERT_COOLDOWN_MS = 10 * 60 * 1000L

        /** Linear acceleration thresholds in m/s² (gravity already removed by sensor type). */
        const val SENSITIVITY_LOW = 40f     // ~4g — severe crashes only
        const val SENSITIVITY_MEDIUM = 30f  // ~3g — default
        const val SENSITIVITY_HIGH = 20f    // ~2g — catches moderate impacts

        // Dedicated prefs file for crash detection. Previously this was set to
        // "geofence_prefs" (copy-paste bug) — those keys cohabited the geofence
        // settings file. Users who toggled crash detection in older builds may
        // have their flag stored under "geofence_prefs"; settings UI is the
        // canonical writer and will repopulate this file on next toggle.
        const val PREFS_NAME = "crash_detection_prefs"
        const val PREF_ENABLED = "crash_detection_enabled"
        const val PREF_SENSITIVITY = "crash_detection_sensitivity"
    }
}
