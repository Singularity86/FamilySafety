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
     *
     * The DAO holds replicated copies of EVERY member's conversations (backup
     * design), so filter to the ones this user participates in: the group chat
     * and 1-to-1 threads containing our member ID. Other members' private chats
     * are backup data, not our conversation list.
     */
    fun observeConversations(): Flow<List<ConversationSummary>> {
        return chatMessageDao.observeAllConversationIds()
            .map { conversationIds ->
                val localMemberId = groupStateManager.localMember.value?.memberId ?: ""
                val groupId = groupStateManager.groupDefinition.value?.groupId
                conversationIds
                    .filter { conversationId ->
                        conversationId == groupId ||
                            conversationId.split(":").contains(localMemberId)
                    }
                    .mapNotNull { conversationId ->
                        createConversationSummary(conversationId, localMemberId)
                    }
                    .sortedByDescending { it.lastMessageTimestamp }
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
     * Observe total unread count for conversations this user participates in.
     */
    fun observeTotalUnreadCount(): Flow<Int> {
        val localMemberId = groupStateManager.localMember.value?.memberId ?: ""
        val groupId = groupStateManager.groupDefinition.value?.groupId
        return chatMessageDao.observeTotalUnreadCountFor(localMemberId, groupId)
    }

    // =========================================================================
    // SINGLE CONVERSATION
    // =========================================================================

    /**
     * Set active conversation (for marking as read, etc.)
     */
    fun setActiveConversation(conversationId: String?) {
        _activeConversationId.value = conversationId

        // Mark messages as read when entering conversation, and tell their
        // senders — this is what moves messages to READ on the other side.
        if (conversationId != null) {
            scope.launch {
                val unread = chatMessageDao.getUnreadIncoming(conversationId)
                chatMessageDao.markConversationAsRead(conversationId)
                sendReadReceipts(unread)
            }
        }
    }

    private suspend fun sendReadReceipts(messages: List<ChatMessageEntity>) {
        if (messages.isEmpty()) return
        val group = groupStateManager.groupDefinition.value ?: return
        messages.groupBy { it.senderId }.forEach { (senderId, senderMessages) ->
            val sender = group.findMemberById(senderId) ?: return@forEach
            senderMessages.forEach { message ->
                sendReceipt(sender, message.messageId, MessageStatus.READ)
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

            // Confirm receipt — READ if the conversation is open on screen right now,
            // DELIVERED otherwise (the read receipt then follows via setActiveConversation).
            sendReceipt(
                recipient = sender,
                messageId = message.messageId,
                status = if (message.isReadLocally) MessageStatus.READ else MessageStatus.DELIVERED
            )

            // Replicate to other family members
            scope.launch {
                replicationManager.replicateChatMessage(message)
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to process incoming message")
        }
    }

    /**
     * Send a delivery or read receipt, E2E-encrypted and signed like any other
     * message — a plaintext receipt would let anyone on the broker rewrite
     * message statuses.
     */
    private suspend fun sendReceipt(
        recipient: FamilyMember,
        messageId: String,
        status: MessageStatus
    ) {
        val localMemberId = groupStateManager.localMember.value?.memberId ?: return

        try {
            val receipt = DeliveryReceipt(
                messageId = messageId,
                recipientId = localMemberId,
                status = status,
                timestamp = System.currentTimeMillis()
            )

            val encrypted = e2eeManager.encryptMessage(
                plaintext = json.encodeToString(receipt),
                recipientMemberId = recipient.memberId,
                recipientX25519PublicKey = hexToByteArray(recipient.x25519PublicKey)
            )

            val topic = if (status == MessageStatus.READ) {
                MqttConfig.getChatReadTopic(recipient.memberId)
            } else {
                MqttConfig.getChatReceiptTopic(recipient.memberId)
            }

            transportProvider.sendMessage(
                recipientId = recipient.memberId,
                topic = topic,
                payload = encrypted.toByteArray(),
                qos = MqttConfig.QOS_AT_LEAST_ONCE
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to send $status receipt")
        }
    }

    /**
     * Handle an incoming encrypted delivery/read receipt.
     *
     * Decryption verifies the sender's signature; the checks after it stop a
     * valid group member from forging receipts for messages that were never
     * addressed to them (message IDs are known group-wide via replication).
     */
    suspend fun handleDeliveryReceipt(encryptedPayload: String, senderMemberId: String) {
        try {
            val sender = groupStateManager.groupDefinition.value?.findMemberById(senderMemberId)
                ?: run {
                    Timber.w("$TAG: Receipt from unknown sender ${senderMemberId.take(8)} — ignoring")
                    return
                }

            val receiptJson = e2eeManager.decryptMessage(
                encryptedMessageJson = encryptedPayload,
                senderX25519PublicKey = hexToByteArray(sender.x25519PublicKey),
                senderEd25519PublicKey = hexToByteArray(sender.ed25519PublicKey)
            )
            val receipt = json.decodeFromString<DeliveryReceipt>(receiptJson)

            // Receipts are self-reported: the signer must be the recipient it claims to be.
            if (receipt.recipientId != senderMemberId) {
                Timber.w("$TAG: Receipt recipient does not match its signer — ignoring")
                return
            }
            // Receipts may only carry DELIVERED or READ — never FAILED/PENDING/SENT.
            if (receipt.status != MessageStatus.DELIVERED && receipt.status != MessageStatus.READ) {
                return
            }

            val message = chatMessageDao.getMessageById(receipt.messageId) ?: return
            if (!message.isOutgoing) return
            // 1-to-1 messages: only the actual recipient may confirm.
            // Group messages (recipientId == null): any member's receipt counts.
            if (message.recipientId != null && message.recipientId != senderMemberId) {
                Timber.w("$TAG: Receipt from non-recipient ${senderMemberId.take(8)} — ignoring")
                return
            }
            // Statuses only move forward (SENT → DELIVERED → READ), never back.
            if (statusRank(receipt.status) <= statusRank(message.status)) return

            chatMessageDao.updateStatus(receipt.messageId, receipt.status)
            Timber.d("$TAG: Updated message ${receipt.messageId} status to ${receipt.status}")
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to handle delivery receipt")
        }
    }

    private fun statusRank(status: MessageStatus): Int = when (status) {
        MessageStatus.READ -> 3
        MessageStatus.DELIVERED -> 2
        MessageStatus.SENT -> 1
        else -> 0
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
