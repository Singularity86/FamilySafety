package com.example.familysafety.transport

import android.content.Context
import com.example.familysafety.core.*
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.crypto.RecipientKeys
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.LazysodiumCryptoProvider
import com.example.familysafety.location.LocationRepository
import com.example.familysafety.location.MemberLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class MqttTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationRepository: LocationRepository,
    private val e2eeManager: E2EEManager,
    private val cryptoProvider: LazysodiumCryptoProvider
) {
    private var mqttClient: MqttAsyncClient? = null
    private var memberId: String? = null
    private var groupId: String? = null

    // Callback for incoming messages (set by UnifiedTransportManager)
    var onMessageReceived: (suspend (topic: String, payload: String) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val pendingMessages = ConcurrentLinkedQueue<PendingMessage>()
    private var familyMemberKeys = mutableMapOf<String, RecipientKeys>()
    private var currentKeepAlive = MqttConfig.KEEP_ALIVE_MOVING

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String, val canRetry: Boolean) : ConnectionState()
    }

    private companion object {
        private const val TAG = "MqttTransport"
    }

    suspend fun initialize(
        memberIdParam: String,
        familyMembers: List<FamilyMember>,
        groupIdParam: String
    ) {
        this.memberId = memberIdParam
        this.groupId = groupIdParam
        this.familyMemberKeys = familyMembers.associate { it.memberId to RecipientKeys(it.x25519PublicKey, it.ed25519PublicKey) }.toMutableMap()

        if (_connectionState.value == ConnectionState.Connected) {
            Timber.d("$TAG: already connected, skipping re-init")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                _connectionState.value = ConnectionState.Connecting

                val brokerUrl = MqttConfig.BROKER_URL
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

                    subscribeToOwnTopics()
                    subscribeToFamilyMembers(familyMembers.map { it.memberId })
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

        // Group sync topic (broadcasts membership changes)
        topics.add(MqttConfig.getGroupSyncTopic(gId))
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
                            continuation.resume(false)
                        }
                    })
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error publishing to $topic")
                queueMessage(topic, payload, qos, retained)
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

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(MqttConfig.RECONNECT_DELAY_MS)
            val id = memberId ?: return@launch
            val gId = groupId ?: return@launch
            Timber.i("$TAG: Attempting to reconnect...")
            initialize(id, emptyList(), gId) // keys are already in familyMemberKeys
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
        scope.launch {
            val newKeys = members.associate {
                it.memberId to RecipientKeys(it.x25519PublicKey, it.ed25519PublicKey)
            }
            val addedIds = newKeys.keys - familyMemberKeys.keys
            familyMemberKeys = newKeys.toMutableMap()
            if (addedIds.isNotEmpty()) {
                subscribeToFamilyMembers(addedIds.toList())
            }
        }
    }

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
            Timber.d("$TAG: Failed to decrypt message from $senderId")
            return
        }

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
        mqttClient?.disconnect()
        scope.cancel()
        _connectionState.value = ConnectionState.Disconnected
    }
}

private data class PendingMessage(
    val topic: String,
    val payload: ByteArray,
    val qos: Int,
    val retained: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
