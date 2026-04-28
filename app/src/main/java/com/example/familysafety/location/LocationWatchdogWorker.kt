package com.example.familysafety.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.familysafety.R
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job that ensures the location foreground service stays alive.
 *
 * Runs every 15 minutes (the minimum WorkManager allows). If the OS has killed the
 * service due to battery optimization or low memory, this worker detects the missing
 * member ID and restarts it. If the service is already running, startTracking() is a
 * no-op (the service's onStartCommand guard returns early).
 *
 * On Android 12+ (API 31+), starting a foreground service from a background context
 * throws ForegroundServiceStartNotAllowedException. We avoid that by promoting THIS
 * worker to foreground state via setForeground(...) before calling startForegroundService.
 * Foreground workers are exempt from the FGS background-start restriction.
 */
class LocationWatchdogWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val memberId = appContext
            .getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LocationService.PREFS_MEMBER_ID, null)

        if (memberId == null) {
            Timber.d("Watchdog: no active session — skipping restart")
            return Result.success()
        }

        // Promote ourselves to foreground BEFORE attempting startForegroundService,
        // which is required on Android 12+ to avoid ForegroundServiceStartNotAllowedException.
        try {
            setForeground(buildForegroundInfo())
        } catch (e: Exception) {
            // setForeground can fail if WM doesn't think we're allowed to run in foreground;
            // fall through and try startForegroundService anyway — it might still succeed
            // (e.g., on Android <12 or if we have an exemption).
            Timber.w(e, "Watchdog: setForeground failed; trying service start anyway")
        }

        val isRunning = appContext
            .getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(LocationService.PREF_SERVICE_ALIVE, false)

        if (isRunning) {
            Timber.d("Watchdog: service reports alive — calling startTracking idempotently")
        } else {
            Timber.w("Watchdog: service not running for member $memberId — restarting now")
        }

        return try {
            LocationService.startTracking(appContext, memberId)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Watchdog: failed to start LocationService — will retry next cycle")
            Result.retry()
        }
    }

    private fun buildForegroundInfo(): ForegroundInfo {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("FamilySafety")
            .setContentText("Checking location service…")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location service watchdog",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Periodic check that location sharing is still running"
                setShowBadge(false)
            }
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val WORK_NAME = "location_watchdog"
        private const val CHANNEL_ID = "location_watchdog_channel"
        private const val NOTIFICATION_ID = 2

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LocationWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).setConstraints(Constraints.NONE).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.i("Watchdog: scheduled (15 min interval)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.i("Watchdog: cancelled")
        }
    }
}
