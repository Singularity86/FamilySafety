package com.example.familysafety.billing

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class EntitlementCalculatorTest {

    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_800_000_000_000L
    private val groupId = "family-1"
    private val paywallIntroducedAt = 1_000_000_000_000L // in the past — new families are gated
    private val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
    private val publicKeyHex = privateKey.generatePublicKey().encoded.joinToString("") { "%02x".format(it) }

    private fun grantCode(expiryEpochMs: Long): String {
        val message = GrantCode.signingMessage(groupId, expiryEpochMs)
        val signer = Ed25519Signer().apply {
            init(true, privateKey)
            update(message, 0, message.size)
        }
        val sig = signer.generateSignature().joinToString("") { "%02x".format(it) }
        return GrantCode.encode(GrantCodePayload(expiryEpochMs, sig))
    }

    private fun compute(
        createdAtEpochMs: Long = now - day,
        grantCode: String? = null,
        subscriptionConfirmedAtEpochMs: Long? = null,
        paywallAt: Long = paywallIntroducedAt
    ) = EntitlementCalculator.compute(
        groupId, createdAtEpochMs, grantCode, subscriptionConfirmedAtEpochMs, now, publicKeyHex, paywallAt
    )

    @Test
    fun familyOlderThanThePaywallIsGrandfathered() {
        assertEquals(Entitlement.Grandfathered, compute(createdAtEpochMs = paywallIntroducedAt - 1))
    }

    @Test
    fun familyCreatedExactlyAtTheCutoffIsNotGrandfathered() {
        // createdAtEpochMs < cutoff is the rule; equal means "created at the same instant the
        // paywall shipped," which is new, not old — it just also isn't within 60 days of
        // `now` in this fixture, so it reads as Expired rather than Trial. Either is fine;
        // what matters is that it is not Grandfathered.
        val result = compute(createdAtEpochMs = paywallIntroducedAt)
        assertTrue(result !is Entitlement.Grandfathered)
    }

    @Test
    fun newFamilyStartsInTrial() {
        val result = compute(createdAtEpochMs = now - day)
        assertTrue(result is Entitlement.Trial)
        assertEquals(59, (result as Entitlement.Trial).daysLeft) // 1 day elapsed of 60
    }

    @Test
    fun trialDayCountRoundsUpSoLastDayIsStillOne() {
        val result = compute(createdAtEpochMs = now - (60 * day) + 1000)
        assertTrue(result is Entitlement.Trial)
        assertEquals(1, (result as Entitlement.Trial).daysLeft)
    }

    @Test
    fun trialExpiresAtSixtyDays() {
        assertEquals(Entitlement.Expired, compute(createdAtEpochMs = now - 60 * day))
    }

    @Test
    fun freshSubscriptionConfirmationWins() {
        val result = compute(
            createdAtEpochMs = now - 90 * day, // trial long over
            subscriptionConfirmedAtEpochMs = now - day
        )
        assertEquals(Entitlement.Subscribed, result)
    }

    @Test
    fun staleSubscriptionConfirmationDoesNotCount() {
        val result = compute(
            createdAtEpochMs = now - 90 * day,
            subscriptionConfirmedAtEpochMs = now - 5 * day
        )
        assertEquals(Entitlement.Expired, result)
    }

    @Test
    fun validGrantCodeWinsEvenAfterTrialExpired() {
        val result = compute(createdAtEpochMs = now - 90 * day, grantCode = grantCode(0L))
        assertEquals(Entitlement.Granted(null), result)
    }

    @Test
    fun expiredGrantCodeFallsThroughToTrialLogic() {
        val result = compute(createdAtEpochMs = now - 90 * day, grantCode = grantCode(now - 1000))
        assertEquals(Entitlement.Expired, result)
    }

    @Test
    fun malformedGrantCodeFallsThroughRatherThanCrashing() {
        val result = compute(createdAtEpochMs = now - day, grantCode = "garbage")
        assertTrue(result is Entitlement.Trial)
    }

    @Test
    fun grandfatheringBeatsEverythingElse() {
        // Even an expired grant code or a stale subscription shouldn't matter for a
        // grandfathered family — but grandfathering is also just cheaper to check first.
        val result = compute(
            createdAtEpochMs = paywallIntroducedAt - 1,
            grantCode = "garbage",
            subscriptionConfirmedAtEpochMs = now - 999 * day
        )
        assertEquals(Entitlement.Grandfathered, result)
    }
}
