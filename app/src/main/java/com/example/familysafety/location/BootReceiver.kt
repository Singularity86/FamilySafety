package com.example.familysafety.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the location foreground service after the device boots.
 * Only acts if tracking was previously active (memberId persisted in SharedPreferences).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val memberId = context
            .getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LocationService.PREFS_MEMBER_ID, null) ?: return

        LocationService.startTracking(context, memberId)
        LocationWatchdogWorker.schedule(context)
    }
}
