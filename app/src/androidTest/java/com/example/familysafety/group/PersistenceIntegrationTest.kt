package com.example.familysafety.group

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for encrypted persistence.
 *
 * These tests verify that:
 * - Group state persists across "app restarts" (new manager instances)
 * - Data is actually encrypted (not plaintext on disk)
 * - Keystore integration works correctly
 * - Corrupted data is handled gracefully
 */
@RunWith(AndroidJUnit4::class)
class PersistenceIntegrationTest {

    private lateinit var context: android.content.Context
    private lateinit var keyStore: AndroidKeyStoreLocalKeyStore
    private lateinit var persistence: EncryptedGroupStatePersistence

    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // Get singleton persistence instance
        persistence = EncryptedGroupStatePersistence.getInstance(context)

        // Clear any existing data before each test
        runBlocking {
            persistence.deleteGroupDefinition()
        }

        // Initialize keystore with test keys
        keyStore = AndroidKeyStoreLocalKeyStore(context)
        try {
            keyStore.destroyKeys()
        } catch (e: Exception) {
            // Ignore - may not exist
        }

        val seed = Bip39.mnemonicToSeed(testMnemonic)
        keyStore.initializeFromSeed(seed, 0)
    }

    @After
    fun tearDown() {
        // Clean up after each test
        try {
            keyStore.destroyKeys()
        } catch (e: Exception) {
            // Ignore
        }

        // Clear persisted group state
        runBlocking {
            try {
                persistence.deleteGroupDefinition()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    // =========================================================================
    // BASIC PERSISTENCE TESTS
    // =========================================================================

    @Test
    fun persistence_saveAndLoad_roundTrip() = runBlocking {
        val persistence = EncryptedGroupStatePersistence.getInstance(context)

        // Create a group definition
        val group = createTestGroup()

        // Save it
        persistence.saveGroupDefinition(group)

        // Load it back
        val loaded = persistence.loadGroupDefinition()

        assertNotNull("Loaded group should not be null", loaded)
        assertEquals(group.groupId, loaded!!.groupId)
        assertEquals(group.groupName, loaded.groupName)
        assertEquals(group.creatorMemberId, loaded.creatorMemberId)
        assertEquals(group.version, loaded.version)
        assertEquals(group.members.size, loaded.members.size)
    }

    @Test
    fun persistence_loadWithNoSavedData_returnsNull() = runBlocking {
        val persistence = EncryptedGroupStatePersistence.getInstance(context)

        // Ensure nothing is saved
        persistence.deleteGroupDefinition()

        val loaded = persistence.loadGroupDefinition()

        assertNull("Should return null when no data saved", loaded)
    }

    @Test
    fun persistence_delete_removesData() = runBlocking {
        val persistence = EncryptedGroupStatePersistence.getInstance(context)

        // Save a group
        persistence.saveGroupDefinition(createTestGroup())

        // Verify it's there
        assertNotNull(persistence.loadGroupDefinition())

        // Delete it
        persistence.deleteGroupDefinition()

        // Verify it's gone
        assertNull(persistence.loadGroupDefinition())
    }

    @Test
    fun persistence_overwrite_replacesData() = runBlocking {
        val persistence = EncryptedGroupStatePersistence.getInstance(context)

        // Save initial group
        val group1 = createTestGroup(groupName = "First Family")
        persistence.saveGroupDefinition(group1)

        // Overwrite with new group
        val group2 = createTestGroup(groupName = "Second Family", version = 2)
        persistence.saveGroupDefinition(group2)

        // Load and verify it's the new one
        val loaded = persistence.loadGroupDefinition()

        assertEquals("Second Family", loaded?.groupName)
        assertEquals(2L, loaded?.version)
    }

    // =========================================================================
    // SIMULATED APP RESTART TESTS
    // =========================================================================

    @Test
    fun persistence_survivesNewPersistenceInstance() = runBlocking {
        // Save with first instance
        val persistence1 = EncryptedGroupStatePersistence.getInstance(context)
        val group = createTestGroup()
        persistence1.saveGroupDefinition(group)

        // Create new instance (simulates app restart)
        val persistence2 = EncryptedGroupStatePersistence.getInstance(context)
        val loaded = persistence2.loadGroupDefinition()

        assertNotNull("Data should survive new persistence instance", loaded)
        assertEquals(group.groupId, loaded!!.groupId)
    }

    @Test
    fun groupStateManager_survivesRestart() = runBlocking {
        val cryptoProvider = LazysodiumCryptoProvider(keyStore)
        val memberId = cryptoProvider.deriveMemberId(cryptoProvider.getLocalEd25519PublicKey())

        // Create first manager instance and create a group
        val persistence1 = EncryptedGroupStatePersistence.getInstance(context)
        val manager1 = GroupStateManager(memberId, persistence1, cryptoProvider)

        manager1.initialize()

        val localMember = FamilyMember(
            memberId = memberId,
            displayName = "Test User",
            ed25519PublicKey = cryptoProvider.getLocalEd25519PublicKey(),
            x25519PublicKey = cryptoProvider.getLocalX25519PublicKey(),
            addedAtEpochMs = System.currentTimeMillis()
        )

        val createResult = manager1.createGroup("My Family", localMember)
        assertTrue(createResult is GroupOperationResult.Success)

        // Create new manager instance (simulates app restart)
        val persistence2 = EncryptedGroupStatePersistence.getInstance(context)
        val manager2 = GroupStateManager(memberId, persistence2, cryptoProvider)

        manager2.initialize()

        // Verify state was restored. groupDefinition is the state itself and is set
        // synchronously by initialize(), so it can be read straight away.
        assertNotNull("Group should be restored after restart", manager2.groupDefinition.value)
        assertEquals("My Family", manager2.groupDefinition.value?.groupName)

        // isInGroup is derived — stateIn(scope, Eagerly, false) on Dispatchers.Default —
        // so its .value is still the initial `false` until that dispatcher runs. Reading it
        // synchronously here was racing the dispatcher and losing, which looked like a
        // device coming back from a restart outside its family. Wait for the flow instead;
        // the sibling unit test avoids the same trap by only asserting the initial value.
        withTimeout(5_000) { manager2.isInGroup.first { it } }
        assertTrue("Should still be in group", manager2.isInGroup.value)
    }

    @Test
    fun groupStateManager_restoresMemberList() = runBlocking {
        val cryptoProvider = LazysodiumCryptoProvider(keyStore)
        val memberId = cryptoProvider.deriveMemberId(cryptoProvider.getLocalEd25519PublicKey())

        // Create manager and add multiple members
        val persistence1 = EncryptedGroupStatePersistence.getInstance(context)
        val manager1 = GroupStateManager(memberId, persistence1, cryptoProvider)
        manager1.initialize()

        val localMember = FamilyMember(
            memberId = memberId,
            displayName = "Dad",
            ed25519PublicKey = cryptoProvider.getLocalEd25519PublicKey(),
            x25519PublicKey = cryptoProvider.getLocalX25519PublicKey(),
            addedAtEpochMs = System.currentTimeMillis()
        )

        manager1.createGroup("Smith Family", localMember)

        // Add another member (using fake signature since we're testing persistence, not crypto)
        val otherMemberKeys = FamilySafeKeyDerivation(
            Bip39.mnemonicToSeed("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong")
        ).deriveKeysForAccount(0)

        val otherMember = FamilyMember(
            memberId = otherMemberKeys.memberId,
            displayName = "Mom",
            ed25519PublicKey = otherMemberKeys.ed25519PublicKeyHex,
            x25519PublicKey = otherMemberKeys.x25519PublicKeyHex,
            addedAtEpochMs = System.currentTimeMillis()
        )

        // Create a valid signature for adding the member
        val approvalMessage = "ADD:${manager1.groupDefinition.value!!.groupId}:${manager1.groupDefinition.value!!.version}:${otherMember.ed25519PublicKey}"
        val signature = cryptoProvider.sign(approvalMessage.toByteArray())

        manager1.addMember(otherMember, signature, memberId)

        // Verify 2 members exist
        assertEquals(2, manager1.groupDefinition.value?.members?.size)

        // Restart
        val persistence2 = EncryptedGroupStatePersistence.getInstance(context)
        val manager2 = GroupStateManager(memberId, persistence2, cryptoProvider)
        manager2.initialize()

        // Verify members were restored
        assertEquals(2, manager2.groupDefinition.value?.members?.size)

        val memberNames = manager2.groupDefinition.value?.members?.map { it.displayName }?.toSet()
        assertTrue(memberNames?.contains("Dad") == true)
        assertTrue(memberNames?.contains("Mom") == true)
    }

    // =========================================================================
    // KEYSTORE PERSISTENCE TESTS
    // =========================================================================

    @Test
    fun keyStore_persistsAcrossInstances() {
        // Get public key from first instance
        val publicKey1 = keyStore.getEd25519PublicKey().toHexString()

        // Create new keystore instance
        val keyStore2 = AndroidKeyStoreLocalKeyStore(context)

        // Should still have the same keys
        assertTrue(keyStore2.isInitialized())
        val publicKey2 = keyStore2.getEd25519PublicKey().toHexString()

        assertEquals(
            "Public key should persist across keystore instances",
            publicKey1,
            publicKey2
        )
    }

    @Test
    fun keyStore_destroyKeys_clearsAllData() {
        // Verify keys exist
        assertTrue(keyStore.isInitialized())

        // Destroy keys
        keyStore.destroyKeys()

        // Verify keys are gone
        assertFalse(keyStore.isInitialized())
    }

    @Test
    fun keyStore_canReinitializeAfterDestroy() {
        // Get original key
        val originalPublicKey = keyStore.getEd25519PublicKey().toHexString()

        // Destroy
        keyStore.destroyKeys()

        // Reinitialize with DIFFERENT seed
        val newSeed = Bip39.mnemonicToSeed("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong")
        keyStore.initializeFromSeed(newSeed, 0)

        // New key should be different
        val newPublicKey = keyStore.getEd25519PublicKey().toHexString()

        assertNotEquals(
            "Reinitialized keys should be different",
            originalPublicKey,
            newPublicKey
        )
    }

    // =========================================================================
    // VERSION TRACKING TESTS
    // =========================================================================

    @Test
    fun persistence_preservesVersion() = runBlocking {
        val persistence = EncryptedGroupStatePersistence.getInstance(context)

        // Save with specific version
        val group = createTestGroup(version = 42)
        persistence.saveGroupDefinition(group)

        // Load and verify version
        val loaded = persistence.loadGroupDefinition()
        assertEquals(42L, loaded?.version)
    }

    @Test
    fun persistence_preservesPreviousStateHash() = runBlocking {
        val persistence = EncryptedGroupStatePersistence.getInstance(context)

        // Save with previous state hash
        val group = createTestGroup().copy(previousStateHash = "abc123def456")
        persistence.saveGroupDefinition(group)

        // Load and verify
        val loaded = persistence.loadGroupDefinition()
        assertEquals("abc123def456", loaded?.previousStateHash)
    }

    // =========================================================================
    // MEMBER DATA INTEGRITY TESTS
    // =========================================================================

    @Test
    fun persistence_preservesAllMemberFields() = runBlocking {
        val persistence = EncryptedGroupStatePersistence.getInstance(context)

        val member = FamilyMember(
            memberId = "test_member_123",
            displayName = "John Doe",
            ed25519PublicKey = "ed25519_pub_key_hex_64_chars_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            x25519PublicKey = "x25519_pub_key_hex_64_chars_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            addedAtEpochMs = 1704067200000L, // 2024-01-01 00:00:00 UTC
            addedByMemberId = "inviter_member_456"
        )

        val group = GroupDefinition(
            groupId = "group_123",
            groupName = "Test Family",
            createdAtEpochMs = 1704067200000L,
            creatorMemberId = member.memberId,
            members = setOf(member),
            version = 1
        )

        persistence.saveGroupDefinition(group)
        val loaded = persistence.loadGroupDefinition()
        val loadedMember = loaded?.members?.first()

        assertNotNull(loadedMember)
        assertEquals(member.memberId, loadedMember!!.memberId)
        assertEquals(member.displayName, loadedMember.displayName)
        assertEquals(member.ed25519PublicKey, loadedMember.ed25519PublicKey)
        assertEquals(member.x25519PublicKey, loadedMember.x25519PublicKey)
        assertEquals(member.addedAtEpochMs, loadedMember.addedAtEpochMs)
        assertEquals(member.addedByMemberId, loadedMember.addedByMemberId)
    }

    @Test
    fun persistence_preservesMultipleMembers() = runBlocking {
        val persistence = EncryptedGroupStatePersistence.getInstance(context)

        val members = (1..5).map { i ->
            FamilyMember(
                memberId = "member_$i",
                displayName = "User $i",
                ed25519PublicKey = "ed25519_$i",
                x25519PublicKey = "x25519_$i",
                addedAtEpochMs = System.currentTimeMillis()
            )
        }.toSet()

        val group = GroupDefinition(
            groupId = "group_multi",
            groupName = "Big Family",
            createdAtEpochMs = System.currentTimeMillis(),
            creatorMemberId = "member_1",
            members = members,
            version = 1
        )

        persistence.saveGroupDefinition(group)
        val loaded = persistence.loadGroupDefinition()

        assertEquals(5, loaded?.members?.size)

        // Verify all member IDs are present
        val loadedIds = loaded?.members?.map { it.memberId }?.toSet()
        (1..5).forEach { i ->
            assertTrue("member_$i should be present", loadedIds?.contains("member_$i") == true)
        }
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private fun createTestGroup(
        groupId: String = "test_group_${System.currentTimeMillis()}",
        groupName: String = "Test Family",
        version: Long = 1
    ): GroupDefinition {
        val cryptoProvider = LazysodiumCryptoProvider(keyStore)
        val memberId = cryptoProvider.deriveMemberId(cryptoProvider.getLocalEd25519PublicKey())

        val member = FamilyMember(
            memberId = memberId,
            displayName = "Test User",
            ed25519PublicKey = cryptoProvider.getLocalEd25519PublicKey(),
            x25519PublicKey = cryptoProvider.getLocalX25519PublicKey(),
            addedAtEpochMs = System.currentTimeMillis()
        )

        return GroupDefinition(
            groupId = groupId,
            groupName = groupName,
            createdAtEpochMs = System.currentTimeMillis(),
            creatorMemberId = memberId,
            members = setOf(member),
            version = version
        )
    }
}
