package com.example.familysafety.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces a real family's topology, reported 2026-08-14, where three devices each held a
 * different roster:
 *
 * - Daddy created the family and added Courage Phone.
 * - Courage Phone added Extra Phone.
 * - Second phone was missing *both*; Daddy was missing Extra Phone.
 *
 * The shape is a cascade. Once a device rejects one update it never learns about the member
 * that update added — and because an update signed by an unknown member is refused outright,
 * it then cannot accept anything that member sends either. One missed addition compounds into
 * never catching up.
 *
 * The trigger was a hash chain that could not be verified. Phase 2's reconciliation only
 * covered branches sitting at the *same* version; once they advanced to different versions the
 * update fell through to the ordinary rules, failed the chain check, and was rejected
 * permanently.
 */
class DivergedBranchReconciliationTest {

    private fun member(seed: String): FamilyMember {
        val ed25519 = seed.repeat(64).take(64)
        return FamilyMember(
            memberId = GroupTransitionValidator.deriveMemberIdFromKey(ed25519),
            displayName = "Member $seed",
            ed25519PublicKey = ed25519,
            x25519PublicKey = seed.repeat(64).take(64),
            addedAtEpochMs = 1_700_000_000_000L
        )
    }

    private val daddy = member("a")
    private val secondPhone = member("b")
    private val couragePhone = member("c")
    private val extraPhone = member("d")

    private fun group(
        members: Set<FamilyMember>,
        version: Long,
        previousStateHash: String? = null,
        removed: Set<String> = emptySet()
    ) = GroupDefinition(
        groupId = "group-1",
        groupName = "Familia",
        createdAtEpochMs = 1_700_000_000_000L,
        creatorMemberId = daddy.memberId,
        members = members,
        version = version,
        previousStateHash = previousStateHash,
        fileEncryptionKey = "ab".repeat(32),
        removedMemberIds = removed
    )

    /** What Second phone holds: it never learned about anyone added after it joined. */
    private val secondPhoneState = group(setOf(daddy, secondPhone), version = 1)

    /** Daddy's branch, two additions further on and chained to states we never saw. */
    private val daddyState = group(
        members = setOf(daddy, secondPhone, couragePhone),
        version = 2,
        previousStateHash = "a-hash-from-a-branch-we-never-saw"
    )

    @Test
    fun theOrdinaryRulesRejectADivergedBranch() {
        // This is the rejection that used to be final.
        val reason = GroupTransitionValidator.validate(
            current = secondPhoneState,
            remote = daddyState.copy(version = secondPhoneState.version + 1),
            updaterMemberId = daddy.memberId
        )

        assertNotNull(reason)
        assertTrue(reason!!.startsWith(GroupTransitionValidator.CHAIN_MISMATCH_PREFIX))
    }

    @Test
    fun aChainMismatchIsDistinguishableFromARealAuthorizationFailure() {
        // The recovery path keys on this prefix, so a genuine violation must not match it and
        // get quietly merged instead of refused.
        // Chained correctly, so it gets past the chain rule and is judged on its contents.
        // That ordering matters: a forgery that *also* breaks the chain is reported as a chain
        // mismatch and routed to reconciliation, where validateConcurrent applies these same
        // identity rules and refuses it. Either path rejects it; only the message differs.
        val impostor = couragePhone.copy(ed25519PublicKey = "ff".repeat(32))
        val forged = group(
            members = setOf(daddy, secondPhone, impostor),
            version = secondPhoneState.version + 1,
            previousStateHash = secondPhoneState.computeStateHash()
        )

        val reason = GroupTransitionValidator.validate(secondPhoneState, forged, daddy.memberId)
        assertNotNull(reason)
        assertTrue(!reason!!.startsWith(GroupTransitionValidator.CHAIN_MISMATCH_PREFIX))

        // And it is still refused on the reconciliation path.
        assertNotNull(
            GroupTransitionValidator.validateConcurrent(secondPhoneState, forged, daddy.memberId)
        )
    }

    @Test
    fun aDivergedBranchIsAcceptableAsASibling() {
        // Everything except the chain rule still has to hold, and it does.
        assertNull(
            GroupTransitionValidator.validateConcurrent(
                current = secondPhoneState,
                remote = daddyState,
                updaterMemberId = daddy.memberId
            )
        )
    }

    @Test
    fun reconcilingRecoversTheMemberThatWasMissing() {
        val merged = GroupStateMerge.merge(winner = daddyState, ours = secondPhoneState)
            ?: daddyState

        assertTrue("Courage Phone must be recovered", merged.containsMember(couragePhone.memberId))
        assertTrue(merged.containsMember(daddy.memberId))
        assertTrue(merged.containsMember(secondPhone.memberId))
    }

    @Test
    fun beingStrictlyBehindMeansAdoptingTheirStateOutright() {
        // We hold nothing they lack, so there is nothing to merge and no version to burn.
        // The caller treats null as "adopt the winner", which is why it must not be an error.
        assertNull(GroupStateMerge.merge(winner = daddyState, ours = secondPhoneState))
    }

    @Test
    fun theResultIsAnOrdinarySuccessorOfTheBranchWeAdopted() {
        // When we do have something to contribute, the merge chains from the state we adopted,
        // so its author accepts it through the normal rules with no special case. That is what
        // makes both sides converge rather than trading states forever.
        val weAlsoHaveExtra = group(
            members = setOf(daddy, secondPhone, extraPhone),
            version = 1
        )
        val merged = GroupStateMerge.merge(winner = daddyState, ours = weAlsoHaveExtra)!!

        assertEquals(daddyState.version + 1, merged.version)
        assertEquals(daddyState.computeStateHash(), merged.previousStateHash)
        assertNull(
            GroupTransitionValidator.validate(daddyState, merged, secondPhone.memberId)
        )
    }

    @Test
    fun theCascadeUnwinds() {
        // The whole point. Once Courage Phone is back in the roster, the update it signed —
        // the one that added Extra Phone — is acceptable. While Courage Phone was missing,
        // that update was refused for coming from someone we did not know, so Extra Phone
        // could never arrive either.
        val recovered = GroupStateMerge.merge(winner = daddyState, ours = secondPhoneState)
            ?: daddyState

        val couragesAddition = recovered.copy(
            members = recovered.members + extraPhone,
            version = recovered.version + 1,
            previousStateHash = recovered.computeStateHash()
        )

        assertNull(
            "an update from a member we have just recovered must be acceptable",
            GroupTransitionValidator.validate(recovered, couragesAddition, couragePhone.memberId)
        )
    }

    @Test
    fun aRemovalOnOurBranchSurvivesReconciliation() {
        // Merging must not quietly undo a removal. Daddy's branch predates the removal and
        // carries no tombstone, which is also what every peer on a build without tombstones
        // sends.
        val weRemovedExtra = group(
            members = setOf(daddy, secondPhone),
            version = 1,
            removed = setOf(extraPhone.memberId)
        )
        val theirBranchStillHasExtra = group(
            members = setOf(daddy, secondPhone, couragePhone, extraPhone),
            version = 2,
            previousStateHash = "unrelated"
        )

        val merged = GroupStateMerge.merge(winner = theirBranchStillHasExtra, ours = weRemovedExtra)!!

        assertTrue("the addition still lands", merged.containsMember(couragePhone.memberId))
        assertTrue(
            "the removal must stand",
            !merged.containsMember(extraPhone.memberId)
        )
        assertTrue(merged.removedMemberIds.contains(extraPhone.memberId))
    }

    @Test
    fun threeWayDivergenceConvergesOnEveryMember() {
        // Daddy missing Extra Phone, Second phone missing two — the reported state.
        val courageBranch = group(
            members = setOf(daddy, secondPhone, couragePhone, extraPhone),
            version = 3,
            previousStateHash = "another-branch"
        )

        val secondCaughtUp = GroupStateMerge.merge(winner = daddyState, ours = secondPhoneState)
            ?: daddyState
        val everyone = GroupStateMerge.merge(winner = courageBranch, ours = secondCaughtUp)
            ?: courageBranch

        assertEquals(
            setOf(
                daddy.memberId,
                secondPhone.memberId,
                couragePhone.memberId,
                extraPhone.memberId
            ),
            everyone.members.map { it.memberId }.toSet()
        )
    }
}
