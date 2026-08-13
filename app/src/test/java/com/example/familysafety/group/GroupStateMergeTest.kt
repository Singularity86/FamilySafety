package com.example.familysafety.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The property that matters here is convergence: two devices that edited from the same
 * parent must end up holding byte-identical state without a human choosing between them.
 * These tests drive both sides of the exchange rather than checking the merge in isolation,
 * because a merge that is individually correct on each device can still fail to agree.
 */
class GroupStateMergeTest {

    /**
     * Member IDs must be the real SHA-256 of the signing key — the validator enforces that,
     * so a merged state built from invented IDs would be rejected in ways the merge itself
     * is not responsible for.
     */
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

    private val creator = member("a")
    private val bob = member("b")
    private val carol = member("c")
    private val dave = member("d")

    private fun baseGroup(vararg members: FamilyMember) = GroupDefinition(
        groupId = "group-1",
        groupName = "Family",
        createdAtEpochMs = 1_700_000_000_000L,
        creatorMemberId = creator.memberId,
        members = members.toSet(),
        version = 5,
        previousStateHash = null,
        fileEncryptionKey = "ab".repeat(32)
    )

    /** Runs the real two-device exchange and returns what each side ends up holding. */
    private fun reconcile(
        ours: GroupDefinition,
        theirs: GroupDefinition
    ): Pair<GroupDefinition, GroupDefinition> {
        val winnerForUs = GroupStateMerge.pickWinner(ours, theirs)
        val winnerForThem = GroupStateMerge.pickWinner(theirs, ours)
        // Both devices must independently name the same winner, or nothing else holds.
        assertEquals(winnerForUs.computeStateHash(), winnerForThem.computeStateHash())

        val ourResult = if (winnerForUs === ours) ours
        else GroupStateMerge.merge(winner = theirs, ours = ours) ?: theirs
        val theirResult = if (winnerForThem === theirs) theirs
        else GroupStateMerge.merge(winner = ours, ours = theirs) ?: ours

        return ourResult to theirResult
    }

    @Test
    fun concurrentAdditionsConvergeToTheUnionOfBothRosters() {
        // The exact case observed on 2026-08-12: two members approved a join at the same
        // time, each producing a different v5.
        val ours = baseGroup(creator, bob, carol)
        val theirs = baseGroup(creator, bob, dave)

        val (ourResult, theirResult) = reconcile(ours, theirs)

        // Whichever side merged holds the union; the winner is one broadcast behind and
        // adopts it through the ordinary successor path.
        val merged = listOf(ourResult, theirResult).maxByOrNull { it.version }!!
        assertEquals(
            setOf(creator.memberId, bob.memberId, carol.memberId, dave.memberId),
            merged.members.map { it.memberId }.toSet()
        )
    }

    @Test
    fun bothSidesAgreeOnTheWinnerRegardlessOfArgumentOrder() {
        val ours = baseGroup(creator, bob, carol)
        val theirs = baseGroup(creator, bob, dave)

        assertEquals(
            GroupStateMerge.pickWinner(ours, theirs).computeStateHash(),
            GroupStateMerge.pickWinner(theirs, ours).computeStateHash()
        )
    }

    @Test
    fun theMergedStateIsAnOrdinarySuccessorOfTheWinner() {
        // This is what lets the winner accept the merge with no special case: it must chain
        // and be exactly one version ahead.
        val ours = baseGroup(creator, bob, carol)
        val theirs = baseGroup(creator, bob, dave)
        val winner = GroupStateMerge.pickWinner(ours, theirs)
        val loser = if (winner === ours) theirs else ours

        val merged = GroupStateMerge.merge(winner = winner, ours = loser)!!

        assertEquals(winner.version + 1, merged.version)
        assertEquals(winner.computeStateHash(), merged.previousStateHash)
        assertNull(
            "the winner must accept the merge through the normal rules",
            GroupTransitionValidator.validate(winner, merged, loser.members.first().memberId)
        )
    }

    @Test
    fun mergingIdenticalRostersProducesNothing() {
        // Without this, two devices holding equivalent states would each keep "merging" the
        // other and bumping the version forever.
        val ours = baseGroup(creator, bob)
        val theirs = baseGroup(creator, bob)

        assertNull(GroupStateMerge.merge(winner = theirs, ours = ours))
    }

    @Test
    fun aMergeThatOnlyLearnsMembersDoesNotBumpTheVersionPointlessly() {
        val winner = baseGroup(creator, bob, carol)
        val ours = baseGroup(creator, bob)

        // We know strictly less than the winner, so there is nothing to contribute.
        assertNull(GroupStateMerge.merge(winner = winner, ours = ours))
    }

    @Test
    fun aRemovedMemberIsNotResurrectedByTheOtherSideStillListingThem() {
        // The dangerous case. The creator removed Carol; concurrently Bob added Dave from a
        // state that still had her. A plain union would quietly readmit her.
        val creatorSide = baseGroup(creator, bob).copy(
            removedMemberIds = setOf(carol.memberId)
        )
        val bobSide = baseGroup(creator, bob, carol, dave)

        val (ourResult, theirResult) = reconcile(creatorSide, bobSide)
        val merged = listOf(ourResult, theirResult).maxByOrNull { it.version }!!

        assertFalse(
            "removed member must not come back through a merge",
            merged.members.any { it.memberId == carol.memberId }
        )
        assertTrue(merged.members.any { it.memberId == dave.memberId })
        assertTrue(merged.removedMemberIds.contains(carol.memberId))
    }

    @Test
    fun tombstonesAreUnionedSoNeitherSideLosesARemoval() {
        val ours = baseGroup(creator, dave).copy(removedMemberIds = setOf(bob.memberId))
        val theirs = baseGroup(creator, dave).copy(removedMemberIds = setOf(carol.memberId))

        val winner = GroupStateMerge.pickWinner(ours, theirs)
        val loser = if (winner === ours) theirs else ours
        val merged = GroupStateMerge.merge(winner = winner, ours = loser)!!

        assertEquals(setOf(bob.memberId, carol.memberId), merged.removedMemberIds)
    }

    @Test
    fun theGroupKeySurvivesAMergeWithAPeerThatPredatesIt() {
        val withKey = baseGroup(creator, bob, carol)
        val withoutKey = baseGroup(creator, bob, dave).copy(fileEncryptionKey = null)

        val merged = GroupStateMerge.merge(winner = withoutKey, ours = withKey)!!

        assertEquals(
            "losing the group key would silently break every shared file",
            withKey.fileEncryptionKey,
            merged.fileEncryptionKey
        )
    }

    @Test
    fun mergeIsIdempotent() {
        val ours = baseGroup(creator, bob, carol)
        val theirs = baseGroup(creator, bob, dave)
        val winner = GroupStateMerge.pickWinner(ours, theirs)
        val loser = if (winner === ours) theirs else ours

        val once = GroupStateMerge.merge(winner = winner, ours = loser)!!

        // Re-merging the result against the same winner must add nothing further.
        assertNull(GroupStateMerge.merge(winner = once, ours = loser))
    }

    @Test
    fun aThreeWayForkStillConvergesOnTheSameRoster() {
        // Three members approving at once is rarer but not impossible, and pairwise merges
        // must not depend on the order the devices happen to meet.
        val a = baseGroup(creator, bob)
        val b = baseGroup(creator, bob, carol)
        val c = baseGroup(creator, bob, dave)

        fun pairwise(x: GroupDefinition, y: GroupDefinition): GroupDefinition {
            val w = GroupStateMerge.pickWinner(x, y)
            val l = if (w === x) y else x
            return GroupStateMerge.merge(winner = w, ours = l) ?: w
        }

        val leftFirst = pairwise(pairwise(a, b), c)
        val rightFirst = pairwise(a, pairwise(b, c))

        assertEquals(
            leftFirst.members.map { it.memberId }.toSet(),
            rightFirst.members.map { it.memberId }.toSet()
        )
        assertEquals(
            setOf(creator.memberId, bob.memberId, carol.memberId, dave.memberId),
            leftFirst.members.map { it.memberId }.toSet()
        )
    }

    @Test
    fun pickWinnerIsStableWhenStatesAreIdentical() {
        val a = baseGroup(creator, bob)
        val b = baseGroup(creator, bob)

        assertSame(a, GroupStateMerge.pickWinner(a, b))
    }

    @Test
    fun theMergedStateCarriesEveryMemberRecordIntact() {
        val ours = baseGroup(creator, bob, carol)
        val theirs = baseGroup(creator, bob, dave)
        val winner = GroupStateMerge.pickWinner(ours, theirs)
        val loser = if (winner === ours) theirs else ours

        val merged = GroupStateMerge.merge(winner = winner, ours = loser)!!

        val daveRecord = merged.members.find { it.memberId == dave.memberId }
        assertNotNull(daveRecord)
        assertEquals(dave.ed25519PublicKey, daveRecord!!.ed25519PublicKey)
        assertEquals(dave.x25519PublicKey, daveRecord.x25519PublicKey)
    }
}
