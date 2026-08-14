package com.example.familysafety.transport

import android.content.Context
import com.example.familysafety.core.*
import com.example.familysafety.core.NetworkMonitor
import com.example.familysafety.core.SecurityEventRepository
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.crypto.GroupCipher
import com.example.familysafety.crypto.RecipientKeys
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupStateManager
// Aliased: this class has its own nested ConnectionState for the MQTT client itself,
// which would otherwise shadow the per-member one.
import com.example.familysafety.group.ConnectionState as MemberConnectionState
import com.example.familysafety.group.LazysodiumCryptoProvider
import com.example.familysafety.group.PresenceStatus
import com.example.familysafety.location.LocationRepository
import com.example.familysafety.location.MemberLocation
import com.example.familysafety.sync.GroupSyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val networkMonitor: NetworkMonitor,
    private val groupStateManager: GroupStateManager
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
    private var networkObserverJob: Job? = null

    /**
     * Members from whom a validly signed presence update has been seen. Once a member is
     * in here, an unsigned update claiming to be them is treated as a downgrade attempt
     * rather than an old client.
     */
    private val presenceSigners = ConcurrentHashMap<String, Boolean>()

    /** Newest accepted presence timestamp per member, used to reject replays. */
    private val lastPresenceTimestamp = ConcurrentHashMap<String, Long>()

    /**
     * Wire-format version each peer last advertised, learned passively from presence.
     *
     * Peers below [MessageProtocol.PROTOCOL_VERSION] cannot exchange files or presence
     * with this build. Surfacing that is the difference between the UI saying "they need
     * to update" and the member simply never appearing, which reads as the app being
     * broken.
     */
    private val _peerProtocolVersions = MutableStateFlow<Map<String, Int>>(emptyMap())
    val peerProtocolVersions: StateFlow<Map<String, Int>> = _peerProtocolVersions.asStateFlow()

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

    /**
     * Serialises connection setup. [initialize] is called from four places — MainActivity
     * and three paths in LocationService — which race on startup, and the client ID is
     * `familysafe_{memberId}`, deterministic by design so a reconnect resumes the same
     * session. Two live connections under one client ID is precisely the case MQTT
     * requires the broker to resolve by disconnecting the older one, so overlapping
     * initialize() calls made the app evict itself in a loop: connect, get kicked,
     * reconnect, kick the one that just replaced it. Observed as presence and location
     * simply never arriving, with "Connection lost (32109) - EOFException" every few
     * seconds in the log.
     */
    private val connectMutex = Mutex()

    suspend fun initialize(
        memberIdParam: String,
        familyMembers: List<FamilyMember>,
        groupIdParam: String
    ) = connectMutex.withLock {
        initializeLocked(memberIdParam, familyMembers, groupIdParam)
    }

    private suspend fun initializeLocked(
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

        // Ask the client, not our own state field: a connection can be live while
        // _connectionState still reads Connecting, and reconnecting on top of it is what
        // triggers the eviction loop described above.
        if (_connectionState.value == ConnectionState.Connected || mqttClient?.isConnected == true) {
            Timber.d("$TAG: already connected, skipping re-init")
            _connectionState.value = ConnectionState.Connected
            return
        }

        // A client that exists but is not connected is a leftover from an attempt that
        // failed or was superseded. Close it before building another, or its callbacks and
        // its socket outlive it and keep competing for the same client ID.
        mqttClient?.let { stale ->
            try {
                stale.setCallback(null)
                stale.close(true)
            } catch (e: Exception) {
                Timber.d("$TAG: could not close stale client: ${e.message}")
            }
        }
        mqttClient = null

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
                    keepAliveInterval = MqttConfig.KEEP_ALIVE_SECONDS

                    // Broker authentication (absent = anonymous, e.g. public dev broker)
                    val broker = BrokerConfig.getCurrentBroker()
                    broker.username?.let { userName = it }
                    broker.password?.let { password = it.toCharArray() }

                    // Set offline will so peers know if we drop off abruptly. Both signed
                    // and sealed here, at connect time: the broker publishes this for us
                    // later, when we are gone and can neither sign nor encrypt. That is
                    // exactly why presence is sealed under the group key rather than to
                    // each recipient — there is no send-time to encrypt at.
                    val willJson = MessageProtocol.encodePresenceUpdate(
                        memberIdParam,
                        false,
                        sign = { cryptoProvider.signMessage(it) }
                    )
                    setWill(
                        MqttConfig.getPresenceTopic(memberIdParam),
                        sealPresence(willJson),
                        MqttConfig.DEFAULT_QOS,
                        true
                    )
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
                                // Paho reports "already connected" and "connect in progress"
                                // as failures. Retrying those, then giving up and scheduling
                                // a reconnect, tore down the connection that had just
                                // succeeded. Both mean the connection is in hand.
                                val code = (exception as? MqttException)?.reasonCode
                                if (code == MqttException.REASON_CODE_CLIENT_CONNECTED.toInt() ||
                                    code == MqttException.REASON_CODE_CONNECT_IN_PROGRESS.toInt()
                                ) {
                                    Timber.d("$TAG: connect already satisfied (reason $code)")
                                    continuation.resume(Unit)
                                    return
                                }
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
                        // Location and presence are handled here — e2eeManager + familyMemberKeys live here
                        when {
                            topicStr.endsWith("/location_inbox") -> handleLocationInbox(payloadStr)
                            topicStr.endsWith("/location") || topicStr.endsWith("/presence") ->
                                handleLocationOrPresence(topicStr, payloadStr)
                            else -> onMessageReceived?.invoke(topicStr, payloadStr)
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

        // Per-recipient encrypted location inbox
        topics.add(MqttConfig.getLocationInboxTopic(id))
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

        // Targeted requests for specific missing chunks, addressed to us
        topics.add(MqttConfig.getFileRepairTopic(id))
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
            // Legacy location topic kept so peers on older builds still work;
            // new builds publish to our location_inbox instead.
            val locationTopic = MqttConfig.getLocationTopic(otherMemberId)
            val presenceTopic = MqttConfig.getPresenceTopic(otherMemberId)

            mqttClient?.subscribe(
                arrayOf(locationTopic, presenceTopic),
                intArrayOf(MqttConfig.DEFAULT_QOS, MqttConfig.DEFAULT_QOS),
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
        val presenceJson = MessageProtocol.encodePresenceUpdate(
            id,
            isOnline,
            sign = { cryptoProvider.signMessage(it) }
        )
        publishRaw(topic, sealPresence(presenceJson), MqttConfig.DEFAULT_QOS, true)
    }

    /** The group's presence subkey, or null for a group predating the shared key. */
    private fun presenceKey(): ByteArray? =
        groupStateManager.groupDefinition.value?.fileEncryptionKey
            ?.takeIf { it.isNotBlank() }
            ?.let { GroupCipher.deriveSubkey(it, GroupCipher.PURPOSE_PRESENCE) }

    /**
     * Seal a presence envelope under the group key, or pass it through unchanged when the
     * group has no shared key.
     *
     * The fallback is plaintext rather than the legacy derived key on purpose: that key is
     * computable by anyone who can reach the broker, so sealing with it would look like
     * protection while providing none, and would break older peers for nothing.
     */
    private fun sealPresence(presenceJson: String): ByteArray {
        val key = presenceKey() ?: return presenceJson.toByteArray()
        return try {
            val sealed = MessageProtocol.SealedPresence(
                v = 2,
                data = java.util.Base64.getEncoder()
                    .encodeToString(GroupCipher.seal(presenceJson.toByteArray(), key))
            )
            MessageProtocol.encodeSealedPresence(sealed).toByteArray()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: failed to seal presence — publishing unsealed")
            presenceJson.toByteArray()
        }
    }

    /**
     * Reverse of [sealPresence]. Accepts both shapes: peers on older builds still publish
     * plaintext, and a retained plaintext presence from before the upgrade outlives it.
     * Returns null when sealed under a key this device does not hold.
     */
    private fun openPresence(payload: String): String? {
        val sealed = MessageProtocol.decodeSealedPresenceOrNull(payload) ?: return payload
        val key = presenceKey() ?: run {
            Timber.w("$TAG: sealed presence received but this group has no shared key")
            return null
        }
        return try {
            String(GroupCipher.open(java.util.Base64.getDecoder().decode(sealed.data), key))
        } catch (e: Exception) {
            Timber.w(e, "$TAG: could not open sealed presence")
            null
        }
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

    /**
     * Wake-time reconnect for alarm-driven heartbeats. The exponential-backoff
     * reconnect uses coroutine delays, which do not advance while the CPU sleeps
     * in Doze — so a connection dropped during sleep stays down until something
     * else wakes the process. A heartbeat that wants to deliver must revive the
     * connection itself, synchronously, while it still holds a wakelock.
     */
    suspend fun ensureConnectedNow() {
        when (_connectionState.value) {
            ConnectionState.Connected -> return
            ConnectionState.Connecting -> return  // attempt already in flight
            else -> {}
        }
        val id = memberId ?: return
        val gId = groupId ?: return
        Timber.i("$TAG: wake-triggered reconnect")
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        initialize(id, emptyList(), gId)
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

    private suspend fun handleLocationOrPresence(topic: String, payload: String) {
        if (topic.endsWith("/presence")) {
            handlePresence(topic, payload)
            return
        }
        // Legacy shared location topic: the sender is named in the topic path.
        val senderId = topic.split("/").getOrNull(1) ?: return
        handleEncryptedLocation(senderId, payload)
    }

    /**
     * Presence is plaintext by design: the MQTT last-will message is published by the
     * broker on our behalf when we drop, so it cannot be encrypted per-recipient.
     * The topic path names the sender; the payload must agree with it.
     */
    /**
     * Whether a presence update really came from the member it names.
     *
     * Broker ACLs cannot settle this — even per-device credentials would still let one
     * family member forge another's presence — so it is settled cryptographically, using
     * the Ed25519 keys the group definition already distributes.
     *
     * Three checks, in order of cost:
     *  - **Replay.** A captured "offline" message could otherwise be republished later to
     *    mark someone offline at will. Equal timestamps are allowed because the broker
     *    redelivers retained messages on every resubscribe.
     *  - **Downgrade.** An unsigned update from a member previously seen signing is an
     *    attacker stripping the signature, not an old client.
     *  - **Signature**, over the canonical payload rather than the raw JSON.
     */
    private fun isPresenceAuthentic(
        senderId: String,
        update: MessageProtocol.PresenceUpdate
    ): Boolean {
        val last = lastPresenceTimestamp[senderId]
        if (last != null && update.timestamp < last) {
            Timber.w("$TAG: stale presence for ${senderId.take(8)} — ignoring replay")
            return false
        }

        val signatureHex = update.signature
        if (signatureHex == null) {
            if (presenceSigners[senderId] == true) {
                Timber.w("$TAG: unsigned presence for ${senderId.take(8)} after a signed one — ignoring")
                return false
            }
            // Peer predates signed presence. Accepted so it does not appear permanently
            // offline; forgeable until that peer updates, which is why the downgrade
            // check above exists.
            lastPresenceTimestamp[senderId] = update.timestamp
            return true
        }

        val publicKey = familyMemberKeys[senderId]?.ed25519PublicKey
        if (publicKey == null) {
            Timber.w("$TAG: no key to verify presence for ${senderId.take(8)} — ignoring")
            return false
        }

        val valid = try {
            cryptoProvider.verifySignature(
                message = MessageProtocol.presenceSigningPayload(
                    update.memberId,
                    update.isOnline,
                    update.timestamp
                ),
                signature = signatureHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                publicKeyHex = publicKey
            )
        } catch (e: Exception) {
            Timber.w(e, "$TAG: presence signature check failed for ${senderId.take(8)}")
            false
        }

        if (!valid) {
            Timber.w("$TAG: forged or corrupt presence signature for ${senderId.take(8)} — ignoring")
            return false
        }

        presenceSigners[senderId] = true
        lastPresenceTimestamp[senderId] = update.timestamp
        return true
    }

    private suspend fun handlePresence(topic: String, payload: String) {
        val senderId = topic.split("/").getOrNull(1) ?: return
        if (senderId == memberId) return
        if (!familyMemberKeys.containsKey(senderId)) return

        val opened = openPresence(payload) ?: return
        val presenceUpdate = try {
            val envelope = MessageProtocol.decodeEnvelope(opened)
            if (envelope.type != "presence_update") return
            MessageProtocol.decodePresenceUpdate(envelope.payload)
        } catch (e: Exception) {
            Timber.w("$TAG: Failed to decode presence from $senderId: ${e.message}")
            return
        }
        // Necessary but nowhere near sufficient on its own: anyone who can publish to an
        // arbitrary topic satisfies this by publishing to the victim's own presence topic.
        if (presenceUpdate.memberId != senderId) {
            Timber.w("$TAG: Presence payload sender mismatch on $topic — ignoring")
            return
        }

        if (!isPresenceAuthentic(senderId, presenceUpdate)) return

        // Recorded after authentication so a rejected message cannot mark a peer stale.
        if (_peerProtocolVersions.value[senderId] != presenceUpdate.protocolVersion) {
            _peerProtocolVersions.value = _peerProtocolVersions.value +
                (senderId to presenceUpdate.protocolVersion)
            if (presenceUpdate.protocolVersion < MessageProtocol.PROTOCOL_VERSION) {
                Timber.w(
                    "$TAG: ${senderId.take(8)} speaks protocol " +
                        "${presenceUpdate.protocolVersion}, this build speaks " +
                        "${MessageProtocol.PROTOCOL_VERSION} — files and presence will not " +
                        "interoperate until they update"
                )
            }
        }

        Timber.d("$TAG: $senderId is ${if (presenceUpdate.isOnline) "online" else "offline"}")
        groupStateManager.updatePresenceStatus(
            memberId = senderId,
            newStatus = if (presenceUpdate.isOnline) PresenceStatus.ACTIVE else PresenceStatus.OFFLINE
        )

        // Presence also tells us the route. Without this the connection state stayed
        // Unknown forever — only LAN discovery ever set it — so members the broker had
        // already reported as offline showed up as "no contact yet" instead of "Offline".
        if (presenceUpdate.isOnline) {
            // Reaching them over the broker does not mean the LAN route is gone, and
            // Local is the better route, so never downgrade an existing Local peer.
            val current = groupStateManager.memberStates.value[senderId]?.connectionState
            if (current !is MemberConnectionState.Local) {
                groupStateManager.updateConnectionState(
                    senderId,
                    MemberConnectionState.Cloud(topic)
                )
            }
        } else {
            // A retained "offline" or a fired last-will means their app is gone, which
            // invalidates any LAN route we thought we had as well.
            groupStateManager.updateConnectionState(senderId, MemberConnectionState.Offline)
        }
    }

    /**
     * Per-recipient location inbox: the topic names US (the recipient), so the sender
     * comes from the envelope metadata and is authenticated by signature verification
     * inside decryptMessage.
     */
    suspend fun handleLocationInbox(encryptedPayload: String) {
        val senderId = E2EEManager.peekSenderMemberId(encryptedPayload) ?: run {
            Timber.w("$TAG: Location inbox message without sender metadata — ignoring")
            return
        }
        if (senderId == memberId) return
        handleEncryptedLocation(senderId, encryptedPayload)
    }

    private suspend fun handleEncryptedLocation(senderId: String, encryptedPayload: String) {
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
        if (envelope.type == "location_update") {
            val locationUpdate = MessageProtocol.decodeLocationUpdate(envelope.payload)
            val memberLocation = MessageProtocol.locationUpdateToMemberLocation(locationUpdate)
            if (DataValidator.validateLocation(memberLocation) !is ValidationResult.Valid) {
                Timber.w("$TAG: Rejected invalid location from $senderId")
                return
            }
            locationRepository.updateMemberLocation(memberLocation)
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
