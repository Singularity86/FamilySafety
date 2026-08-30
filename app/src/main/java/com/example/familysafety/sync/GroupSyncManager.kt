package com.example.familysafety.sync

import com.example.familysafety.core.ErrorHandler
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.*
import com.example.familysafety.transport.MqttConfig
import com.example.familysafety.transport.TransportProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupSyncManager @Inject constructor(
    private val e2eeManager: E2EEManager,
    private val cryptoProvider: LazysodiumCryptoProvider,
    private val transportProvider: TransportProvider
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var groupStateManager: GroupStateManager? = null
    private var currentMemberId: String? = null

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // Concurrent maps so concurrent ack arrivals + waitForAcknowledgments polling
    // can't trip a CME / lose entries.
    private val versionAcks = ConcurrentHashMap<Long, MutableSet<String>>()

    sealed class SyncState {
        data object Idle : SyncState()
        data object Syncing : SyncState()
        data class Synced(val version: Long) : SyncState()
        data class Conflict(val localVersion: Long, val remoteVersion: Long) : SyncState()

        /**
         * The update could not go out yet and is queued for retry.
         *
         * Distinct from [Error] because nothing has gone wrong and nothing is lost — the
         * transport holds control-plane messages in their own queue and drains them on
         * reconnect. Reporting it as an error made an ordinary offline moment look like a
         * defect, and made a real defect harder to spot among the noise.
         */
        data class Deferred(val version: Long, val peerCount: Int) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    data class RefreshRequestResult(
        val requestedVersion: Long,
        val peerCount: Int,
        val sentCount: Int,
        val failedCount: Int
    )

    fun initialize(
        memberId: String,
        groupStateManager: GroupStateManager
    ) {
        this.currentMemberId = memberId
        this.groupStateManager = groupStateManager
        Timber.i("GroupSyncManager initialized for member ${memberId.take(8)}…")
    }

    suspend fun broadcastGroupUpdate(
        groupDefinition: GroupDefinition,
        changeType: ChangeType,
        changedMemberId: String? = null
    ) {
        withContext(Dispatchers.IO) {
            try {
                val myMemberId = currentMemberId ?: run {
                    Timber.d("GroupSyncManager not initialized, skipping broadcast")
                    return@withContext
                }

                _syncState.value = SyncState.Syncing

                val syncMessage = GroupSyncMessage(
                    groupId = groupDefinition.groupId,
                    version = groupDefinition.version,
                    groupDefinition = groupDefinition,
                    updaterMemberId = myMemberId,
                    changeType = changeType,
                    changedMemberId = changedMemberId,
                    timestamp = System.currentTimeMillis(),
                    signature = ""
                )

                val payload = createSyncSignaturePayload(syncMessage)
                val signature = cryptoProvider.signMessage(payload.toByteArray())

                val signedMessage = syncMessage.copy(signature = signature.toHexString())
                val messageJson = json.encodeToString(signedMessage)

                val peers = groupDefinition.members.filter { it.memberId != myMemberId }
                var sentCount = 0
                peers.forEach { member ->
                    try {
                        val encrypted = e2eeManager.encryptMessage(
                            plaintext = messageJson,
                            recipientMemberId = member.memberId,
                            recipientX25519PublicKey = member.x25519PublicKey.hexToByteArray()
                        )
                        val topic = MqttConfig.getGroupSyncInboxTopic(member.memberId)
                        val sent = transportProvider.sendMessage(
                            recipientId = member.memberId,
                            topic = topic,
                            payload = encrypted.toByteArray(),
                            qos = MqttConfig.QOS_AT_LEAST_ONCE
                        )
                        if (sent) sentCount++
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to send encrypted sync to ${member.memberId.take(8)}")
                    }
                }

                val published = peers.isEmpty() || sentCount > 0
                if (published) {
                    Timber.i("Sent encrypted group update v${groupDefinition.version} to $sentCount/${peers.size} peers")
                    // Report Synced only after the ack window; until then peers may
                    // not have applied the update yet.
                    waitForAcknowledgments(groupDefinition.version, groupDefinition.members.size)
                    _syncState.value = SyncState.Synced(groupDefinition.version)
                } else {
                    // Not a failure. Group sync is a control-plane topic, so the transport
                    // has queued every one of these and will drain them when it can. Calling
                    // it an error put "Failed to broadcast update" on screen for a message
                    // that was sitting in a queue waiting for a connection.
                    Timber.w("No peer reachable for group update v${groupDefinition.version} — queued for retry")
                    _syncState.value = SyncState.Deferred(groupDefinition.version, peers.size)
                }

            } catch (e: Exception) {
                Timber.e(e, "Error broadcasting group update")
                _syncState.value = SyncState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Ask every other member in the group to rebroadcast the latest group state.
     * This is used after membership changes so devices that missed the update can
     * recover from a retained or delayed refresh.
     */
    suspend fun requestGroupStateRefresh(
        reason: String,
        minimumVersion: Long? = null,
        changedMemberId: String? = null
    ): RefreshRequestResult = withContext(Dispatchers.IO) {
            try {
                val myMemberId = currentMemberId ?: run {
                    Timber.d("GroupSyncManager not initialized, skipping refresh request")
                    return@withContext RefreshRequestResult(0, 0, 0, 0)
                }
                val groupDefinition = groupStateManager?.groupDefinition?.value
                    ?: return@withContext RefreshRequestResult(0, 0, 0, 0)
                val request = GroupStateRefreshRequest(
                    groupId = groupDefinition.groupId,
                    requesterMemberId = myMemberId,
                    minimumVersion = minimumVersion ?: groupDefinition.version,
                    changedMemberId = changedMemberId,
                    reason = reason,
                    timestamp = System.currentTimeMillis()
                )
                val payload = json.encodeToString(request).toByteArray()

                val peers = groupDefinition.members.filter { it.memberId != myMemberId }
                var sentCount = 0
                var failedCount = 0
                peers.forEach { member ->
                    val topic = MqttConfig.getSyncRequestTopic(member.memberId)
                    val sent = transportProvider.sendMessage(
                        recipientId = member.memberId,
                        topic = topic,
                        payload = payload,
                        qos = MqttConfig.DEFAULT_QOS
                    )
                    if (sent) {
                        sentCount++
                    } else {
                        failedCount++
                    }
                }

                Timber.i(
                    "Requested group state refresh v${request.minimumVersion} for ${peers.size} peers ($sentCount sent, $failedCount failed)"
                )
                RefreshRequestResult(
                    requestedVersion = request.minimumVersion,
                    peerCount = peers.size,
                    sentCount = sentCount,
                    failedCount = failedCount
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to request group state refresh")
                RefreshRequestResult(0, 0, 0, 0)
            }
    }

    /**
     * Called by MqttTransport when a message arrives on the group sync topic.
     */
    suspend fun handleSyncMessage(payload: String) {
        ErrorHandler.safely(
            tag = "GroupSyncManager",
            operation = "handling group sync message"
        ) {
            handleGroupSyncMessageInternal(payload)
        }
    }

    /**
     * Called by MqttTransport when a message arrives on the group ack topic.
     */
    suspend fun handleAckMessage(payload: String) {
        try {
            val ack = json.decodeFromString<GroupUpdateAck>(payload)
            val myMemberId = currentMemberId
            if (ack.memberId == myMemberId) return // ignore our own
            val set = versionAcks.computeIfAbsent(ack.version) {
                java.util.Collections.synchronizedSet(mutableSetOf())
            }
            set.add(ack.memberId)
            // Evict buckets for versions well behind the current one so the map
            // doesn't grow for the life of the process.
            groupStateManager?.groupDefinition?.value?.version?.let { currentVersion ->
                versionAcks.keys.removeIf { it < currentVersion - 5 }
            }
            Timber.d("Received ack for v${ack.version} from ${ack.memberId.take(8)} (${set.size} total)")
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse ack message")
        }
    }

    /**
     * Handle a request for the latest group definition.
     * A peer only responds if it is already at the requested version or newer.
     */
    suspend fun handleSyncRequestMessage(payload: String) {
        try {
            val request = json.decodeFromString<GroupStateRefreshRequest>(payload)
            val manager = groupStateManager ?: return
            val currentGroup = manager.groupDefinition.value ?: return
            val myMemberId = currentMemberId ?: return

            if (request.groupId != currentGroup.groupId) {
                Timber.d("Ignoring refresh request for different group ${request.groupId}")
                return
            }

            if (request.requesterMemberId == myMemberId) {
                return
            }

            if (currentGroup.version < request.minimumVersion) {
                Timber.d(
                    "Ignoring stale refresh request for v${request.minimumVersion} " +
                        "because local version is ${currentGroup.version}"
                )
                return
            }

            Timber.i(
                "Responding to group refresh request from ${request.requesterMemberId.take(8)} " +
                    "for v${request.minimumVersion}"
            )
            broadcastGroupUpdate(currentGroup, ChangeType.FULL_SYNC, request.changedMemberId)
        } catch (e: Exception) {
            Timber.w(e, "Failed to handle group sync request")
        }
    }

    private suspend fun handleGroupSyncMessageInternal(encryptedPayload: String) {
        val manager = groupStateManager ?: return
        val currentGroup = manager.groupDefinition.value ?: return
        val myMemberId = currentMemberId ?: return

        val senderMemberId = extractSenderMemberId(encryptedPayload) ?: run {
            Timber.w("Cannot extract sender from group sync payload")
            return
        }
        val sender = currentGroup.findMemberById(senderMemberId) ?: run {
            Timber.w("Unknown sender ${senderMemberId.take(8)} for group sync — ignoring")
            return
        }
        val decryptedJson = try {
            e2eeManager.decryptMessage(
                encryptedMessageJson = encryptedPayload,
                senderX25519PublicKey = sender.x25519PublicKey.hexToByteArray(),
                senderEd25519PublicKey = sender.ed25519PublicKey.hexToByteArray()
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to decrypt group sync message from ${senderMemberId.take(8)}")
            return
        }

        val syncMessage = try {
            json.decodeFromString<GroupSyncMessage>(decryptedJson)
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse sync message")
            return
        }

        val isValidSignature = verifyGroupSyncSignature(syncMessage)
        if (!isValidSignature) {
            Timber.e("Invalid signature on group sync message")
            return
        }

        if (syncMessage.updaterMemberId == myMemberId) {
            Timber.d("Ignoring our own sync message")
            return
        }

        when {
            syncMessage.version < currentGroup.version -> {
                Timber.w("Received outdated version")
                broadcastGroupUpdate(currentGroup, ChangeType.VERSION_SYNC)
            }

            syncMessage.version == currentGroup.version -> {
                if (syncMessage.groupDefinition.computeStateHash() ==
                    currentGroup.computeStateHash()
                ) {
                    Timber.d("Received same version, identical state")
                    sendAcknowledgment(syncMessage.groupId, syncMessage.version)
                } else {
                    // Two members edited from the same parent. Acknowledging and dropping
                    // this — the old behaviour — is what forked groups permanently: both
                    // sides believed they were in sync while holding different rosters.
                    Timber.w(
                        "Concurrent edit detected at v${currentGroup.version} from " +
                            "${syncMessage.updaterMemberId.take(8)} — reconciling"
                    )
                    applyGroupUpdate(syncMessage)
                }
            }

            syncMessage.version == currentGroup.version + 1 -> {
                Timber.i("Applying group update")
                applyGroupUpdate(syncMessage)
            }

            syncMessage.version > currentGroup.version + 1 -> {
                Timber.w("Version jump detected")
                _syncState.value = SyncState.Conflict(currentGroup.version, syncMessage.version)
                applyGroupUpdate(syncMessage)
            }
        }
    }

    private suspend fun applyGroupUpdate(syncMessage: GroupSyncMessage) {
        withContext(Dispatchers.IO) {
            try {
                val manager = groupStateManager ?: return@withContext

                val result = manager.applyVerifiedRemoteGroupState(
                    syncMessage.groupDefinition,
                    syncMessage.updaterMemberId
                )
                if (result is GroupOperationResult.Failure) {
                    Timber.w("Rejected group update v${syncMessage.version}: ${result.error}")
                    _syncState.value = SyncState.Error("Rejected group update: ${result.error}")
                    return@withContext
                }

                sendAcknowledgment(syncMessage.groupId, syncMessage.version)

                // Reconciling a concurrent edit leaves us holding a state the sender does
                // not have — either ours won, or we merged their branch into a successor.
                // Sending it back is what closes the loop; without it each side keeps its
                // own answer and the group stays split. Ordered after the acknowledgment
                // because broadcasting waits on an ack window of its own.
                val resolved = (result as GroupOperationResult.Success).value
                if (resolved.computeStateHash() !=
                    syncMessage.groupDefinition.computeStateHash()
                ) {
                    Timber.i(
                        "Reconciled to v${resolved.version} with ${resolved.members.size} " +
                            "members — publishing back to the family"
                    )
                    broadcastGroupUpdate(resolved, ChangeType.FULL_SYNC)
                }

                _syncState.value = SyncState.Synced(syncMessage.version)
                Timber.i("Applied group update: ${syncMessage.changeType}")

                if (syncMessage.changeType == ChangeType.MEMBER_ADDED) {
                    scope.launch {
                        requestGroupStateRefresh(
                            reason = "member_added",
                            minimumVersion = syncMessage.version,
                            changedMemberId = syncMessage.changedMemberId
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to apply group update")
                _syncState.value = SyncState.Error(e.message ?: "Failed to apply update")
            }
        }
    }

    private suspend fun sendAcknowledgment(groupId: String, version: Long) {
        withContext(Dispatchers.IO) {
            try {
                val myMemberId = currentMemberId ?: return@withContext

                val ack = GroupUpdateAck(
                    groupId = groupId,
                    version = version,
                    memberId = myMemberId,
                    timestamp = System.currentTimeMillis()
                )

                val ackJson = json.encodeToString(ack)
                val topic = MqttConfig.getGroupAckTopic(groupId)

                // Acks are broadcast to anyone watching for that version
                transportProvider.broadcastMessage(topic, ackJson.toByteArray(), MqttConfig.DEFAULT_QOS)
                Timber.d("Sent acknowledgment for version $version")

            } catch (e: Exception) {
                Timber.e(e, "Failed to send acknowledgment")
            }
        }
    }

    private suspend fun waitForAcknowledgments(version: Long, memberCount: Int) {
        withContext(Dispatchers.IO) {
            val timeout = 30_000L
            val startTime = System.currentTimeMillis()

            // Initialize the bucket so handleAckMessage has somewhere to write into
            // even if it arrives before this function gets a chance to look.
            versionAcks.computeIfAbsent(version) {
                java.util.Collections.synchronizedSet(mutableSetOf())
            }

            while ((versionAcks[version]?.size ?: 0) < memberCount - 1) {
                if (System.currentTimeMillis() - startTime > timeout) {
                    Timber.w("Timeout waiting for acks")
                    break
                }
                delay(500)
            }

            Timber.i("Received ${versionAcks[version]?.size ?: 0}/${memberCount - 1} acks")
        }
    }

    private fun verifyGroupSyncSignature(syncMessage: GroupSyncMessage): Boolean {
        return try {
            val manager = groupStateManager ?: return false
            val currentGroup = manager.groupDefinition.value ?: return false

            // The updater's key must come from OUR current roster. Falling back to the
            // incoming definition's roster would let an attacker validate their own
            // signature against a key they supplied themselves.
            val updater = currentGroup.members.find { it.memberId == syncMessage.updaterMemberId }
                ?: run {
                    Timber.w(
                        "Sync update signed by ${syncMessage.updaterMemberId.take(8)}, " +
                            "who is not in our current roster — rejecting"
                    )
                    return false
                }

            val payload = createSyncSignaturePayload(syncMessage)
            val signature = syncMessage.signature.hexToByteArray()

            cryptoProvider.verifySignature(
                message = payload.toByteArray(),
                signature = signature,
                publicKey = updater.ed25519PublicKey.hexToByteArray()
            )
        } catch (e: Exception) {
            Timber.e(e, "Signature verification failed")
            false
        }
    }

    private fun createSyncSignaturePayload(syncMessage: GroupSyncMessage): String {
        val definitionHash = syncMessage.groupDefinition.computeStateHash()
        return "${syncMessage.groupId}|${syncMessage.version}|${syncMessage.updaterMemberId}|${syncMessage.timestamp}|${definitionHash}"
    }

    fun clearError() {
        if (_syncState.value is SyncState.Error) {
            _syncState.value = SyncState.Idle
        }
    }

    fun cleanup() {
        scope.cancel()
        versionAcks.clear()
    }

    private fun extractSenderMemberId(encryptedPayload: String): String? {
        return try {
            val regex = """"senderMemberId"\s*:\s*"([^"]+)"""".toRegex()
            regex.find(encryptedPayload)?.groupValues?.getOrNull(1)
        } catch (e: Exception) { null }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

@Serializable
data class GroupSyncMessage(
    val groupId: String,
    val version: Long,
    val groupDefinition: GroupDefinition,
    val updaterMemberId: String,
    val changeType: ChangeType,
    val changedMemberId: String? = null,
    val timestamp: Long,
    val signature: String
)

@Serializable
data class GroupUpdateAck(
    val groupId: String,
    val version: Long,
    val memberId: String,
    val timestamp: Long
)

@Serializable
data class GroupStateRefreshRequest(
    val groupId: String,
    val requesterMemberId: String,
    val minimumVersion: Long,
    val changedMemberId: String? = null,
    val reason: String,
    val timestamp: Long
)

@Serializable
enum class ChangeType {
    MEMBER_ADDED,
    MEMBER_REMOVED,
    NAME_CHANGED,
    VERSION_SYNC,
    CONFLICT_RESOLUTION,
    FULL_SYNC
}
