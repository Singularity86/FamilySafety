package com.example.familysafety.group

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {

    private fun createMember(
        id: String = "member_001",
        name: String = "Alice",
        ed25519Key: String = "a".repeat(64),
        x25519Key: String = "b".repeat(64)
    ) = FamilyMember(
        memberId = id,
        displayName = name,
        ed25519PublicKey = ed25519Key,
        x25519PublicKey = x25519Key,
        addedAtEpochMs = 1000L
    )

    private fun createGroup(
        members: Set<FamilyMember> = setOf(createMember()),
        version: Long = 1L,
        creatorId: String = members.first().memberId
    ) = GroupDefinition(
        groupId = "group_123",
        groupName = "Test Family",
        createdAtEpochMs = 1000L,
        creatorMemberId = creatorId,
        members = members,
        version = version
    )

    // ── computeStateHash ────────────────────────────────────────

    @Test
    fun `computeStateHash is deterministic`() {
        val group = createGroup()
        assertEquals(group.computeStateHash(), group.computeStateHash())
    }

    @Test
    fun `computeStateHash returns 64 hex characters`() {
        val hash = createGroup().computeStateHash()
        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `computeStateHash is order independent for members`() {
        val alice = createMember(id = "a_member", name = "Alice", ed25519Key = "a".repeat(64))
        val bob = createMember(id = "b_member", name = "Bob", ed25519Key = "c".repeat(64))

        val group1 = createGroup(members = setOf(alice, bob), creatorId = "a_member")
        val group2 = createGroup(members = setOf(bob, alice), creatorId = "a_member")

        assertEquals(group1.computeStateHash(), group2.computeStateHash())
    }

    @Test
    fun `computeStateHash changes when version changes`() {
        val group1 = createGroup(version = 1L)
        val group2 = createGroup(version = 2L)
        assertNotEquals(group1.computeStateHash(), group2.computeStateHash())
    }

    @Test
    fun `computeStateHash changes when member added`() {
        val alice = createMember(id = "alice")
        val bob = createMember(id = "bob", ed25519Key = "c".repeat(64))

        val group1 = createGroup(members = setOf(alice))
        val group2 = createGroup(members = setOf(alice, bob))

        assertNotEquals(group1.computeStateHash(), group2.computeStateHash())
    }

    @Test
    fun `computeStateHash changes when group name changes`() {
        val group1 = createGroup()
        val group2 = group1.copy(groupName = "Different Name")
        assertNotEquals(group1.computeStateHash(), group2.computeStateHash())
    }

    // ── findMemberById / containsMember ─────────────────────────

    @Test
    fun `findMemberById returns member when found`() {
        val alice = createMember(id = "alice")
        val group = createGroup(members = setOf(alice))
        assertEquals(alice, group.findMemberById("alice"))
    }

    @Test
    fun `findMemberById returns null when not found`() {
        val group = createGroup()
        assertNull(group.findMemberById("nonexistent"))
    }

    @Test
    fun `containsMember returns true for existing member`() {
        val alice = createMember(id = "alice")
        val group = createGroup(members = setOf(alice))
        assertTrue(group.containsMember("alice"))
    }

    @Test
    fun `containsMember returns false for missing member`() {
        val group = createGroup()
        assertFalse(group.containsMember("nonexistent"))
    }

    // ── GroupOperationResult ────────────────────────────────────

    @Test
    fun `Success wraps value correctly`() {
        val result: GroupOperationResult<String> = GroupOperationResult.Success("ok")
        assertTrue(result is GroupOperationResult.Success)
        assertEquals("ok", (result as GroupOperationResult.Success).value)
    }

    @Test
    fun `Failure wraps error correctly`() {
        val result: GroupOperationResult<String> = GroupOperationResult.Failure(GroupError.NotGroupMember)
        assertTrue(result is GroupOperationResult.Failure)
        assertEquals(GroupError.NotGroupMember, (result as GroupOperationResult.Failure).error)
    }
}
