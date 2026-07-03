package com.example.familysafety.chat

import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.replication.ReplicationManager
import com.example.familysafety.storage.ChatMessageDao
import com.example.familysafety.storage.ChatMessageEntity
import com.example.familysafety.storage.MessageStatus
import com.example.familysafety.storage.MessageType
import com.example.familysafety.transport.TransportProvider
import io.mockk.coEvery
import io.mockk.coVerify
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

/**
 * Tests for ChatRepository focusing on synchronous state management and
 * data-class logic that does not require a live Room database or native crypto.
 *
 * sendTextMessage / handleIncomingMessage require the full E2EE + DAO stack
 * and are covered by instrumented tests.
 */
class ChatRepositoryTest {

    private lateinit var mockChatMessageDao: ChatMessageDao
    private lateinit var mockGroupStateManager: GroupStateManager
    private lateinit var mockE2EEManager: E2EEManager
    private lateinit var mockReplicationManager: ReplicationManager
    private lateinit var mockTransportProvider: TransportProvider
    private lateinit var mockNotificationHelper: ChatNotificationHelper
    private lateinit var chatRepository: ChatRepository

    private val json = Json { ignoreUnknownKeys = true }

    private fun makeMember(id: String = "local_001") = FamilyMember(
        memberId = id,
        displayName = "Alice",
        ed25519PublicKey = "a".repeat(64),
        x25519PublicKey = "b".repeat(64),
        addedAtEpochMs = 1000L
    )

    @Before
    fun setup() {
        mockChatMessageDao = mockk(relaxed = true)
        mockGroupStateManager = mockk(relaxed = true)
        mockE2EEManager = mockk(relaxed = true)
        mockReplicationManager = mockk(relaxed = true)
        mockTransportProvider = mockk(relaxed = true)
        mockNotificationHelper = mockk(relaxed = true)

        // Default: local member is null (not in a group)
        every { mockGroupStateManager.localMember } returns MutableStateFlow(null)
        every { mockGroupStateManager.groupDefinition } returns MutableStateFlow(null)
        coEvery {
            mockTransportProvider.sendMessage(any(), any(), any(), any(), any())
        } returns true

        chatRepository = ChatRepository(
            mockChatMessageDao,
            mockGroupStateManager,
            mockE2EEManager,
            mockReplicationManager,
            mockTransportProvider,
            mockNotificationHelper
        )
    }

    // ── activeConversationId ──────────────────────────────────────────────────

    @Test
    fun `activeConversationId initial value is null`() {
        assertNull(chatRepository.activeConversationId.value)
    }

    @Test
    fun `setActiveConversation updates activeConversationId`() {
        chatRepository.setActiveConversation("conv_abc")
        assertEquals("conv_abc", chatRepository.activeConversationId.value)
    }

    @Test
    fun `setActiveConversation with null clears activeConversationId`() {
        chatRepository.setActiveConversation("conv_abc")
        chatRepository.setActiveConversation(null)
        assertNull(chatRepository.activeConversationId.value)
    }

    @Test
    fun `setActiveConversation can be called multiple times`() {
        chatRepository.setActiveConversation("conv_1")
        chatRepository.setActiveConversation("conv_2")
        assertEquals("conv_2", chatRepository.activeConversationId.value)
    }

    // ── getConversationId ─────────────────────────────────────────────────────

    @Test
    fun `getConversationId returns sorted member IDs`() {
        every { mockGroupStateManager.localMember } returns MutableStateFlow(makeMember("alice"))

        val id = chatRepository.getConversationId("bob")

        // generateConversationId sorts lexicographically: "alice" < "bob"
        assertEquals("alice:bob", id)
    }

    @Test
    fun `getConversationId is symmetric regardless of who calls it`() {
        every { mockGroupStateManager.localMember } returns MutableStateFlow(makeMember("bob"))

        val id = chatRepository.getConversationId("alice")
        // Even though local is "bob" and other is "alice", result should be "alice:bob"
        assertEquals("alice:bob", id)
    }

    @Test
    fun `getConversationId uses empty string when localMember is null`() {
        every { mockGroupStateManager.localMember } returns MutableStateFlow(null)
        // Should not throw; empty string used for localMemberId
        val id = chatRepository.getConversationId("other_001")
        assertNotNull(id)
    }

    // ── sendTextMessage (error cases only) ───────────────────────────────────

    @Test
    fun `sendTextMessage returns failure when local member is null`() = runTest {
        every { mockGroupStateManager.localMember } returns MutableStateFlow(null)

        val result = chatRepository.sendTextMessage("recipient_001", "Hello")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Not in a group") == true)
    }

    @Test
    fun `sendTextMessage returns failure when recipient not found in group`() = runTest {
        val member = makeMember("local_001")
        val groupDef = GroupDefinition(
            groupId = "group_123",
            groupName = "Test",
            createdAtEpochMs = 1000L,
            creatorMemberId = "local_001",
            members = setOf(member),
            version = 1L
        )
        every { mockGroupStateManager.localMember } returns MutableStateFlow(member)
        every { mockGroupStateManager.groupDefinition } returns MutableStateFlow(groupDef)

        // "unknown_recipient" is not in the group
        val result = chatRepository.sendTextMessage("unknown_recipient", "Hello")

        assertTrue(result.isFailure)
    }

    // ── setMqttPublisher ──────────────────────────────────────────────────────

    // ── handleDeliveryReceipt ─────────────────────────────────────────────────

    private fun makeGroup(vararg members: FamilyMember) =
        com.example.familysafety.group.GroupDefinition(
            groupId = "group_1",
            groupName = "Family",
            createdAtEpochMs = 1000L,
            creatorMemberId = "local_001",
            members = members.toSet(),
            version = 1
        )

    private fun outgoingMessage(
        recipientId: String?,
        status: MessageStatus
    ) = ChatMessageEntity(
        messageId = "msg_001",
        conversationId = "conv_1",
        senderId = "local_001",
        recipientId = recipientId,
        content = "hi",
        messageType = MessageType.TEXT,
        status = status,
        isOutgoing = true,
        isReadLocally = true
    )

    /** Wires a group of local+bob and stubs decryption to yield [receipt]. */
    private fun stubReceiptFrom(senderId: String, receipt: DeliveryReceipt) {
        every { mockGroupStateManager.groupDefinition } returns MutableStateFlow(
            makeGroup(makeMember("local_001"), makeMember(senderId))
        )
        every { mockE2EEManager.decryptMessage(any(), any(), any()) } returns
            json.encodeToString(receipt)
    }

    @Test
    fun `valid receipt from the message recipient upgrades status`() = runTest {
        stubReceiptFrom("bob_001", DeliveryReceipt("msg_001", "bob_001", MessageStatus.READ, 1L))
        coEvery { mockChatMessageDao.getMessageById("msg_001") } returns
            outgoingMessage(recipientId = "bob_001", status = MessageStatus.SENT)

        chatRepository.handleDeliveryReceipt("encrypted", "bob_001")

        coVerify { mockChatMessageDao.updateStatus("msg_001", MessageStatus.READ) }
    }

    @Test
    fun `group message receipt is accepted from any member`() = runTest {
        stubReceiptFrom("bob_001", DeliveryReceipt("msg_001", "bob_001", MessageStatus.DELIVERED, 1L))
        coEvery { mockChatMessageDao.getMessageById("msg_001") } returns
            outgoingMessage(recipientId = null, status = MessageStatus.SENT)

        chatRepository.handleDeliveryReceipt("encrypted", "bob_001")

        coVerify { mockChatMessageDao.updateStatus("msg_001", MessageStatus.DELIVERED) }
    }

    @Test
    fun `receipt claiming to be from someone other than its signer is ignored`() = runTest {
        stubReceiptFrom("bob_001", DeliveryReceipt("msg_001", "carol_001", MessageStatus.READ, 1L))
        coEvery { mockChatMessageDao.getMessageById("msg_001") } returns
            outgoingMessage(recipientId = "bob_001", status = MessageStatus.SENT)

        chatRepository.handleDeliveryReceipt("encrypted", "bob_001")

        coVerify(exactly = 0) { mockChatMessageDao.updateStatus(any(), any()) }
    }

    @Test
    fun `receipt from a member the message was not addressed to is ignored`() = runTest {
        stubReceiptFrom("bob_001", DeliveryReceipt("msg_001", "bob_001", MessageStatus.READ, 1L))
        coEvery { mockChatMessageDao.getMessageById("msg_001") } returns
            outgoingMessage(recipientId = "carol_001", status = MessageStatus.SENT)

        chatRepository.handleDeliveryReceipt("encrypted", "bob_001")

        coVerify(exactly = 0) { mockChatMessageDao.updateStatus(any(), any()) }
    }

    @Test
    fun `receipt cannot downgrade READ back to DELIVERED`() = runTest {
        stubReceiptFrom("bob_001", DeliveryReceipt("msg_001", "bob_001", MessageStatus.DELIVERED, 1L))
        coEvery { mockChatMessageDao.getMessageById("msg_001") } returns
            outgoingMessage(recipientId = "bob_001", status = MessageStatus.READ)

        chatRepository.handleDeliveryReceipt("encrypted", "bob_001")

        coVerify(exactly = 0) { mockChatMessageDao.updateStatus(any(), any()) }
    }

    @Test
    fun `receipt carrying a non-receipt status like FAILED is ignored`() = runTest {
        stubReceiptFrom("bob_001", DeliveryReceipt("msg_001", "bob_001", MessageStatus.FAILED, 1L))
        coEvery { mockChatMessageDao.getMessageById("msg_001") } returns
            outgoingMessage(recipientId = "bob_001", status = MessageStatus.SENT)

        chatRepository.handleDeliveryReceipt("encrypted", "bob_001")

        coVerify(exactly = 0) { mockChatMessageDao.updateStatus(any(), any()) }
    }

    @Test
    fun `receipt for an incoming message is ignored`() = runTest {
        stubReceiptFrom("bob_001", DeliveryReceipt("msg_001", "bob_001", MessageStatus.READ, 1L))
        coEvery { mockChatMessageDao.getMessageById("msg_001") } returns
            outgoingMessage(recipientId = "bob_001", status = MessageStatus.SENT)
                .copy(isOutgoing = false)

        chatRepository.handleDeliveryReceipt("encrypted", "bob_001")

        coVerify(exactly = 0) { mockChatMessageDao.updateStatus(any(), any()) }
    }

    @Test
    fun `receipt from unknown sender is ignored without throwing`() = runTest {
        // Default setup has no group — sender lookup fails.
        chatRepository.handleDeliveryReceipt("whatever", "stranger_001")

        coVerify(exactly = 0) { mockChatMessageDao.updateStatus(any(), any()) }
    }

    @Test
    fun `undecryptable receipt is ignored without throwing`() = runTest {
        every { mockGroupStateManager.groupDefinition } returns MutableStateFlow(
            makeGroup(makeMember("local_001"), makeMember("bob_001"))
        )
        every { mockE2EEManager.decryptMessage(any(), any(), any()) } throws
            com.example.familysafety.crypto.EncryptionException("bad signature")

        chatRepository.handleDeliveryReceipt("{invalid}", "bob_001")

        coVerify(exactly = 0) { mockChatMessageDao.updateStatus(any(), any()) }
    }

    // ── ChatMessagePayload serialization ──────────────────────────────────────

    @Test
    fun `ChatMessagePayload serializes and deserializes correctly`() {
        val payload = ChatMessagePayload(
            messageId = "msg_001",
            content = "Hello, world!",
            messageType = MessageType.TEXT,
            timestamp = 1_700_000_000_000L,
            replyToMessageId = null
        )

        val serialized = json.encodeToString(payload)
        val deserialized = json.decodeFromString<ChatMessagePayload>(serialized)

        assertEquals(payload.messageId, deserialized.messageId)
        assertEquals(payload.content, deserialized.content)
        assertEquals(payload.messageType, deserialized.messageType)
        assertEquals(payload.timestamp, deserialized.timestamp)
        assertNull(deserialized.replyToMessageId)
    }

    @Test
    fun `ChatMessagePayload preserves replyToMessageId`() {
        val payload = ChatMessagePayload(
            messageId = "msg_002",
            content = "Reply",
            messageType = MessageType.TEXT,
            timestamp = 1_700_000_000_000L,
            replyToMessageId = "msg_001"
        )

        val deserialized = json.decodeFromString<ChatMessagePayload>(json.encodeToString(payload))
        assertEquals("msg_001", deserialized.replyToMessageId)
    }

    // ── DeliveryReceipt serialization ─────────────────────────────────────────

    @Test
    fun `DeliveryReceipt serializes and deserializes correctly`() {
        val receipt = DeliveryReceipt(
            messageId = "msg_001",
            recipientId = "member_001",
            status = MessageStatus.DELIVERED,
            timestamp = 1_700_000_000_000L
        )

        val deserialized = json.decodeFromString<DeliveryReceipt>(json.encodeToString(receipt))

        assertEquals(receipt.messageId, deserialized.messageId)
        assertEquals(receipt.recipientId, deserialized.recipientId)
        assertEquals(receipt.status, deserialized.status)
        assertEquals(receipt.timestamp, deserialized.timestamp)
    }

    // ── LocationShareContent serialization ────────────────────────────────────

    @Test
    fun `LocationShareContent serializes latitude and longitude`() {
        val content = LocationShareContent(latitude = 37.7749, longitude = -122.4194)

        val deserialized = json.decodeFromString<LocationShareContent>(json.encodeToString(content))

        assertEquals(37.7749, deserialized.latitude, 0.0001)
        assertEquals(-122.4194, deserialized.longitude, 0.0001)
    }

    // ── ChatMessageEntity helpers ─────────────────────────────────────────────

    @Test
    fun `ChatMessageEntity generateConversationId is order independent`() {
        val id1 = ChatMessageEntity.generateConversationId("alice", "bob")
        val id2 = ChatMessageEntity.generateConversationId("bob", "alice")
        assertEquals(id1, id2)
    }

    @Test
    fun `ChatMessageEntity createTextMessage sets correct fields`() {
        val msg = ChatMessageEntity.createTextMessage(
            senderId = "alice",
            recipientId = "bob",
            content = "Hi there",
            isOutgoing = true
        )

        assertEquals("alice", msg.senderId)
        assertEquals("bob", msg.recipientId)
        assertEquals("Hi there", msg.content)
        assertEquals(MessageType.TEXT, msg.messageType)
        assertTrue(msg.isOutgoing)
        assertEquals(MessageStatus.PENDING, msg.status)
    }
}
