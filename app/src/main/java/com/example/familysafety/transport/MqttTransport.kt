package com.example.familysafety.transport

import android.content.Context
import com.example.familysafety.core.*
import com.example.familysafety.core.NetworkMonitor
import com.example.familysafety.core.SecurityEventRepository
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.crypto.GroupCipher
import com.example.familysafety.crypto.RecipientKeys
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupKeys
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
    @Volatile private var connectingSince = 0L
    @Volatile private var connectedAt = 0L
    private var shortLivedConnections = 0

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Two queues, not one. See MqttConfig.isControlPlaneTopic: a shared queue drops by age,
    // so an outage full of location updates evicts the group-state message that must not be
    // lost, and keeps the pin positions that the next fix would have replaced anyway.
    private val pendingControlMessages = ConcurrentLinkedQueue<PendingMessage>()
    private val pendingBulkMessages = ConcurrentLinkedQueue<PendingMessage>()
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

    // Group-sync requests triggered by decrypt failures: at most once a minute in total. This
    // was once per sender, but the sender is read from the unauthenticated envelope, so
    // forging failures under each member's ID in turn multiplied it (F16). A real key change
    // is healed by the first refresh anyway.
    private val lastDecryptFailureSyncAt = java.util.concurrent.atomic.AtomicLong(0L)

    // F15: one identity in use on two installs.
    private val cloneDetector = CloneDetector()
    private val peerSightings by lazy { PeerSightings(context) }

    /** Which group key sealed the last-will at connect; a rotation needs a reconnect to re-seal it. */
    @Volatile private var willKid: String? = null
    private var keyWatchStarted = false

    /** "Unknown key, ask for a refresh" is triggerable by anyone publishing junk kids (F16). */
    private val unknownKeyLimiter = com.example.familysafety.core.RateLimiter(maxRequests = 2, windowMs = 10 * 60_000L)

    /** Called by [openPresence] when presence is sealed under a key we don't hold yet. */
    private val onUnknownGroupKey: (() -> Unit) = {
        scope.launch {
            if (unknownKeyLimiter.allowRequest(UNKNOWN_KEY_LIMIT_KEY)) {
                groupSyncManager?.requestGroupStateRefresh(reason = "unknown group key")
            }
        }
    }
    private val lastSelfCloneAlertAt = java.util.concurrent.atomic.AtomicLong(0L)
    companion object {
        /**
         * The location in a decrypted `location_update` from [senderId], or null to drop it.
         *
         * [senderId] is the member whose signature verified on decrypt. The update names a
         * member of its own, and that used to be trusted as given: any family member could
         * sign an update naming someone else and move that person's pin (F23). A member may
         * only report their own location.
         */
        internal fun acceptedLocation(senderId: String, payloadJson: String): MemberLocation? {
            val update = try {
                MessageProtocol.decodeLocationUpdate(payloadJson)
            } catch (e: Exception) {
                Timber.w("$TAG: Undecodable location from $senderId")
                return null
            }
            if (update.memberId != senderId) {
                Timber.w("$TAG: Dropped location from $senderId claiming to be ${update.memberId}")
                return null
            }
            val location = MessageProtocol.locationUpdateToMemberLocation(update)
            if (DataValidator.validateLocation(location) !is ValidationResult.Valid) {
                Timber.w("$TAG: Rejected invalid location from $senderId")
                return null
            }
            return location
        }

        private const val TAG = "MqttTransport"
        private const val DECRYPT_SYNC_COOLDOWN_MS = 60_000L
        private const val SUBSCRIBE_TIMEOUT_MS = 15_000L
        private const val SESSION_PREFS = "mqtt_session"
        private const val PREF_SUBSCRIBED_FINGERPRINT = "subscribed_fingerprint"
        private const val PREF_RESET_SESSION = "reset_session"

        /** EMQX Serverless: subscriptions granted per connection; the rest are refused. */
        const val BROKER_SUBSCRIPTION_QUOTA = 10

        /**
         * What this device holds on the broker: one wildcard for its own topics, one for its
         * group's, and each other member's presence.
         *
         * EMQX Serverless grants at most 10 subscriptions per connection and refuses the
         * rest — not adjustable on that plan. Subscribed one by one, the 21 own and group
         * topics left everything after the tenth refused: group sync, removal votes, files,
         * the vault and every peer's presence. As wildcards they cost 2, which leaves room
         * for a family of nine.
         */
        fun subscriptionFilters(memberId: String, groupId: String, otherMemberIds: List<String>): List<String> =
            listOf(MqttConfig.getOwnWildcardTopic(memberId), MqttConfig.getGroupWildcardTopic(groupId)) +
                otherMemberIds.flatMap { memberTopicFilters(it) }

        /**
         * The filters builds before the wildcards subscribed one by one. A kept session still
         * holds whichever of them the broker granted, and those count against the quota, so
         * they are unsubscribed before the wildcards go in.
         */
        fun legacyTopicFilters(memberId: String, groupId: String, otherMemberIds: List<String>): List<String> =
            ownTopicFilters(memberId, groupId) + otherMemberIds.flatMap {
                listOf(MqttConfig.getLocationTopic(it), MqttConfig.getPresenceTopic(it))
            }

        /**
         * Every own and group topic, one by one. No longer subscribed individually — the
         * wildcards in [subscriptionFilters] cover all of them — but kept as the list the
         * wildcards must cover, and as what [legacyTopicFilters] unsubscribes.
         */
        fun ownTopicFilters(memberId: String, groupId: String): List<String> = listOf(
            MqttConfig.getChatTopic(memberId),
            // Per-recipient encrypted location inbox
            MqttConfig.getLocationInboxTopic(memberId),
            MqttConfig.getChatReceiptTopic(memberId),
            MqttConfig.getChatReadTopic(memberId),
            MqttConfig.getReplicationRequestTopic(memberId),
            MqttConfig.getReplicationDataTopic(memberId),
            MqttConfig.getReplicationAnnounceInboxTopic(memberId),
            // Join requests (received by approvers) and approvals (received by joiners)
            MqttConfig.getJoinRequestTopic(memberId),
            MqttConfig.getJoinApprovalTopic(memberId),
            MqttConfig.getSyncRequestTopic(memberId),
            // Quorum-removal votes addressed to us
            MqttConfig.getRemovalVoteTopic(memberId),
            MqttConfig.getGroupSyncInboxTopic(memberId),
            MqttConfig.getGroupAckTopic(groupId),
            // File transfer manifest (retained broadcast for the group)
            MqttConfig.getFileManifestTopic(groupId),
            MqttConfig.getFileChunkWildcardTopic(groupId),
            MqttConfig.getFileRequestTopic(memberId),
            MqttConfig.getFileRepairTopic(memberId),
            MqttConfig.getFileAvailabilityTopic(memberId),
            // The vault container, retained for the group. Subscribed unconditionally: a
            // device that subscribed only once a vault existed would announce that one does.
            MqttConfig.getVaultContainerTopic(groupId),
            // Vault document bytes, stored opaquely whether or not this device holds a code.
            MqttConfig.getVaultChunkWildcardTopic(groupId),
            MqttConfig.getVaultRepairTopic(memberId)
        )

        /**
         * Filters for one other family member: their presence only. The legacy shared
         * location topic is no longer subscribed — no current build publishes to it (spec §5),
         * and dropping it halves what each member costs against the 10-subscription quota.
         */
        fun memberTopicFilters(otherMemberId: String): List<String> = listOf(
            MqttConfig.getPresenceTopic(otherMemberId)
        )

        /** Order-independent digest of a subscription set on a given broker. */
        fun subscriptionFingerprint(brokerUrl: String, topics: Collection<String>): String {
            val canonical = (listOf(brokerUrl) + topics.toSortedSet()).joinToString("\n")
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        /**
         * Whether a fresh connection needs SUBSCRIBE. Only a session the broker kept,
         * holding exactly the filters we last confirmed, can skip it.
         */
        fun shouldResubscribe(
            sessionPresent: Boolean,
            storedFingerprint: String?,
            currentFingerprint: String
        ): Boolean = !sessionPresent || storedFingerprint != currentFingerprint

        /** Paho's own timeout plus margin; a single connect attempt must never outlive this. */
        const val CONNECT_ATTEMPT_TIMEOUT_MS = MqttConfig.CONNECTION_TIMEOUT * 1000L + 5_000L

        /**
         * A Connecting state older than one full attempt plus a retry-backoff margin cannot be
         * a live attempt, because every attempt is bounded by [CONNECT_ATTEMPT_TIMEOUT_MS].
         * Pure so the wedge check is testable without a client.
         */
        private const val UNKNOWN_KEY_LIMIT_KEY = "unknown_group_key"

        /** A connection this brief, with the network up, is how a session takeover looks (2.2b). */
        const val SHORT_LIVED_CONNECTION_MS = 30_000L

        fun isShortLivedConnection(connectedAtMs: Long, nowMs: Long): Boolean =
            connectedAtMs > 0L && nowMs - connectedAtMs < SHORT_LIVED_CONNECTION_MS

        fun isConnectingStale(connectingSinceMs: Long, nowMs: Long): Boolean =
            connectingSinceMs > 0L &&
                nowMs - connectingSinceMs > 3 * CONNECT_ATTEMPT_TIMEOUT_MS + 10_000L
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
                connectingSince = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Connecting

                val brokerUrl = MqttConfig.BROKER_URL
                if (!BrokerConfig.isSecureBroker()) {
                    Timber.w("$TAG: connecting to INSECURE broker (no TLS) — development only: $brokerUrl")
                }
                // A new client ID has no session on the broker, so restoreSubscriptions sees
                // sessionPresent = false and subscribes in full; the old familysafe_{memberId}
                // session is left to the broker's session expiry.
                val clientId = MqttConfig.generateClientId(memberIdParam, DeviceIdentity.get(context))
                mqttClient = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())

                setupCallbacks()

                // Set when the broker refused filters that fit the quota: the kept session holds
                // something we cannot name to unsubscribe, so start one fresh session instead.
                // Costs whatever QoS 1 traffic the broker had queued for us, once.
                val sessionPrefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
                val resetSession = sessionPrefs.getBoolean(PREF_RESET_SESSION, false)
                val connOpts = MqttConnectOptions().apply {
                    isCleanSession = resetSession
                    isAutomaticReconnect = false  // we handle reconnect via scheduleReconnect()
                    connectionTimeout = MqttConfig.CONNECTION_TIMEOUT
                    keepAliveInterval = MqttConfig.KEEP_ALIVE_SECONDS
                    // Paho defaults this to 10; see MqttConfig.MAX_INFLIGHT for why that is
                    // reachable here and why the replacement is only modestly larger.
                    maxInflight = MqttConfig.MAX_INFLIGHT

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
                        sign = { cryptoProvider.signMessage(it) },
                        deviceId = DeviceIdentity.get(context)
                    )
                    willKid = groupStateManager.groupDefinition.value?.let { GroupKeys.stampFor(it) }
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
                    // Paho's own connectionTimeout has been observed not to fire when the
                    // process is throttled mid-handshake. Without this bound the coroutine
                    // never resumes, connectMutex is never released, and every reconnect
                    // path queues behind it until the process dies.
                    withTimeout(CONNECT_ATTEMPT_TIMEOUT_MS) {
                    suspendCancellableCoroutine { continuation ->
                        val attemptClient = mqttClient
                        continuation.invokeOnCancellation {
                            try {
                                attemptClient?.disconnectForcibly(0, 0)
                            } catch (e: Exception) {
                                Timber.d("$TAG: could not abort timed-out connect: ${e.message}")
                            }
                        }
                        attemptClient?.connect(connOpts, null, object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                continuation.resume(asyncActionToken?.sessionPresent == true)
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
                                    // Session state unknown here; false forces a resubscribe,
                                    // which is the safe direction.
                                    continuation.resume(false)
                                    return
                                }
                                continuation.resumeWith(Result.failure(exception ?: Exception("MQTT connection failed")))
                            }
                        })
                    }
                    }
                }

                if (connectResult.isSuccess) {
                    Timber.i("$TAG: Connected to MQTT broker")
                    if (resetSession) {
                        Timber.w("$TAG: connected with a fresh session to clear refused subscriptions")
                        sessionPrefs.edit().remove(PREF_RESET_SESSION).apply()
                    }
                    _connectionState.value = ConnectionState.Connected
                    connectedAt = System.currentTimeMillis()
                    startKeyWatch()
                    reconnectAttempts = 0

                    val memberIdsForSubscriptions = if (familyMembers.isNotEmpty()) {
                        familyMembers.map { it.memberId }
                    } else {
                        familyMemberKeys.keys.toList()
                    }

                    restoreSubscriptions(
                        sessionPresent = connectResult.getOrDefault(false),
                        memberIds = memberIdsForSubscriptions
                    )
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
                if (isShortLivedConnection(connectedAt, System.currentTimeMillis()) &&
                    networkMonitor.isCurrentlyConnected()
                ) {
                    // MQTT 3.1.1 gives no reason for a disconnect, so a session takeover (another
                    // install with this client ID) looks exactly like a network drop. Counted for
                    // the debug log only; a user-facing alert would fire on flaky networks.
                    shortLivedConnections++
                    Timber.w("$TAG: connection lasted under ${SHORT_LIVED_CONNECTION_MS / 1000}s with the network up ($shortLivedConnections so far) — possible session takeover")
                }
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

    /**
     * Subscribe after a connect — but only when the broker does not already hold our
     * subscriptions.
     *
     * The session is persistent (cleanSession = false), so after a reconnect the broker
     * still has every subscription and has been queueing QoS 1 traffic for us. Sending
     * SUBSCRIBE again anyway is not free: MQTT requires the broker to re-send every
     * retained message matching a re-subscribed filter. That is the vault container
     * (128 KiB, ~175 KB as Base64), the file manifest and a presence message per member,
     * on every reconnect, on every phone. Reconnects happen constantly on mobile — Doze,
     * Wi-Fi/cellular handoffs, the heartbeat reviving a dropped socket — and this replay
     * was the bulk of the family's monthly broker traffic.
     *
     * Skipped only when the broker reports the session present AND the topic set is the
     * one we last subscribed successfully. A new member, a new group or a new app version
     * with new topics changes the fingerprint and forces a full subscribe.
     */
    private suspend fun restoreSubscriptions(sessionPresent: Boolean, memberIds: List<String>) {
        val id = memberId ?: return
        val gId = groupId ?: return
        val peers = memberIds.filter { it != id }
        val topics = subscriptionFilters(id, gId, peers)
        val fingerprint = subscriptionFingerprint(MqttConfig.BROKER_URL, topics)
        val prefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)

        if (!shouldResubscribe(sessionPresent, prefs.getString(PREF_SUBSCRIBED_FINGERPRINT, null), fingerprint)) {
            Timber.i("$TAG: broker kept our session — skipping resubscribe (no retained replay)")
            return
        }

        // Cleared first: if the process dies mid-subscribe, the next connect must not
        // trust a fingerprint for subscriptions that may never have reached the broker.
        prefs.edit().remove(PREF_SUBSCRIBED_FINGERPRINT).apply()
        // A kept session from an older build still holds its one-by-one filters, and they
        // count against the 10-subscription quota: left in place, the wildcards are refused.
        // Unsubscribing a filter the session does not hold is harmless.
        unsubscribeAwait(legacyTopicFilters(id, gId, peers))
        when (subscribeAwait(topics)) {
            true -> {
                prefs.edit().putString(PREF_SUBSCRIBED_FINGERPRINT, fingerprint).apply()
                Timber.i("$TAG: Subscribed to ${topics.size} topics (sessionPresent=$sessionPresent)")
            }
            false -> if (topics.size <= BROKER_SUBSCRIPTION_QUOTA) {
                Timber.w("$TAG: broker refused filters within quota — next connect starts a fresh session")
                prefs.edit().putBoolean(PREF_RESET_SESSION, true).apply()
            } else {
                // A fresh session would be refused the same way; don't throw queued traffic away.
                Timber.e("$TAG: ${topics.size} filters exceed the broker's quota of $BROKER_SUBSCRIPTION_QUOTA")
            }
            null -> Timber.w("$TAG: subscribe incomplete — will resubscribe on next connect")
        }
    }

    /** One UNSUBSCRIBE for [topics]; best effort, true once the broker acknowledged it. */
    private suspend fun unsubscribeAwait(topics: List<String>): Boolean {
        val client = mqttClient ?: return false
        return withTimeoutOrNull(SUBSCRIBE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                try {
                    client.unsubscribe(
                        topics.toTypedArray(),
                        null,
                        object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                continuation.resume(true)
                            }

                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                Timber.w(exception, "$TAG: unsubscribe failed")
                                continuation.resume(false)
                            }
                        }
                    )
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: unsubscribe threw")
                    continuation.resume(false)
                }
            }
        } ?: false
    }

    /**
     * One SUBSCRIBE for [topics]: true if the broker granted every filter, false if it
     * refused any, null if there was no answer (failure, timeout, no client).
     */
    private suspend fun subscribeAwait(topics: List<String>): Boolean? {
        val client = mqttClient ?: return null
        return withTimeoutOrNull(SUBSCRIBE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean?> { continuation ->
                try {
                    client.subscribe(
                        topics.toTypedArray(),
                        IntArray(topics.size) { MqttConfig.DEFAULT_QOS },
                        null,
                        object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                // 0x80 in the SUBACK is a per-filter refusal, reported as success.
                                val granted = asyncActionToken?.grantedQos
                                continuation.resume(granted == null || granted.none { it == 0x80 })
                            }

                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                Timber.e(exception, "$TAG: subscribe failed")
                                continuation.resume(null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: subscribe threw")
                    continuation.resume(null)
                }
            }
        }
    }

    /**
     * The group changed while connected: swap the old group's wildcard for the new one.
     * The own wildcard is (re)subscribed too, since a device with no group yet had none.
     * The fingerprint no longer matches, so the next connect resubscribes in full.
     */
    private suspend fun subscribeToOwnTopics(oldGroupId: String?) {
        val id = memberId ?: return
        val gId = groupId ?: return
        if (oldGroupId != null) {
            unsubscribeAwait(listOf(MqttConfig.getGroupWildcardTopic(oldGroupId)))
        }
        val topics = listOf(MqttConfig.getOwnWildcardTopic(id), MqttConfig.getGroupWildcardTopic(gId))
        if (subscribeAwait(topics) == true) {
            Timber.i("$TAG: Subscribed to own topics")
        } else {
            Timber.e("$TAG: Failed to subscribe to own topics")
        }
    }

    private suspend fun subscribeToFamilyMembers(memberIds: List<String>) {
        memberIds.filter { it != memberId }.forEach { otherId ->
            subscribeToMember(otherId)
        }
    }

    private suspend fun subscribeToMember(otherMemberId: String) {
        withContext(Dispatchers.IO) {
            val topics = memberTopicFilters(otherMemberId)
            mqttClient?.subscribe(
                topics.toTypedArray(),
                IntArray(topics.size) { MqttConfig.DEFAULT_QOS },
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
                            handlePublishFailure(topic, payload, qos, retained, exception)
                            continuation.resume(false)
                        }
                    })
                }
            } catch (e: Exception) {
                handlePublishFailure(topic, payload, qos, retained, e)
                false
            }
        }
    }

    /**
     * Queue a failed publish, and decide whether the failure means the connection is gone.
     *
     * It usually does not. The common failure is the in-flight window filling up, which
     * Paho reports by throwing immediately — the socket is fine, the broker is fine, the
     * client is simply refusing to hold another unacknowledged message. Treating that as a
     * disconnect had three consequences, all of them observed in the field:
     *
     *  - the app showed itself as disconnected with no network event behind it, then
     *    recovered a few seconds later when the reconnect found the client still connected;
     *  - each false alarm incremented the reconnect backoff (5 s, 10 s, 20 s … capped at
     *    five minutes) without ever resetting it, so the phantom outages grew;
     *  - every later send in the same loop hit the not-connected guard at the top of
     *    [publishRaw] and never even attempted, so one failed recipient silently became all
     *    of them. That is what turned a group-state broadcast into "Failed to broadcast
     *    update" while every peer was reachable.
     *
     * So ask the client, which knows. A publish failure on a live connection is
     * backpressure: the message is queued and the next drain will carry it.
     */
    private fun handlePublishFailure(
        topic: String,
        payload: ByteArray,
        qos: Int,
        retained: Boolean,
        cause: Throwable?
    ) {
        queueMessage(topic, payload, qos, retained)

        if (mqttClient?.isConnected == true) {
            Timber.w(
                cause,
                "$TAG: publish to $topic failed while still connected — queued as backpressure"
            )
            return
        }

        Timber.w(cause, "$TAG: publish to $topic failed and the client is not connected")
        _connectionState.value = ConnectionState.Disconnected
        scheduleReconnect()
    }

    private suspend fun publishPresence(isOnline: Boolean) {
        val id = memberId ?: return
        val topic = MqttConfig.getPresenceTopic(id)
        val presenceJson = MessageProtocol.encodePresenceUpdate(
            id,
            isOnline,
            sign = { cryptoProvider.signMessage(it) },
            deviceId = DeviceIdentity.get(context)
        )
        // At-most-once, because retained does the work here. The broker keeps the last
        // presence message whatever its QoS and hands it to every new subscriber, and the
        // next update supersedes this one entirely — so an acknowledged redelivery buys
        // nothing and costs an in-flight slot on every publish.
        publishRaw(topic, sealPresence(presenceJson), MqttConfig.QOS_AT_MOST_ONCE, true)
    }

    /**
     * Whether every member who counts advertises the protocol a rotated key needs (3.3). Members
     * silent for 30 days don't count; see [GroupKeys.everyoneAtLeast].
     */
    private fun keyRotationGateOpen(def: com.example.familysafety.group.GroupDefinition): Boolean {
        val me = memberId ?: return false
        return GroupKeys.everyoneAtLeast(
            memberIds = def.members.map { it.memberId },
            selfId = me,
            sightings = peerSightings.snapshot(),
            observingSinceMs = peerSightings.observingSinceMs,
            nowMs = System.currentTimeMillis(),
            required = GroupKeys.ROTATION_PROTOCOL_VERSION
        )
    }

    private suspend fun rotateGroupKeyIfDue() {
        val me = memberId ?: return
        if (groupStateManager.rotateGroupKeyIfDue(::keyRotationGateOpen)) {
            groupStateManager.groupDefinition.value?.let {
                groupSyncManager?.broadcastGroupUpdate(it, com.example.familysafety.sync.ChangeType.VERSION_SYNC, me)
            }
        }
    }

    /**
     * Watch the family's state for removals that call for a new key, and for a current key that
     * differs from the one our last-will was sealed under. The will is fixed at connect, so after
     * a rotation it would still open under the old key, and whoever was removed could read our
     * next abrupt "offline". Reconnecting re-seals it.
     */
    private fun startKeyWatch() {
        if (keyWatchStarted) return
        keyWatchStarted = true
        scope.launch {
            groupStateManager.groupDefinition.collect { def ->
                def ?: return@collect
                if (GroupKeys.rotationDue(def)) rotateGroupKeyIfDue()
                val stamp = GroupKeys.stampFor(def)
                if (stamp != willKid && _connectionState.value == ConnectionState.Connected) {
                    Timber.i("$TAG: group key changed — reconnecting to re-seal the last-will")
                    willKid = stamp
                    try { mqttClient?.disconnect()?.waitForCompletion(5_000) } catch (_: Exception) { }
                    _connectionState.value = ConnectionState.Disconnected
                    scheduleReconnect()
                }
            }
        }
    }

    /**
     * The presence subkey for the key [kid] names (absent = epoch 0), or null when this device
     * doesn't hold it or the group predates the shared key.
     */
    private fun presenceKey(kid: String?): ByteArray? =
        groupStateManager.groupDefinition.value
            ?.let { GroupKeys.keyHexFor(it, kid) }
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
        val kid = groupStateManager.groupDefinition.value?.let { GroupKeys.stampFor(it) }
        val key = presenceKey(kid) ?: return presenceJson.toByteArray()
        return try {
            val sealed = MessageProtocol.SealedPresence(
                v = 2,
                data = java.util.Base64.getEncoder()
                    .encodeToString(GroupCipher.seal(presenceJson.toByteArray(), key)),
                kid = kid
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
        val key = presenceKey(sealed.kid) ?: run {
            if (sealed.kid != null) {
                // Probably behind on keys after a rotation. Ask for the current state, through
                // the same limiter as every other reply an outsider can trigger (F16): anyone
                // can publish junk kids.
                Timber.w("$TAG: sealed presence under unknown key ${sealed.kid.take(8)}")
                onUnknownGroupKey()
            } else {
                Timber.w("$TAG: sealed presence received but this group has no shared key")
            }
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
        val control = MqttConfig.isControlPlaneTopic(topic)
        val queue = if (control) pendingControlMessages else pendingBulkMessages
        val bound = if (control) MqttConfig.MAX_PENDING_CONTROL else MqttConfig.MAX_PENDING_BULK

        queue.offer(PendingMessage(topic, payload, qos, retained))
        val now = System.currentTimeMillis()
        queue.removeIf { now - it.timestamp > 3600_000 }
        while (queue.size > bound) queue.poll()

        Timber.d(
            "$TAG: Queued ${if (control) "control" else "bulk"} message for $topic " +
                "(control: ${pendingControlMessages.size}, bulk: ${pendingBulkMessages.size})"
        )
    }

    /**
     * Drain what was queued while sending was failing, control plane first.
     *
     * The two queues are drained independently so a bulk send that fails cannot strand a
     * membership update behind it — with one queue and one `break`, the first failure ended
     * the drain for everything after it, and what came after was whatever happened to be
     * newest.
     */
    private suspend fun processPendingMessages() {
        drainQueue(pendingControlMessages)
        drainQueue(pendingBulkMessages)
    }

    private suspend fun drainQueue(queue: ConcurrentLinkedQueue<PendingMessage>) {
        while (queue.isNotEmpty()) {
            if (_connectionState.value != ConnectionState.Connected) return
            val msg = queue.poll() ?: return
            if (System.currentTimeMillis() - msg.timestamp > 3600_000) continue
            val sent = publishRaw(msg.topic, msg.payload, msg.qos, msg.retained)
            // publishRaw re-queues on failure, so a message is never dropped by giving up
            // here — stopping only avoids hammering a connection that is refusing work.
            if (!sent) return
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
            ConnectionState.Connecting -> {
                if (!isConnectingStale(connectingSince, System.currentTimeMillis())) return
                // Backstop for a hang the attempt timeout somehow missed: aborting the client
                // makes Paho fail the pending connect, which resumes the stuck coroutine and
                // releases connectMutex so the initialize() below can proceed.
                Timber.w("$TAG: Connecting state stale — aborting wedged attempt")
                try {
                    mqttClient?.disconnectForcibly(0, 0)
                } catch (e: Exception) {
                    Timber.d("$TAG: could not abort wedged client: ${e.message}")
                }
            }
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
            scope.launch { subscribeToOwnTopics(old) }
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
            // A removed member's presence filter would otherwise hold one of the 10 slots.
            if (removedIds.isNotEmpty()) {
                unsubscribeAwait(removedIds.filter { it != memberId }.flatMap { memberTopicFilters(it) })
            }
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

    /** The device ID in [update] if its device signature verifies under [ed25519Hex], else null. */
    private fun verifiedDeviceId(update: MessageProtocol.PresenceUpdate, ed25519Hex: String): String? {
        val deviceId = update.deviceId ?: return null
        val signature = update.deviceSignature ?: return null
        val valid = try {
            cryptoProvider.verifySignature(
                message = MessageProtocol.deviceSigningPayload(update.memberId, update.timestamp, deviceId),
                signature = signature.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                publicKeyHex = ed25519Hex
            )
        } catch (e: Exception) {
            false
        }
        return deviceId.takeIf { valid }
    }

    /**
     * Presence on our own topic. Ours comes back to us as the retained copy; one signed with
     * our identity key from a *different* install means this identity is in use on another
     * phone, which this phone used to ignore entirely (F15).
     */
    private fun checkOwnPresence(payload: String) {
        val myId = memberId ?: return
        val update = try {
            val envelope = MessageProtocol.decodeEnvelope(openPresence(payload) ?: return)
            if (envelope.type != "presence_update") return
            MessageProtocol.decodePresenceUpdate(envelope.payload)
        } catch (e: Exception) {
            return
        }
        if (update.memberId != myId) return
        val deviceId = verifiedDeviceId(update, cryptoProvider.getLocalEd25519PublicKey()) ?: return
        if (!CloneDetector.ownPresenceIsAnotherDevice(update.isOnline, deviceId, DeviceIdentity.get(context))) return

        val now = System.currentTimeMillis()
        val last = lastSelfCloneAlertAt.get()
        if (now - last > CloneDetector.COOLDOWN_MS && lastSelfCloneAlertAt.compareAndSet(last, now)) {
            Timber.w("$TAG: this identity is publishing presence from another device")
            IdentityAlerts.selfOnAnotherDevice(context, myId)
        }
    }

    private suspend fun handlePresence(topic: String, payload: String) {
        val senderId = topic.split("/").getOrNull(1) ?: return
        if (senderId == memberId) {
            checkOwnPresence(payload)
            return
        }
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

        familyMemberKeys[senderId]?.ed25519PublicKey?.let { key ->
            verifiedDeviceId(presenceUpdate, key)?.let { deviceId ->
                if (cloneDetector.observe(senderId, deviceId, presenceUpdate.isOnline, System.currentTimeMillis())) {
                    val name = groupStateManager.groupDefinition.value
                        ?.findMemberById(senderId)?.displayName ?: senderId.take(8)
                    Timber.w("$TAG: ${senderId.take(8)} is publishing from two devices")
                    IdentityAlerts.memberOnAnotherDevice(context, senderId, name)
                }
            }
        }

        // Kept across restarts for the protocol-4 gate (3.3); a changed version may open it.
        if (peerSightings.record(senderId, presenceUpdate.protocolVersion, System.currentTimeMillis())) {
            scope.launch { rotateGroupKeyIfDue() }
        }

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
            // A retained "offline" or a fired last-will says they left the *broker*. That also
            // fires when only their internet dropped and they are still on our WiFi, so it must
            // not erase a LAN route: LocalTransport owns Local, and NSD reports the peer lost
            // when their app really is gone (the system unregisters its service).
            val current = groupStateManager.memberStates.value[senderId]?.connectionState
            if (current !is MemberConnectionState.Local) {
                groupStateManager.updateConnectionState(senderId, MemberConnectionState.Offline)
            }
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
            val now = System.currentTimeMillis()
            val lastSync = lastDecryptFailureSyncAt.get()
            if (now - lastSync > DECRYPT_SYNC_COOLDOWN_MS &&
                lastDecryptFailureSyncAt.compareAndSet(lastSync, now)
            ) {
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
            val memberLocation = acceptedLocation(senderId, envelope.payload) ?: return
            // The envelope already binds a live fix to its sender; a signature that doesn't
            // verify is still a fault worth refusing rather than storing and passing on (F24).
            memberLocation.authorSig?.let { sig ->
                val ok = runCatching {
                    e2eeManager.verifyAuthor(
                        com.example.familysafety.replication.AuthorSignatures.location(memberLocation.copy(authorSig = null)),
                        sig,
                        senderKeys.ed25519PublicKey
                    )
                }.getOrDefault(false)
                if (!ok) {
                    Timber.w("$TAG: location from ${senderId.take(8)} carries a bad author signature — dropped")
                    return
                }
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
