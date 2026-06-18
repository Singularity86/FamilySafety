package com.example.familysafety.replication

import com.example.familysafety.core.DataValidator
import com.example.familysafety.core.ValidationResult
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.location.LocationRepository
import com.example.familysafety.storage.ChatMessageDao
import com.example.familysafety.storage.LocationHistoryRepository
import com.example.familysafety.transport.MqttConfig
import com.example.familysafety.transport.TransportProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages data replication between family members' devices.
 *
 * Each device stores location history and chat messages for ALL family members.
 * When a device comes online, it syncs missing data from peers.
 * When new data is received, it's broadcast to all peers for backup.
 *
 * This ensures no single point of failure - if one device is lost,
 * other family members' devices have the data backed up.
 */
@Singleton
class ReplicationManager @Inject constructor(
    private val groupStateManager: GroupStateManager,
    private val locationRepository: LocationRepository,
    private val locationHistoryRepository: LocationHistoryRepository,
    private val chatMessageDao: ChatMessageDao,
    private val e2eeManager: E2EEManager,
    private val transportProvider: TransportProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val syncMutex = Mutex()

    // Pending requests waiting for response
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()

    // Replication state
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // Events for UI/logging
    private val _events = MutableSharedFlow<ReplicationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ReplicationEvent> = _events.asSharedFlow()

    companion object {
        private const val TAG = "ReplicationManager"
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_ITEMS_PER_REQUEST = 500
    }

    // =========================================================================
    // SYNC INITIATION
    // =========================================================================

    /**
     * Request a full sync with all online peers.
     * Called when app starts or comes online.
     */
    suspend fun requestFullSync() {
        syncMutex.withLock {
            _syncState.value = SyncState.Syncing

            val group = groupStateManager.groupDefinition.value ?: run {
                _syncState.value = SyncState.Idle
                return@withLock
            }

            val localMemberId = groupStateManager.localMember.value?.memberId ?: return@withLock

            Timber.d("$TAG: Starting full sync with ${group.members.size - 1} peers")
            _events.emit(ReplicationEvent.SyncStarted)

            // Request location history for each member from peers
            group.members
                .filter { it.memberId != localMemberId }
                .forEach { member ->
                    requestLocationDataForMember(member.memberId, localMemberId)
                }

            // Also request our own data (in case we lost some)
            requestLocationDataForMember(localMemberId, localMemberId)

            // Request chat messages
            requestChatData(localMemberId)

            _syncState.value = SyncState.Synced
            _events.emit(ReplicationEvent.SyncCompleted)
        }
    }

    /**
     * Request location data for a specific member from peers.
     */
    private suspend fun requestLocationDataForMember(
        targetMemberId: String,
        requesterId: String
    ) {
        val group = groupStateManager.groupDefinition.value ?: return

        // Find what we already have
        val newestTimestamp = locationHistoryRepository.getNewestTimestamp(targetMemberId) ?: 0L

        // Create request
        val request = ReplicationRequest(
            requestId = UUID.randomUUID().toString(),
            requesterId = requesterId,
            dataType = ReplicationDataType.LOCATION_HISTORY,
            targetMemberId = targetMemberId,
            afterTimestamp = newestTimestamp,
            limit = MAX_ITEMS_PER_REQUEST
        )

        // Send to all other members (they'll respond if they have data)
        group.members
            .filter { it.memberId != requesterId }
            .forEach { peer ->
                sendReplicationRequest(peer, request)
            }
    }

    /**
     * Request chat data from peers.
     */
    private suspend fun requestChatData(requesterId: String) {
        val group = groupStateManager.groupDefinition.value ?: return

        // Get all conversation IDs we have
        val conversationIds = chatMessageDao.getAllConversationIds()

        conversationIds.forEach { conversationId ->
            val newestTimestamp = chatMessageDao.getNewestTimestamp(conversationId) ?: 0L

            val request = ReplicationRequest(
                requestId = UUID.randomUUID().toString(),
                requesterId = requesterId,
                dataType = ReplicationDataType.CHAT_MESSAGES,
                conversationId = conversationId,
                afterTimestamp = newestTimestamp,
                limit = MAX_ITEMS_PER_REQUEST
            )

            group.members
                .filter { it.memberId != requesterId }
                .forEach { peer ->
                    sendReplicationRequest(peer, request)
                }
        }
    }

    /**
     * Send a replication request to a peer.
     */
    private suspend fun sendReplicationRequest(
        peer: FamilyMember,
        request: ReplicationRequest
    ) {
        try {
            val topic = MqttConfig.getReplicationRequestTopic(peer.memberId)
            val plaintext = json.encodeToString(request)
            val payload = encryptForPeer(plaintext, peer) ?: return

            // Track pending request
            pendingRequests[request.requestId] = PendingRequest(
                request = request,
                timestamp = System.currentTimeMillis()
            )

            transportProvider.sendMessage(
                recipientId = peer.memberId,
                topic = topic,
                payload = payload,
                qos = MqttConfig.DEFAULT_QOS
            )
            Timber.d("$TAG: Sent replication request ${request.requestId} to ${peer.memberId}")

            // Set timeout to clean up
            scope.launch {
                delay(REQUEST_TIMEOUT_MS)
                pendingRequests.remove(request.requestId)
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to send replication request")
        }
    }

    /**
     * Encrypt a plaintext string for a specific peer using their X25519 public key.
     * Returns the encrypted JSON envelope as bytes, or null if encryption failed
     * (e.g., the peer's keys aren't known to us).
     */
    private fun encryptForPeer(plaintext: String, peer: FamilyMember): ByteArray? {
        return try {
            val encrypted = e2eeManager.encryptMessage(
                plaintext = plaintext,
                recipientMemberId = peer.memberId,
                recipientX25519PublicKey = peer.x25519PublicKey.hexToBytes()
            )
            encrypted.toByteArray()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Encryption failed for peer ${peer.memberId.take(8)}")
            null
        }
    }

    /**
     * Decrypt an incoming replication payload from a known peer. Returns the
     * plaintext string, or null if decryption fails (sender unknown / signature
     * invalid / key mismatch).
     */
    private fun decryptFromPeer(encryptedJson: String, senderMemberId: String): String? {
        val sender = groupStateManager.groupDefinition.value
            ?.findMemberById(senderMemberId) ?: run {
                Timber.w("$TAG: Unknown sender for decryption: ${senderMemberId.take(8)}")
                return null
            }
        return try {
            e2eeManager.decryptMessage(
                encryptedMessageJson = encryptedJson,
                senderX25519PublicKey = sender.x25519PublicKey.hexToBytes(),
                senderEd25519PublicKey = sender.ed25519PublicKey.hexToBytes()
            )
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Decryption failed for sender ${senderMemberId.take(8)}")
            null
        }
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // =========================================================================
    // INCOMING MESSAGE HANDLING
    // =========================================================================

    /**
     * Handle incoming replication request from a peer.
     */
    suspend fun handleReplicationRequest(
        requestJson: String,
        senderMemberId: String
    ) {
        try {
            val plaintext = decryptFromPeer(requestJson, senderMemberId) ?: return
            val request = json.decodeFromString<ReplicationRequest>(plaintext)
            Timber.d("$TAG: Received replication request ${request.requestId} from $senderMemberId")

            when (request.dataType) {
                ReplicationDataType.LOCATION_HISTORY -> {
                    handleLocationDataRequest(request, senderMemberId)
                }
                ReplicationDataType.CHAT_MESSAGES -> {
                    handleChatDataRequest(request, senderMemberId)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to handle replication request")
        }
    }

    /**
     * Handle request for location data.
     */
    private suspend fun handleLocationDataRequest(
        request: ReplicationRequest,
        senderMemberId: String
    ) {
        val targetMemberId = request.targetMemberId ?: return
        val sender = groupStateManager.groupDefinition.value
            ?.findMemberById(senderMemberId) ?: return

        // Get locations we have after the requested timestamp
        val locations = locationHistoryRepository.getLocationsAfter(
            memberId = targetMemberId,
            afterTimestamp = request.afterTimestamp
        ).take(request.limit)

        if (locations.isEmpty()) {
            Timber.d("$TAG: No location data to send for ${request.requestId}")
            return
        }

        // Convert to replication format
        val replicatedLocations = locations.map { ReplicatedLocation.fromMemberLocation(it) }

        val response = ReplicationResponse(
            requestId = request.requestId,
            senderId = groupStateManager.localMember.value?.memberId ?: return,
            dataType = ReplicationDataType.LOCATION_HISTORY,
            locations = replicatedLocations,
            hasMore = locations.size >= request.limit,
            newestTimestamp = locations.maxOfOrNull { it.timestamp }
        )

        sendReplicationResponse(sender, response)
    }

    /**
     * Handle request for chat data.
     */
    private suspend fun handleChatDataRequest(
        request: ReplicationRequest,
        senderMemberId: String
    ) {
        val conversationId = request.conversationId ?: return
        val sender = groupStateManager.groupDefinition.value
            ?.findMemberById(senderMemberId) ?: return

        // Get messages we have after the requested timestamp
        val messages = chatMessageDao.getMessagesAfter(
            conversationId = conversationId,
            afterTimestamp = request.afterTimestamp
        ).take(request.limit)

        if (messages.isEmpty()) {
            Timber.d("$TAG: No chat data to send for ${request.requestId}")
            return
        }

        // Convert to replication format
        val replicatedMessages = messages.map { ReplicatedMessage.fromChatMessageEntity(it) }

        val response = ReplicationResponse(
            requestId = request.requestId,
            senderId = groupStateManager.localMember.value?.memberId ?: return,
            dataType = ReplicationDataType.CHAT_MESSAGES,
            messages = replicatedMessages,
            hasMore = messages.size >= request.limit,
            newestTimestamp = messages.maxOfOrNull { it.timestamp }
        )

        sendReplicationResponse(sender, response)
    }

    /**
     * Send replication response to a peer.
     */
    private suspend fun sendReplicationResponse(
        peer: FamilyMember,
        response: ReplicationResponse
    ) {
        try {
            val topic = MqttConfig.getReplicationDataTopic(peer.memberId)
            val plaintext = json.encodeToString(response)
            val payload = encryptForPeer(plaintext, peer) ?: return

            transportProvider.sendMessage(
                recipientId = peer.memberId,
                topic = topic,
                payload = payload,
                qos = MqttConfig.DEFAULT_QOS
            )
            Timber.d("$TAG: Sent replication response ${response.requestId} to ${peer.memberId}")

            _events.emit(
                ReplicationEvent.DataSent(
                    peerId = peer.memberId,
                    dataType = response.dataType,
                    itemCount = (response.locations?.size ?: 0) + (response.messages?.size ?: 0)
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to send replication response")
        }
    }

    /**
     * Handle incoming replication response (data from peer).
     */
    suspend fun handleReplicationResponse(
        responseJson: String,
        senderMemberId: String
    ) {
        try {
            val plaintext = decryptFromPeer(responseJson, senderMemberId) ?: return
            val response = json.decodeFromString<ReplicationResponse>(plaintext)
            Timber.d("$TAG: Received replication response ${response.requestId} from $senderMemberId")

            // Remove from pending
            pendingRequests.remove(response.requestId)

            when (response.dataType) {
                ReplicationDataType.LOCATION_HISTORY -> {
                    response.locations?.let { locations ->
                        val memberLocations = locations.map { it.toMemberLocation() }
                        val validLocations = memberLocations.filter { loc ->
                            val result = DataValidator.validateLocation(loc)
                            if (result !is ValidationResult.Valid) {
                                Timber.w("$TAG: Dropping invalid replicated location from $senderMemberId")
                            }
                            result is ValidationResult.Valid
                        }
                        if (validLocations.isEmpty()) return@let
                        locationRepository.updateMemberLocations(
                            locations = validLocations,
                            isReplicated = true,
                            replicatedFrom = senderMemberId
                        )
                        Timber.d("$TAG: Stored ${validLocations.size}/${locations.size} replicated locations")
                        _events.emit(
                            ReplicationEvent.DataReceived(
                                peerId = senderMemberId,
                                dataType = ReplicationDataType.LOCATION_HISTORY,
                                itemCount = validLocations.size
                            )
                        )
                    }
                }
                ReplicationDataType.CHAT_MESSAGES -> {
                    response.messages?.let { messages ->
                        val localId = groupStateManager.localMember.value?.memberId
                        val entities = messages.map { it.toChatMessageEntity(senderMemberId, localId) }
                        chatMessageDao.insertAll(entities)
                        Timber.d("$TAG: Stored ${messages.size} replicated messages")
                        _events.emit(
                            ReplicationEvent.DataReceived(
                                peerId = senderMemberId,
                                dataType = ReplicationDataType.CHAT_MESSAGES,
                                itemCount = messages.size
                            )
                        )
                    }
                }
            }

            // Request more if available
            if (response.hasMore && response.newestTimestamp != null) {
                val originalRequest = pendingRequests.values
                    .find { it.request.requestId == response.requestId }
                    ?.request

                if (originalRequest != null) {
                    val sender = groupStateManager.groupDefinition.value
                        ?.findMemberById(senderMemberId)

                    if (sender != null) {
                        val followUpRequest = originalRequest.copy(
                            requestId = UUID.randomUUID().toString(),
                            afterTimestamp = response.newestTimestamp
                        )
                        sendReplicationRequest(sender, followUpRequest)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to handle replication response")
        }
    }

    // =========================================================================
    // DATA AVAILABILITY ANNOUNCEMENTS
    // =========================================================================

    /**
     * Broadcast what data we have available.
     * Called periodically and when significant new data is received.
     */
    suspend fun announceDataAvailability() {
        val group = groupStateManager.groupDefinition.value ?: return
        val localMemberId = groupStateManager.localMember.value?.memberId ?: return

        try {
            // Build location data summary
            val dataSummary = locationHistoryRepository.getDataSummary()
            val locationSummary = dataSummary.mapNotNull { (memberId, summary) ->
                if (summary.oldestTimestamp != null && summary.newestTimestamp != null) {
                    MemberDataSummary(
                        memberId = memberId,
                        oldestTimestamp = summary.oldestTimestamp,
                        newestTimestamp = summary.newestTimestamp,
                        count = summary.count
                    )
                } else null
            }

            // Build chat data summary
            val conversationIds = chatMessageDao.getAllConversationIds()
            val chatSummary = conversationIds.mapNotNull { conversationId ->
                val newest = chatMessageDao.getNewestTimestamp(conversationId)
                val messages = chatMessageDao.getConversation(conversationId)
                if (newest != null && messages.isNotEmpty()) {
                    ConversationDataSummary(
                        conversationId = conversationId,
                        oldestTimestamp = messages.minOf { it.timestamp },
                        newestTimestamp = newest,
                        count = messages.size
                    )
                } else null
            }

            val announcement = DataAvailabilityAnnouncement(
                announcerId = localMemberId,
                locationDataSummary = locationSummary,
                chatDataSummary = chatSummary
            )

            val plaintext = json.encodeToString(announcement)

            // NaCl box is per-pair: one ciphertext can't be decrypted by every member.
            // Send the announcement to each peer's announcement inbox individually,
            // encrypted under their key, rather than to a shared group topic in plaintext.
            group.members
                .filter { it.memberId != localMemberId }
                .forEach { peer ->
                    val payload = encryptForPeer(plaintext, peer) ?: return@forEach
                    val topic = MqttConfig.getReplicationAnnounceInboxTopic(peer.memberId)
                    transportProvider.sendMessage(
                        recipientId = peer.memberId,
                        topic = topic,
                        payload = payload,
                        qos = MqttConfig.QOS_AT_MOST_ONCE
                    )
                }
            Timber.d("$TAG: Announced data availability to ${group.members.size - 1} peer(s)")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to announce data availability")
        }
    }

    /**
     * Handle data availability announcement from a peer.
     */
    suspend fun handleDataAvailabilityAnnouncement(
        announcementJson: String,
        senderMemberId: String
    ) {
        try {
            val plaintext = decryptFromPeer(announcementJson, senderMemberId) ?: return
            val announcement = json.decodeFromString<DataAvailabilityAnnouncement>(plaintext)
            Timber.d("$TAG: Received data availability from $senderMemberId")

            val localMemberId = groupStateManager.localMember.value?.memberId ?: return
            val sender = groupStateManager.groupDefinition.value
                ?.findMemberById(senderMemberId) ?: return

            // Check if peer has data we don't have
            announcement.locationDataSummary.forEach { summary ->
                val ourNewest = locationHistoryRepository.getNewestTimestamp(summary.memberId)

                if (ourNewest == null || summary.newestTimestamp > ourNewest) {
                    // Peer has newer data - request it
                    val request = ReplicationRequest(
                        requestId = UUID.randomUUID().toString(),
                        requesterId = localMemberId,
                        dataType = ReplicationDataType.LOCATION_HISTORY,
                        targetMemberId = summary.memberId,
                        afterTimestamp = ourNewest ?: 0L,
                        limit = MAX_ITEMS_PER_REQUEST
                    )
                    sendReplicationRequest(sender, request)
                }
            }

            announcement.chatDataSummary.forEach { summary ->
                val ourNewest = chatMessageDao.getNewestTimestamp(summary.conversationId)

                if (ourNewest == null || summary.newestTimestamp > ourNewest) {
                    val request = ReplicationRequest(
                        requestId = UUID.randomUUID().toString(),
                        requesterId = localMemberId,
                        dataType = ReplicationDataType.CHAT_MESSAGES,
                        conversationId = summary.conversationId,
                        afterTimestamp = ourNewest ?: 0L,
                        limit = MAX_ITEMS_PER_REQUEST
                    )
                    sendReplicationRequest(sender, request)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to handle data availability announcement")
        }
    }

    // =========================================================================
    // REAL-TIME REPLICATION
    // =========================================================================

    /**
     * Replicate a new location to all peers.
     * Called when we receive a location update (own or from family member).
     */
    suspend fun replicateLocation(location: com.example.familysafety.location.MemberLocation) {
        val group = groupStateManager.groupDefinition.value ?: return
        val localMemberId = groupStateManager.localMember.value?.memberId ?: return

        val response = ReplicationResponse(
            requestId = "realtime-${UUID.randomUUID()}",
            senderId = localMemberId,
            dataType = ReplicationDataType.LOCATION_HISTORY,
            locations = listOf(ReplicatedLocation.fromMemberLocation(location)),
            hasMore = false
        )

        // Send to all other members
        val plaintext = json.encodeToString(response)
        group.members
            .filter { it.memberId != localMemberId }
            .forEach { peer ->
                try {
                    val topic = MqttConfig.getReplicationDataTopic(peer.memberId)
                    val payload = encryptForPeer(plaintext, peer) ?: return@forEach
                    transportProvider.sendMessage(
                        recipientId = peer.memberId,
                        topic = topic,
                        payload = payload,
                        qos = MqttConfig.QOS_AT_MOST_ONCE
                    )
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to replicate location to ${peer.memberId}")
                }
            }
    }

    /**
     * Replicate a new chat message to all peers.
     */
    suspend fun replicateChatMessage(message: com.example.familysafety.storage.ChatMessageEntity) {
        val group = groupStateManager.groupDefinition.value ?: return
        val localMemberId = groupStateManager.localMember.value?.memberId ?: return

        val response = ReplicationResponse(
            requestId = "realtime-${UUID.randomUUID()}",
            senderId = localMemberId,
            dataType = ReplicationDataType.CHAT_MESSAGES,
            messages = listOf(ReplicatedMessage.fromChatMessageEntity(message)),
            hasMore = false
        )

        // Send to all other members (for backup, not direct chat)
        val plaintext = json.encodeToString(response)
        group.members
            .filter { it.memberId != localMemberId }
            .forEach { peer ->
                try {
                    val topic = MqttConfig.getReplicationDataTopic(peer.memberId)
                    val payload = encryptForPeer(plaintext, peer) ?: return@forEach
                    transportProvider.sendMessage(
                        recipientId = peer.memberId,
                        topic = topic,
                        payload = payload,
                        qos = MqttConfig.QOS_AT_MOST_ONCE
                    )
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to replicate message to ${peer.memberId}")
                }
            }
    }
}

/**
 * Tracks pending replication requests.
 */
private data class PendingRequest(
    val request: ReplicationRequest,
    val timestamp: Long
)

/**
 * Sync state for UI.
 */
sealed class SyncState {
    object Idle : SyncState()
    object Syncing : SyncState()
    object Synced : SyncState()
    data class Error(val message: String) : SyncState()
}

/**
 * Events emitted during replication.
 */
sealed class ReplicationEvent {
    object SyncStarted : ReplicationEvent()
    object SyncCompleted : ReplicationEvent()

    data class DataReceived(
        val peerId: String,
        val dataType: ReplicationDataType,
        val itemCount: Int
    ) : ReplicationEvent()

    data class DataSent(
        val peerId: String,
        val dataType: ReplicationDataType,
        val itemCount: Int
    ) : ReplicationEvent()

    data class Error(val message: String) : ReplicationEvent()
}
