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
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var mockChatMessageDao: ChatMessageDao
    private lateinit var mockGroupStateManager: GroupStateManager
    private lateinit var mockE2eeManager: E2EEManager
    private lateinit var mockReplicationManager: ReplicationManager

    private val testDispatcher = StandardTestDispatcher()

    // Test data
    private val localMemberId = "local_member_id"
    private val localEd25519Key = "a".repeat(64)
    private val localX25519Key = "b".repeat(64)

    private val recipientMemberId = "recipient_member_id"
    private val recipientEd25519Key = "c".repeat(64)
    private val recipientX25519Key = "d".repeat(64)

    private val localMember = FamilyMember(
        memberId = localMemberId,
        displayName = "Local User",
        ed25519PublicKey = localEd25519Key,
        x25519PublicKey = localX25519Key,
        addedAtEpochMs = System.currentTimeMillis()
    )

    private val recipientMember = FamilyMember(
        memberId = recipientMemberId,
        displayName = "Recipient User",
        ed25519PublicKey = recipientEd25519Key,
        x25519PublicKey = recipientX25519Key,
        addedAtEpochMs = System.currentTimeMillis()
    )

    private val groupDefinition = GroupDefinition(
        groupId = "test_group_id",
        groupName = "Test Family",
        createdAtEpochMs = System.currentTimeMillis(),
        creatorMemberId = localMemberId,
        members = setOf(localMember, recipientMember),
        version = 1
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockChatMessageDao = mockk(relaxed = true)
        mockGroupStateManager = mockk(relaxed = true)
        mockE2eeManager = mockk(relaxed = true)
        mockReplicationManager = mockk(relaxed = true)

        // Setup group state manager mocks
        every { mockGroupStateManager.localMember } returns MutableStateFlow(localMember)
        every { mockGroupStateManager.groupDefinition } returns MutableStateFlow(groupDefinition)

        chatRepository = ChatRepository(
            chatMessageDao = mockChatMessageDao,
            groupStateManager = mockGroupStateManager,
            e2eeManager = mockE2eeManager,
            replicationManager = mockReplicationManager
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `getConversationId generates correct ID`() {
        val conversationId = chatRepository.getConversationId(recipientMemberId)

        // Should be sorted lexicographically
        val expected = if (localMemberId < recipientMemberId) {
            "$localMemberId:$recipientMemberId"
        } else {
            "$recipientMemberId:$localMemberId"
        }

        assertEquals(expected, conversationId)
    }

    @Test
    fun `setActiveConversation updates flow and marks messages as read`() = runTest {
        val conversationId = "test_conversation"

        chatRepository.setActiveConversation(conversationId)
        advanceUntilIdle()

        assertEquals(conversationId, chatRepository.activeConversationId.first())
        coVerify { mockChatMessageDao.markConversationAsRead(conversationId) }
    }

    @Test
    fun `setActiveConversation with null does not mark as read`() = runTest {
        chatRepository.setActiveConversation(null)
        advanceUntilIdle()

        assertNull(chatRepository.activeConversationId.first())
        coVerify(exactly = 0) { mockChatMessageDao.markConversationAsRead(any()) }
    }

    @Test
    fun `sendTextMessage creates message with correct fields`() = runTest {
        val messageSlot = slot<ChatMessageEntity>()
        coEvery { mockChatMessageDao.insert(capture(messageSlot)) } just Runs

        // Mock encryption
        every { mockE2eeManager.encryptMessage(any(), any(), any()) } returns "encrypted_content"

        // Setup mqtt publisher
        var capturedTopic: String? = null
        var capturedPayload: ByteArray? = null
        chatRepository.setMqttPublisher { topic, payload, qos ->
            capturedTopic = topic
            capturedPayload = payload
            true
        }

        chatRepository.sendTextMessage(recipientMemberId, "Hello, world!")
        advanceUntilIdle()

        val capturedMessage = messageSlot.captured
        assertEquals(localMemberId, capturedMessage.senderId)
        assertEquals(recipientMemberId, capturedMessage.recipientId)
        assertEquals("Hello, world!", capturedMessage.content)
        assertEquals(MessageType.TEXT, capturedMessage.messageType)
        assertTrue(capturedMessage.isOutgoing)
        assertTrue(capturedMessage.isReadLocally)
    }

    @Test
    fun `sendTextMessage updates status to SENT on success`() = runTest {
        coEvery { mockChatMessageDao.insert(any()) } just Runs
        every { mockE2eeManager.encryptMessage(any(), any(), any()) } returns "encrypted"

        var messageId: String? = null
        coEvery { mockChatMessageDao.insert(any()) } answers {
            messageId = firstArg<ChatMessageEntity>().messageId
        }

        chatRepository.setMqttPublisher { _, _, _ -> true }

        chatRepository.sendTextMessage(recipientMemberId, "Test message")
        advanceUntilIdle()

        coVerify {
            mockChatMessageDao.updateStatus(any(), MessageStatus.SENT)
        }
    }

    @Test
    fun `sendTextMessage updates status to FAILED on network failure`() = runTest {
        coEvery { mockChatMessageDao.insert(any()) } just Runs
        every { mockE2eeManager.encryptMessage(any(), any(), any()) } returns "encrypted"

        chatRepository.setMqttPublisher { _, _, _ -> false }

        val result = chatRepository.sendTextMessage(recipientMemberId, "Test message")
        advanceUntilIdle()

        assertTrue(result.isFailure)
        coVerify { mockChatMessageDao.updateStatus(any(), MessageStatus.FAILED) }
    }

    @Test
    fun `sendTextMessage fails when not in group`() = runTest {
        every { mockGroupStateManager.localMember } returns MutableStateFlow(null)

        val result = chatRepository.sendTextMessage(recipientMemberId, "Test")
        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `sendTextMessage fails when recipient not found`() = runTest {
        val result = chatRepository.sendTextMessage("unknown_member", "Test")
        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `sendLocationMessage creates location message`() = runTest {
        val messageSlot = slot<ChatMessageEntity>()
        coEvery { mockChatMessageDao.insert(capture(messageSlot)) } just Runs
        every { mockE2eeManager.encryptMessage(any(), any(), any()) } returns "encrypted"
        chatRepository.setMqttPublisher { _, _, _ -> true }

        chatRepository.sendLocationMessage(recipientMemberId, 37.7749, -122.4194)
        advanceUntilIdle()

        val capturedMessage = messageSlot.captured
        assertEquals(MessageType.LOCATION, capturedMessage.messageType)
        assertTrue(capturedMessage.content.contains("37.7749"))
        assertTrue(capturedMessage.content.contains("-122.4194"))
    }

    @Test
    fun `handleDeliveryReceipt updates message status`() = runTest {
        val receiptJson = """{"messageId":"msg123","recipientId":"$recipientMemberId","status":"DELIVERED","timestamp":12345}"""

        chatRepository.handleDeliveryReceipt(receiptJson)
        advanceUntilIdle()

        coVerify { mockChatMessageDao.updateStatus("msg123", MessageStatus.DELIVERED) }
    }

    @Test
    fun `deleteMessage calls DAO`() = runTest {
        chatRepository.deleteMessage("message_to_delete")
        advanceUntilIdle()

        coVerify { mockChatMessageDao.deleteMessage("message_to_delete") }
    }

    @Test
    fun `deleteConversation calls DAO`() = runTest {
        chatRepository.deleteConversation("conversation_to_delete")
        advanceUntilIdle()

        coVerify { mockChatMessageDao.deleteConversation("conversation_to_delete") }
    }

    @Test
    fun `applyRetentionPolicy deletes old messages`() = runTest {
        coEvery { mockChatMessageDao.deleteOlderThan(any()) } returns 10

        chatRepository.applyRetentionPolicy(30L)
        advanceUntilIdle()

        coVerify { mockChatMessageDao.deleteOlderThan(any()) }
    }

    @Test
    fun `observeConversation delegates to DAO`() = runTest {
        val testMessages = listOf(
            ChatMessageEntity(
                conversationId = "test:conversation",
                senderId = localMemberId,
                recipientId = recipientMemberId,
                content = "Hello",
                isOutgoing = true
            )
        )
        every { mockChatMessageDao.observeConversation("test:conversation") } returns flowOf(testMessages)

        val flow = chatRepository.observeConversation("test:conversation")
        val messages = flow.first()

        assertEquals(1, messages.size)
        assertEquals("Hello", messages[0].content)
    }

    @Test
    fun `observeTotalUnreadCount delegates to DAO`() = runTest {
        every { mockChatMessageDao.observeTotalUnreadCount() } returns flowOf(5)

        val count = chatRepository.observeTotalUnreadCount().first()

        assertEquals(5, count)
    }

    @Test
    fun `retrySendingFailedMessages retries each failed message`() = runTest {
        val failedMessages = listOf(
            ChatMessageEntity(
                messageId = "failed1",
                conversationId = "$localMemberId:$recipientMemberId",
                senderId = localMemberId,
                recipientId = recipientMemberId,
                content = "Failed message 1",
                status = MessageStatus.FAILED,
                isOutgoing = true
            ),
            ChatMessageEntity(
                messageId = "failed2",
                conversationId = "$localMemberId:$recipientMemberId",
                senderId = localMemberId,
                recipientId = recipientMemberId,
                content = "Failed message 2",
                status = MessageStatus.FAILED,
                isOutgoing = true
            )
        )

        coEvery { mockChatMessageDao.getFailedMessages() } returns failedMessages
        every { mockE2eeManager.encryptMessage(any(), any(), any()) } returns "encrypted"
        chatRepository.setMqttPublisher { _, _, _ -> true }

        chatRepository.retrySendingFailedMessages()
        advanceUntilIdle()

        // Should update to PENDING first, then to SENT
        coVerify(exactly = 2) { mockChatMessageDao.updateStatus(any(), MessageStatus.PENDING) }
        coVerify(exactly = 2) { mockChatMessageDao.updateStatus(any(), MessageStatus.SENT) }
    }
}
