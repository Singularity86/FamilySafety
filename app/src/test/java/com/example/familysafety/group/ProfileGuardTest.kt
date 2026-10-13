package com.example.familysafety.group

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * F25: a member's profile and the family's subscription change only by their author, and only
 * forward in time. Ed25519 is replaced by a transparent stand-in (SHA-256 of key ‖ message);
 * what's under test is the rule, not the primitive.
 */
class ProfileGuardTest {

    private fun fakeSign(keyHex: String, message: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(keyHex.toByteArray() + message)
            .joinToString("") { "%02x".format(it) }

    private val verify = { message: ByteArray, sig: String, key: String -> fakeSign(key, message) == sig }

    private val danaKey = "dd".repeat(32)
    private val bobKey = "bb".repeat(32)

    private fun dana(name: String = "Dana", at: Long? = 100L, signed: Boolean = true): FamilyMember {
        val m = FamilyMember("dana", name, danaKey, "ee".repeat(32), 1L, profileUpdatedAtMs = at)
        return if (signed) m.copy(profileSignature = fakeSign(danaKey, ProfileGuard.profileMessage("g", m))) else m
    }
    private val bob = FamilyMember("bob", "Bob", bobKey, "ff".repeat(32), 1L)

    private fun family(dana: FamilyMember) = GroupDefinition(
        groupId = "g", groupName = "F", createdAtEpochMs = 1L, creatorMemberId = "bob",
        members = setOf(bob, dana), version = 1L
    )

    private fun danaIn(result: GroupDefinition) = result.findMemberById("dana")!!

    @Test
    fun `a newer rename signed by Dana is taken from any relay`() {
        val result = ProfileGuard.reconcile(family(dana()), family(dana("D", at = 200L)), "bob", verify)
        assertEquals("D", danaIn(result).displayName)
    }

    @Test
    fun `an older signed profile replayed by a relay can't roll a rename back`() {
        val result = ProfileGuard.reconcile(family(dana("D", at = 200L)), family(dana("Dana", at = 100L)), "bob", verify)
        assertEquals("D", danaIn(result).displayName)
    }

    @Test
    fun `an insider can't rename someone else`() {
        val forged = dana("Not Dana", at = 300L, signed = false).copy(profileSignature = "00")
        val result = ProfileGuard.reconcile(family(dana()), family(forged), "bob", verify)
        assertEquals("Dana", danaIn(result).displayName)
        assertEquals(100L, danaIn(result).profileUpdatedAtMs)
    }

    @Test
    fun `Dana's own update is taken directly, if not older`() {
        val unsignedFromHerOldBuild = dana("Dee", at = 150L, signed = false)
        assertEquals("Dee", danaIn(ProfileGuard.reconcile(family(dana()), family(unsignedFromHerOldBuild), "dana", verify)).displayName)
        val stale = dana("Old", at = 50L, signed = false)
        assertEquals("Dana", danaIn(ProfileGuard.reconcile(family(dana()), family(stale), "dana", verify)).displayName)
    }

    @Test
    fun `a relay through an old phone that dropped the signature keeps ours`() {
        val stripped = dana(signed = false).copy(profileUpdatedAtMs = null)
        val result = ProfileGuard.reconcile(family(dana()), family(stripped), "bob", verify)
        assertEquals(dana(), danaIn(result))
    }

    @Test
    fun `subscription changes need the subscriber's signature and a newer time`() {
        val current = family(dana()).copy(subscriberMemberId = "dana", subscriptionConfirmedAtEpochMs = 100L)
        val signedNewer = current.copy(
            subscriptionConfirmedAtEpochMs = 200L,
            subscriptionSignature = fakeSign(danaKey, ProfileGuard.subscriptionMessage("g", "dana", 200L))
        )
        assertEquals(200L, ProfileGuard.reconcile(current, signedNewer, "bob", verify).subscriptionConfirmedAtEpochMs)

        val claimedByBob = current.copy(subscriberMemberId = "bob", subscriptionConfirmedAtEpochMs = 300L)
        val result = ProfileGuard.reconcile(current, claimedByBob, "dana", verify)
        assertEquals("dana", result.subscriberMemberId)
        assertEquals(100L, result.subscriptionConfirmedAtEpochMs)
    }

    @Test
    fun `nothing clears a subscription`() {
        val current = family(dana()).copy(subscriberMemberId = "dana", subscriptionConfirmedAtEpochMs = 100L)
        val cleared = current.copy(subscriberMemberId = null, subscriptionConfirmedAtEpochMs = null)
        assertEquals("dana", ProfileGuard.reconcile(current, cleared, "dana", verify).subscriberMemberId)
    }

    @Test
    fun `signing strings`() {
        val m = FamilyMember("dana", "Dana", danaKey, "ee".repeat(32), 1L, avatarHash = "ab", colorHue = 120.5f, profileUpdatedAtMs = 100L)
        assertEquals("PROFILE:v1:g:dana:0:100:Dana:ab:12050", String(ProfileGuard.profileMessage("g", m)))
        assertEquals("PROFILE:v1:g:dana:0::Dana::", String(ProfileGuard.profileMessage("g", m.copy(avatarHash = null, colorHue = null, profileUpdatedAtMs = null))))
        assertEquals("SUBSCRIPTION:v1:g:dana:100", String(ProfileGuard.subscriptionMessage("g", "dana", 100L)))
    }

    @Test
    fun `profile fields stay outside the state hash`() {
        // Keeping ours must never make two phones disagree about the chain.
        assertEquals(family(dana()).computeStateHash(), family(dana("Other", at = 999L)).computeStateHash())
    }
}
