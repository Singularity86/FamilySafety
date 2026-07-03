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
}
