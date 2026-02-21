package com.example.familysafety.transport

import android.content.Context
import com.example.familysafety.chat.ChatRepository
import com.example.familysafety.invite.InviteManager
import com.example.familysafety.core.*
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.crypto.RecipientKeys
import com.example.familysafety.group.AndroidKeyStoreLocalKeyStore
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.LazysodiumCryptoProvider
import com.example.familysafety.location.LocationRepository
import com.example.familysafety.location.MemberLocation
import com.example.familysafety.replication.ReplicationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MqttTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationRepository: LocationRepository,
    private val e2eeManager: E2EEManager,
    private val networkMonitor: NetworkMonitor
) {
    private var mqttClient: MqttAsyncClient? = null
    private var memberId: String? = null
    private var groupId: String? = null
    private var cryptoProvider: LazysodiumCryptoProvider? = null

    // Late-initialized to avoid circular dependency
    private var replicationManager: ReplicationManager? = null
    private var chatRepository: ChatRepository? = null
    private var inviteManager: InviteManager? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val familyMemberKeys = mutableMapOf<String, RecipientKeys>()
    private val pendingMessages = ConcurrentLinkedQueue<PendingMessage>()
    
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Error(val message: String, val canRetry: Boolean = true) : ConnectionState()
    }
    
    private data class PendingMessage(
        val topic: String,
        val payload: ByteArray,
        val qos: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    init {
        scope.launch {
            networkMonitor.isNetworkAvailable.collect { isAvailable ->
                if (isAvailable && _connectionState.value is ConnectionState.Disconnected) {
                    Timber.i("Network available, attempting reconnect")
                    reconnect()
                } else if (!isAvailable) {
                    Timber.w("Network unavailable")
                    _connectionState.value = ConnectionState.Error(
                        "No network connection",
                        canRetry = false
                    )
                }
            }
        }
    }

    /**
     * Wire up ReplicationManager for handling replication messages.
     * Must be called after construction to avoid circular dependency.
     */
    fun setReplicationManager(manager: ReplicationManager) {
        replicationManager = manager
        // Provide publisher callback to ReplicationManager
        manager.setMqttPublisher { topic, payload, qos ->
            publishRaw(topic, payload, qos)
        }
        Timber.d("ReplicationManager wired up to MqttTransport")
    }

    /**
     * Wire up InviteManager for handling join requests.
     * Must be called after construction to avoid circular dependency.
     */
    fun setInviteManager(manager: InviteManager) {
        inviteManager = manager
        Timber.d("InviteManager wired up to MqttTransport")
    }

    /**
     * Wire up ChatRepository for handling chat messages.
     * Must be called after construction to avoid circular dependency.
     */
    fun setChatRepository(repository: ChatRepository) {
        chatRepository = repository
        // Provide publisher callback to ChatRepository
        repository.setMqttPublisher { topic, payload, qos ->
            publishRaw(topic, payload, qos)
        }
        Timber.d("ChatRepository wired up to MqttTransport")
    }

    suspend fun initialize(
        memberIdParam: String,
        familyMembers: List<FamilyMember>,
        groupIdParam: String? = null
    ) {
        ErrorHandler.withRetry(
            maxAttempts = 3,
            initialDelayMs = 2000,
            onError = { e, attempt ->
                Timber.w(e, "Initialization attempt $attempt failed")
                _connectionState.value = ConnectionState.Error(
                    "Initializing... (attempt $attempt)",
                    canRetry = true
                )
            }
        ) {
            initializeInternal(memberIdParam, familyMembers, groupIdParam)
        }.onFailure { e ->
            Timber.e(e, "Failed to initialize after retries")
            _connectionState.value = ConnectionState.Error(
                "Initialization failed: ${e.message}",
                canRetry = true
            )
        }
    }

    private suspend fun initializeInternal(
        memberIdParam: String,
        familyMembers: List<FamilyMember>,
        groupIdParam: String? = null
    ) {
        withContext(Dispatchers.IO) {
            memberId = memberIdParam
            groupId = groupIdParam

            val keyStore = AndroidKeyStoreLocalKeyStore(context)
            cryptoProvider = LazysodiumCryptoProvider(keyStore)

            familyMembers.forEach { member ->
                if (member.memberId != memberIdParam) {
                    familyMemberKeys[member.memberId] = RecipientKeys(
                        x25519PublicKey = member.x25519PublicKey,
                        ed25519PublicKey = member.ed25519PublicKey
                    )
                }
            }

            val clientId = MqttConfig.generateClientId(memberIdParam)
            mqttClient = MqttAsyncClient(
                MqttConfig.BROKER_URL,
                clientId,
                MemoryPersistence()
            )

            setupCallbacks()
            connect(familyMembers.map { it.memberId })
        }
    }

    private fun setupCallbacks() {
        mqttClient?.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                Timber.w(cause, "Connection lost")
                _connectionState.value = ConnectionState.Disconnected
                scheduleReconnect()
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                message?.let {
                    scope.launch {
                        ErrorHandler.safely(
                            tag = "MqttTransport",
                            operation = "message handling",
                            fallback = Unit
                        ) {
                            handleIncomingMessage(topic ?: "", String(it.payload))
                        }
                    }
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                Timber.d("Message delivered")
            }
        })
    }

    private suspend fun connect(familyMemberIds: List<String>) {
        withContext(Dispatchers.IO) {
            try {
                if (!networkMonitor.isCurrentlyConnected()) {
                    throw NetworkException.NoConnection()
                }
                
                _connectionState.value = ConnectionState.Connecting
                
                val options = MqttConnectOptions().apply {
                    isCleanSession = false
                    connectionTimeout = MqttConfig.CONNECTION_TIMEOUT
                    keepAliveInterval = MqttConfig.KEEP_ALIVE_INTERVAL
                    isAutomaticReconnect = false
                    
                    // Capture memberId as a local val so the compiler can smart-cast
                    // it as non-null inside the lambda, eliminating the !! operator.
                    memberId?.let { id ->
                        val willMessage = MqttMessage(
                            createOfflineWillMessage(id).toByteArray()
                        ).apply {
                            qos = MqttConfig.DEFAULT_QOS
                            isRetained = true
                        }
                        setWill(
                            MqttConfig.getPresenceTopic(id),
                            willMessage.payload,
                            willMessage.qos,
                            willMessage.isRetained
                        )
                    }
                }

                val connectResult = ErrorHandler.withTimeout(
                    timeoutMs = 30_000,
                    onTimeout = {
                        Timber.e("Connection timeout")
                        _connectionState.value = ConnectionState.Error(
                            "Connection timeout",
                            canRetry = true
                        )
                    }
                ) {
                    suspendCancellableCoroutine { continuation ->
                        mqttClient?.connect(options, null, object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                continuation.resume(Unit) {}
                            }

                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                continuation.resumeWith(
                                    Result.failure(exception ?: Exception("Connection failed"))
                                )
                            }
                        })
                    }
                }

                if (connectResult.isSuccess) {
                    Timber.i("Connected to MQTT broker")
                    _connectionState.value = ConnectionState.Connected
                    reconnectAttempts = 0

                    // Subscribe to own topics for receiving chat and replication data
                    subscribeToOwnTopics()

                    subscribeToFamilyMembers(familyMemberIds)
                    processPendingMessages()

                    // Trigger full sync after connection
                    scope.launch {
                        delay(2000) // Wait for subscriptions to complete
                        replicationManager?.requestFullSync()
                    }
                } else {
                    throw connectResult.exceptionOrNull() ?: Exception("Unknown connection error")
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Connection failed")
                val errorMessage = when (e) {
                    is NetworkException.NoConnection -> "No network connection"
                    is NetworkException.Timeout -> "Connection timeout"
                    is TimeoutException -> "Connection timeout"
                    else -> "Connection failed: ${e.message}"
                }
                _connectionState.value = ConnectionState.Error(errorMessage, canRetry = true)
                throw e
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        
        if (reconnectAttempts >= maxReconnectAttempts) {
            Timber.e("Max reconnection attempts reached")
            _connectionState.value = ConnectionState.Error(
                "Connection failed after $maxReconnectAttempts attempts",
                canRetry = false
            )
            return
        }
        
        reconnectJob = scope.launch {
            val delay = calculateBackoff(reconnectAttempts)
            Timber.i("Scheduling reconnect in ${delay}ms (attempt ${reconnectAttempts + 1})")
            
            delay(delay)
            reconnect()
        }
    }

    private fun calculateBackoff(attempt: Int): Long {
        val baseDelay = MqttConfig.RECONNECT_DELAY_MS
        val maxDelay = 60_000L
        val delay = (baseDelay * Math.pow(2.0, attempt.toDouble())).toLong()
        return delay.coerceAtMost(maxDelay)
    }

    private suspend fun reconnect() {
        val currentMemberId = memberId ?: return
        val currentMembers = familyMemberKeys.keys.toList() + currentMemberId
        
        reconnectAttempts++
        
        ErrorHandler.withRetry(
            maxAttempts = 1,
            onError = { e, _ ->
                Timber.w(e, "Reconnect failed")
            }
        ) {
            connect(currentMembers)
        }.onFailure {
            scheduleReconnect()
        }
    }

    private suspend fun subscribeToFamilyMembers(memberIds: List<String>) {
        memberIds.forEach { otherMemberId ->
            if (otherMemberId != memberId) {
                ErrorHandler.safely(
                    tag = "MqttTransport",
                    operation = "subscribing to $otherMemberId"
                ) {
                    subscribeToMember(otherMemberId)
                }
            }
        }
    }

    /**
     * Subscribe to topics where this device receives messages directly.
     * Includes chat, replication requests, and replication data.
     */
    private suspend fun subscribeToOwnTopics() {
        val currentMemberId = memberId ?: return
        val currentGroupId = groupId

        withContext(Dispatchers.IO) {
            val topics = mutableListOf<String>()
            val qosLevels = mutableListOf<Int>()

            // Chat topics - messages sent directly to us
            topics.add(MqttConfig.getChatTopic(currentMemberId))
            qosLevels.add(MqttConfig.DEFAULT_QOS)

            // Chat receipt topics
            topics.add(MqttConfig.getChatReceiptTopic(currentMemberId))
            qosLevels.add(MqttConfig.DEFAULT_QOS)

            // Chat read topics
            topics.add(MqttConfig.getChatReadTopic(currentMemberId))
            qosLevels.add(MqttConfig.DEFAULT_QOS)

            // Join request topic - other members asking to join the group
            topics.add(MqttConfig.getJoinRequestTopic(currentMemberId))
            qosLevels.add(MqttConfig.DEFAULT_QOS)

            // Replication request topic - peers asking us for data
            topics.add(MqttConfig.getReplicationRequestTopic(currentMemberId))
            qosLevels.add(MqttConfig.DEFAULT_QOS)

            // Replication data topic - peers sending us data
            topics.add(MqttConfig.getReplicationDataTopic(currentMemberId))
            qosLevels.add(MqttConfig.DEFAULT_QOS)

            // Group-level replication announcement topic
            if (currentGroupId != null) {
                topics.add(MqttConfig.getReplicationAnnounceTopic(currentGroupId))
                qosLevels.add(MqttConfig.QOS_AT_MOST_ONCE)
            }

            mqttClient?.subscribe(
                topics.toTypedArray(),
                qosLevels.toIntArray(),
                null,
                object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Timber.i("Subscribed to own topics: ${topics.size} topics")
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Timber.e(exception, "Failed to subscribe to own topics")
                        // Retry after delay
                        scope.launch {
                            delay(5000)
                            subscribeToOwnTopics()
                        }
                    }
                }
            )
        }
    }

    private suspend fun subscribeToMember(otherMemberId: String) {
        withContext(Dispatchers.IO) {
            val locationTopic = MqttConfig.getLocationTopic(otherMemberId)
            val presenceTopic = MqttConfig.getPresenceTopic(otherMemberId)
            
            mqttClient?.subscribe(
                arrayOf(locationTopic, presenceTopic),
                intArrayOf(MqttConfig.DEFAULT_QOS, MqttConfig.DEFAULT_QOS),
                null,
                object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Timber.i("Subscribed to member: $otherMemberId")
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Timber.e(exception, "Failed to subscribe to member: $otherMemberId")
                        scope.launch {
                            delay(5000)
                            ErrorHandler.safely("MqttTransport", "retry subscribe") {
                                subscribeToMember(otherMemberId)
                            }
                        }
                    }
                }
            )
        }
    }

    suspend fun publishLocation(location: MemberLocation) {
        ErrorHandler.safely(
            tag = "MqttTransport",
            operation = "publishing location"
        ) {
            publishLocationInternal(location)
        }
    }

    private suspend fun publishLocationInternal(location: MemberLocation) {
        withContext(Dispatchers.IO) {
            val currentMemberId = memberId ?: throw GroupStateException.NotInitialized()
            val topic = MqttConfig.getLocationTopic(currentMemberId)
            val messageJson = MessageProtocol.encodeLocationUpdate(location)

            if (familyMemberKeys.isEmpty()) {
                Timber.d("No recipients yet, skipping location publish")
                return@withContext
            }

            // Encrypt separately for each recipient so every member can decrypt.
            // NaCl box uses per-pair shared secrets, so one ciphertext cannot be
            // read by multiple recipients — we publish one copy per member.
            familyMemberKeys.forEach { (recipientId, recipientKeys) ->
                val encryptedMessage = try {
                    e2eeManager.encryptMessage(
                        plaintext = messageJson,
                        recipientMemberId = recipientId,
                        recipientX25519PublicKey = recipientKeys.x25519PublicKeyBytes()
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Encryption failed for $recipientId")
                    return@forEach
                }

                val payload = encryptedMessage.toByteArray()

                if (_connectionState.value != ConnectionState.Connected) {
                    queueMessage(topic, payload, MqttConfig.DEFAULT_QOS)
                    return@forEach
                }

                val publishResult = ErrorHandler.withRetry(
                    maxAttempts = 3,
                    initialDelayMs = 500,
                    onError = { e, attempt ->
                        Timber.w(e, "Publish attempt $attempt failed for $recipientId")
                    }
                ) {
                    suspendCancellableCoroutine { continuation ->
                        val message = MqttMessage(payload).apply {
                            qos = MqttConfig.DEFAULT_QOS
                            isRetained = false
                        }
                        mqttClient?.publish(topic, message, null, object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                Timber.d("Published location to $recipientId")
                                continuation.resume(Unit) {}
                            }
                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                continuation.resumeWith(Result.failure(exception ?: Exception("Publish failed")))
                            }
                        })
                    }
                }

                if (publishResult.isFailure) {
                    queueMessage(topic, payload, MqttConfig.DEFAULT_QOS)
                }
            }
        }
    }

    private fun queueMessage(topic: String, payload: ByteArray, qos: Int) {
        val message = PendingMessage(topic, payload, qos)
        pendingMessages.offer(message)
        
        while (pendingMessages.size > 100) {
            pendingMessages.poll()
        }
        
        Timber.i("Queued message, pending: ${pendingMessages.size}")
    }

    private suspend fun processPendingMessages() {
        withContext(Dispatchers.IO) {
            Timber.i("Processing ${pendingMessages.size} pending messages")
            
            var processed = 0
            var failed = 0
            
            while (pendingMessages.isNotEmpty()) {
                val message = pendingMessages.poll() ?: break
                
                if (System.currentTimeMillis() - message.timestamp > 3600_000) {
                    Timber.w("Discarding stale message")
                    continue
                }
                
                try {
                    val mqttMessage = MqttMessage(message.payload).apply {
                        qos = message.qos
                    }
                    
                    suspendCancellableCoroutine { continuation ->
                        mqttClient?.publish(message.topic, mqttMessage, null, object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                processed++
                                continuation.resume(Unit) {}
                            }

                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                failed++
                                Timber.w(exception, "Failed to send pending message")
                                pendingMessages.offer(message)
                                continuation.resume(Unit) {}
                            }
                        })
                    }
                    
                    delay(100)
                    
                } catch (e: Exception) {
                    Timber.e(e, "Error processing pending message")
                    failed++
                }
            }
            
            Timber.i("Processed pending messages: $processed sent, $failed failed")
        }
    }

    suspend fun publishPresence(isOnline: Boolean) {
        ErrorHandler.safely(
            tag = "MqttTransport",
            operation = "publishing presence"
        ) {
            publishPresenceInternal(isOnline)
        }
    }

    private suspend fun publishPresenceInternal(isOnline: Boolean) {
        withContext(Dispatchers.IO) {
            val currentMemberId = memberId ?: return@withContext
            val topic = MqttConfig.getPresenceTopic(currentMemberId)
            
            val messageJson = MessageProtocol.encodePresenceUpdate(currentMemberId, isOnline)
            
            val encryptedMessage = try {
                val firstRecipient = familyMemberKeys.entries.firstOrNull()
                if (firstRecipient != null) {
                    e2eeManager.encryptMessage(
                        plaintext = messageJson,
                        recipientMemberId = firstRecipient.key,
                        recipientX25519PublicKey = firstRecipient.value.x25519PublicKeyBytes()
                    )
                } else {
                    messageJson
                }
            } catch (e: Exception) {
                Timber.e(e, "Presence encryption failed")
                return@withContext
            }
            
            val message = MqttMessage(encryptedMessage.toByteArray()).apply {
                qos = MqttConfig.DEFAULT_QOS
                isRetained = true
            }
            
            mqttClient?.publish(topic, message)
        }
    }

    private suspend fun handleIncomingMessage(topic: String, payload: String) {
        try {
            val topicParts = topic.split("/")
            Timber.d("Received message on topic: $topic")

            // Route based on topic structure
            when {
                // Chat messages: familysafe/{memberId}/chat
                topic.endsWith("/chat") -> {
                    val senderId = extractSenderFromTopic(topic)
                    if (senderId != null) {
                        chatRepository?.handleIncomingMessage(payload, senderId)
                    }
                }

                // Chat receipts: familysafe/{memberId}/chat/receipt
                topic.endsWith("/chat/receipt") -> {
                    chatRepository?.handleDeliveryReceipt(payload)
                }

                // Chat read receipts: familysafe/{memberId}/chat/read
                topic.endsWith("/chat/read") -> {
                    chatRepository?.handleDeliveryReceipt(payload)
                }

                // Replication requests: familysafe/{memberId}/replication/request
                topic.endsWith("/replication/request") -> {
                    val senderId = extractSenderFromTopic(topic)
                    if (senderId != null) {
                        replicationManager?.handleReplicationRequest(payload, senderId)
                    }
                }

                // Replication data: familysafe/{memberId}/replication/data
                topic.endsWith("/replication/data") -> {
                    val senderId = extractSenderFromTopic(topic)
                    if (senderId != null) {
                        replicationManager?.handleReplicationResponse(payload, senderId)
                    }
                }

                // Replication announcements: familysafe/group/{groupId}/replication/announce
                topic.contains("/replication/announce") -> {
                    val senderId = extractSenderFromAnnounceTopic(payload)
                    if (senderId != null) {
                        replicationManager?.handleDataAvailabilityAnnouncement(payload, senderId)
                    }
                }

                // Join requests: familysafe/{inviterMemberId}/join_request
                topic.endsWith("/join_request") -> {
                    inviteManager?.handleIncomingJoinRequest(payload)
                }

                // Location updates: familysafe/{memberId}/location
                topic.endsWith("/location") -> {
                    handleEncryptedLocationOrPresence(topic, payload)
                }

                // Presence updates: familysafe/{memberId}/presence
                topic.endsWith("/presence") -> {
                    handleEncryptedLocationOrPresence(topic, payload)
                }

                else -> {
                    Timber.w("Unknown topic pattern: $topic")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle incoming message on topic: $topic")
        }
    }

    /**
     * Handle encrypted location and presence messages (original functionality).
     */
    private suspend fun handleEncryptedLocationOrPresence(topic: String, encryptedPayload: String) {
        val senderId = topic.split("/").getOrNull(1) ?: run {
            Timber.w("Could not extract sender ID from topic: $topic")
            return
        }

        val senderKeys = familyMemberKeys[senderId] ?: run {
            Timber.w("Unknown sender: $senderId")
            return
        }

        val decryptResult = ErrorHandler.withRetry(
            maxAttempts = 2,
            initialDelayMs = 100
        ) {
            e2eeManager.decryptMessage(
                encryptedMessageJson = encryptedPayload,
                senderX25519PublicKey = senderKeys.x25519PublicKeyBytes(),
                senderEd25519PublicKey = senderKeys.ed25519PublicKeyBytes()
            )
        }

        val decryptedPayload = decryptResult.getOrElse {
            Timber.d("Failed to decrypt message from $senderId (not encrypted for us)")
            return
        }

        val envelope = MessageProtocol.decodeEnvelope(decryptedPayload)

        when (envelope.type) {
            "location_update" -> {
                val locationUpdate = MessageProtocol.decodeLocationUpdate(envelope.payload)
                val memberLocation = MessageProtocol.locationUpdateToMemberLocation(locationUpdate)
                locationRepository.updateMemberLocation(memberLocation)
                Timber.d("Received encrypted location update for ${locationUpdate.memberId}")

                // Replicate to peers for backup
                replicationManager?.replicateLocation(memberLocation)
            }
            "presence_update" -> {
                val presenceUpdate = MessageProtocol.decodePresenceUpdate(envelope.payload)
                Timber.d("Received encrypted presence update: ${presenceUpdate.memberId} is ${if (presenceUpdate.isOnline) "online" else "offline"}")
            }
            else -> {
                Timber.w("Unknown message type: ${envelope.type}")
            }
        }
    }

    /**
     * Extract sender member ID from topic like familysafe/{memberId}/...
     */
    private fun extractSenderFromTopic(topic: String): String? {
        val parts = topic.split("/")
        return if (parts.size >= 2 && parts[0] == "familysafe") {
            parts[1]
        } else null
    }

    /**
     * Extract sender from announcement payload (JSON contains announcerId).
     */
    private fun extractSenderFromAnnounceTopic(payload: String): String? {
        return try {
            // Simple extraction - look for "announcerId":"xxx"
            val regex = """"announcerId"\s*:\s*"([^"]+)"""".toRegex()
            regex.find(payload)?.groupValues?.getOrNull(1)
        } catch (e: Exception) {
            null
        }
    }

    private fun createOfflineWillMessage(memberId: String): String {
        return MessageProtocol.encodePresenceUpdate(memberId, isOnline = false)
    }

    fun updateFamilyMembers(members: List<FamilyMember>) {
        scope.launch {
            ErrorHandler.safely("MqttTransport", "updating family members") {
                updateFamilyMembersInternal(members)
            }
        }
    }

    private suspend fun updateFamilyMembersInternal(members: List<FamilyMember>) {
        val currentMemberId = memberId ?: return
        
        familyMemberKeys.clear()
        e2eeManager.clearSharedSecretCache()
        
        members.forEach { member ->
            if (member.memberId != currentMemberId) {
                familyMemberKeys[member.memberId] = RecipientKeys(
                    x25519PublicKey = member.x25519PublicKey,
                    ed25519PublicKey = member.ed25519PublicKey
                )
            }
        }
        
        if (_connectionState.value == ConnectionState.Connected) {
            subscribeToFamilyMembers(members.map { it.memberId })
        }
    }

    fun disconnect() {
        scope.launch {
            ErrorHandler.safely("MqttTransport", "disconnecting") {
                disconnectInternal()
            }
        }
    }

    private suspend fun disconnectInternal() {
        reconnectJob?.cancel()
        publishPresence(false)
        mqttClient?.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    fun cleanup() {
        disconnect()
        scope.cancel()
        e2eeManager.clearSharedSecretCache()
        pendingMessages.clear()
    }

    // =========================================================================
    // RAW PUBLISH FOR REPLICATION AND CHAT
    // =========================================================================

    /**
     * Publish raw message to a topic.
     * Used by ReplicationManager and ChatRepository for their specific messages.
     * Returns true if publish succeeded.
     */
    suspend fun publishRaw(topic: String, payload: ByteArray, qos: Int): Boolean {
        return withContext(Dispatchers.IO) {
            if (_connectionState.value != ConnectionState.Connected) {
                Timber.w("Not connected, queueing raw message")
                queueMessage(topic, payload, qos)
                return@withContext false
            }

            try {
                suspendCancellableCoroutine { continuation ->
                    val message = MqttMessage(payload).apply {
                        this.qos = qos
                        isRetained = false
                    }

                    mqttClient?.publish(topic, message, null, object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            Timber.d("Published raw message to $topic")
                            continuation.resume(true) {}
                        }

                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            Timber.w(exception, "Failed to publish raw message to $topic")
                            queueMessage(topic, payload, qos)
                            continuation.resume(false) {}
                        }
                    })
                }
            } catch (e: Exception) {
                Timber.e(e, "Error publishing raw message")
                queueMessage(topic, payload, qos)
                false
            }
        }
    }

    /**
     * Update the group ID (for group-level topic subscriptions).
     */
    fun setGroupId(newGroupId: String) {
        val oldGroupId = groupId
        groupId = newGroupId

        // Resubscribe to group topics if connected
        if (_connectionState.value == ConnectionState.Connected && oldGroupId != newGroupId) {
            scope.launch {
                subscribeToOwnTopics()
            }
        }
    }
}
