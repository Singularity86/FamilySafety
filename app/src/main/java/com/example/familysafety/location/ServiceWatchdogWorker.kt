package com.example.familysafety.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.PowerManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.familysafety.MainActivity
import com.example.familysafety.R
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based watchdog for LocationService.
 *
 * Runs every 15 minutes. On each tick it:
 *  1. Restarts LocationService if it is not currently running (and a session exists).
 *  2. Checks whether the ServiceWatchdogReceiver alarm chain is still pending, and
 *     reschedules it if the alarm was cancelled (e.g., after a device reboot that
 *     wiped pending alarms before BootRecoveryReceiver fired).
 *
 * Enqueued from AppInitializer.initialize() using a query-first pattern to avoid
 * the KEEP/REPLACE trap: we only call enqueueUniquePeriodicWork if no ENQUEUED or
 * RUNNING instance already exists, letting a healthy worker run uninterrupted while
 * still recovering from FAILED/CANCELLED states.
 */
class ServiceWatchdogWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val memberId = appContext
            .getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LocationService.PREFS_MEMBER_ID, null)

        if (memberId == null) {
            Timber.d("$TAG: no active session — skipping")
            return Result.success()
        }

        // Promote to foreground before calling startForegroundService to avoid
        // ForegroundServiceStartNotAllowedException on Android 12+.
        try {
            setForeground(buildForegroundInfo())
        } catch (e: Exception) {
            Timber.w(e, "$TAG: setForeground failed — attempting service start anyway")
        }

        // 1. Restart LocationService if not running
        if (!LocationService.isRunning) {
            Timber.w("$TAG: LocationService not running — restarting for ${memberId.take(8)}")
            return try {
                LocationService.startTracking(appContext, memberId)
                Result.success()
            } catch (e: Exception) {
                Timber.e(e, "$TAG: failed to restart LocationService")
                Result.retry()
            }
        } else {
            Timber.d("$TAG: LocationService alive")
        }

        // 2. Verify ServiceWatchdogReceiver alarm chain is active
        val alarmIntent = Intent(appContext, ServiceWatchdogReceiver::class.java).apply {
            action = ServiceWatchdogReceiver.ACTION_WATCHDOG_TICK
        }
        val existingAlarm = PendingIntent.getBroadcast(
            appContext,
            0x5747,
            alarmIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (existingAlarm == null) {
            Timber.w("$TAG: alarm chain not active — rescheduling")
            ServiceWatchdogReceiver.schedule(appContext)
        } else {
            Timber.d("$TAG: alarm chain active")
        }

        // 3. Check battery optimization exemption — notify if silently revoked
        checkBatteryOptimization()

        return Result.success()
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isExempt = pm.isIgnoringBatteryOptimizations(appContext.packageName)
        val prefs = appContext.getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE)
        val currentFingerprint = Build.FINGERPRINT
        val storedFingerprint = prefs.getString(PREFS_KEY_BUILD_FINGERPRINT, null)
        val fingerprintChanged = storedFingerprint != null && storedFingerprint != currentFingerprint
        val wasExempt = prefs.getBoolean(PREFS_KEY_BATTERY_OPT_STATUS, true)
        if (fingerprintChanged) {
            Timber.i("$TAG: build fingerprint changed — treating as potential OTA permission reset")
        }
        if (!isExempt && (wasExempt || fingerprintChanged)) {
            Timber.w("$TAG: battery optimization exemption revoked — posting notification")
            postBatteryRevocationNotification()
        }
        prefs.edit()
            .putBoolean(PREFS_KEY_BATTERY_OPT_STATUS, isExempt)
            .putString(PREFS_KEY_BUILD_FINGERPRINT, currentFingerprint)
            .apply()
    }

    private fun postBatteryRevocationNotification() {
        ensureBatteryAlertChannel()
        val intent = Intent(appContext, MainActivity::class.java).apply {
            putExtra("navigate_to", "battery_fix")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            BATTERY_ALERT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, BATTERY_ALERT_CHANNEL_ID)
            .setContentTitle("Jibaro Family Safety battery protection disabled")
            .setContentText("Location sharing may stop. Tap to restore.")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(BATTERY_ALERT_NOTIFICATION_ID, notification)
    }

    private fun ensureBatteryAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BATTERY_ALERT_CHANNEL_ID,
                "Battery protection alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when battery optimization exemption is revoked"
            }
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildForegroundInfo(): ForegroundInfo {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Jibaro Family Safety")
            .setContentText("Checking location service…")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Service watchdog",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Periodic check that location service is still running"
                setShowBadge(false)
            }
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "ServiceWatchdogWorker"
        const val WORK_NAME = "service_watchdog"
        private const val CHANNEL_ID = "service_watchdog_channel"
        private const val NOTIFICATION_ID = 3
        private const val BATTERY_ALERT_CHANNEL_ID      = "battery_alert_channel"
        private const val BATTERY_ALERT_NOTIFICATION_ID = 4
        private const val BATTERY_ALERT_REQUEST_CODE    = 0x5748
        private const val PREFS_KEY_BATTERY_OPT_STATUS  = "last_battery_opt_status"
        private const val PREFS_KEY_BUILD_FINGERPRINT   = "last_build_fingerprint"

        /**
         * Enqueue the worker only when no healthy (ENQUEUED or RUNNING) instance exists.
         * This avoids REPLACE resetting a live worker's schedule while still recovering
         * from FAILED or CANCELLED states that KEEP would silently ignore.
         */
        fun scheduleIfNeeded(context: Context) {
            val wm = WorkManager.getInstance(context)
            val infos = try {
                wm.getWorkInfosForUniqueWork(WORK_NAME).get()
            } catch (e: Exception) {
                Timber.w(e, "$TAG: could not query work state — scheduling defensively")
                emptyList()
            }

            val hasActiveWorker = infos.any { info ->
                info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING
            }

            if (hasActiveWorker) {
                Timber.d("$TAG: already ENQUEUED/RUNNING — skipping enqueue")
                return
            }

            val request = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            Timber.i("$TAG: enqueued (previous state: ${infos.firstOrNull()?.state ?: "none"})")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.i("$TAG: cancelled")
        }
    }
}
