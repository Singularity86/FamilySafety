package com.example.familysafety.location

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import timber.log.Timber

/**
 * What stands between this app and staying alive in the background, read from Android rather
 * than guessed.
 *
 * Two questions a "my phone dropped off the map" report could not answer before:
 *
 * - **Why did it stop?** Android 11+ keeps a short history of why each process ended. Nothing
 *   read it, so a phone that went quiet for hours left no trace of whether memory, the battery
 *   manager, a crash or the user ended it.
 * - **Is it allowed back?** "Restricted" battery use blocks a background restart outright, so
 *   the alarm and WorkManager watchdogs fail silently. It also drops the battery-optimisation
 *   exemption, which is all the Settings banner checked — so it looked like ordinary
 *   "Optimized", whose fix does not lift it.
 */
object BackgroundHealth {

    /** How many past stops are worth showing. Android keeps a few dozen at most. */
    const val MAX_STOPS = 5

    enum class BatteryMode { UNRESTRICTED, OPTIMIZED, RESTRICTED }

    /** One recorded end of this app's main process. */
    data class Stop(
        val atMs: Long,
        val reason: String,
        /** Android's own wording, for the diagnostics report only — it is not user copy. */
        val systemDetail: String?
    )

    fun batteryMode(context: Context): BatteryMode {
        if (isBackgroundRestricted(context)) return BatteryMode.RESTRICTED
        return if (LocationPermissionHelper.isIgnoringBatteryOptimizations(context)) {
            BatteryMode.UNRESTRICTED
        } else {
            BatteryMode.OPTIMIZED
        }
    }

    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching {
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        }.getOrDefault(false)
    }

    /**
     * The most recent ends of the main process, newest first. Empty below Android 11, where
     * the history does not exist — callers say so rather than showing "none".
     */
    fun recentStops(context: Context, max: Int = MAX_STOPS): List<Stop> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 0)
                // Other processes of this package would only add noise; the main one is
                // the one that shares location.
                .filter { it.processName == context.packageName }
                .take(max)
                .map { Stop(it.timestamp, describe(it.reason), it.description) }
        }.onFailure { Timber.w(it, "BackgroundHealth: could not read exit reasons") }
            .getOrDefault(emptyList())
    }

    /** Plain words for an [ApplicationExitInfo] reason code. */
    fun describe(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed it to free memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android closed it for using too much battery"
        ApplicationExitInfo.REASON_FREEZER -> "Android froze it in the background"
        ApplicationExitInfo.REASON_SIGNALED -> "The phone's system closed it"
        ApplicationExitInfo.REASON_OTHER -> "The phone's system closed it"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "Stopped from the phone's settings"
        ApplicationExitInfo.REASON_USER_STOPPED -> "Stopped from the phone's task manager"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "Updated"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "Turned off or changed in the phone's settings"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "A permission was changed"
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "It crashed"
        ApplicationExitInfo.REASON_ANR -> "It stopped responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "It failed to start"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Something it depends on stopped"
        ApplicationExitInfo.REASON_EXIT_SELF -> "It closed itself"
        else -> "Unknown reason"
    }
}
