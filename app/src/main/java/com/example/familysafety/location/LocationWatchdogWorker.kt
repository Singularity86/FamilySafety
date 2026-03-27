package com.example.familysafety.location

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job that ensures the location foreground service stays alive.
 *
 * Runs every 15 minutes (the minimum WorkManager allows). If the OS has killed the
 * service due to battery optimization or low memory, this worker detects the missing
 * member ID and restarts it. If the service is already running, startTracking() is a
 * no-op (the service's onStartCommand guard returns early).
 */
class LocationWatchdogWorker(
    private val appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val memberId = appContext
            .getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LocationService.PREFS_MEMBER_ID, null)

        if (memberId == null) {
            Timber.d("Watchdog: no active session — skipping restart")
            return Result.success()
        }

        val isRunning = appContext
            .getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(LocationService.PREF_SERVICE_ALIVE, false)

        if (isRunning) {
            Timber.d("Watchdog: service reports alive — no action needed")
        } else {
            Timber.w("Watchdog: service not running for member $memberId — restarting now")
        }

        // Always call startTracking: safe if already running, revives it if dead
        LocationService.startTracking(appContext, memberId)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "location_watchdog"

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
