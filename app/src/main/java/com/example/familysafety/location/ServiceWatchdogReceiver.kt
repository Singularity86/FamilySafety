package com.example.familysafety.location

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import timber.log.Timber

/**
 * AlarmManager-based watchdog that ensures LocationService stays alive.
 *
 * Complements ServiceWatchdogWorker (WorkManager). AlarmManager alarms are more
 * precise and can fire during Doze idle windows when WorkManager jobs are deferred.
 *
 * The receiver reschedules itself on every tick, so a single call to schedule()
 * keeps the chain going until cancel() is called or the session ends.
 */
class ServiceWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WATCHDOG_TICK) return

        val memberId = context
            .getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LocationService.PREFS_MEMBER_ID, null)

        if (memberId == null) {
            Timber.d("ServiceWatchdog: no active session - stopping alarm chain")
            return
        }

        if (!LocationService.isRunning) {
            Timber.w("ServiceWatchdog: LocationService not running - restarting for ${memberId.take(8)}")
            try {
                LocationService.startTracking(context, memberId)
            } catch (e: Exception) {
                Timber.e(e, "ServiceWatchdog: failed to restart LocationService")
            }
        } else {
            Timber.d("ServiceWatchdog: LocationService alive - no action needed")
        }

        schedule(context)
    }

    companion object {
        const val ACTION_WATCHDOG_TICK = "com.example.familysafety.WATCHDOG_TICK"
        private const val REQUEST_CODE = 0x5747 // "WG"
        private const val INTERVAL_MS = 15 * 60 * 1000L
        private const val SOON_INTERVAL_MS = 30 * 1000L

        private fun buildPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, ServiceWatchdogReceiver::class.java).apply {
                action = ACTION_WATCHDOG_TICK
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun schedule(context: Context) {
            schedule(context, INTERVAL_MS)
        }

        fun scheduleSoon(context: Context) {
            schedule(context, SOON_INTERVAL_MS)
        }

        private fun schedule(context: Context, delayMs: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = buildPendingIntent(context)
            val triggerAt = SystemClock.elapsedRealtime() + delayMs

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pi
                    )
                    Timber.i("ServiceWatchdog: exact alarm scheduled in ${delayMs / 1000}s")
                } catch (e: SecurityException) {
                    // SCHEDULE_EXACT_ALARM not granted by user - use inexact fallback.
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                    Timber.w("ServiceWatchdog: exact alarm denied - using inexact fallback")
                }
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                Timber.i("ServiceWatchdog: alarm scheduled in ${delayMs / 1000}s")
            }
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(buildPendingIntent(context))
            Timber.i("ServiceWatchdog: alarm cancelled")
        }
    }
}
