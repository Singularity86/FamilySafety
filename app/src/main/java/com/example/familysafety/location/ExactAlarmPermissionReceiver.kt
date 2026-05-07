package com.example.familysafety.location

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber

/**
 * Listens for ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31+).
 *
 * When the user grants the SCHEDULE_EXACT_ALARM special permission in
 * Settings → Special app access → Alarms & reminders, pending alarms are not
 * automatically re-created — Android only delivers this broadcast to notify us
 * of the change. We use it to upgrade any inexact fallback alarms to exact ones.
 *
 * On permission revocation we log and take no action: ServiceWatchdogWorker
 * (WorkManager) continues to run independently and will re-establish the alarm
 * chain using the inexact fallback path the next time it ticks.
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                Timber.d("ExactAlarmPermissionReceiver: permission revoked — WorkManager watchdog will recover")
                return
            }
        }

        val memberId = context
            .getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LocationService.PREFS_MEMBER_ID, null)

        if (memberId == null) {
            Timber.d("ExactAlarmPermissionReceiver: permission granted but no active session")
            return
        }

        Timber.i("ExactAlarmPermissionReceiver: exact alarm permission granted — re-establishing alarm chains")
        ServiceWatchdogReceiver.schedule(context)
    }
}
