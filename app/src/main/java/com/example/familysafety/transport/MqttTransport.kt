package com.example.familysafety.transport

import android.content.Context
import com.example.familysafety.core.*
import com.example.familysafety.core.NetworkMonitor
import com.example.familysafety.core.SecurityEventRepository
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.crypto.RecipientKeys
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.LazysodiumCryptoProvider
import com.example.familysafety.location.LocationRepository
import com.example.familysafety.location.MemberLocation
import com.example.familysafety.sync.GroupSyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class MqttTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationRepository: LocationRepository,
    private val e2eeManager: E2EEManager,
    private val cryptoProvider: LazysodiumCryptoProvider,
    private val networkMonitor: NetworkMonitor
) {
    private var mqttClient: MqttAsyncClient? = null
    private var memberId: String? = null
    private var groupId: String? = null

    // Callback for incoming messages (set by UnifiedTransportManager)
    var onMessageReceived: (suspend (topic: String, payload: String) -> Unit)? = null

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val pendingMessages = ConcurrentLinkedQueue<PendingMessage>()
    private var familyMemberKeys = mutableMapOf<String, RecipientKeys>()
    private var currentKeepAlive = MqttConfig.KEEP_ALIVE_MOVING
    private var networkObserverJob: Job? = null

    // Setter-injected to avoid circular dependency: MqttTransport ← TransportProvider ← GroupSyncManager
    var groupSyncManager: GroupSyncManager? = null
    var securityEventRepository: SecurityEventRepository? = null

    // Throttle group-sync requests triggered by decrypt failures to once per sender per minute.
    private val decryptFailureSyncCooldowns = ConcurrentHashMap<String, Long>()
    private companion object {
        private const val TAG = "MqttTransport"
        private const val DECRYPT_SYNC_COOLDOWN_MS = 60_000L
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String, val canRetry: Boolean) : ConnectionState()
    }


    suspend fun initialize(
        memberIdParam: String,
        familyMembers: List<FamilyMember>,
        groupIdParam: String
    ) {
        ensureActiveScope()
        this.memberId = memberIdParam
        this.groupId = groupIdParam
        if (familyMembers.isNotEmpty()) {
            this.familyMemberKeys = familyMembers
                .associate { it.memberId to RecipientKeys(it.x25519PublicKey, it.ed25519PublicKey) }
                .toMutableMap()
        }

        startNetworkMonitoring()

        if (_connectionState.value == ConnectionState.Connected) {
            Timber.d("$TAG: already connected, skipping re-init")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                _connectionState.value = ConnectionState.Connecting

                val brokerUrl = MqttConfig.BROKER_URL
                if (!BrokerConfig.isSecureBroker()) {
                    Timber.w("$TAG: connecting to INSECURE broker (no TLS) — development only: $brokerUrl")
                }
                val clientId = MqttConfig.generateClientId(memberIdParam)
                mqttClient = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())

                setupCallbacks()

                val connOpts = MqttConnectOptions().apply {
                    isCleanSession = false
                    isAutomaticReconnect = false  // we handle reconnect via scheduleReconnect()
                    connectionTimeout = MqttConfig.CONNECTION_TIMEOUT
                    keepAliveInterval = currentKeepAlive
                    
                    // Set offline will so peers know if we drop off abruptly
                    val willJson = MessageProtocol.encodePresenceUpdate(memberIdParam, false)
                    setWill(MqttConfig.getPresenceTopic(memberIdParam), willJson.toByteArray(), MqttConfig.DEFAULT_QOS, true)
                }

                val connectResult = ErrorHandler.withRetry(
                    maxAttempts = 3,
                    initialDelayMs = 2000,
                    onError = { e, attempt ->
                        Timber.w(e, "$TAG: connection attempt $attempt failed")
                    }
                ) {
                    suspendCancellableCoroutine { continuation ->
                        mqttClient?.connect(connOpts, null, object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                continuation.resume(Unit)
                            }
                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                continuation.resumeWith(Result.failure(exception ?: Exception("MQTT connection failed")))
                            }
                        })
                    }
                }

                if (connectResult.isSuccess) {
                    Timber.i("$TAG: Connected to MQTT broker")
                    _connectionState.value = ConnectionState.Connected
                    reconnectAttempts = 0

                    val memberIdsForSubscriptions = if (familyMembers.isNotEmpty()) {
                        familyMembers.map { it.memberId }
                    } else {
                        familyMemberKeys.keys.toList()
                    }

                    subscribeToOwnTopics()
                    subscribeToFamilyMembers(memberIdsForSubscriptions)
                    processPendingMessages()
                    
                    // Announce online
                    publishPresence(true)

                } else {
                    throw connectResult.exceptionOrNull() ?: Exception("Unknown connection error")
                }

            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to initialize MQTT")
                _connectionState.value = ConnectionState.Error(
                    e.message ?: "Unknown error",
                    canRetry = true
                )
                scheduleReconnect()
            }
        }
    }

    private fun setupCallbacks() {
        mqttClient?.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                Timber.w(cause, "$TAG: Connection lost")
                _connectionState.value = ConnectionState.Disconnected
                scheduleReconnect()
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                message?.let {
                    val topicStr = topic ?: ""
                    val payloadStr = String(it.payload)
                    scope.launch {
                        // Location and presence are decrypted here — e2eeManager + familyMemberKeys live here
                        if (topicStr.endsWith("/location") || topicStr.endsWith("/presence")) {
                            handleLocationOrPresence(topicStr, payloadStr)
                        } else {
                            onMessageReceived?.invoke(topicStr, payloadStr)
                        }
                    }
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                // Handled via listeners for critical messages
            }
        })
    }

    private suspend fun subscribeToOwnTopics() {
        val id = memberId ?: return
        val gId = groupId ?: return
        
        val topics = mutableListOf<String>()
        val qosLevels = mutableListOf<Int>()

        // Chat inbox
        topics.add(MqttConfig.getChatTopic(id))
        qosLevels.add(MqttConfig.DEFAULT_QOS)

        // Chat receipts/read receipts
        topics.add(MqttConfig.getChatReceiptTopic(id))
        qosLevels.add(MqttConfig.DEFAULT_QOS)
        topics.add(MqttConfig.getChatReadTopic(id))
        qosLevels.add(MqttConfig.DEFAULT_QOS)

        // Replication requests/data
        topics.add(MqttConfig.getReplicationRequestTopic(id))
        qosLevels.add(MqttConfig.DEFAULT_QOS)
        topics.add(MqttConfig.getReplicationDataTopic(id))
        qosLevels.add(MqttConfig.DEFAULT_QOS)
        topics.add(MqttConfig.getReplicationAnnounceInboxTopic(id))
        qosLevels.add(MqttConfig.DEFAULT_QOS)

        // Join requests (received by approvers) and approvals (received by joiners)
        topics.add(MqttConfig.getJoinRequestTopic(id))
        qosLevels.add(MqttConfig.DEFAULT_QOS)
        topics.add(MqttConfig.getJoinApprovalTopic(id))
        qosLevels.add(MqttConfig.DEFAULT_QOS)
        topics.add(MqttConfig.getSyncRequestTopic(id))
        qosLevels.add(MqttConfig.DEFAULT_QOS)

        // Per-member encrypted group sync inbox
        topics.add(MqttConfig.getGroupSyncInboxTopic(id))
        qosLevels.add(MqttConfig.DEFAULT_QOS)
        
        // Group ack topic
        topics.add(MqttConfig.getGroupAckTopic(gId))
        qosLevels.add(MqttConfig.DEFAULT_QOS)
        
        // File transfer manifest (retained broadcast for the group)
        topics.add(MqttConfig.getFileManifestTopic(gId))
        qosLevels.add(MqttConfig.DEFAULT_QOS)
        
        // File chunks (wildcard)
        topics.add(MqttConfig.getFileChunkWildcardTopic(gId))
        qosLevels.add(MqttConfig.DEFAULT_QOS)
        
        // File re-broadcast requests addressed to us
        topics.add(MqttConfig.getFileRequestTopic(id))
        qosLevels.add(MqttConfig.DEFAULT_QOS)

        try {
            mqttClient?.subscribe(topics.toTypedArray(), qosLevels.toIntArray())
            Timber.i("$TAG: Subscribed to own topics")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to subscribe to own topics")
        }
    }

    private suspend fun subscribeToFamilyMembers(memberIds: List<String>) {
        memberIds.filter { it != memberId }.forEach { otherId ->
            subscribeToMember(otherId)
        }
    }

    private suspend fun subscribeToMember(otherMemberId: String) {
        withContext(Dispatchers.IO) {
            val locationTopic = MqttConfig.getLocationTopic(otherMemberId)
            val presenceTopic = MqttConfig.getPresenceTopic(otherMemberId)
            val movementTopic = MqttConfig.getMovementTopic(otherMemberId)
            
            mqttClient?.subscribe(
                arrayOf(locationTopic, presenceTopic, movementTopic),
                intArrayOf(MqttConfig.DEFAULT_QOS, MqttConfig.DEFAULT_QOS, MqttConfig.DEFAULT_QOS),
                null,
                object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Timber.i("$TAG: Subscribed to member: $otherMemberId")
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Timber.e(exception, "$TAG: Failed to subscribe to member: $otherMemberId")
                    }
                }
            )
        }
    }

    suspend fun publishRaw(topic: String, payload: ByteArray, qos: Int, retained: Boolean = false): Boolean {
        return withContext(Dispatchers.IO) {
            if (_connectionState.value != ConnectionState.Connected) {
                queueMessage(topic, payload, qos, retained)
                return@withContext false
            }

            try {
                val message = MqttMessage(payload).apply {
                    this.qos = qos
                    isRetained = retained
                }

                suspendCancellableCoroutine { continuation ->
                    mqttClient?.publish(topic, message, null, object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            continuation.resume(true)
                        }
                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            Timber.w(exception, "$TAG: Failed to publish to $topic")
                            queueMessage(topic, payload, qos, retained)
                            _connectionState.value = ConnectionState.Disconnected
                            scheduleReconnect()
                            continuation.resume(false)
                        }
                    })
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error publishing to $topic")
                queueMessage(topic, payload, qos, retained)
                _connectionState.value = ConnectionState.Disconnected
                scheduleReconnect()
                false
            }
        }
    }

    private suspend fun publishPresence(isOnline: Boolean) {
        val id = memberId ?: return
        val topic = MqttConfig.getPresenceTopic(id)
        val presenceJson = MessageProtocol.encodePresenceUpdate(id, isOnline)
        publishRaw(topic, presenceJson.toByteArray(), MqttConfig.DEFAULT_QOS, true)
    }

    private fun queueMessage(topic: String, payload: ByteArray, qos: Int, retained: Boolean) {
        val msg = PendingMessage(topic, payload, qos, retained)
        pendingMessages.offer(msg)
        val now = System.currentTimeMillis()
        pendingMessages.removeIf { now - it.timestamp > 3600_000 }
        while (pendingMessages.size > 200) pendingMessages.poll()
        Timber.d("$TAG: Queued message for $topic (pending: ${pendingMessages.size})")
    }

    private suspend fun processPendingMessages() {
        while (pendingMessages.isNotEmpty()) {
            if (_connectionState.value != ConnectionState.Connected) break
            val msg = pendingMessages.poll() ?: break
            if (System.currentTimeMillis() - msg.timestamp > 3600_000) continue
            val sent = publishRaw(msg.topic, msg.payload, msg.qos, msg.retained)
            if (!sent) break  // connection dropped mid-drain; remaining stay queued
            delay(50)
        }
    }

    private fun startNetworkMonitoring() {
        ensureActiveScope()
        if (networkObserverJob?.isActive == true) return
        networkObserverJob = scope.launch {
            var wasAvailable = networkMonitor.isCurrentlyConnected()
            networkMonitor.isNetworkAvailable.collect { isAvailable ->
                if (isAvailable && !wasAvailable
                    && _connectionState.value != ConnectionState.Connected) {
                    Timber.i("$TAG: Network restored — cancelling backoff, reconnecting immediately")
                    reconnectJob?.cancel()
                    reconnectJob = null
                    reconnectAttempts = 0
                    val id = memberId ?: return@collect
                    val gId = groupId ?: return@collect
                    initialize(id, emptyList(), gId)
                }
                wasAvailable = isAvailable
            }
        }
    }

    private fun scheduleReconnect() {
        ensureActiveScope()
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(12)
            // Exponential backoff: 5 s, 10 s, 20 s, 40 s … capped at 5 minutes.
            val backoffFactor = 1L shl (reconnectAttempts - 1)
            val delay = (MqttConfig.RECONNECT_DELAY_MS * backoffFactor).coerceAtMost(5 * 60_000L)
            Timber.i("$TAG: Reconnect attempt $reconnectAttempts in ${delay / 1000}s")
            delay(delay)
            val id = memberId ?: return@launch
            val gId = groupId ?: return@launch
            initialize(id, emptyList(), gId)
            // initialize() called scheduleReconnect() in its catch block, but that call was a
            // no-op because this job was still active. Self-schedule the next attempt now that
            // we are about to complete, so the loop keeps going until connected.
            if (_connectionState.value != ConnectionState.Connected) {
                reconnectJob = null  // release the guard so the next call proceeds
                scheduleReconnect()
            }
        }
    }

    fun setGroupId(newGroupId: String) {
        val old = groupId
        groupId = newGroupId
        if (old != newGroupId && _connectionState.value == ConnectionState.Connected) {
            scope.launch { subscribeToOwnTopics() }
        }
    }

    fun updateFamilyMembers(members: List<FamilyMember>) {
        ensureActiveScope()
        scope.launch {
            val newKeys = members.associate {
                it.memberId to RecipientKeys(it.x25519PublicKey, it.ed25519PublicKey)
            }
            val addedIds = newKeys.keys - familyMemberKeys.keys
            val removedIds = familyMemberKeys.keys - newKeys.keys
            familyMemberKeys = newKeys.toMutableMap()
            removedIds.forEach { e2eeManager.removeSharedSecret(it) }
            if (addedIds.isNotEmpty()) {
                subscribeToFamilyMembers(addedIds.toList())
            }
        }
    }

    suspend fun handleLocationOrPresencePublic(topic: String, encryptedPayload: String) =
        handleLocationOrPresence(topic, encryptedPayload)

    private suspend fun handleLocationOrPresence(topic: String, encryptedPayload: String) {
        val senderId = topic.split("/").getOrNull(1) ?: return
        val senderKeys = familyMemberKeys[senderId] ?: return

        val decryptResult = ErrorHandler.withRetry(maxAttempts = 2, initialDelayMs = 100) {
            e2eeManager.decryptMessage(
                encryptedMessageJson = encryptedPayload,
                senderX25519PublicKey = senderKeys.x25519PublicKeyBytes(),
                senderEd25519PublicKey = senderKeys.ed25519PublicKeyBytes()
            )
        }

        val decryptedPayload = decryptResult.getOrElse {
            Timber.w("$TAG: Failed to decrypt message from $senderId")
            securityEventRepository?.recordDecryptFailure(senderId)
            val lastSync = decryptFailureSyncCooldowns[senderId] ?: 0L
            if (System.currentTimeMillis() - lastSync > DECRYPT_SYNC_COOLDOWN_MS) {
                decryptFailureSyncCooldowns[senderId] = System.currentTimeMillis()
                scope.launch {
                    groupSyncManager?.requestGroupStateRefresh(
                        reason = "decrypt_failure",
                        changedMemberId = senderId
                    )
                }
            }
            return
        }
        securityEventRepository?.recordDecryptSuccess(senderId)

        val envelope = try {
            MessageProtocol.decodeEnvelope(decryptedPayload)
        } catch (e: Exception) {
            Timber.w("$TAG: Failed to decode envelope from $senderId: ${e.message}")
            return
        }
        when (envelope.type) {
            "location_update" -> {
                val locationUpdate = MessageProtocol.decodeLocationUpdate(envelope.payload)
                val memberLocation = MessageProtocol.locationUpdateToMemberLocation(locationUpdate)
                if (DataValidator.validateLocation(memberLocation) !is ValidationResult.Valid) {
                    Timber.w("$TAG: Rejected invalid location from $senderId")
                    return
                }
                locationRepository.updateMemberLocation(memberLocation)
            }
            "presence_update" -> {
                val presenceUpdate = MessageProtocol.decodePresenceUpdate(envelope.payload)
                Timber.d("$TAG: ${presenceUpdate.memberId} is ${if (presenceUpdate.isOnline) "online" else "offline"}")
            }
        }
    }

    fun cleanup() {
        reconnectJob?.cancel()
        networkObserverJob?.cancel()
        try {
            mqttClient?.disconnect()
        } catch (e: Exception) {
            Timber.w(e, "$TAG: disconnect during cleanup failed")
        }
        mqttClient = null
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        reconnectJob = null
        networkObserverJob = null
        reconnectAttempts = 0
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun ensureActiveScope() {
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            reconnectJob = null
            networkObserverJob = null
        }
    }
}

private data class PendingMessage(
    val topic: String,
    val payload: ByteArray,
    val qos: Int,
    val retained: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
