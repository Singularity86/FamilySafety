package com.example.familysafety.transport

import com.example.familysafety.chat.ChatRepository
import com.example.familysafety.files.SharedFileRepository
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.LocalMemberId
import com.example.familysafety.invite.InviteManager
import com.example.familysafety.replication.ReplicationManager
import com.example.familysafety.sync.GroupSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import javax.inject.Provider

/**
 * Unified transport manager that intelligently routes messages between P2P and Cloud Relay.
 */
@Singleton
class UnifiedTransportManager @Inject constructor(
    private val localTransport: LocalTransport,
    private val mqttTransport: MqttTransport,
    private val groupStateManager: GroupStateManager,
    private val localMemberId: LocalMemberId,
    // Providers to avoid circular dependencies
    private val chatRepositoryProvider: Provider<ChatRepository>,
    private val replicationManagerProvider: Provider<ReplicationManager>,
    private val inviteManagerProvider: Provider<InviteManager>,
    private val groupSyncManagerProvider: Provider<GroupSyncManager>,
    private val sharedFileRepositoryProvider: Provider<SharedFileRepository>
) : TransportProvider {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private companion object {
        const val TAG = "UnifiedTransport"
    }

    init {
        // Hook up MQTT incoming messages
        mqttTransport.onMessageReceived = { topic, payload ->
            handleIncomingMessage(topic, payload)
        }

        // Hook up Local P2P incoming messages
        localTransport.onMessageReceived = { topic, payloadString ->
            // LocalTransport payloads are already strings (Base64 or JSON)
            handleIncomingMessage(topic, payloadString)
        }
    }

    override suspend fun sendMessage(
        recipientId: String,
        topic: String,
        payload: ByteArray,
        qos: Int,
        retained: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        // 1. Try Local P2P first
        if (localTransport.isReachable(recipientId)) {
            val payloadString = Base64.getEncoder().encodeToString(payload)
            val success = localTransport.send(topic, payloadString, recipientId)
            if (success) {
                Timber.d("$TAG: Delivered to ${recipientId.take(8)} via P2P")
                return@withContext true
            }
            Timber.d("$TAG: P2P delivery failed for ${recipientId.take(8)}, falling back to Relay")
        }

        // 2. Fall back to MQTT Relay
        val success = mqttTransport.publishRaw(topic, payload, qos, retained)
        if (success) {
            Timber.d("$TAG: Delivered to ${recipientId.take(8)} via Relay")
        } else {
            Timber.w("$TAG: Failed to deliver to ${recipientId.take(8)} via any transport")
        }
        return@withContext success
    }

    override suspend fun broadcastMessage(
        topic: String,
        payload: ByteArray,
        qos: Int,
        retained: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val group = groupStateManager.groupDefinition.value
        if (group == null) {
            return@withContext mqttTransport.publishRaw(topic, payload, qos, retained)
        }

        val myId = localMemberId.value
        val otherMembers = group.members.filter { it.memberId != myId }

        if (otherMembers.isEmpty()) {
            // Even if no other members, we might want to publish to MQTT for persistence/sync
            return@withContext mqttTransport.publishRaw(topic, payload, qos, retained)
        }

        var anySucceeded = false
        // For broadcast, we try both paths to ensure maximum coverage
        // In a real P2P system, we'd be more selective.
        
        // Always try MQTT for group-level topics
        if (mqttTransport.publishRaw(topic, payload, qos, retained)) {
            anySucceeded = true
        }

        // Also try P2P for everyone currently on LAN
        for (member in otherMembers) {
            if (localTransport.isReachable(member.memberId)) {
                val payloadString = Base64.getEncoder().encodeToString(payload)
                if (localTransport.send(topic, payloadString, member.memberId)) {
                    anySucceeded = true
                }
            }
        }

        return@withContext anySucceeded
    }

    private fun handleIncomingMessage(topic: String, payload: String) {
        scope.launch {
            try {
                // Route based on topic structure (Extracted from legacy MqttTransport)
                when {
                    topic.endsWith("/chat") -> {
                        val senderId = extractSenderFromTopic(topic) ?: extractSenderFromEncryptedEnvelope(payload)
                        if (senderId != null) {
                            chatRepositoryProvider.get().handleIncomingMessage(payload, senderId)
                        }
                    }

                    topic.endsWith("/chat/receipt") || topic.endsWith("/chat/read") -> {
                        chatRepositoryProvider.get().handleDeliveryReceipt(payload)
                    }

                    topic.endsWith("/replication/request") -> {
                        val senderId = extractSenderFromEncryptedEnvelope(payload)
                        if (senderId != null) {
                            replicationManagerProvider.get().handleReplicationRequest(payload, senderId)
                        }
                    }

                    topic.endsWith("/replication/data") -> {
                        val senderId = extractSenderFromEncryptedEnvelope(payload)
                        if (senderId != null) {
                            replicationManagerProvider.get().handleReplicationResponse(payload, senderId)
                        }
                    }

                    topic.endsWith("/replication/announce") -> {
                        val senderId = extractSenderFromEncryptedEnvelope(payload) ?: extractSenderFromAnnounceTopic(payload)
                        if (senderId != null) {
                            replicationManagerProvider.get().handleDataAvailabilityAnnouncement(payload, senderId)
                        }
                    }

                    topic.contains("/group/") && topic.endsWith("/sync") -> {
                        groupSyncManagerProvider.get().handleSyncMessage(payload)
                    }

                    topic.contains("/group/") && topic.endsWith("/ack") -> {
                        groupSyncManagerProvider.get().handleAckMessage(payload)
                    }

                    topic.endsWith("/join_request") -> {
                        inviteManagerProvider.get().handleIncomingJoinRequest(payload)
                    }

                    topic.endsWith("/location") -> {
                        // Forward to specialized handler (can be kept in UnifiedTransport or moved)
                        handleEncryptedLocation(topic, payload)
                    }

                    topic.contains("/files/manifest") -> {
                        sharedFileRepositoryProvider.get().handleIncomingManifest(payload.toByteArray())
                    }

                    topic.contains("/files/chunk/") -> {
                        val parts = topic.split("/files/chunk/", limit = 2)
                        if (parts.size == 2) {
                            val chunkParts = parts[1].split("/")
                            if (chunkParts.size >= 2) {
                                val fileId = chunkParts[0]
                                val chunkIndex = chunkParts[1].toIntOrNull() ?: 0
                                sharedFileRepositoryProvider.get().handleIncomingChunk(fileId, chunkIndex, payload.toByteArray())
                            }
                        }
                    }

                    topic.endsWith("/files/request") -> {
                        sharedFileRepositoryProvider.get().handleFileRequest(payload.toByteArray())
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error routing incoming message on $topic")
            }
        }
    }

    private suspend fun handleEncryptedLocation(topic: String, encryptedPayload: String) {
        mqttTransport.handleLocationOrPresencePublic(topic, encryptedPayload)
    }

    private fun extractSenderFromTopic(topic: String): String? {
        val parts = topic.split("/")
        return if (parts.size >= 2 && parts[0] == "familysafe") parts[1] else null
    }

    private fun extractSenderFromEncryptedEnvelope(payload: String): String? {
        return try {
            val regex = """"senderMemberId"\s*:\s*"([^"]+)"""".toRegex()
            regex.find(payload)?.groupValues?.getOrNull(1)
        } catch (e: Exception) { null }
    }

    private fun extractSenderFromAnnounceTopic(payload: String): String? {
        return try {
            val regex = """"announcerId"\s*:\s*"([^"]+)"""".toRegex()
            regex.find(payload)?.groupValues?.getOrNull(1)
        } catch (e: Exception) { null }
    }
}
