package com.example.familysafety.group

import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class GroupStateManagerTest {

    private lateinit var groupStateManager: GroupStateManager
    private lateinit var mockPersistence: GroupStatePersistence
    private lateinit var mockCryptoProvider: CryptoProvider

    // Use hex-encoded strings for keys (32 bytes = 64 hex characters)
    private val testEd25519PublicKey = "a".repeat(64) // 32 bytes hex
    private val testX25519PublicKey = "b".repeat(64) // 32 bytes hex
    private val testMemberId = "test_member_12345678"

    @Before
    fun setup() {
        mockPersistence = mockk(relaxed = true)
        mockCryptoProvider = mockk(relaxed = true)

        coEvery { mockPersistence.loadGroupDefinition() } returns null

        // Mock the CryptoProvider to return consistent values
        every { mockCryptoProvider.deriveMemberId(any()) } returns testMemberId
        every { mockCryptoProvider.verifySignature(any(), any(), any()) } returns true
        every { mockCryptoProvider.sign(any()) } returns ByteArray(64)

        groupStateManager = GroupStateManager(
            localMemberId = testMemberId,
            persistence = mockPersistence,
            cryptoProvider = mockCryptoProvider
        )
    }

    @After
    fun teardown() {
        clearAllMocks()
    }

    private fun createTestMember(
        memberId: String = testMemberId,
        displayName: String = "Test User",
        ed25519Key: String = testEd25519PublicKey,
        x25519Key: String = testX25519PublicKey
    ): FamilyMember {
        return FamilyMember(
            memberId = memberId,
            displayName = displayName,
            ed25519PublicKey = ed25519Key,
            x25519PublicKey = x25519Key,
            addedAtEpochMs = System.currentTimeMillis()
        )
    }

    @Test
    fun `creates new group successfully`() = runTest {
        val member = createTestMember()

        val result = groupStateManager.createGroup(
            groupName = "Test Family",
            localMember = member
        )

        assertTrue(result is GroupOperationResult.Success)

        val currentGroup = groupStateManager.groupDefinition.first()
        assertNotNull(currentGroup)
        assertEquals("Test Family", currentGroup?.groupName)
        assertEquals(1L, currentGroup?.version)
        assertEquals(1, currentGroup?.members?.size)
        assertTrue(currentGroup?.members?.any { it.memberId == testMemberId } == true)
    }

    @Test
    fun `prevents creating group when already in one`() = runTest {
        val member = createTestMember()

        // Create first group
        groupStateManager.createGroup(
            groupName = "First Family",
            localMember = member
        )

        // Try to create second group
        val result = groupStateManager.createGroup(
            groupName = "Second Family",
            localMember = member
        )

        assertTrue(result is GroupOperationResult.Failure)
        val failure = result as GroupOperationResult.Failure
        assertEquals(GroupError.MemberAlreadyExists, failure.error)
    }

    @Test
    fun `adds member to existing group`() = runTest {
        val creator = createTestMember()

        // Create group first
        groupStateManager.createGroup(
            groupName = "Test Family",
            localMember = creator
        )

        // Add new member
        val newMemberEd25519Key = "c".repeat(64)
        val newMemberX25519Key = "d".repeat(64)
        val newMemberId = "new_member_987654"

        val newMember = FamilyMember(
            memberId = newMemberId,
            displayName = "New User",
            ed25519PublicKey = newMemberEd25519Key,
            x25519PublicKey = newMemberX25519Key,
            addedAtEpochMs = System.currentTimeMillis()
        )

        val result = groupStateManager.addMember(
            newMember = newMember,
            inviterSignature = ByteArray(64),
            inviterMemberId = testMemberId
        )

        assertTrue(result is GroupOperationResult.Success)

        val currentGroup = groupStateManager.groupDefinition.first()
        assertEquals(2, currentGroup?.members?.size)
        assertEquals(2L, currentGroup?.version)
    }

    @Test
    fun `fails to add member when not in group`() = runTest {
        val newMember = createTestMember(memberId = "new_member")

        val result = groupStateManager.addMember(
            newMember = newMember,
            inviterSignature = ByteArray(64),
            inviterMemberId = testMemberId
        )

        assertTrue(result is GroupOperationResult.Failure)
        assertEquals(GroupError.NotGroupMember, (result as GroupOperationResult.Failure).error)
    }

    @Test
    fun `prevents adding duplicate member`() = runTest {
        val member = createTestMember()

        groupStateManager.createGroup(
            groupName = "Test Family",
            localMember = member
        )

        // Try to add the same member again
        val result = groupStateManager.addMember(
            newMember = member,
            inviterSignature = ByteArray(64),
            inviterMemberId = testMemberId
        )

        assertTrue(result is GroupOperationResult.Failure)
        assertEquals(GroupError.MemberAlreadyExists, (result as GroupOperationResult.Failure).error)
    }

    @Test
    fun `persists group state on creation`() = runTest {
        val member = createTestMember()

        groupStateManager.createGroup(
            groupName = "Test Family",
            localMember = member
        )

        coVerify { mockPersistence.saveGroupDefinition(any()) }
    }

    @Test
    fun `loads persisted group on initialize`() = runTest {
        val persistedMember = createTestMember()
        val persistedGroup = GroupDefinition(
            groupId = "persisted_group",
            groupName = "Persisted Family",
            createdAtEpochMs = System.currentTimeMillis(),
            creatorMemberId = testMemberId,
            members = setOf(persistedMember),
            version = 5
        )

        coEvery { mockPersistence.loadGroupDefinition() } returns persistedGroup

        val result = groupStateManager.initialize()

        assertTrue(result is GroupOperationResult.Success)

        val currentGroup = groupStateManager.groupDefinition.first()
        assertEquals("Persisted Family", currentGroup?.groupName)
        assertEquals(5L, currentGroup?.version)
    }

    @Test
    fun `updates connection state for member`() = runTest {
        val member = createTestMember()

        groupStateManager.createGroup(
            groupName = "Test Family",
            localMember = member
        )

        groupStateManager.updateConnectionState(
            memberId = testMemberId,
            newState = ConnectionState.Cloud("inbox/test_topic")
        )

        val memberStates = groupStateManager.memberStates.first()
        val memberState = memberStates[testMemberId]

        assertNotNull(memberState)
        assertTrue(memberState?.connectionState is ConnectionState.Cloud)
    }

    @Test
    fun `updates presence status for member`() = runTest {
        val member = createTestMember()

        groupStateManager.createGroup(
            groupName = "Test Family",
            localMember = member
        )

        groupStateManager.updatePresenceStatus(
            memberId = testMemberId,
            newStatus = PresenceStatus.ACTIVE
        )

        val memberStates = groupStateManager.memberStates.first()
        val memberState = memberStates[testMemberId]

        assertEquals(PresenceStatus.ACTIVE, memberState?.presenceStatus)
    }

    @Test
    fun `isInGroup returns true when in group`() = runTest {
        val member = createTestMember()

        assertFalse(groupStateManager.isInGroup.first())

        groupStateManager.createGroup(
            groupName = "Test Family",
            localMember = member
        )

        assertTrue(groupStateManager.isInGroup.first())
    }

    @Test
    fun `localMember returns correct member`() = runTest {
        val member = createTestMember()

        assertNull(groupStateManager.localMember.first())

        groupStateManager.createGroup(
            groupName = "Test Family",
            localMember = member
        )

        val localMember = groupStateManager.localMember.first()
        assertNotNull(localMember)
        assertEquals(testMemberId, localMember?.memberId)
    }
}
