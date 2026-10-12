package com.example.familysafety.crash

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.familysafety.AppInitializer
import com.example.familysafety.chat.ChatRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Brings the app back to keep sending a crash alert after its process was killed.
 *
 * While the app runs, ChatRepository's retry loop does the work. This covers the gap the
 * loop can't: a crash is exactly when the phone may be rebooted, or Android may kill the app
 * before anyone confirms. The alert itself lives in the `emergency_outbox` table; this only
 * restarts the app and gives it time to send and hear back. Scheduled when an alert is
 * raised; finishes once nothing is owed.
 */
class EmergencyAlertWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /** Entry point rather than @HiltWorker; see FileTransferWorker for why. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EmergencyAlertEntryPoint {
        fun appInitializer(): AppInitializer
        fun chatRepository(): ChatRepository
    }

    override suspend fun doWork(): Result {
        return try {
            val entryPoint = EntryPointAccessors
                .fromApplication(applicationContext, EmergencyAlertEntryPoint::class.java)
            // Connects to the family and starts the in-process retry loop.
            entryPoint.appInitializer().initialize()
            val chat = entryPoint.chatRepository()

            // Stay a while: sending is half of it, the receipts have to come back too.
            val deadline = System.currentTimeMillis() + WORK_BUDGET_MS
            while (System.currentTimeMillis() < deadline) {
                chat.flushEmergencyAlerts()
                if (!chat.hasPendingEmergencyAlerts()) {
                    Timber.i("$TAG: every crash alert delivered or given up")
                    return Result.success()
                }
                delay(POLL_MS)
            }
            Result.retry()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: crash alert pass failed")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EmergencyAlertWorker"
        const val WORK_NAME = "emergency_alert_retry"
        // WorkManager stops a worker at 10 minutes.
        private const val WORK_BUDGET_MS = 8 * 60_000L
        private const val POLL_MS = 15_000L

        fun schedule(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<EmergencyAlertWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: could not schedule crash alert retries")
            }
        }
    }
}
