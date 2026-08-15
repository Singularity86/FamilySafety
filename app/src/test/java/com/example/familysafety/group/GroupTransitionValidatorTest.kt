package com.example.familysafety.group

import org.junit.Assert.*
import org.junit.Test

class GroupTransitionValidatorTest {

    // 64 hex chars = 32 bytes, matching real key sizes
    private val creatorKey = "aa".repeat(32)
    private val aliceKey = "bb".repeat(32)
    private val bobKey = "cc".repeat(32)
    private val newcomerKey = "dd".repeat(32)

    private val creatorId = GroupTransitionValidator.deriveMemberIdFromKey(creatorKey)
    private val aliceId = GroupTransitionValidator.deriveMemberIdFromKey(aliceKey)
    private val bobId = GroupTransitionValidator.deriveMemberIdFromKey(bobKey)
    private val newcomerId = GroupTransitionValidator.deriveMemberIdFromKey(newcomerKey)

    private fun member(id: String, ed25519: String, x25519: String = "ee".repeat(32)) =
        FamilyMember(
            memberId = id,
            displayName = "Member ${id.take(6)}",
            ed25519PublicKey = ed25519,
            x25519PublicKey = x25519,
            addedAtEpochMs = 1_000L
        )

    private val creator = member(creatorId, creatorKey)
    private val alice = member(aliceId, aliceKey)
    private val bob = member(bobId, bobKey)

    private fun group(
        members: Set<FamilyMember> = setOf(creator, alice, bob),
        version: Long = 3,
        groupName: String = "Family",
        previousStateHash: String? = "unused",
        groupId: String = "group-1",
        creatorMemberId: String = creatorId,
        createdAtEpochMs: Long = 500L
    ) = GroupDefinition(
        groupId = groupId,
        groupName = groupName,
        createdAtEpochMs = createdAtEpochMs,
        creatorMemberId = creatorMemberId,
        members = members,
        version = version,
        previousStateHash = previousStateHash
    )

    /** Successor with a correctly chained previousStateHash. */
    private fun successorOf(
        current: GroupDefinition,
        members: Set<FamilyMember> = current.members,
        groupName: String = current.groupName
    ) = current.copy(
        members = members,
        groupName = groupName,
        version = current.version + 1,
        previousStateHash = current.computeStateHash()
    )

    // ── happy paths ───────────────────────────────────────────────────────────

    @Test
    fun `any member may add a consistent new member`() {
        val current = group()
        val remote = successorOf(current, members = current.members + member(newcomerId, newcomerKey))
        assertNull(GroupTransitionValidator.validate(current, remote, aliceId))
    }

    @Test
    fun `creator may remove another member`() {
        val current = group()
        val remote = successorOf(current, members = setOf(creator, alice))
        assertNull(GroupTransitionValidator.validate(current, remote, creatorId))
    }

    @Test
    fun `a member may remove themselves`() {
        val current = group()
        val remote = successorOf(current, members = setOf(creator, alice))
        assertNull(GroupTransitionValidator.validate(current, remote, bobId))
    }

    @Test
    fun `creator may rename the group`() {
        val current = group()
        val remote = successorOf(current, groupName = "New Name")
        assertNull(GroupTransitionValidator.validate(current, remote, creatorId))
    }

    @Test
    fun `version jump with authorized cumulative changes passes`() {
        // We are 2 versions behind; the creator relays a state that added a member
        // and removed bob. Chain can't be verified across the gap, but the deltas
        // are all creator-authorized.
        val current = group(version = 3)
        val remote = group(
            members = setOf(creator, alice, member(newcomerId, newcomerKey)),
            version = 5,
            previousStateHash = "some-intermediate-hash"
        )
        assertNull(GroupTransitionValidator.validate(current, remote, creatorId))
    }

    // ── removal authorization ─────────────────────────────────────────────────

    @Test
    fun `non-creator may not remove another member`() {
        val current = group()
        val remote = successorOf(current, members = setOf(creator, alice)) // bob removed
        val reason = GroupTransitionValidator.validate(current, remote, aliceId)
        assertNotNull(reason)
        assertTrue(reason!!.contains("only the group creator may remove"))
    }

    @Test
    fun `non-creator may not remove the creator`() {
        val current = group()
        val remote = successorOf(current, members = setOf(alice, bob))
        assertNotNull(GroupTransitionValidator.validate(current, remote, aliceId))
    }

    @Test
    fun `self-removal bundled with removing someone else is rejected`() {
        val current = group()
        val remote = successorOf(current, members = setOf(creator)) // alice AND bob gone
        assertNotNull(GroupTransitionValidator.validate(current, remote, aliceId))
    }

    // ── identity and key integrity ────────────────────────────────────────────

    @Test
    fun `updater must be in our current roster`() {
        val current = group()
        val outsiderId = GroupTransitionValidator.deriveMemberIdFromKey("ff".repeat(32))
        val remote = successorOf(current)
        val reason = GroupTransitionValidator.validate(current, remote, outsiderId)
        assertNotNull(reason)
        assertTrue(reason!!.contains("not a member"))
    }

    @Test
    fun `key substitution under an existing member ID is rejected`() {
        val current = group()
        val swapped = member(bobId, newcomerKey) // bob's id, attacker's key
        val remote = successorOf(current, members = setOf(creator, alice, swapped))
        val reason = GroupTransitionValidator.validate(current, remote, creatorId)
        assertNotNull(reason)
        assertTrue(reason!!.contains("key rotation is not supported"))
    }

    @Test
    fun `key comparison is case-insensitive`() {
        val current = group()
        val sameKeyUppercase = member(bobId, bobKey.uppercase())
        val remote = successorOf(current, members = setOf(creator, alice, sameKeyUppercase))
        assertNull(GroupTransitionValidator.validate(current, remote, creatorId))
    }

    @Test
    fun `added member whose ID does not match its key is rejected`() {
        val current = group()
        val inconsistent = member("0123456789abcdef".repeat(2), newcomerKey)
        val remote = successorOf(current, members = current.members + inconsistent)
        val reason = GroupTransitionValidator.validate(current, remote, aliceId)
        assertNotNull(reason)
        assertTrue(reason!!.contains("does not match its signing key"))
    }

    @Test
    fun `added member with malformed key is rejected without throwing`() {
        val current = group()
        val malformed = FamilyMember(
            memberId = "zz",
            displayName = "Broken",
            ed25519PublicKey = "not-hex",
            x25519PublicKey = "also-not-hex",
            addedAtEpochMs = 1_000L
        )
        val remote = successorOf(current, members = current.members + malformed)
        val reason = GroupTransitionValidator.validate(current, remote, aliceId)
        assertNotNull(reason)
        assertTrue(reason!!.contains("malformed key"))
    }

    // ── immutable group identity ──────────────────────────────────────────────

    @Test
    fun `changing the group ID is rejected`() {
        val current = group()
        val remote = successorOf(current).copy(groupId = "other-group")
        assertNotNull(GroupTransitionValidator.validate(current, remote, creatorId))
    }

    @Test
    fun `changing the creator is rejected`() {
        val current = group()
        val remote = successorOf(current).copy(creatorMemberId = aliceId)
        assertNotNull(GroupTransitionValidator.validate(current, remote, creatorId))
    }

    @Test
    fun `changing the creation timestamp is rejected`() {
        val current = group()
        val remote = successorOf(current).copy(createdAtEpochMs = 999_999L)
        assertNotNull(GroupTransitionValidator.validate(current, remote, creatorId))
    }

    @Test
    fun `non-creator may not rename the group`() {
        val current = group()
        val remote = successorOf(current, groupName = "Hijacked")
        val reason = GroupTransitionValidator.validate(current, remote, aliceId)
        assertNotNull(reason)
        assertTrue(reason!!.contains("rename"))
    }

    // ── hash chain ────────────────────────────────────────────────────────────

    @Test
    fun `direct successor with wrong previousStateHash is rejected`() {
        val current = group()
        val remote = current.copy(
            version = current.version + 1,
            previousStateHash = "0".repeat(64) // wrong parent
        )
        val reason = GroupTransitionValidator.validate(current, remote, creatorId)
        assertNotNull(reason)
        assertTrue(reason!!.contains("chain"))
    }

    @Test
    fun `direct successor with null previousStateHash is rejected`() {
        val current = group()
        val remote = current.copy(version = current.version + 1, previousStateHash = null)
        assertNotNull(GroupTransitionValidator.validate(current, remote, creatorId))
    }

    // ── relay tolerance ───────────────────────────────────────────────────────

    @Test
    fun `metadata changes by a different member are tolerated for relay support`() {
        // Bob relays a FULL_SYNC state in which alice changed her own display name.
        val current = group()
        val renamedAlice = alice.copy(displayName = "Alice Renamed", colorHue = 120f)
        val remote = successorOf(current, members = setOf(creator, renamedAlice, bob))
        assertNull(GroupTransitionValidator.validate(current, remote, bobId))
    }

    // ── removal tombstones ────────────────────────────────────────────────────
    //
    // Tombstones are what make merging two rosters safe (see GroupStateMerge). If any of
    // these rules can be evaded, a merge readmits someone who was removed.

    @Test
    fun `creator may tombstone another member`() {
        val current = group()
        val remote = successorOf(current, members = setOf(creator, bob))
            .copy(removedMemberIds = setOf(aliceId))

        assertNull(GroupTransitionValidator.validate(current, remote, creatorId))
    }

    @Test
    fun `a member may tombstone themselves`() {
        val current = group()
        val remote = successorOf(current, members = setOf(creator, bob))
            .copy(removedMemberIds = setOf(aliceId))

        assertNull(GroupTransitionValidator.validate(current, remote, aliceId))
    }

    @Test
    fun `a non-creator may not tombstone someone else`() {
        val current = group()
        val remote = successorOf(current, members = setOf(creator, bob))
            .copy(removedMemberIds = setOf(aliceId))

        assertNotNull(GroupTransitionValidator.validate(current, remote, bobId))
    }

    @Test
    fun `dropping a tombstone cannot readmit the member`() {
        // Dropping a tombstone is how a removed member gets back in without anyone
        // authorizing an addition. What matters is that they stay out — the rejection is
        // keyed on the member reappearing, not on the tombstone set shrinking, so it holds
        // whether the sender omitted the tombstone deliberately or simply does not know
        // tombstones exist.
        val current = group().copy(removedMemberIds = setOf(aliceId))
        val remote = successorOf(current).copy(removedMemberIds = emptySet())

        val reason = GroupTransitionValidator.validate(current, remote, creatorId)
        assertNotNull(reason)
        assertTrue(reason!!.contains("present in the roster"))
    }

    @Test
    fun `a peer that does not know about tombstones is still accepted`() {
        // The interop case, and the reason the old rule was wrong. Builds from before
        // tombstones existed serialize no such field, so it arrives as an empty set. Reading
        // that as "they withdrew our tombstones" and rejecting meant that as soon as one
        // member left, a device stopped accepting *every* update from an older peer —
        // including member additions. Rosters diverged permanently, which reads to a user as
        // family members going missing.
        val current = group(members = setOf(creator, bob)).copy(removedMemberIds = setOf(aliceId))
        // Older peer: no tombstones, and it does not list the removed member either.
        val remote = successorOf(current, members = setOf(creator, bob))
            .copy(removedMemberIds = emptySet())

        assertNull(GroupTransitionValidator.validate(current, remote, creatorId))
    }

    @Test
    fun `an older peer adding a member is accepted while our removal stands`() {
        val newcomer = member(
            GroupTransitionValidator.deriveMemberIdFromKey("ee".repeat(32)),
            "ee".repeat(32)
        )
        val current = group(members = setOf(creator, bob)).copy(removedMemberIds = setOf(aliceId))
        val remote = successorOf(current, members = setOf(creator, bob, newcomer))
            .copy(removedMemberIds = emptySet())

        assertNull(GroupTransitionValidator.validate(current, remote, bobId))
    }

    @Test
    fun `a tombstoned member may not appear in the roster`() {
        val current = group(members = setOf(creator, bob))
            .copy(removedMemberIds = setOf(aliceId))
        val remote = successorOf(current, members = setOf(creator, alice, bob))

        val reason = GroupTransitionValidator.validate(current, remote, creatorId)
        assertNotNull(reason)
        assertTrue(reason!!.contains("present in the roster"))
    }

    // ── concurrent siblings ───────────────────────────────────────────────────

    @Test
    fun `a concurrent sibling missing a member we hold is not treated as a removal`() {
        // Equal versions mean they never heard about the member, not that they removed one.
        // Applying the successor rule here is what made concurrent edits unresolvable.
        val current = group(members = setOf(creator, alice, bob))
        val sibling = group(members = setOf(creator, alice))

        assertNotNull(
            "a successor dropping a member without a tombstone is still refused",
            GroupTransitionValidator.validate(current, successorOf(current, setOf(creator, alice)), aliceId)
        )
        assertNull(
            "the same shape as a concurrent sibling is legitimate",
            GroupTransitionValidator.validateConcurrent(current, sibling, aliceId)
        )
    }

    @Test
    fun `a concurrent sibling still cannot resurrect a tombstoned member`() {
        val current = group(members = setOf(creator, bob))
            .copy(removedMemberIds = setOf(aliceId))
        val sibling = group(members = setOf(creator, alice, bob))

        assertNotNull(GroupTransitionValidator.validateConcurrent(current, sibling, bobId))
    }

    @Test
    fun `a concurrent sibling still cannot rotate keys`() {
        val current = group()
        val impostor = alice.copy(ed25519PublicKey = "dd".repeat(32))
        val sibling = group(members = setOf(creator, impostor, bob))

        assertNotNull(GroupTransitionValidator.validateConcurrent(current, sibling, bobId))
    }

    @Test
    fun `a concurrent sibling still cannot add a member whose id does not match its key`() {
        val current = group()
        val forged = member("0".repeat(32), "dd".repeat(32))
        val sibling = group(members = setOf(creator, alice, bob, forged))

        assertNotNull(GroupTransitionValidator.validateConcurrent(current, sibling, bobId))
    }

    @Test
    fun `a concurrent sibling from a non-member is still refused`() {
        val current = group(members = setOf(creator, alice))
        val sibling = group(members = setOf(creator, alice, bob))

        assertNotNull(GroupTransitionValidator.validateConcurrent(current, sibling, bobId))
    }
}
