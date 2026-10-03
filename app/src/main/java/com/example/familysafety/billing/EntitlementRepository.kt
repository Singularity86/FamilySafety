package com.example.familysafety.billing

import com.example.familysafety.group.GroupOperationResult
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.sync.ChangeType
import com.example.familysafety.sync.GroupSyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context

/**
 * The one place that answers "is this family allowed to use the paid-gated features right
 * now" — everything else (the nav gate, the trial popup, the paywall screen, the Settings
 * "manage subscription" row) reads [entitlement] instead of doing this math itself.
 *
 * All the actual computation lives in [EntitlementCalculator], which is pure and unit
 * tested. This class exists only to feed it live inputs: the group definition (which
 * changes on its own schedule) and the current time (which changes on no schedule at all —
 * a trial can expire at midnight with nothing else happening, so this also ticks on a timer,
 * or a day-60 countdown would freeze at "1 day left" until some unrelated group update woke
 * it up).
 */
@Singleton
class EntitlementRepository @Inject constructor(
    private val groupStateManager: GroupStateManager,
    private val groupSyncManager: GroupSyncManager,
    @ApplicationContext context: Context
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun tickEveryMinute() = flow {
        while (true) {
            emit(Unit)
            delay(60_000L)
        }
    }

    val entitlement: StateFlow<Entitlement> = combine(
        groupStateManager.groupDefinition,
        tickEveryMinute()
    ) { group, _ ->
        if (group == null) {
            // No group yet (onboarding) — nothing to gate.
            Entitlement.Grandfathered
        } else {
            EntitlementCalculator.compute(
                groupId = group.groupId,
                createdAtEpochMs = group.createdAtEpochMs,
                grantCode = group.grantCode,
                subscriptionConfirmedAtEpochMs = group.subscriptionConfirmedAtEpochMs,
                nowMs = System.currentTimeMillis(),
                grantPublicKeyHex = BillingConfig.GRANT_PUBLIC_KEY_HEX,
                paywallIntroducedAtEpochMs = BillingConfig.PAYWALL_INTRODUCED_AT_EPOCH_MS
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, Entitlement.Grandfathered)

    /**
     * Verifies [code] against the current family's own groupId and, if valid, stores and
     * broadcasts it via [GroupStateManager.redeemGrantCode]. Verification happens here, not
     * inside GroupStateManager, so that class stays free of billing-specific crypto — it
     * only ever persists a code this repository already checked.
     */
    suspend fun redeemGrantCode(code: String): GrantCodeResult {
        val group = groupStateManager.groupDefinition.value
            ?: return GrantCodeResult.InvalidFormat
        val result = GrantCode.verify(
            code, group.groupId, BillingConfig.GRANT_PUBLIC_KEY_HEX, System.currentTimeMillis()
        )
        if (result is GrantCodeResult.Valid) {
            val outcome = groupStateManager.redeemGrantCode(code)
            if (outcome is GroupOperationResult.Success) {
                groupSyncManager.broadcastGroupUpdate(outcome.value, ChangeType.VERSION_SYNC)
            }
        }
        return result
    }

    /**
     * Called after [com.example.familysafety.billing.BillingManager] confirms an active
     * subscription with Play — locally on first purchase, and roughly daily afterward via
     * [SubscriptionRefreshWorker]. Broadcasts so the rest of the family stays entitled too.
     */
    suspend fun recordSubscriptionConfirmed(memberId: String, atEpochMs: Long) {
        val outcome = groupStateManager.confirmSubscription(memberId, atEpochMs)
        if (outcome is GroupOperationResult.Success) {
            groupSyncManager.broadcastGroupUpdate(outcome.value, ChangeType.VERSION_SYNC)
        }
    }
}
