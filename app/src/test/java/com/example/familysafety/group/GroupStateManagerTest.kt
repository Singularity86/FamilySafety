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
    
    private val testMemberId = "test_member_123"
    
    @Before
    fun setup() {
        mockPersistence = mockk(relaxed = true)
        mockCryptoProvider = mockk(relaxed = true)
        
        coEvery { mockPersistence.loadGroupState() } returns null
        
        groupStateManager = GroupStateManager(
            memberId = testMemberId,
            persistence = mockPersistence,
            cryptoProvider = mockCryptoProvider
        )
    }
    
    @After
    fun teardown() {
        clearAllMocks()
    }
    
    @Test
    fun `creates new group successfully`() = runTest {
        val member = FamilyMember(
            memberId = testMemberId,
            displayName = "Test User",
            ed25519PublicKey = ByteArray(32) { 0 },
            x25519PublicKey = ByteArray(32) { 0 },
            addedAtEpochMs = System.currentTimeMillis()
        )
        
        val groupDef = GroupDefinition(
            groupId = "group_123",
            groupName = "Test Family",
            members = listOf(member),
            version = 1,
            creatorMemberId = testMemberId
        )
        
        groupStateManager.createGroup(groupDef)
        
        val currentGroup = groupStateManager.groupDefinition.first()
        assertNotNull(currentGroup)
        assertEquals(groupDef.groupId, currentGroup?.groupId)
        assertEquals(1, currentGroup?.version)
    }
    
    @Test
    fun `updates group definition correctly`() = runTest {
        // Create initial group
        val member = FamilyMember(
            memberId = testMemberId,
            displayName = "Test User",
            ed25519PublicKey = ByteArray(32) { 0 },
            x25519PublicKey = ByteArray(32) { 0 },
            addedAtEpochMs = System.currentTimeMillis()
        )
        
        val initialGroup = GroupDefinition(
            groupId = "group_123",
            groupName = "Test Family",
            members = listOf(member),
            version = 1,
            creatorMemberId = testMemberId
        )
        
        groupStateManager.createGroup(initialGroup)
        
        // Update with new member
        val newMember = FamilyMember(
            memberId = "new_member_456",
            displayName = "New User",
            ed25519PublicKey = ByteArray(32) { 1 },
            x25519PublicKey = ByteArray(32) { 1 },
            addedAtEpochMs = System.currentTimeMillis()
        )
        
        val updatedGroup = initialGroup.copy(
            members = initialGroup.members + newMember,
            version = 2
        )
        
        groupStateManager.updateGroupDefinition(updatedGroup)
        
        val currentGroup = groupStateManager.groupDefinition.first()
        assertEquals(2, currentGroup?.version)
        assertEquals(2, currentGroup?.members?.size)
    }
    
    @Test
    fun `persists group state on creation`() = runTest {
        val member = FamilyMember(
            memberId = testMemberId,
            displayName = "Test User",
            ed25519PublicKey = ByteArray(32) { 0 },
            x25519PublicKey = ByteArray(32) { 0 },
            addedAtEpochMs = System.currentTimeMillis()
        )
        
        val groupDef = GroupDefinition(
            groupId = "group_123",
            groupName = "Test Family",
            members = listOf(member),
            version = 1,
            creatorMemberId = testMemberId
        )
        
        groupStateManager.createGroup(groupDef)
        
        coVerify { mockPersistence.saveGroupState(groupDef) }
    }
}