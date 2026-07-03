package com.example.familysafety.sync

import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.LazysodiumCryptoProvider
import com.example.familysafety.transport.TransportProvider
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GroupSyncManagerTest {

    private lateinit var mockE2EEManager: E2EEManager
    private lateinit var mockCryptoProvider: LazysodiumCryptoProvider
    private lateinit var mockTransportProvider: TransportProvider
    private lateinit var syncManager: GroupSyncManager

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        mockE2EEManager = mockk(relaxed = true)
        mockCryptoProvider = mockk(relaxed = true)
        mockTransportProvider = mockk(relaxed = true)
        syncManager = GroupSyncManager(mockE2EEManager, mockCryptoProvider, mockTransportProvider)
    }

    // ── Initial state ────────────────────────────────────────────

    @Test
    fun `initial sync state is Idle`() {
        assertEquals(GroupSyncManager.SyncState.Idle, syncManager.syncState.value)
    }

    // ── ChangeType enum ──────────────────────────────────────────

    @Test
    fun `ChangeType has all expected values`() {
        val types = ChangeType.values().map { it.name }.toSet()
        assertTrue(types.contains("MEMBER_ADDED"))
        assertTrue(types.contains("MEMBER_REMOVED"))
        assertTrue(types.contains("NAME_CHANGED"))
        assertTrue(types.contains("VERSION_SYNC"))
        assertTrue(types.contains("CONFLICT_RESOLUTION"))
        assertTrue(types.contains("FULL_SYNC"))
    }

    // ── GroupSyncMessage serialization ───────────────────────────

    @Test
    fun `GroupSyncMessage serializes and deserializes correctly`() {
        val member = FamilyMember(
            memberId = "member_001",
            displayName = "Alice",
            ed25519PublicKey = "a".repeat(64),
            x25519PublicKey = "b".repeat(64),
            addedAtEpochMs = 1000L
        )
        val groupDef = GroupDefinition(
            groupId = "group_123",
            groupName = "Test Family",
            createdAtEpochMs = 1000L,
            creatorMemberId = "member_001",
            members = setOf(member),
            version = 1L
        )
        val message = GroupSyncMessage(
            groupId = "group_123",
            version = 1,
            groupDefinition = groupDef,
            updaterMemberId = "member_001",
            changeType = ChangeType.MEMBER_ADDED,
            changedMemberId = "member_001",
            timestamp = 1700000000000L,
            signature = "deadbeef"
        )

        val serialized = json.encodeToString(message)
        val deserialized = json.decodeFromString<GroupSyncMessage>(serialized)

        assertEquals(message.groupId, deserialized.groupId)
        assertEquals(message.version, deserialized.version)
        assertEquals(message.updaterMemberId, deserialized.updaterMemberId)
        assertEquals(message.changeType, deserialized.changeType)
        assertEquals(message.changedMemberId, deserialized.changedMemberId)
        assertEquals(message.timestamp, deserialized.timestamp)
        assertEquals(message.signature, deserialized.signature)
        assertEquals(groupDef.groupName, deserialized.groupDefinition.groupName)
    }

    @Test
    fun `GroupSyncMessage changedMemberId is nullable`() {
        val member = FamilyMember(
            memberId = "member_001",
            displayName = "Alice",
            ed25519PublicKey = "a".repeat(64),
            x25519PublicKey = "b".repeat(64),
            addedAtEpochMs = 1000L
        )
        val groupDef = GroupDefinition(
            groupId = "group_123",
            groupName = "Test Family",
            createdAtEpochMs = 1000L,
            creatorMemberId = "member_001",
            members = setOf(member),
            version = 1L
        )
        val message = GroupSyncMessage(
            groupId = "group_123",
            version = 1,
            groupDefinition = groupDef,
            updaterMemberId = "member_001",
            changeType = ChangeType.VERSION_SYNC,
            changedMemberId = null,
            timestamp = 1700000000000L,
            signature = ""
        )

        val serialized = json.encodeToString(message)
        val deserialized = json.decodeFromString<GroupSyncMessage>(serialized)

        assertNull(deserialized.changedMemberId)
    }

    // ── GroupUpdateAck serialization ─────────────────────────────

    @Test
    fun `GroupUpdateAck serializes and deserializes correctly`() {
        val ack = GroupUpdateAck(
            groupId = "group_123",
            version = 5,
            memberId = "member_001",
            timestamp = 1700000000000L
        )

        val serialized = json.encodeToString(ack)
        val deserialized = json.decodeFromString<GroupUpdateAck>(serialized)

        assertEquals(ack.groupId, deserialized.groupId)
        assertEquals(ack.version, deserialized.version)
        assertEquals(ack.memberId, deserialized.memberId)
        assertEquals(ack.timestamp, deserialized.timestamp)
    }

    // ── SyncState sealed class ───────────────────────────────────

    @Test
    fun `SyncState Idle is distinct from Syncing`() {
        assertNotEquals(
            GroupSyncManager.SyncState.Idle,
            GroupSyncManager.SyncState.Syncing
        )
    }

    @Test
    fun `SyncState Synced carries version`() {
        val state = GroupSyncManager.SyncState.Synced(version = 42)
        assertEquals(42, state.version)
    }

    @Test
    fun `SyncState Conflict carries local and remote versions`() {
        val state = GroupSyncManager.SyncState.Conflict(localVersion = 3, remoteVersion = 7)
        assertEquals(3, state.localVersion)
        assertEquals(7, state.remoteVersion)
    }

    @Test
    fun `SyncState Error carries message`() {
        val state = GroupSyncManager.SyncState.Error("something went wrong")
        assertEquals("something went wrong", state.message)
    }
}
