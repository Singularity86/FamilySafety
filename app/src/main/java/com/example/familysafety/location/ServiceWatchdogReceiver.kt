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
 * Complements ServiceWatchdogWorker (WorkManager). The receiver reschedules
 * itself on every tick, so a single call to schedule() keeps the chain going
 * until cancel() is called or the session ends.
 *
 * The periodic chain deliberately uses INEXACT alarms: Android allows only one
 * setExactAndAllowWhileIdle firing per app per ~9 minutes during Doze, and that
 * budget belongs to LocationHeartbeatReceiver (which also restarts the service,
 * making this chain a redundant backstop). When this watchdog competed for the
 * budget, it deferred heartbeats unpredictably — causing the very location gaps
 * it exists to prevent. Only scheduleSoon() (rare, one-shot recovery after task
 * removal / service death) uses the exact whileIdle path.
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
            schedule(context, INTERVAL_MS, exact = false)
        }

        fun scheduleSoon(context: Context) {
            schedule(context, SOON_INTERVAL_MS, exact = true)
        }

        private fun schedule(context: Context, delayMs: Long, exact: Boolean) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = buildPendingIntent(context)
            val triggerAt = SystemClock.elapsedRealtime() + delayMs

            if (exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pi
                    )
                    Timber.i("ServiceWatchdog: exact alarm scheduled in ${delayMs / 1000}s")
                    return
                } catch (e: SecurityException) {
                    Timber.w("ServiceWatchdog: exact alarm denied - using inexact fallback")
                }
            }
            // Inexact: fires in Doze maintenance windows without consuming the
            // per-app whileIdle budget reserved for the location heartbeat.
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            Timber.i("ServiceWatchdog: inexact alarm scheduled in ~${delayMs / 1000}s")
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(buildPendingIntent(context))
            Timber.i("ServiceWatchdog: alarm cancelled")
        }
    }
}
