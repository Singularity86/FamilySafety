package com.example.familysafety.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber

/**
 * Consolidated boot and package-replace receiver that restores the location service
 * and watchdog chains after any event that could kill pending work.
 *
 * Handled actions:
 *  - BOOT_COMPLETED          — standard post-unlock boot broadcast
 *  - LOCKED_BOOT_COMPLETED   — direct boot (fires before credential unlock, API 24+)
 *  - QUICKBOOT_POWERON       — fast-boot restart on HTC and some other OEMs
 *  - MY_PACKAGE_REPLACED     — app update; process was killed, static flags reset
 *
 * Direct-boot note: during LOCKED_BOOT_COMPLETED only device-protected storage is
 * accessible, and WorkManager / foreground services cannot be started until after
 * credential unlock. We reschedule the AlarmManager chain (device-protected) for
 * that case and defer the FGS + WorkManager work to the subsequent BOOT_COMPLETED.
 */
class BootRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val lockedBoot = intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        Timber.i("BootRecoveryReceiver: received ${intent.action}")
        recoverServices(context, lockedBoot)
    }

    private fun recoverServices(context: Context, lockedBoot: Boolean) {
        val memberId = readMemberId(context, lockedBoot) ?: run {
            Timber.d("BootRecoveryReceiver: no active session — skipping recovery")
            return
        }

        Timber.i("BootRecoveryReceiver: recovering session for ${memberId.take(8)}")

        // AlarmManager is available in both direct-boot and normal contexts.
        ServiceWatchdogReceiver.schedule(context)

        if (lockedBoot) {
            // Foreground services and WorkManager require credential-unlocked storage.
            // BOOT_COMPLETED will fire after unlock and complete the full recovery.
            Timber.d("BootRecoveryReceiver: direct boot — alarms rescheduled; FGS deferred to BOOT_COMPLETED")
            return
        }

        try {
            LocationService.startTracking(context, memberId)
        } catch (e: Exception) {
            Timber.e(e, "BootRecoveryReceiver: failed to start LocationService")
        }

        ServiceWatchdogWorker.scheduleIfNeeded(context)
    }

    private fun readMemberId(context: Context, lockedBoot: Boolean): String? {
        return try {
            // During direct boot only device-protected storage is readable.
            val storageContext = if (lockedBoot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }
            storageContext
                .getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(LocationService.PREFS_MEMBER_ID, null)
        } catch (e: Exception) {
            Timber.w(e, "BootRecoveryReceiver: could not read prefs (direct-boot storage unavailable?)")
            null
        }
    }
}
