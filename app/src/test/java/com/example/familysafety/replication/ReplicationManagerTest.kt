package com.example.familysafety.replication

import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.location.LocationRepository
import com.example.familysafety.location.MemberLocation
import com.example.familysafety.storage.ChatMessageDao
import com.example.familysafety.storage.ChatMessageEntity
import com.example.familysafety.storage.LocationHistoryRepository
import com.example.familysafety.storage.MessageStatus
import com.example.familysafety.storage.MessageType
import com.example.familysafety.transport.TransportProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReplicationManagerTest {

    private lateinit var mockGroupStateManager: GroupStateManager
    private lateinit var mockLocationRepository: LocationRepository
    private lateinit var mockLocationHistoryRepository: LocationHistoryRepository
    private lateinit var mockChatMessageDao: ChatMessageDao
    private lateinit var mockE2EEManager: E2EEManager
    private lateinit var mockTransportProvider: TransportProvider
    private lateinit var replicationManager: ReplicationManager

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        mockGroupStateManager = mockk(relaxed = true)
        mockLocationRepository = mockk(relaxed = true)
        mockLocationHistoryRepository = mockk(relaxed = true)
        mockChatMessageDao = mockk(relaxed = true)
        mockE2EEManager = mockk(relaxed = true)
        mockTransportProvider = mockk(relaxed = true)

        // No group by default
        every { mockGroupStateManager.groupDefinition } returns MutableStateFlow(null)
        every { mockGroupStateManager.localMember } returns MutableStateFlow(null)
        coEvery {
            mockTransportProvider.sendMessage(any(), any(), any(), any(), any())
        } returns true

        replicationManager = ReplicationManager(
            mockGroupStateManager,
            mockLocationRepository,
            mockLocationHistoryRepository,
            mockChatMessageDao,
            mockE2EEManager,
            mockTransportProvider
        )
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `syncState initial value is Idle`() {
        assertEquals(SyncState.Idle, replicationManager.syncState.value)
    }

    // ── setMqttPublisher ──────────────────────────────────────────────────────

    // ── requestFullSync ───────────────────────────────────────────────────────

    @Test
    fun `requestFullSync with no group leaves syncState at Idle`() = runTest {
        replicationManager.requestFullSync()

        assertEquals(SyncState.Idle, replicationManager.syncState.value)
    }

    @Test
    fun `requestFullSync with no local member leaves syncState at Idle`() = runTest {
        every { mockGroupStateManager.groupDefinition } returns MutableStateFlow(
            kotlinx.coroutines.flow.MutableStateFlow(null).value
        )

        replicationManager.requestFullSync()

        assertEquals(SyncState.Idle, replicationManager.syncState.value)
    }

    // ── replicateLocation ─────────────────────────────────────────────────────

    @Test
    fun `replicateLocation without mqttPublisher does not throw`() = runTest {
        val location = MemberLocation(
            memberId = "member_001",
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 10.0f,
            timestamp = 1_700_000_000_000L
        )
        // No publisher set, no group — should silently do nothing
        replicationManager.replicateLocation(location)
    }

    // ── replicateChatMessage ──────────────────────────────────────────────────

    @Test
    fun `replicateChatMessage without mqttPublisher does not throw`() = runTest {
        val message = ChatMessageEntity(
            conversationId = "conv_001",
            senderId = "member_001",
            recipientId = "member_002",
            content = "Hello",
            messageType = MessageType.TEXT,
            isOutgoing = true
        )
        replicationManager.replicateChatMessage(message)
    }

    // ── SyncState sealed class ────────────────────────────────────────────────

    @Test
    fun `SyncState Idle and Syncing are distinct`() {
        assertNotEquals(SyncState.Idle, SyncState.Syncing)
    }

    @Test
    fun `SyncState Synced is distinct from Idle`() {
        assertNotEquals(SyncState.Idle, SyncState.Synced)
    }

    @Test
    fun `SyncState Error carries message`() {
        val state = SyncState.Error("something went wrong")
        assertEquals("something went wrong", state.message)
    }

    // ── ReplicationDataType ───────────────────────────────────────────────────

    @Test
    fun `ReplicationDataType has all expected values`() {
        val types = ReplicationDataType.values().map { it.name }.toSet()
        assertTrue(types.contains("LOCATION_HISTORY"))
        assertTrue(types.contains("CHAT_MESSAGES"))
    }

    // ── ReplicationRequest serialization ─────────────────────────────────────

    @Test
    fun `ReplicationRequest serializes and deserializes correctly`() {
        val request = ReplicationRequest(
            requestId = "req_001",
            requesterId = "member_001",
            dataType = ReplicationDataType.LOCATION_HISTORY,
            targetMemberId = "member_002",
            afterTimestamp = 1_700_000_000_000L,
            limit = 100
        )

        val deserialized = json.decodeFromString<ReplicationRequest>(json.encodeToString(request))

        assertEquals(request.requestId, deserialized.requestId)
        assertEquals(request.requesterId, deserialized.requesterId)
        assertEquals(request.dataType, deserialized.dataType)
        assertEquals(request.targetMemberId, deserialized.targetMemberId)
        assertEquals(request.afterTimestamp, deserialized.afterTimestamp)
        assertEquals(request.limit, deserialized.limit)
    }

    @Test
    fun `ReplicationRequest conversationId is nullable`() {
        val request = ReplicationRequest(
            requestId = "req_002",
            requesterId = "member_001",
            dataType = ReplicationDataType.CHAT_MESSAGES,
            conversationId = null,
            afterTimestamp = 0L
        )

        val deserialized = json.decodeFromString<ReplicationRequest>(json.encodeToString(request))
        assertNull(deserialized.conversationId)
    }

    // ── ReplicationResponse serialization ─────────────────────────────────────

    @Test
    fun `ReplicationResponse serializes and deserializes correctly`() {
        val response = ReplicationResponse(
            requestId = "req_001",
            senderId = "member_001",
            dataType = ReplicationDataType.CHAT_MESSAGES,
            messages = null,
            hasMore = false,
            newestTimestamp = null
        )

        val deserialized = json.decodeFromString<ReplicationResponse>(json.encodeToString(response))

        assertEquals(response.requestId, deserialized.requestId)
        assertEquals(response.senderId, deserialized.senderId)
        assertEquals(response.dataType, deserialized.dataType)
        assertFalse(deserialized.hasMore)
        assertNull(deserialized.newestTimestamp)
    }

    // ── ReplicatedLocation roundtrip ──────────────────────────────────────────

    @Test
    fun `ReplicatedLocation fromMemberLocation and toMemberLocation are inverse`() {
        val original = MemberLocation(
            memberId = "member_001",
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 15.0f,
            timestamp = 1_700_000_000_000L,
            speed = 5.0f,
            bearing = 90.0f
        )

        val replicated = ReplicatedLocation.fromMemberLocation(original)
        val restored = replicated.toMemberLocation()

        assertEquals(original.memberId, restored.memberId)
        assertEquals(original.latitude, restored.latitude, 0.0001)
        assertEquals(original.longitude, restored.longitude, 0.0001)
        assertEquals(original.accuracy, restored.accuracy)
        assertEquals(original.timestamp, restored.timestamp)
        assertEquals(original.speed, restored.speed)
        assertEquals(original.bearing, restored.bearing)
    }

    @Test
    fun `ReplicatedLocation serializes and deserializes correctly`() {
        val loc = ReplicatedLocation(
            memberId = "member_001",
            latitude = 51.5074,
            longitude = -0.1278,
            accuracy = 5.0f,
            timestamp = 1_700_000_000_000L
        )

        val deserialized = json.decodeFromString<ReplicatedLocation>(json.encodeToString(loc))

        assertEquals(loc.memberId, deserialized.memberId)
        assertEquals(loc.latitude, deserialized.latitude, 0.0001)
        assertEquals(loc.longitude, deserialized.longitude, 0.0001)
        assertNull(deserialized.speed)
        assertNull(deserialized.bearing)
    }

    // ── ReplicatedMessage roundtrip ───────────────────────────────────────────

    @Test
    fun `ReplicatedMessage fromChatMessageEntity and toChatMessageEntity are inverse`() {
        val original = ChatMessageEntity(
            messageId = "msg_001",
            conversationId = "alice:bob",
            senderId = "alice",
            recipientId = "bob",
            content = "Hello!",
            messageType = MessageType.TEXT,
            status = MessageStatus.SENT,
            timestamp = 1_700_000_000_000L,
            isOutgoing = true
        )

        val replicated = ReplicatedMessage.fromChatMessageEntity(original)
        val restored = replicated.toChatMessageEntity(replicatedFrom = "peer_001")

        assertEquals(original.messageId, restored.messageId)
        assertEquals(original.conversationId, restored.conversationId)
        assertEquals(original.senderId, restored.senderId)
        assertEquals(original.recipientId, restored.recipientId)
        assertEquals(original.content, restored.content)
        assertEquals(original.messageType, restored.messageType)
        assertEquals(original.timestamp, restored.timestamp)
        assertEquals(original.isOutgoing, restored.isOutgoing)
        assertTrue(restored.isReplicated)
        assertEquals("peer_001", restored.replicatedFrom)
    }

    @Test
    fun `ReplicatedMessage serializes and deserializes correctly`() {
        val msg = ReplicatedMessage(
            messageId = "msg_001",
            conversationId = "alice:bob",
            senderId = "alice",
            recipientId = "bob",
            content = "Test",
            messageType = MessageType.TEXT,
            status = MessageStatus.DELIVERED,
            timestamp = 1_700_000_000_000L,
            isOutgoing = false
        )

        val deserialized = json.decodeFromString<ReplicatedMessage>(json.encodeToString(msg))

        assertEquals(msg.messageId, deserialized.messageId)
        assertEquals(msg.content, deserialized.content)
        assertNull(deserialized.replyToMessageId)
    }

    // ── ReplicationResult sealed class ────────────────────────────────────────

    @Test
    fun `ReplicationResult Success carries itemsReceived and hasMore`() {
        val result = ReplicationResult.Success(itemsReceived = 42, hasMore = true)
        assertEquals(42, result.itemsReceived)
        assertTrue(result.hasMore)
    }

    @Test
    fun `ReplicationResult Error carries message and isRetryable`() {
        val result = ReplicationResult.Error(message = "timeout", isRetryable = true)
        assertEquals("timeout", result.message)
        assertTrue(result.isRetryable)
    }

    @Test
    fun `ReplicationResult NoDataAvailable is distinct from Timeout`() {
        assertNotEquals(ReplicationResult.NoDataAvailable, ReplicationResult.Timeout)
    }

    // ── ReplicationEvent sealed class ─────────────────────────────────────────

    @Test
    fun `ReplicationEvent DataReceived carries fields`() {
        val event = ReplicationEvent.DataReceived(
            peerId = "peer_001",
            dataType = ReplicationDataType.LOCATION_HISTORY,
            itemCount = 10
        )
        assertEquals("peer_001", event.peerId)
        assertEquals(ReplicationDataType.LOCATION_HISTORY, event.dataType)
        assertEquals(10, event.itemCount)
    }

    @Test
    fun `ReplicationEvent SyncStarted and SyncCompleted are distinct`() {
        assertNotEquals(ReplicationEvent.SyncStarted, ReplicationEvent.SyncCompleted)
    }
}
