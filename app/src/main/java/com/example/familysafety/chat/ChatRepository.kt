package com.example.familysafety.chat

import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.replication.ReplicationManager
import com.example.familysafety.storage.ChatMessageDao
import com.example.familysafety.storage.ChatMessageEntity
import com.example.familysafety.storage.ConversationSummary
import com.example.familysafety.storage.MessageStatus
import com.example.familysafety.storage.MessageType
import com.example.familysafety.transport.MqttConfig
import com.example.familysafety.transport.TransportProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for chat operations.
 * Handles message sending, receiving, encryption, and persistence.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val chatMessageDao: ChatMessageDao,
    private val groupStateManager: GroupStateManager,
    private val e2eeManager: E2EEManager,
    private val replicationManager: ReplicationManager,
    private val transportProvider: TransportProvider,
    private val notificationHelper: ChatNotificationHelper
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    // Current active conversation (for UI state)
    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    companion object {
        private const val TAG = "ChatRepository"
    }

    // =========================================================================
    // CONVERSATION LIST
    // =========================================================================

    /**
     * Get all conversations with summaries.
     */
    fun observeConversations(): Flow<List<ConversationSummary>> {
        val localMemberId = groupStateManager.localMember.value?.memberId ?: ""

        return chatMessageDao.observeAllConversationIds()
            .map { conversationIds ->
                conversationIds.mapNotNull { conversationId ->
                    createConversationSummary(conversationId, localMemberId)
                }.sortedByDescending { it.lastMessageTimestamp }
            }
    }

    private suspend fun createConversationSummary(
        conversationId: String,
        localMemberId: String
    ): ConversationSummary? {
        val lastMessage = chatMessageDao.getLastMessage(conversationId) ?: return null
        val unreadCount = chatMessageDao.getUnreadCount(conversationId)

        // Extract other member ID from conversation ID
        val otherMemberId = conversationId.split(":")
            .firstOrNull { it != localMemberId } ?: return null

        return ConversationSummary(
            conversationId = conversationId,
            otherMemberId = otherMemberId,
            lastMessage = when (lastMessage.messageType) {
                MessageType.TEXT -> lastMessage.content
                MessageType.LOCATION -> "Shared a location"
                MessageType.SYSTEM -> lastMessage.content
            },
            lastMessageTimestamp = lastMessage.timestamp,
            unreadCount = unreadCount,
            lastMessageType = lastMessage.messageType
        )
    }

    /**
     * Observe total unread count.
     */
    fun observeTotalUnreadCount(): Flow<Int> {
        return chatMessageDao.observeTotalUnreadCount()
    }

    // =========================================================================
    // SINGLE CONVERSATION
    // =========================================================================

    /**
     * Set active conversation (for marking as read, etc.)
     */
    fun setActiveConversation(conversationId: String?) {
        _activeConversationId.value = conversationId

        // Mark messages as read when entering conversation
        if (conversationId != null) {
            scope.launch {
                chatMessageDao.markConversationAsRead(conversationId)
            }
        }
    }

    /**
     * Observe messages in a conversation.
     */
    fun observeConversation(conversationId: String): Flow<List<ChatMessageEntity>> {
        return chatMessageDao.observeConversation(conversationId)
    }

    /**
     * Observe messages with a specific member.
     */
    fun observeConversationWithMember(otherMemberId: String): Flow<List<ChatMessageEntity>> {
        val localMemberId = groupStateManager.localMember.value?.memberId ?: ""
        val conversationId = ChatMessageEntity.generateConversationId(localMemberId, otherMemberId)
        return observeConversation(conversationId)
    }

    /**
     * Get conversation ID for a member.
     */
    fun getConversationId(otherMemberId: String): String {
        val localMemberId = groupStateManager.localMember.value?.memberId ?: ""
        return ChatMessageEntity.generateConversationId(localMemberId, otherMemberId)
    }

    // =========================================================================
    // SEND MESSAGES
    // =========================================================================

    /**
     * Send a text message to a family member.
     */
    suspend fun sendTextMessage(
        recipientId: String,
        content: String
    ): Result<ChatMessageEntity> {
        return sendMessage(recipientId, content, MessageType.TEXT)
    }

    /**
     * Send a location share to a family member.
     */
    suspend fun sendLocationMessage(
        recipientId: String,
        latitude: Double,
        longitude: Double
    ): Result<ChatMessageEntity> {
        val content = json.encodeToString(LocationShareContent(latitude, longitude))
        return sendMessage(recipientId, content, MessageType.LOCATION)
    }

    /**
     * Send a text message to all members of the group (group chat).
     */
    suspend fun sendGroupTextMessage(groupId: String, content: String): Result<ChatMessageEntity> {
        val localMember = groupStateManager.localMember.value
            ?: return Result.failure(IllegalStateException("Not in a group"))
        val group = groupStateManager.groupDefinition.value
            ?: return Result.failure(IllegalStateException("No group"))

        val message = ChatMessageEntity(
            conversationId = groupId,
            senderId = localMember.memberId,
            recipientId = null,
            content = content,
            messageType = MessageType.TEXT,
            status = MessageStatus.PENDING,
            isOutgoing = true,
            isReadLocally = true
        )

        chatMessageDao.insert(message)

        val otherMembers = group.members.filter { it.memberId != localMember.memberId }
        var anySucceeded = otherMembers.isEmpty()
        otherMembers.forEach { member ->
            if (sendViaNetwork(message, member, groupId)) anySucceeded = true
        }

        return if (anySucceeded) {
            chatMessageDao.updateStatus(message.messageId, MessageStatus.SENT)
            scope.launch { replicationManager.replicateChatMessage(message) }
            Result.success(message.copy(status = MessageStatus.SENT))
        } else {
            chatMessageDao.updateStatus(message.messageId, MessageStatus.FAILED)
            Result.failure(Exception("Failed to send to any member"))
        }
    }

    /**
     * Core message sending logic.
     */
    private suspend fun sendMessage(
        recipientId: String,
        content: String,
        messageType: MessageType
    ): Result<ChatMessageEntity> {
        val localMember = groupStateManager.localMember.value
            ?: return Result.failure(IllegalStateException("Not in a group"))

        val recipient = groupStateManager.groupDefinition.value?.findMemberById(recipientId)
            ?: return Result.failure(IllegalStateException("Recipient not found in group"))

        // Create message entity
        val message = ChatMessageEntity(
            conversationId = ChatMessageEntity.generateConversationId(localMember.memberId, recipientId),
            senderId = localMember.memberId,
            recipientId = recipientId,
            content = content,
            messageType = messageType,
            status = MessageStatus.PENDING,
            isOutgoing = true,
            isReadLocally = true
        )

        // Save to local database first
        chatMessageDao.insert(message)
        Timber.d("$TAG: Saved outgoing message ${message.messageId}")

        // Send via MQTT
        return try {
            val sent = sendViaNetwork(message, recipient)
            if (sent) {
                chatMessageDao.updateStatus(message.messageId, MessageStatus.SENT)
                Timber.d("$TAG: Message ${message.messageId} sent successfully")

                // Replicate to other family members for backup
                scope.launch {
                    replicationManager.replicateChatMessage(message)
                }

                Result.success(message.copy(status = MessageStatus.SENT))
            } else {
                chatMessageDao.updateStatus(message.messageId, MessageStatus.FAILED)
                Result.failure(Exception("Failed to send message"))
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to send message")
            chatMessageDao.updateStatus(message.messageId, MessageStatus.FAILED)
            Result.failure(e)
        }
    }

    /**
     * Send message over the best available transport with E2E encryption.
     * Pass [conversationId] for group messages (groupId); null uses the 1-to-1 default.
     */
    private suspend fun sendViaNetwork(
        message: ChatMessageEntity,
        recipient: FamilyMember,
        conversationId: String? = null
    ): Boolean {
        return try {
            val recipientX25519Key = hexToByteArray(recipient.x25519PublicKey)

            val encryptedContent = e2eeManager.encryptMessage(
                plaintext = json.encodeToString(
                    ChatMessagePayload(
                        messageId = message.messageId,
                        content = message.content,
                        messageType = message.messageType,
                        timestamp = message.timestamp,
                        conversationId = conversationId,
                        replyToMessageId = message.replyToMessageId
                    )
                ),
                recipientMemberId = recipient.memberId,
                recipientX25519PublicKey = recipientX25519Key
            )

            val topic = MqttConfig.getChatTopic(recipient.memberId)
            transportProvider.sendMessage(
                recipientId = recipient.memberId,
                topic = topic,
                payload = encryptedContent.toByteArray(),
                qos = MqttConfig.DEFAULT_QOS
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to encrypt/send message")
            false
        }
    }

    /**
     * Retry sending failed messages.
     */
    suspend fun retrySendingFailedMessages() {
        val failedMessages = chatMessageDao.getFailedMessages()
        val group = groupStateManager.groupDefinition.value ?: return

        failedMessages.forEach { message ->
            val recipient = group.findMemberById(message.recipientId ?: return@forEach)
                ?: return@forEach

            chatMessageDao.updateStatus(message.messageId, MessageStatus.PENDING)

            try {
                if (sendViaNetwork(message, recipient)) {
                    chatMessageDao.updateStatus(message.messageId, MessageStatus.SENT)
                } else {
                    chatMessageDao.updateStatus(message.messageId, MessageStatus.FAILED)
                }
            } catch (e: Exception) {
                chatMessageDao.updateStatus(message.messageId, MessageStatus.FAILED)
            }
        }
    }

    // =========================================================================
    // RECEIVE MESSAGES
    // =========================================================================

    /**
     * Handle incoming encrypted chat message.
     */
    suspend fun handleIncomingMessage(
        encryptedPayload: String,
        senderMemberId: String
    ) {
        val sender = groupStateManager.groupDefinition.value?.findMemberById(senderMemberId)
            ?: run {
                Timber.w("$TAG: Received message from unknown sender: $senderMemberId")
                return
            }

        try {
            // Decrypt the message
            val senderX25519Key = hexToByteArray(sender.x25519PublicKey)
            val senderEd25519Key = hexToByteArray(sender.ed25519PublicKey)

            val decryptedJson = e2eeManager.decryptMessage(
                encryptedMessageJson = encryptedPayload,
                senderX25519PublicKey = senderX25519Key,
                senderEd25519PublicKey = senderEd25519Key
            )

            val payload = json.decodeFromString<ChatMessagePayload>(decryptedJson)

            // Check for duplicate
            if (chatMessageDao.messageExists(payload.messageId)) {
                Timber.d("$TAG: Duplicate message ${payload.messageId}, ignoring")
                return
            }

            val localMemberId = groupStateManager.localMember.value?.memberId ?: return

            // Group messages carry their conversationId (groupId); 1-to-1 derives it.
            val conversationId = payload.conversationId
                ?: ChatMessageEntity.generateConversationId(localMemberId, senderMemberId)

            // Create message entity
            val message = ChatMessageEntity(
                messageId = payload.messageId,
                conversationId = conversationId,
                senderId = senderMemberId,
                recipientId = if (payload.conversationId == null) localMemberId else null,
                content = payload.content,
                messageType = payload.messageType,
                status = MessageStatus.DELIVERED,
                timestamp = payload.timestamp,
                isOutgoing = false,
                isReadLocally = _activeConversationId.value == conversationId,
                replyToMessageId = payload.replyToMessageId
            )

            // Save to database
            chatMessageDao.insert(message)
            Timber.d("$TAG: Received and saved message ${message.messageId} from $senderMemberId")

            // Post notification if the conversation is not currently open
            if (_activeConversationId.value != conversationId) {
                val preview = when (payload.messageType) {
                    MessageType.TEXT -> payload.content.take(80)
                    MessageType.LOCATION -> "Shared a location"
                    MessageType.SYSTEM -> payload.content.take(80)
                }
                val isGroupMessage = payload.conversationId != null
                val route = if (isGroupMessage) "chat/group"
                            else "chat/conversation/$senderMemberId"
                notificationHelper.notifyNewMessage(
                    senderName = sender.displayName,
                    senderMemberId = senderMemberId,
                    messagePreview = preview,
                    conversationRoute = route
                )
            }

            // Send delivery receipt
            sendDeliveryReceipt(sender, message.messageId)

            // Replicate to other family members
            scope.launch {
                replicationManager.replicateChatMessage(message)
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to process incoming message")
        }
    }

    /**
     * Send delivery receipt to sender.
     */
    private suspend fun sendDeliveryReceipt(sender: FamilyMember, messageId: String) {
        val localMemberId = groupStateManager.localMember.value?.memberId ?: return

        try {
            val receipt = DeliveryReceipt(
                messageId = messageId,
                recipientId = localMemberId,
                status = MessageStatus.DELIVERED,
                timestamp = System.currentTimeMillis()
            )

            val topic = MqttConfig.getChatReceiptTopic(sender.memberId)
            val payload = json.encodeToString(receipt).toByteArray()

            transportProvider.sendMessage(
                recipientId = sender.memberId,
                topic = topic,
                payload = payload,
                qos = MqttConfig.QOS_AT_LEAST_ONCE
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to send delivery receipt")
        }
    }

    /**
     * Handle incoming delivery receipt.
     */
    suspend fun handleDeliveryReceipt(receiptJson: String) {
        try {
            val receipt = json.decodeFromString<DeliveryReceipt>(receiptJson)
            chatMessageDao.updateStatus(receipt.messageId, receipt.status)
            Timber.d("$TAG: Updated message ${receipt.messageId} status to ${receipt.status}")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to handle delivery receipt")
        }
    }

    // =========================================================================
    // DELETE OPERATIONS
    // =========================================================================

    /**
     * Delete a message.
     */
    suspend fun deleteMessage(messageId: String) {
        chatMessageDao.deleteMessage(messageId)
    }

    /**
     * Delete entire conversation.
     */
    suspend fun deleteConversation(conversationId: String) {
        chatMessageDao.deleteConversation(conversationId)
    }

    /**
     * Apply retention policy.
     */
    suspend fun applyRetentionPolicy(retentionDays: Long = 90L) {
        val cutoff = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
        val deleted = chatMessageDao.deleteOlderThan(cutoff)
        Timber.d("$TAG: Deleted $deleted messages older than $retentionDays days")
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun hexToByteArray(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

/**
 * Payload sent over network (encrypted).
 */
@Serializable
data class ChatMessagePayload(
    val messageId: String,
    val content: String,
    val messageType: MessageType,
    val timestamp: Long,
    val conversationId: String? = null, // null = 1-to-1; groupId = group chat
    val replyToMessageId: String? = null
)

/**
 * Location share content.
 */
@Serializable
data class LocationShareContent(
    val latitude: Double,
    val longitude: Double
)

/**
 * Delivery/read receipt.
 */
@Serializable
data class DeliveryReceipt(
    val messageId: String,
    val recipientId: String,
    val status: MessageStatus,
    val timestamp: Long
)
