package com.example.familysafety.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Consolidated boot and package-replace receiver that restores the location service
 * and watchdog chains after any event that could kill pending work.
 *
 * Handled actions:
 *  - BOOT_COMPLETED          — standard post-unlock boot broadcast
 *  - QUICKBOOT_POWERON       — fast-boot restart on HTC and some other OEMs
 *  - MY_PACKAGE_REPLACED     — app update; process was killed, static flags reset
 *
 * No LOCKED_BOOT_COMPLETED: before the first unlock the session is unreadable (it lives in
 * credential-encrypted storage), and starting the process then crashed Application.onCreate
 * at every boot. Nothing can be shared before unlock either way; BOOT_COMPLETED follows it.
 */
class BootRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Only the broadcasts listed above. An explicit intent can reach a receiver whatever
        // its filter says, so anything else is ignored rather than starting tracking.
        if (intent.action !in HANDLED_ACTIONS) {
            Timber.w("BootRecoveryReceiver: ignoring unexpected action ${intent.action}")
            return
        }
        Timber.i("BootRecoveryReceiver: received ${intent.action}")
        recoverServices(context)
    }

    private fun recoverServices(context: Context) {
        val memberId = readMemberId(context) ?: run {
            Timber.d("BootRecoveryReceiver: no active session — skipping recovery")
            return
        }

        Timber.i("BootRecoveryReceiver: recovering session for ${memberId.take(8)}")

        ServiceWatchdogReceiver.schedule(context)
        LocationHeartbeatReceiver.schedule(context)

        try {
            LocationService.startTracking(context, memberId)
        } catch (e: Exception) {
            Timber.e(e, "BootRecoveryReceiver: failed to start LocationService")
        }

        ServiceWatchdogWorker.scheduleIfNeeded(context)
    }

    private fun readMemberId(context: Context): String? {
        return try {
            context
                .getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(LocationService.PREFS_MEMBER_ID, null)
        } catch (e: Exception) {
            Timber.w(e, "BootRecoveryReceiver: could not read prefs")
            null
        }
    }

    companion object {
        internal val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
