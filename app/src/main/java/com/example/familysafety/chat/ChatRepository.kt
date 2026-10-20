package com.example.familysafety.chat

import com.example.familysafety.core.hexToBytes
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.replication.AuthorSignatures
import com.example.familysafety.replication.ReplicationManager
import com.example.familysafety.storage.ChatMessageDao
import com.example.familysafety.storage.ChatMessageEntity
import com.example.familysafety.storage.ConversationSummary
import com.example.familysafety.storage.EmergencyOutboxDao
import com.example.familysafety.storage.EmergencyOutboxEntity
import com.example.familysafety.storage.EmergencyRetrySchedule
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    private val notificationHelper: ChatNotificationHelper,
    private val emergencyOutboxDao: EmergencyOutboxDao
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
        // Re-evaluated when the group loads as well as when messages change. Reading the
        // group once up front left the list empty after a cold start until the next
        // message happened to arrive.
        return combine(
            chatMessageDao.observeAllConversationIds(),
            groupStateManager.localMember,
            groupStateManager.groupDefinition
        ) { conversationIds, localMember, group ->
                val localMemberId = localMember?.memberId ?: return@combine emptyList()
                val groupId = group?.groupId
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
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeTotalUnreadCount(): Flow<Int> {
        // This used to read the member and group once, when the Chat badge was first built
        // — which on a cold start is before the group has loaded. With an empty member ID
        // the query's `LIKE '%' || '' || '%'` matches every row, so the badge counted every
        // unread message in the database: stray copies of other people's conversations,
        // chats from a previous family, messages no screen can show. A number with nothing
        // behind it, until the app was fully restarted with the group already in memory.
        return combine(
            groupStateManager.localMember,
            groupStateManager.groupDefinition
        ) { member, group -> member?.memberId to group?.groupId }
            .distinctUntilChanged()
            .flatMapLatest { (localMemberId, groupId) ->
                if (localMemberId.isNullOrEmpty()) flowOf(0)
                else chatMessageDao.observeTotalUnreadCountFor(localMemberId, groupId)
            }
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

        val message = signed(ChatMessageEntity(
            conversationId = groupId,
            senderId = localMember.memberId,
            recipientId = null,
            content = content,
            messageType = MessageType.TEXT,
            status = MessageStatus.PENDING,
            isOutgoing = true,
            isReadLocally = true
        ))

        chatMessageDao.insert(message)

        val otherMembers = group.members.filter { it.memberId != localMember.memberId }
        var anySucceeded = otherMembers.isEmpty()
        otherMembers.forEach { member ->
            if (sendViaNetwork(message, member, groupId)) anySucceeded = true
        }

        // Each member already received the message directly above; there is no separate
        // backup copy to push. A member who missed it fills the gap through the replication
        // back-fill (announceDataAvailability → ReplicationRequest) when they reconnect.
        return if (anySucceeded) {
            chatMessageDao.updateStatus(message.messageId, MessageStatus.SENT)
            Result.success(message.copy(status = MessageStatus.SENT))
        } else {
            chatMessageDao.updateStatus(message.messageId, MessageStatus.FAILED)
            Result.failure(Exception("Failed to send to any member"))
        }
    }

    // =========================================================================
    // CRASH ALERTS
    // =========================================================================

    private val emergencyFlushLock = Mutex()
    private val emergencyWake = Channel<Unit>(Channel.CONFLATED)
    private val emergencyLoopStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Raise a crash alert to every other family member and return its id, or null when
     * this phone isn't in a family.
     *
     * Not suspending, and not run in the caller's scope: the alert screen closing must not
     * be able to cancel it, which is how an expired countdown used to send nothing. The
     * alert is a normal group chat message on the wire, so every version can read it; what
     * changes is that it is stored per member and retried until each member's phone
     * confirms delivery.
     */
    fun raiseEmergencyAlert(content: String): String? {
        val localMember = groupStateManager.localMember.value ?: return null
        val group = groupStateManager.groupDefinition.value ?: return null
        val now = System.currentTimeMillis()
        val message = signed(ChatMessageEntity(
            conversationId = group.groupId,
            senderId = localMember.memberId,
            recipientId = null,
            content = content,
            messageType = MessageType.TEXT,
            status = MessageStatus.PENDING,
            timestamp = now,
            isOutgoing = true,
            isReadLocally = true
        ))
        val rows = group.members
            .filter { it.memberId != localMember.memberId }
            .map {
                EmergencyOutboxEntity(
                    alertId = message.messageId,
                    recipientId = it.memberId,
                    groupId = group.groupId,
                    content = content,
                    timestamp = now,
                    createdAt = now
                )
            }
        scope.launch {
            chatMessageDao.insert(message)
            emergencyOutboxDao.insertAll(rows)
            flushEmergencyAlerts()
            emergencyWake.trySend(Unit)
        }
        return message.messageId
    }

    /** Per-member delivery of one alert, for the alert screen. */
    fun observeEmergencyAlert(alertId: String): Flow<List<EmergencyOutboxEntity>> =
        emergencyOutboxDao.observeAlert(alertId)

    /** True while any alert is still owed to someone, so a background retry knows to stay. */
    suspend fun hasPendingEmergencyAlerts(): Boolean {
        val next = emergencyOutboxDao.nextAttemptAt(
            System.currentTimeMillis() - EmergencyRetrySchedule.GIVE_UP_AFTER_MS
        )
        // Long.MAX_VALUE marks copies for members who left the family: nothing to retry.
        return next != null && next != Long.MAX_VALUE
    }

    /**
     * Send every alert copy that is due. Delivery is judged only by the member's receipt,
     * so a successful publish still schedules the next attempt; the receipt cancels it.
     */
    suspend fun flushEmergencyAlerts(now: Long = System.currentTimeMillis()) {
        emergencyFlushLock.withLock {
            val due = emergencyOutboxDao.due(now, now - EmergencyRetrySchedule.GIVE_UP_AFTER_MS)
            if (due.isEmpty()) return
            val group = groupStateManager.groupDefinition.value ?: return
            val localMemberId = groupStateManager.localMember.value?.memberId ?: return
            for (row in due) {
                val recipient = group.findMemberById(row.recipientId)
                val error = when {
                    recipient == null -> "no longer in the family"
                    sendViaNetwork(row.toChatMessage(localMemberId), recipient, row.groupId) -> null
                    else -> "send failed"
                }
                val attempts = row.attemptCount + 1
                // Someone who left the family can't be reached; stop trying them.
                val nextAt = if (recipient == null) Long.MAX_VALUE
                else now + EmergencyRetrySchedule.delayAfter(attempts)
                emergencyOutboxDao.recordAttempt(row.alertId, row.recipientId, now, nextAt, error)
            }
            // PENDING → SENT only; a receipt may already have moved it further.
            due.map { it.alertId }.distinct().forEach { alertId ->
                if (chatMessageDao.getMessageById(alertId)?.status == MessageStatus.PENDING) {
                    chatMessageDao.updateStatus(alertId, MessageStatus.SENT)
                }
            }
        }
    }

    /**
     * Keeps retrying while the app is running: sleeps until the next copy is due, or until
     * a new alert is raised. Started once by AppInitializer. After the process is killed,
     * [EmergencyAlertWorker] brings the app back to do the same.
     */
    fun startEmergencyRetryLoop() {
        if (!emergencyLoopStarted.compareAndSet(false, true)) return
        scope.launch {
            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    val next = emergencyOutboxDao.nextAttemptAt(now - EmergencyRetrySchedule.GIVE_UP_AFTER_MS)
                    if (next == null || next == Long.MAX_VALUE) {
                        emergencyWake.receive()
                        continue
                    }
                    if (next > now) {
                        withTimeoutOrNull(next - now) { emergencyWake.receive() }
                    }
                    flushEmergencyAlerts()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: emergency retry loop error")
                    delay(EmergencyRetrySchedule.delayAfter(1))
                }
            }
        }
    }

    // Signed again on each retry: Ed25519 is deterministic, so it's the same signature.
    private fun EmergencyOutboxEntity.toChatMessage(senderId: String) = signed(ChatMessageEntity(
        messageId = alertId,
        conversationId = groupId,
        senderId = senderId,
        recipientId = null,
        content = content,
        messageType = MessageType.TEXT,
        status = MessageStatus.PENDING,
        timestamp = timestamp,
        isOutgoing = true,
        isReadLocally = true
    ))

    /**
     * [message] with this phone's author signature (F24), so a copy passed on later by anyone
     * still proves who wrote it. Stored with the message and sent with it.
     */
    private fun signed(message: ChatMessageEntity): ChatMessageEntity =
        runCatching { message.copy(authorSig = e2eeManager.signAsSelf(AuthorSignatures.chatMessage(message))) }
            .onFailure { Timber.w(it, "$TAG: could not sign message ${message.messageId}") }
            .getOrDefault(message)

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
        val message = signed(ChatMessageEntity(
            conversationId = ChatMessageEntity.generateConversationId(localMember.memberId, recipientId),
            senderId = localMember.memberId,
            recipientId = recipientId,
            content = content,
            messageType = messageType,
            status = MessageStatus.PENDING,
            isOutgoing = true,
            isReadLocally = true
        ))

        // Save to local database first
        chatMessageDao.insert(message)
        Timber.d("$TAG: Saved outgoing message ${message.messageId}")

        // Send via MQTT
        return try {
            val sent = sendViaNetwork(message, recipient)
            if (sent) {
                chatMessageDao.updateStatus(message.messageId, MessageStatus.SENT)
                Timber.d("$TAG: Message ${message.messageId} sent successfully")
                // Private messages are never copied to third parties, so nothing to replicate.
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
            val recipientX25519Key = recipient.x25519PublicKey.hexToBytes()

            val encryptedContent = e2eeManager.encryptMessage(
                plaintext = json.encodeToString(
                    ChatMessagePayload(
                        messageId = message.messageId,
                        content = message.content,
                        messageType = message.messageType,
                        timestamp = message.timestamp,
                        conversationId = conversationId,
                        replyToMessageId = message.replyToMessageId,
                        authorSig = message.authorSig
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

        val localMemberId = groupStateManager.localMember.value?.memberId ?: return

        failedMessages.forEach { message ->
            if (message.recipientId == null) {
                // A group message. These used to be skipped here, so one that failed was
                // never sent again. Receivers keep one copy per messageId, so re-sending to
                // everyone is safe.
                chatMessageDao.updateStatus(message.messageId, MessageStatus.PENDING)
                var anySent = false
                group.members.filter { it.memberId != localMemberId }.forEach { member ->
                    if (sendViaNetwork(message, member, message.conversationId)) anySent = true
                }
                chatMessageDao.updateStatus(
                    message.messageId,
                    if (anySent) MessageStatus.SENT else MessageStatus.FAILED
                )
                return@forEach
            }

            val recipient = group.findMemberById(message.recipientId) ?: return@forEach

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
            val senderX25519Key = sender.x25519PublicKey.hexToBytes()
            val senderEd25519Key = sender.ed25519PublicKey.hexToBytes()

            val decryptedJson = e2eeManager.decryptMessage(
                encryptedMessageJson = encryptedPayload,
                senderX25519PublicKey = senderX25519Key,
                senderEd25519PublicKey = senderEd25519Key
            )

            val payload = json.decodeFromString<ChatMessagePayload>(decryptedJson)

            // Check for duplicate
            if (chatMessageDao.messageExists(payload.messageId)) {
                // Confirm it again. The sender re-sends until it hears back, so when the
                // first receipt was lost, a silent duplicate would mean a crash alert being
                // retried for a day. Only DELIVERED: a re-send says nothing about reading.
                Timber.d("$TAG: Duplicate message ${payload.messageId}, re-confirming")
                sendReceipt(recipient = sender, messageId = payload.messageId, status = MessageStatus.DELIVERED)
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
                replyToMessageId = payload.replyToMessageId,
                authorSig = payload.authorSig
            )

            // Kept so this message can be passed on later with proof of who wrote it (F24). A
            // live message is already bound to its sender by the envelope, so an unsigned one
            // from an older build is fine; a signature that doesn't verify is not.
            if (payload.authorSig != null &&
                AuthorSignatures.judge(
                    payload.authorSig, sender.ed25519PublicKey, AuthorSignatures.chatMessage(message),
                    acceptUnsigned = true, verify = e2eeManager::verifyAuthor
                ) == AuthorSignatures.Verdict.DROP
            ) {
                Timber.w("$TAG: message ${payload.messageId} carries a bad author signature — dropped")
                return
            }

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

            // Not re-broadcast. Every recipient of a group message used to push it on to
            // every other member, so one message cost N² publishes for copies everyone
            // already had (E1). Gaps are filled on request by the replication back-fill.
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
                recipientX25519PublicKey = recipient.x25519PublicKey.hexToBytes()
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
                senderX25519PublicKey = sender.x25519PublicKey.hexToBytes(),
                senderEd25519PublicKey = sender.ed25519PublicKey.hexToBytes()
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

            // A crash alert owed to this member: their signed receipt is what stops the
            // retries. Rows exist only for members the alert was addressed to.
            if (emergencyOutboxDao.markDelivered(receipt.messageId, senderMemberId, System.currentTimeMillis()) > 0) {
                Timber.i("$TAG: Crash alert ${receipt.messageId.take(8)} delivered to ${senderMemberId.take(8)}")
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
        // Alert bookkeeping is only needed while an alert is live; a week is plenty.
        emergencyOutboxDao.deleteOlderThan(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

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
    val replyToMessageId: String? = null,
    /** The sender's Ed25519 over AuthorSignatures.chatMessage (F24). Optional for older senders. */
    val authorSig: String? = null
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
