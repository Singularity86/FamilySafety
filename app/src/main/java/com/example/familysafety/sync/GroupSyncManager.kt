package com.example.familysafety.sync

import android.util.Log
import com.example.familysafety.core.ErrorHandler
import com.example.familysafety.core.GroupStateException
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.crypto.RecipientKeys
import com.example.familysafety.group.*
import com.example.familysafety.transport.MqttConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.eclipse.paho.client.mqttv3.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupSyncManager @Inject constructor(
    private val e2eeManager: E2EEManager,
    private val cryptoProvider: LazysodiumCryptoProvider
) {
    private val json = Json { 
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var mqttClient: MqttAsyncClient? = null
    private var groupStateManager: GroupStateManager? = null
    private var currentMemberId: String? = null
    
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    
    private val versionAcks = mutableMapOf<Int, MutableSet<String>>()
    private val conflictQueue = mutableListOf<GroupDefinition>()

    sealed class SyncState {
        data object Idle : SyncState()
        data object Syncing : SyncState()
        data class Synced(val version: Int) : SyncState()
        data class Conflict(val localVersion: Int, val remoteVersion: Int) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    fun initialize(
        memberId: String,
        mqttClient: MqttAsyncClient,
        groupStateManager: GroupStateManager
    ) {
        this.currentMemberId = memberId
        this.mqttClient = mqttClient
        this.groupStateManager = groupStateManager
        
        subscribeToGroupSync()
        subscribeToAcknowledgments()
        observeGroupChanges()
    }

    private fun subscribeToGroupSync() {
        scope.launch {
            val groupDef = groupStateManager?.groupDefinition?.value ?: return@launch
            val topic = MqttConfig.getGroupSyncTopic(groupDef.groupId)
            
            ErrorHandler.withRetry(
                maxAttempts = 3,
                initialDelayMs = 1000
            ) {
                suspendCancellableCoroutine { continuation ->
                    mqttClient?.subscribe(topic, MqttConfig.DEFAULT_QOS, null, object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            Timber.i("Subscribed to group sync topic")
                            continuation.resume(Unit) {}
                        }

                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            Timber.e(exception, "Failed to subscribe to group sync")
                            continuation.resumeWith(Result.failure(exception ?: Exception("Subscribe failed")))
                        }
                    })
                }
            }
            
            setupSyncMessageHandler()
        }
    }

    private fun subscribeToAcknowledgments() {
        scope.launch {
            val groupDef = groupStateManager?.groupDefinition?.value ?: return@launch
            val topic = MqttConfig.getGroupAckTopic(groupDef.groupId)
            
            mqttClient?.subscribe(topic, MqttConfig.DEFAULT_QOS)
            setupAckMessageHandler()
        }
    }

    private fun setupSyncMessageHandler() {
        mqttClient?.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                Timber.w(cause, "MQTT connection lost in GroupSyncManager")
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                // Delivery complete
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val groupDef = groupStateManager?.groupDefinition?.value
                if (groupDef != null && topic == MqttConfig.getGroupSyncTopic(groupDef.groupId)) {
                    message?.let {
                        scope.launch {
                            handleGroupSyncMessage(String(it.payload))
                        }
                    }
                }
            }
        })
    }

    private fun setupAckMessageHandler() {
        scope.launch {
        }
    }

    private fun observeGroupChanges() {
        scope.launch {
            groupStateManager?.groupDefinition?.collect { groupDef ->
                groupDef?.let {
                    if (_syncState.value is SyncState.Syncing) {
                        return@collect
                    }
                    
                    Timber.d("Group definition changed to version ${it.version}")
                }
            }
        }
    }

    suspend fun broadcastGroupUpdate(
        groupDefinition: GroupDefinition,
        changeType: ChangeType,
        changedMemberId: String? = null
    ) {
        withContext(Dispatchers.IO) {
            try {
                _syncState.value = SyncState.Syncing
                
                val myMemberId = currentMemberId ?: throw GroupStateException.NotInitialized()
                
                val syncMessage = GroupSyncMessage(
                    groupId = groupDefinition.groupId,
                    version = groupDefinition.version.toInt(),
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
                
                val topic = MqttConfig.getGroupSyncTopic(groupDefinition.groupId)
                
                val publishResult = ErrorHandler.withRetry(
                    maxAttempts = 3,
                    initialDelayMs = 1000
                ) {
                    suspendCancellableCoroutine { continuation ->
                        val message = MqttMessage(messageJson.toByteArray()).apply {
                            qos = MqttConfig.QOS_AT_LEAST_ONCE
                            isRetained = false
                        }
                        
                        mqttClient?.publish(topic, message, null, object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                Timber.i("Broadcasted group update v${groupDefinition.version}")
                                continuation.resume(Unit) {}
                            }

                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                Timber.e(exception, "Failed to broadcast group update")
                                continuation.resumeWith(Result.failure(exception ?: Exception("Publish failed")))
                            }
                        })
                    }
                }
                
                if (publishResult.isSuccess) {
                    _syncState.value = SyncState.Synced(groupDefinition.version.toInt())
                    waitForAcknowledgments(groupDefinition.version.toInt(), groupDefinition.members.size)
                } else {
                    _syncState.value = SyncState.Error("Failed to broadcast update")
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Error broadcasting group update")
                _syncState.value = SyncState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun handleGroupSyncMessage(encryptedPayload: String) {
        ErrorHandler.safely(
            tag = "GroupSyncManager",
            operation = "handling group sync message"
        ) {
            handleGroupSyncMessageInternal(encryptedPayload)
        }
    }

    private suspend fun handleGroupSyncMessageInternal(encryptedPayload: String) {
        val manager = groupStateManager ?: return
        val currentGroup = manager.groupDefinition.value ?: return
        val myMemberId = currentMemberId ?: return
        
        val syncMessage = try {
            json.decodeFromString<GroupSyncMessage>(encryptedPayload)
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
            
            syncMessage.version == currentGroup.version.toInt() -> {
                Timber.d("Received same version")
                sendAcknowledgment(syncMessage.groupId, syncMessage.version)
            }
            
            syncMessage.version == currentGroup.version.toInt() + 1 -> {
                Timber.i("Applying group update")
                applyGroupUpdate(syncMessage)
            }
            
            syncMessage.version > currentGroup.version.toInt() + 1 -> {
                Timber.w("Version jump detected")
                _syncState.value = SyncState.Conflict(currentGroup.version.toInt(), syncMessage.version)
                applyGroupUpdate(syncMessage)
            }
        }
    }

    private suspend fun applyGroupUpdate(syncMessage: GroupSyncMessage) {
        withContext(Dispatchers.IO) {
            try {
                val manager = groupStateManager ?: return@withContext
                
                // Apply the remote group state - signature already verified
                manager.applyRemoteGroupState(
                    syncMessage.groupDefinition,
                    syncMessage.signature.hexToByteArray(),
                    syncMessage.updaterMemberId
                )
                sendAcknowledgment(syncMessage.groupId, syncMessage.version)
                
                _syncState.value = SyncState.Synced(syncMessage.version)
                Timber.i("Applied group update: ${syncMessage.changeType}")
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to apply group update")
                _syncState.value = SyncState.Error(e.message ?: "Failed to apply update")
            }
        }
    }

    private suspend fun sendAcknowledgment(groupId: String, version: Int) {
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
                
                val message = MqttMessage(ackJson.toByteArray()).apply {
                    qos = MqttConfig.DEFAULT_QOS
                }
                
                mqttClient?.publish(topic, message)
                Timber.d("Sent acknowledgment for version $version")
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to send acknowledgment")
            }
        }
    }

    private suspend fun waitForAcknowledgments(version: Int, memberCount: Int) {
        withContext(Dispatchers.IO) {
            val timeout = 30_000L
            val startTime = System.currentTimeMillis()
            
            versionAcks[version] = mutableSetOf()
            
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

    private suspend fun verifyGroupSyncSignature(syncMessage: GroupSyncMessage): Boolean {
        return try {
            val manager = groupStateManager ?: return false
            val currentGroup = manager.groupDefinition.value ?: return false
            
            val updater = currentGroup.members.find { it.memberId == syncMessage.updaterMemberId }
                ?: syncMessage.groupDefinition.members.find { it.memberId == syncMessage.updaterMemberId }
                ?: return false
            
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
        return "${syncMessage.groupId}|${syncMessage.version}|${syncMessage.updaterMemberId}|${syncMessage.timestamp}"
    }

    fun cleanup() {
        scope.cancel()
        versionAcks.clear()
        conflictQueue.clear()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

@Serializable
data class GroupSyncMessage(
    val groupId: String,
    val version: Int,
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
    val version: Int,
    val memberId: String,
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

enum class ConflictResolutionStrategy {
    USE_LOCAL,
    USE_REMOTE,
    MERGE
}
