package com.example.familysafety.files

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Chases file transfers that are missing chunks, in the background, across restarts.
 *
 * Before this, the only thing that ever repaired a stalled transfer was a user tapping a file
 * that had not downloaded — so a file could sit incomplete for as long as nobody happened to
 * touch it, which is exactly how one took half a day to arrive. The retry schedule lives in
 * the `shared_files` row (`nextAttemptAt`, `attemptCount`), so it survives process death in a
 * way the in-memory MQTT queue does not.
 */
class FileTransferWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /**
     * Hilt injection is done through an entry point rather than `@HiltWorker`.
     *
     * `androidx.hilt:hilt-work` is not a dependency, and adding it requires the application to
     * implement `Configuration.Provider` and the default WorkManager initializer to be removed
     * from the manifest. That initializer is what `ServiceWatchdogWorker` — the location
     * watchdog, the app's core reliability mechanism — depends on. Not worth the risk for this.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface FileTransferEntryPoint {
        fun sharedFileRepository(): SharedFileRepository
    }

    override suspend fun doWork(): Result {
        return try {
            val repository = EntryPointAccessors
                .fromApplication(applicationContext, FileTransferEntryPoint::class.java)
                .sharedFileRepository()

            // WorkManager kills a worker at 10 minutes. Stopping short of that lets the pass
            // finish cleanly and report whether more remains, rather than being cut off.
            val remaining = withTimeoutOrNull(WORK_BUDGET_MS) {
                repository.runRepairPass()
            }

            when {
                remaining == null -> {
                    Timber.w("$TAG: repair pass ran out of time, will retry")
                    Result.retry()
                }
                remaining -> Result.retry()
                else -> Result.success()
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: repair pass failed")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "FileTransferWorker"
        const val WORK_NAME = "file_transfer_repair"
        private const val WORK_BUDGET_MS = 8 * 60_000L

        /**
         * Schedule the periodic pass if one is not already pending.
         *
         * Query-first rather than `REPLACE`, copied from `ServiceWatchdogWorker.scheduleIfNeeded`:
         * REPLACE on every launch resets a healthy worker's schedule, so a frequently-opened
         * app never actually runs it. KEEP alone is not enough either, because it cannot tell
         * a live worker from a cancelled one.
         */
        fun scheduleIfNeeded(context: Context) {
            try {
                val wm = WorkManager.getInstance(context)
                val existing = try {
                    wm.getWorkInfosForUniqueWork(WORK_NAME).get()
                } catch (e: Exception) {
                    emptyList()
                }
                val active = existing.any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                }
                if (active) return

                // Emergency documents have to arrive on cellular too, so this is CONNECTED
                // rather than UNMETERED. Battery and storage guards keep a stalled transfer
                // from being the thing that finishes off a dying phone.
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()

                val request = PeriodicWorkRequestBuilder<FileTransferWorker>(
                    REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()

                wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
                Timber.i("$TAG: scheduled periodic file repair")
            } catch (e: Exception) {
                Timber.e(e, "$TAG: could not schedule file repair")
            }
        }

        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (e: Exception) {
                Timber.d("$TAG: could not cancel: ${e.message}")
            }
        }

        // WorkManager's floor for periodic work is 15 minutes.
        private const val REPEAT_INTERVAL_MINUTES = 15L
    }
}
