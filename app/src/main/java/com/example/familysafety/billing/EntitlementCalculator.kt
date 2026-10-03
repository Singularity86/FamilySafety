package com.example.familysafety.billing

/**
 * Pure computation of [Entitlement] from group facts plus the current time. No Android
 * dependency on purpose, so this is exercised directly in JVM unit tests rather than only
 * on-device — unlike `LazysodiumCryptoProvider`, nothing here needs a native library.
 *
 * Order of checks matters: grandfathering and a valid grant code are permanent-ish escapes
 * that should never be shadowed by an expired trial, so they are checked first.
 */
object EntitlementCalculator {

    const val TRIAL_DURATION_MS = 60L * 24 * 60 * 60 * 1000

    /**
     * How long a subscription-confirmed signal from a family member is trusted before it is
     * treated as stale. Longer than Play's own billing-issue grace period so an ordinary
     * missed daily re-check doesn't itself cause a lapse; short enough that a subscription
     * cancelled or refunded days ago stops being honoured by every other device.
     */
    const val SUBSCRIPTION_STALENESS_MS = 4L * 24 * 60 * 60 * 1000

    fun compute(
        groupId: String,
        createdAtEpochMs: Long,
        grantCode: String?,
        subscriptionConfirmedAtEpochMs: Long?,
        nowMs: Long,
        grantPublicKeyHex: String,
        /**
         * Families whose group predates this are exempt forever. Set once, at release, to the
         * epoch millis of the build that introduces the paywall — see [BillingConfig] for the
         * real value. `GroupDefinition.createdAtEpochMs` is already part of the signed group
         * state every device holds, so this needs no new wire field and can't be gamed by
         * leaving and recreating the family, which loses everything else too.
         */
        paywallIntroducedAtEpochMs: Long
    ): Entitlement {
        if (createdAtEpochMs < paywallIntroducedAtEpochMs) return Entitlement.Grandfathered

        if (!grantCode.isNullOrBlank()) {
            val result = GrantCode.verify(grantCode, groupId, grantPublicKeyHex, nowMs)
            if (result is GrantCodeResult.Valid) return Entitlement.Granted(result.expiresAtEpochMs)
        }

        if (subscriptionConfirmedAtEpochMs != null &&
            nowMs - subscriptionConfirmedAtEpochMs < SUBSCRIPTION_STALENESS_MS
        ) {
            return Entitlement.Subscribed
        }

        val trialElapsed = nowMs - createdAtEpochMs
        if (trialElapsed < TRIAL_DURATION_MS) {
            val dayMs = 24 * 60 * 60 * 1000L
            val remainingMs = TRIAL_DURATION_MS - trialElapsed
            // Ceiling division: any part of a day remaining still counts as a full day left,
            // but an exact multiple (e.g. exactly 59 days left) must not round up to 60.
            val daysLeft = ((remainingMs + dayMs - 1) / dayMs).toInt()
            return Entitlement.Trial(daysLeft)
        }

        return Entitlement.Expired
    }
}
