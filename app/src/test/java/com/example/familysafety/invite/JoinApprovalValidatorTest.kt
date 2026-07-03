package com.example.familysafety.invite

import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class JoinApprovalValidatorTest {

    // 64 hex chars = 32 bytes, matching real key sizes
    private val inviterEd25519 = "ab".repeat(32)
    private val inviterX25519 = "cd".repeat(32)
    private val joinerEd25519 = "12".repeat(32)

    private val inviterMemberId = JoinApprovalValidator.deriveMemberId(inviterEd25519)
    private val joinerMemberId = JoinApprovalValidator.deriveMemberId(joinerEd25519)

    private fun member(memberId: String, ed25519: String) = FamilyMember(
        memberId = memberId,
        displayName = "Member $memberId",
        ed25519PublicKey = ed25519,
        x25519PublicKey = inviterX25519,
        addedAtEpochMs = 1_000L
    )

    private fun groupDef(
        groupId: String = "group-1",
        members: Set<FamilyMember> = setOf(
            member(inviterMemberId, inviterEd25519),
            member(joinerMemberId, joinerEd25519)
        )
    ) = GroupDefinition(
        groupId = groupId,
        groupName = "Test Family",
        createdAtEpochMs = 1_000L,
        creatorMemberId = inviterMemberId,
        members = members,
        version = 2
    )

    private fun validate(
        groupDefinition: GroupDefinition = groupDef(),
        expectedGroupId: String = "group-1",
        expectedInviterMemberId: String = inviterMemberId,
        inviterKeyHex: String = inviterEd25519,
        myMemberId: String = joinerMemberId
    ) = JoinApprovalValidator.validate(
        groupDefinition = groupDefinition,
        expectedGroupId = expectedGroupId,
        expectedInviterMemberId = expectedInviterMemberId,
        inviterEd25519PublicKeyHex = inviterKeyHex,
        myMemberId = myMemberId
    )

    // ── deriveMemberId ────────────────────────────────────────────────────────

    @Test
    fun `deriveMemberId matches CryptoProvider derivation`() {
        // Reference implementation: SHA-256 over the key BYTES, first 16 bytes, hex
        val keyBytes = inviterEd25519.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(keyBytes)
            .take(16).joinToString("") { "%02x".format(it) }

        assertEquals(expected, JoinApprovalValidator.deriveMemberId(inviterEd25519))
        assertEquals(32, JoinApprovalValidator.deriveMemberId(inviterEd25519).length)
    }

    @Test
    fun `deriveMemberId differs for different keys`() {
        assertNotEquals(
            JoinApprovalValidator.deriveMemberId(inviterEd25519),
            JoinApprovalValidator.deriveMemberId(joinerEd25519)
        )
    }

    // ── validate: happy path ──────────────────────────────────────────────────

    @Test
    fun `valid approval passes`() {
        assertNull(validate())
    }

    @Test
    fun `inviter key hex case is ignored when matching the roster entry`() {
        val def = groupDef(
            members = setOf(
                member(inviterMemberId, inviterEd25519.uppercase()),
                member(joinerMemberId, joinerEd25519)
            )
        )
        assertNull(validate(groupDefinition = def))
    }

    // ── validate: forgery attempts ────────────────────────────────────────────

    @Test
    fun `rejects signing key that does not hash to the scanned inviter ID`() {
        // Attacker signs with their own key: it cannot hash to the QR's inviterMemberId.
        val attackerKey = "ee".repeat(32)
        val reason = validate(inviterKeyHex = attackerKey)
        assertNotNull(reason)
        assertTrue(reason!!.contains("does not hash"))
    }

    @Test
    fun `rejects group definition for a different group`() {
        val reason = validate(groupDefinition = groupDef(groupId = "other-group"))
        assertNotNull(reason)
        assertTrue(reason!!.contains("group ID"))
    }

    @Test
    fun `rejects definition where the inviter is not a member`() {
        val def = groupDef(members = setOf(member(joinerMemberId, joinerEd25519)))
        assertNotNull(validate(groupDefinition = def))
    }

    @Test
    fun `rejects definition where the roster carries a different key for the inviter`() {
        // A definition claiming the inviter's memberId but binding a different key —
        // would let an attacker swap keys underneath a known ID.
        val def = groupDef(
            members = setOf(
                member(inviterMemberId, "ff".repeat(32)),
                member(joinerMemberId, joinerEd25519)
            )
        )
        val reason = validate(groupDefinition = def)
        assertNotNull(reason)
        assertTrue(reason!!.contains("does not match the signing key"))
    }

    @Test
    fun `rejects definition that does not include this device`() {
        val def = groupDef(members = setOf(member(inviterMemberId, inviterEd25519)))
        val reason = validate(groupDefinition = def)
        assertNotNull(reason)
        assertTrue(reason!!.contains("does not include this device"))
    }

    @Test
    fun `rejects non-hex inviter key without throwing`() {
        val reason = validate(inviterKeyHex = "not-hex-at-all")
        assertNotNull(reason)
        assertTrue(reason!!.contains("not valid hex"))
    }
}
