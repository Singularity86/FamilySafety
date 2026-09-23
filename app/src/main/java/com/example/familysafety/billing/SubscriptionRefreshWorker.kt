package com.example.familysafety.billing

import android.content.Context
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
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Keeps a family's subscription "warm" by periodically asking Play whether it is still
 * active. This is the entire enforcement mechanism: there is no server to push a "your
 * subscription lapsed" event, so instead the family's entitlement quietly expires on its own
 * whenever this stops succeeding — see [EntitlementCalculator.SUBSCRIPTION_STALENESS_MS].
 *
 * Only the device holding the subscription accomplishes anything here; on every other family
 * device [BillingManager.refreshFromPlay] correctly finds nothing and this is a no-op. That
 * is fine — it costs one cheap Play query, and it means any member's app can become "the
 * payer" without special configuration if the family moves who is subscribed.
 */
class SubscriptionRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /** Entry point rather than `@HiltWorker` — see FileTransferWorker for why. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SubscriptionRefreshEntryPoint {
        fun billingManager(): BillingManager
    }

    override suspend fun doWork(): Result {
        return try {
            val billingManager = EntryPointAccessors
                .fromApplication(applicationContext, SubscriptionRefreshEntryPoint::class.java)
                .billingManager()
            billingManager.refreshFromPlay()
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: refresh failed")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SubscriptionRefreshWorker"
        const val WORK_NAME = "subscription_refresh"

        /**
         * Once a day comfortably clears the staleness window (a few days, see
         * EntitlementCalculator) even if a run or two is skipped for lack of network.
         */
        private const val REPEAT_INTERVAL_HOURS = 24L

        /** Query-first scheduling — see FileTransferWorker.scheduleIfNeeded for why. */
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

                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(
                    REPEAT_INTERVAL_HOURS, TimeUnit.HOURS
                )
                    .setConstraints(constraints)
                    .build()

                wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
                Timber.i("$TAG: scheduled periodic subscription refresh")
            } catch (e: Exception) {
                Timber.e(e, "$TAG: could not schedule refresh")
            }
        }

        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (e: Exception) {
                Timber.d("$TAG: could not cancel: ${e.message}")
            }
        }
    }
}
