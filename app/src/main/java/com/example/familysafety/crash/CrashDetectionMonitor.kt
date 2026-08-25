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

/**
 * Sensor plumbing for crash detection. The rule that decides whether a sample is a crash lives
 * in [ImpactDecider]; this class registers and unregisters the listener, and turns a
 * [ImpactDecider.Decision.FIRE] into the full-screen alert.
 */
@Singleton
class CrashDetectionMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private val decider = ImpactDecider()

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = sqrt(x * x + y * y + z * z)
            when (val decision = decider.onSample(magnitude, System.currentTimeMillis())) {
                ImpactDecider.Decision.FIRE -> {
                    Timber.w("CrashDetection: impact detected! accel=${magnitude}m/s²")
                    triggerCrashAlert()
                }
                ImpactDecider.Decision.BELOW_THRESHOLD -> Unit // the overwhelming majority
                else -> Timber.d("CrashDetection: ${magnitude}m/s² sample rejected — $decision")
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    fun setEnabled(enabled: Boolean) {
        decider.setEnabled(enabled)
        syncListener()
    }

    /** Called by LocationService when ActivityRecognition reports IN_VEHICLE state changes. */
    fun setArmed(inVehicle: Boolean) {
        decider.setArmed(inVehicle)
        syncListener()
    }

    /** Called from LocationService on every GPS fix so the speed guard is always current. */
    fun feedSpeed(speedMs: Float) {
        decider.feedSpeed(speedMs, System.currentTimeMillis())
    }

    fun setThreshold(thresholdMs2: Float) {
        decider.setThreshold(thresholdMs2)
    }

    /**
     * Raises the alert as though a crash had been detected, bypassing every guard. For the debug
     * settings screen: it exercises the notification, the full-screen intent and
     * [CrashAlertActivity] without needing motion. Never call this from a release path.
     */
    fun simulateAlert() {
        Timber.w("CrashDetection: simulated alert requested")
        triggerCrashAlert()
    }

    private fun syncListener() {
        if (decider.shouldListen) startListening() else stopListening()
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
