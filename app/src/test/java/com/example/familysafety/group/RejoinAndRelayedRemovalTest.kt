package com.example.familysafety.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A member who leaves and restores from their recovery phrase comes back with the same member
 * ID, so removals are recorded per membership ([Tombstones]) and carry proofs ([RemovalProof])
 * that let any member pass them on. Signature checking of the proofs happens in the sync layer;
 * here the verified authorizers are given directly, as the validator receives them.
 */
class RejoinAndRelayedRemovalTest {

    private fun member(seed: String, joinEpoch: Int = 0): FamilyMember {
        val ed25519 = seed.repeat(64).take(64)
        return FamilyMember(
            memberId = GroupTransitionValidator.deriveMemberIdFromKey(ed25519),
            displayName = "Member $seed",
            ed25519PublicKey = ed25519,
            x25519PublicKey = seed.repeat(64).take(64),
            addedAtEpochMs = 1_000L,
            joinEpoch = joinEpoch
        )
    }

    private val creator = member("a")
    private val alice = member("b")
    private val bob = member("c")
    private val aliceBack = alice.copy(joinEpoch = 1, addedAtEpochMs = 9_000L)

    private fun group(
        members: Set<FamilyMember>,
        removed: Set<String> = emptySet(),
        version: Long = 3
    ) = GroupDefinition(
        groupId = "group-1",
        groupName = "Family",
        createdAtEpochMs = 500L,
        creatorMemberId = creator.memberId,
        members = members,
        version = version,
        previousStateHash = "unused",
        removedMemberIds = removed
    )

    private fun successorOf(
        current: GroupDefinition,
        members: Set<FamilyMember>,
        removed: Set<String> = current.removedMemberIds
    ) = current.copy(
        members = members,
        removedMemberIds = removed,
        version = current.version + 1,
        previousStateHash = current.computeStateHash()
    )

    // ── tombstone keys ────────────────────────────────────────────────────────

    @Test
    fun `the first membership keeps the bare member ID older builds wrote`() {
        assertEquals(alice.memberId, Tombstones.key(alice))
        assertEquals("${alice.memberId}#1", Tombstones.key(aliceBack))
        assertEquals(alice.memberId, Tombstones.memberIdOf("${alice.memberId}#1"))
        assertEquals(1, Tombstones.epochOf("${alice.memberId}#1"))
        assertEquals(0, Tombstones.epochOf(alice.memberId))
    }

    @Test
    fun `a returning member gets the membership after their last removal`() {
        assertEquals(0, Tombstones.nextJoinEpoch(alice.memberId, emptySet()))
        assertEquals(1, Tombstones.nextJoinEpoch(alice.memberId, setOf(alice.memberId)))
        assertEquals(
            2,
            Tombstones.nextJoinEpoch(alice.memberId, setOf(alice.memberId, "${alice.memberId}#1", bob.memberId))
        )
    }

    @Test
    fun `existing rosters hash as before, and a membership number is covered by the hash`() {
        val plain = group(setOf(creator, alice))
        assertEquals(plain.computeStateHash(), plain.copy(members = setOf(creator, alice.copy(joinEpoch = 0))).computeStateHash())
        assertNotEquals(plain.computeStateHash(), plain.copy(members = setOf(creator, alice.copy(joinEpoch = 1))).computeStateHash())
    }

    @Test
    fun `removal proofs are outside the hash`() {
        val plain = group(setOf(creator), removed = setOf(alice.memberId))
        val withProof = plain.copy(removalProofs = setOf(RemovalProof(alice.memberId, alice.memberId, "00")))
        assertEquals(plain.computeStateHash(), withProof.computeStateHash())
    }

    // ── letting a member back in ──────────────────────────────────────────────

    @Test
    fun `a member who left can be let back in as their next membership`() {
        val afterLeave = group(setOf(creator, bob), removed = setOf(alice.memberId))
        val readmitted = successorOf(afterLeave, setOf(creator, bob, aliceBack))
        assertNull(GroupTransitionValidator.validate(afterLeave, readmitted, bob.memberId))
    }

    @Test
    fun `the membership that ended can never come back`() {
        val afterLeave = group(setOf(creator, bob), removed = setOf(alice.memberId))
        val resurrected = successorOf(afterLeave, setOf(creator, bob, alice))
        assertNotNull(GroupTransitionValidator.validate(afterLeave, resurrected, creator.memberId))
    }

    @Test
    fun `a phone that missed the leave accepts the return when the leave is proven`() {
        val stale = group(setOf(creator, alice, bob))
        val remote = successorOf(stale, setOf(creator, bob, aliceBack), removed = setOf(alice.memberId))
        val proven = mapOf(alice.memberId to alice.memberId)

        assertNull(GroupTransitionValidator.validate(stale, remote, bob.memberId, verifiedRemovalAuthorizers = proven))
        assertNotNull(GroupTransitionValidator.validate(stale, remote, bob.memberId))
    }

    @Test
    fun `a new membership without ending the old one is refused`() {
        val current = group(setOf(creator, alice, bob))
        val remote = successorOf(current, setOf(creator, bob, aliceBack))
        assertNotNull(GroupTransitionValidator.validate(current, remote, creator.memberId))
    }

    @Test
    fun `a membership number may not go backwards`() {
        val current = group(setOf(creator, aliceBack, bob), removed = setOf(alice.memberId))
        val remote = successorOf(current, setOf(creator, alice, bob), removed = setOf(alice.memberId, "${alice.memberId}#1"))
        assertNotNull(GroupTransitionValidator.validate(current, remote, creator.memberId))
    }

    @Test
    fun `a returning member can leave again`() {
        val current = group(setOf(creator, aliceBack, bob), removed = setOf(alice.memberId))
        val remote = successorOf(current, setOf(creator, bob), removed = setOf(alice.memberId, Tombstones.key(aliceBack)))
        assertNull(GroupTransitionValidator.validate(current, remote, alice.memberId))
    }

    // ── relayed removals ──────────────────────────────────────────────────────

    @Test
    fun `a creator removal relayed by another member is accepted with the creator's proof`() {
        val current = group(setOf(creator, alice, bob))
        val remote = successorOf(current, setOf(creator, alice), removed = setOf(bob.memberId))

        assertNotNull(GroupTransitionValidator.validate(current, remote, alice.memberId))
        assertNull(
            GroupTransitionValidator.validate(
                current, remote, alice.memberId,
                verifiedRemovalAuthorizers = mapOf(bob.memberId to creator.memberId)
            )
        )
    }

    @Test
    fun `a departure relayed by another member is accepted with the leaver's proof`() {
        val current = group(setOf(creator, alice, bob))
        val remote = successorOf(current, setOf(creator, bob), removed = setOf(alice.memberId))
        assertNull(
            GroupTransitionValidator.validate(
                current, remote, bob.memberId,
                verifiedRemovalAuthorizers = mapOf(alice.memberId to alice.memberId)
            )
        )
    }

    @Test
    fun `a proof from a member who may not remove others counts for nothing`() {
        val current = group(setOf(creator, alice, bob))
        val remote = successorOf(current, setOf(creator, alice), removed = setOf(bob.memberId))
        assertNotNull(
            GroupTransitionValidator.validate(
                current, remote, alice.memberId,
                verifiedRemovalAuthorizers = mapOf(bob.memberId to alice.memberId)
            )
        )
    }

    @Test
    fun `every new tombstone needs its own proof`() {
        val current = group(setOf(creator, alice, bob))
        val remote = successorOf(current, setOf(creator), removed = setOf(alice.memberId, bob.memberId))
        assertNotNull(
            GroupTransitionValidator.validate(
                current, remote, alice.memberId,
                verifiedRemovalAuthorizers = mapOf(alice.memberId to alice.memberId)
            )
        )
    }

    // ── merging ───────────────────────────────────────────────────────────────

    @Test
    fun `a merge keeps the returning membership over the one that ended`() {
        val readmitted = group(setOf(creator, bob, aliceBack), removed = setOf(alice.memberId), version = 6)
        val stale = group(setOf(creator, alice, bob), version = 6)

        for ((winner, ours) in listOf(readmitted to stale, stale to readmitted)) {
            val merged = GroupStateMerge.merge(winner = winner, ours = ours) ?: winner
            assertEquals(aliceBack, merged.findMemberById(alice.memberId))
            assertEquals(setOf(alice.memberId), merged.removedMemberIds)
        }
    }

    @Test
    fun `a merge carries removal proofs for its tombstones`() {
        val proof = RemovalProof(alice.memberId, alice.memberId, "00")
        val left = group(setOf(creator, bob), removed = setOf(alice.memberId), version = 6)
            .copy(removalProofs = setOf(proof))
        val other = group(setOf(creator, alice, bob), version = 6)

        val merged = GroupStateMerge.merge(winner = other, ours = left)
        assertNotNull(merged)
        assertEquals(setOf(proof), merged!!.removalProofs)
    }
}
