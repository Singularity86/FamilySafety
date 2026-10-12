package com.example.familysafety.group

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for GroupStateManager focusing on synchronous state accessible via
 * StateFlow.value and operations whose post-conditions are immediately observable.
 *
 * The derived flows `isInGroup`, `localMember`, and `onlineMembers` use
 * `stateIn(scope, SharingStarted.Eagerly, ...)` backed by Dispatchers.Default.
 * Their .value is only tested for the guaranteed initial value; updates
 * propagated through that dispatcher are not asserted here to avoid flakiness.
 */
class GroupStateManagerTest {

    private lateinit var mockPersistence: GroupStatePersistence
    private lateinit var mockCryptoProvider: CryptoProvider
    private lateinit var gsm: GroupStateManager

    private fun makeLocalMember(id: String = "local_001") = FamilyMember(
        memberId = id,
        displayName = "Alice",
        ed25519PublicKey = "a".repeat(64),
        x25519PublicKey = "b".repeat(64),
        addedAtEpochMs = 1000L
    )

    private fun makeGroup(members: Set<FamilyMember> = setOf(makeLocalMember())) =
        GroupDefinition(
            groupId = "group_123",
            groupName = "Test Family",
            createdAtEpochMs = 1000L,
            creatorMemberId = members.first().memberId,
            members = members,
            version = 1L
        )

    @Before
    fun setup() {
        mockPersistence = mockk(relaxed = true)
        coEvery { mockPersistence.loadGroupDefinition() } returns null
        mockCryptoProvider = mockk(relaxed = true)
        gsm = GroupStateManager("local_001", mockPersistence, mockCryptoProvider)
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `groupDefinition initial value is null`() {
        assertNull(gsm.groupDefinition.value)
    }

    @Test
    fun `memberStates initial value is empty`() {
        assertTrue(gsm.memberStates.value.isEmpty())
    }

    @Test
    fun `isInGroup initial value is false`() {
        assertFalse(gsm.isInGroup.value)
    }

    // ── initialize ────────────────────────────────────────────────────────────

    @Test
    fun `initialize loads persisted group into groupDefinition`() = runTest {
        val group = makeGroup()
        coEvery { mockPersistence.loadGroupDefinition() } returns group

        gsm.initialize()

        assertEquals(group, gsm.groupDefinition.value)
    }

    @Test
    fun `initialize with no persisted group leaves groupDefinition null`() = runTest {
        coEvery { mockPersistence.loadGroupDefinition() } returns null

        gsm.initialize()

        assertNull(gsm.groupDefinition.value)
    }

    @Test
    fun `initialize with persisted group populates memberStates`() = runTest {
        val group = makeGroup()
        coEvery { mockPersistence.loadGroupDefinition() } returns group

        gsm.initialize()

        assertTrue(gsm.memberStates.value.isNotEmpty())
        assertNotNull(gsm.memberStates.value["local_001"])
    }

    @Test
    fun `initialize returns Success`() = runTest {
        coEvery { mockPersistence.loadGroupDefinition() } returns null

        val result = gsm.initialize()

        assertTrue(result is GroupOperationResult.Success)
    }

    @Test
    fun `initialize returns Failure when persistence throws`() = runTest {
        coEvery { mockPersistence.loadGroupDefinition() } throws RuntimeException("disk error")

        val result = gsm.initialize()

        assertTrue(result is GroupOperationResult.Failure)
        assertEquals(GroupError.StorageError, (result as GroupOperationResult.Failure).error)
    }

    // ── createGroup ───────────────────────────────────────────────────────────

    @Test
    fun `createGroup returns Success when not in a group`() = runTest {
        val localMember = makeLocalMember()
        val result = gsm.createGroup("Test Family", localMember)

        assertTrue(result is GroupOperationResult.Success)
    }

    @Test
    fun `createGroup sets groupDefinition on success`() = runTest {
        val localMember = makeLocalMember()
        gsm.createGroup("Test Family", localMember)

        assertNotNull(gsm.groupDefinition.value)
        assertEquals("Test Family", gsm.groupDefinition.value?.groupName)
    }

    @Test
    fun `createGroup populates memberStates on success`() = runTest {
        val localMember = makeLocalMember()
        gsm.createGroup("Test Family", localMember)

        assertTrue(gsm.memberStates.value.containsKey("local_001"))
    }

    @Test
    fun `createGroup saves to persistence`() = runTest {
        gsm.createGroup("Test Family", makeLocalMember())

        coVerify { mockPersistence.saveGroupDefinition(any()) }
    }

    @Test
    fun `createGroup returns Failure when already in a group`() = runTest {
        val localMember = makeLocalMember()
        gsm.createGroup("Test Family", localMember)

        val result = gsm.createGroup("Another Family", localMember)

        assertTrue(result is GroupOperationResult.Failure)
        assertEquals(
            GroupError.MemberAlreadyExists,
            (result as GroupOperationResult.Failure).error
        )
    }

    @Test
    fun `createGroup refuses when a family is stored but not loaded`() = runTest {
        // Onboarding's manager never calls initialize(), so memory alone says "no family".
        coEvery { mockPersistence.loadGroupDefinition() } returns makeGroup()

        val result = gsm.createGroup("Another Family", makeLocalMember())

        assertEquals(
            GroupError.MemberAlreadyExists,
            (result as GroupOperationResult.Failure).error
        )
        coVerify(exactly = 0) { mockPersistence.saveGroupDefinition(any()) }
    }

    @Test
    fun `createGroup refuses when the stored family can't be read`() = runTest {
        coEvery { mockPersistence.loadGroupDefinition() } throws
            com.example.familysafety.storage.StorageUnavailableException("Family")

        val result = gsm.createGroup("Another Family", makeLocalMember())

        assertEquals(GroupError.StorageError, (result as GroupOperationResult.Failure).error)
        coVerify(exactly = 0) { mockPersistence.saveGroupDefinition(any()) }
    }

    @Test
    fun `createGroup returns Failure when persistence throws`() = runTest {
        coEvery { mockPersistence.saveGroupDefinition(any()) } throws RuntimeException("disk error")

        val result = gsm.createGroup("Test Family", makeLocalMember())

        assertTrue(result is GroupOperationResult.Failure)
        assertEquals(GroupError.StorageError, (result as GroupOperationResult.Failure).error)
    }

    // ── addMember ─────────────────────────────────────────────────────────────

    @Test
    fun `addMember returns Failure when not in a group`() = runTest {
        val newMember = FamilyMember(
            memberId = "new_001",
            displayName = "Bob",
            ed25519PublicKey = "c".repeat(64),
            x25519PublicKey = "d".repeat(64),
            addedAtEpochMs = 1000L
        )

        val result = gsm.addMember(newMember, ByteArray(64), "local_001")

        assertTrue(result is GroupOperationResult.Failure)
        assertEquals(GroupError.NotGroupMember, (result as GroupOperationResult.Failure).error)
    }

    @Test
    fun `addMember returns Failure when inviter is not in the group`() = runTest {
        gsm.createGroup("Test Family", makeLocalMember())

        val newMember = FamilyMember(
            memberId = "new_001",
            displayName = "Bob",
            ed25519PublicKey = "c".repeat(64),
            x25519PublicKey = "d".repeat(64),
            addedAtEpochMs = 1000L
        )

        val result = gsm.addMember(newMember, ByteArray(64), "nonexistent_inviter")

        assertTrue(result is GroupOperationResult.Failure)
        assertEquals(GroupError.MemberNotFound, (result as GroupOperationResult.Failure).error)
    }

    // ── removeMember ──────────────────────────────────────────────────────────

    @Test
    fun `removeMember returns Failure when not in a group`() = runTest {
        val result = gsm.removeMember("some_id", ByteArray(64), "local_001")

        assertTrue(result is GroupOperationResult.Failure)
        assertEquals(GroupError.NotGroupMember, (result as GroupOperationResult.Failure).error)
    }

    // ── applyRemoteGroupState ─────────────────────────────────────────────────

    @Test
    fun `applyRemoteGroupState returns Failure when sender not in remote definition`() = runTest {
        val group = makeGroup()

        val result = gsm.applyRemoteGroupState(
            remoteDefinition = group,
            senderSignature = ByteArray(64),
            senderMemberId = "unknown_sender"
        )

        assertTrue(result is GroupOperationResult.Failure)
        assertEquals(GroupError.MemberNotFound, (result as GroupOperationResult.Failure).error)
    }

    // ── F26: absence is not removal ─────────────────────────────────────────────

    private suspend fun joinedFamily(): GroupDefinition {
        val creator = makeLocalMember("creator_001").copy(displayName = "Pat")
        val group = GroupDefinition(
            groupId = "group_123", groupName = "Test Family", createdAtEpochMs = 1000L,
            creatorMemberId = "creator_001", members = setOf(creator, makeLocalMember()), version = 1L
        )
        coEvery { mockPersistence.loadGroupDefinition() } returns group
        gsm.initialize()
        return group
    }

    @Test
    fun `a stale creator roster without us no longer wipes this phone`() = runTest {
        val current = joinedFamily()
        // The creator never heard we joined: their next roster simply lacks us, no tombstone.
        val stale = current.copy(
            version = 2L,
            members = current.members.filter { it.memberId == "creator_001" }.toSet(),
            previousStateHash = current.computeStateHash()
        )

        val result = gsm.applyVerifiedRemoteGroupState(stale, "creator_001")

        assertEquals(GroupError.AbsentWithoutRemoval, (result as GroupOperationResult.Failure).error)
        assertTrue(gsm.groupDefinition.value!!.containsMember("local_001"))
        coVerify(exactly = 0) { mockPersistence.saveGroupDefinition(any()) }
    }

    @Test
    fun `a roster that tombstones our membership still removes us`() = runTest {
        val current = joinedFamily()
        val removal = current.copy(
            version = 2L,
            members = current.members.filter { it.memberId == "creator_001" }.toSet(),
            removedMemberIds = setOf(Tombstones.key(makeLocalMember())),
            previousStateHash = current.computeStateHash()
        )

        val result = gsm.applyVerifiedRemoteGroupState(removal, "creator_001")

        assertTrue(result is GroupOperationResult.Success)
        assertFalse(gsm.groupDefinition.value!!.containsMember("local_001"))
    }

    // ── updateConnectionState ─────────────────────────────────────────────────

    @Test
    fun `updateConnectionState for unknown member does not throw`() = runTest {
        // gsm has no member states yet (not in a group)
        gsm.updateConnectionState("unknown_member", ConnectionState.Unknown)
    }

    // ── GroupError sealed class ───────────────────────────────────────────────

    @Test
    fun `GroupError variants are sealed objects`() {
        // Verify the expected error types are accessible and are sealed class instances
        assertTrue(GroupError.NotGroupMember is GroupError)
        assertTrue(GroupError.MemberNotFound is GroupError)
        assertTrue(GroupError.MemberAlreadyExists is GroupError)
        assertTrue(GroupError.InvalidSignature is GroupError)
        assertTrue(GroupError.StorageError is GroupError)
        assertTrue(GroupError.VersionConflict is GroupError)
        assertTrue(GroupError.NotGroupCreator is GroupError)
    }

    @Test
    fun `GroupError objects are distinct`() {
        assertNotEquals(GroupError.NotGroupMember, GroupError.MemberNotFound)
        assertNotEquals(GroupError.StorageError, GroupError.InvalidSignature)
    }
}
