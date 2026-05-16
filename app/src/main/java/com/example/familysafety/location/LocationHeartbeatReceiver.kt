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
 * Exact-alarm heartbeat that wakes the location stack during Doze and asks it to
 * flush any locally cached location snapshots.
 */
class LocationHeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HEARTBEAT_TICK) return

        val memberId = context
            .getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LocationService.PREFS_MEMBER_ID, null)

        if (memberId == null) {
            Timber.d("LocationHeartbeat: no active session - stopping alarm chain")
            return
        }

        if (!LocationService.isRunning) {
            Timber.w("LocationHeartbeat: LocationService not running - restarting for ${memberId.take(8)}")
            try {
                LocationService.startTracking(context, memberId)
            } catch (e: Exception) {
                Timber.e(e, "LocationHeartbeat: failed to restart LocationService")
            }
        } else {
            try {
                LocationService.requestHeartbeat(context)
            } catch (e: Exception) {
                Timber.e(e, "LocationHeartbeat: failed to request heartbeat")
            }
        }

        schedule(context)
    }

    companion object {
        const val ACTION_HEARTBEAT_TICK = "com.example.familysafety.HEARTBEAT_TICK"
        private const val REQUEST_CODE = 0x4842 // "HB"
        private const val INTERVAL_MS = 5 * 60 * 1000L

        private fun buildPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, LocationHeartbeatReceiver::class.java).apply {
                action = ACTION_HEARTBEAT_TICK
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = buildPendingIntent(context)
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pi
                    )
                    Timber.i("LocationHeartbeat: exact alarm scheduled in ${INTERVAL_MS / 1000}s")
                } catch (e: SecurityException) {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                    Timber.w("LocationHeartbeat: exact alarm denied - using inexact fallback")
                }
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                Timber.i("LocationHeartbeat: alarm scheduled in ${INTERVAL_MS / 1000}s")
            }
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(buildPendingIntent(context))
            Timber.i("LocationHeartbeat: alarm cancelled")
        }
    }
}
