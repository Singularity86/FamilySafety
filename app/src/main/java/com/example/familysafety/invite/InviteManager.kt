package com.example.familysafety.invite

import com.example.familysafety.group.*
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.sync.ChangeType
import com.example.familysafety.sync.GroupSyncManager
import com.example.familysafety.transport.MqttConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.eclipse.paho.client.mqttv3.*
import timber.log.Timber
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InviteManager @Inject constructor(
    private val cryptoProvider: LazysodiumCryptoProvider,
    private val e2eeManager: E2EEManager
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var mqttClient: MqttAsyncClient? = null
    private var groupStateManager: GroupStateManager? = null
    private var groupSyncManager: GroupSyncManager? = null
    private var currentMemberId: String? = null
    
    private val _pendingJoinRequests = MutableStateFlow<List<JoinRequest>>(emptyList())
    val pendingJoinRequests: StateFlow<List<JoinRequest>> = _pendingJoinRequests.asStateFlow()
    
    fun initialize(
        memberId: String,
        mqttClient: MqttAsyncClient,
        groupStateManager: GroupStateManager,
        groupSyncManager: GroupSyncManager
    ) {
        this.currentMemberId = memberId
        this.mqttClient = mqttClient
        this.groupStateManager = groupStateManager
        this.groupSyncManager = groupSyncManager
        
        subscribeToJoinRequests(memberId)
    }
    
    private fun subscribeToJoinRequests(memberId: String) {
        scope.launch {
            val topic = MqttConfig.getJoinRequestTopic(memberId)
            
            mqttClient?.subscribe(topic, MqttConfig.DEFAULT_QOS, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Timber.i("Subscribed to join requests")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Timber.e(exception, "Failed to subscribe to join requests")
                }
            })
            
            setupJoinRequestHandler()
        }
    }
    
    private fun setupJoinRequestHandler() {
        mqttClient?.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                Timber.e(cause, "MQTT Connection lost")
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                // Not needed
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val myMemberId = currentMemberId
                if (myMemberId != null && topic == MqttConfig.getJoinRequestTopic(myMemberId)) {
                    message?.let {
                        scope.launch {
                            handleJoinRequest(String(it.payload))
                        }
                    }
                }
            }
        })
    }
    
    /**
     * Handles an incoming join request by deserializing and adding to pending requests
     */
    private suspend fun handleJoinRequest(payload: String) {
        try {
            Timber.d("Received join request payload: $payload")
            
            val joinRequest = json.decodeFromString<JoinRequest>(payload)
            
            // Verify the request is for our current group
            // NOTE: Adjust this based on your GroupStateManager's actual API
            val currentGroup = groupStateManager?.groupDefinition?.value
            if (currentGroup?.groupId != joinRequest.groupId) {
                Timber.w("Join request for different group: ${joinRequest.groupId}")
                return
            }
            
            // Add to pending requests if not already present
            _pendingJoinRequests.update { current ->
                if (current.any { it.requestId == joinRequest.requestId }) {
                    Timber.d("Join request ${joinRequest.requestId} already pending")
                    current
                } else {
                    Timber.i("Added join request from ${joinRequest.displayName}")
                    current + joinRequest
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle join request")
        }
    }
    
    /**
     * Generates an invite code for the current family group
     */
    suspend fun generateInviteCode(): Result<String> {
        return try {
            // NOTE: Adjust this based on your GroupStateManager's actual API
            val groupDef = groupStateManager?.groupDefinition?.value
                ?: return Result.failure(IllegalStateException("No group state available"))
            
            // Create invite payload with group info
            val inviteData = mapOf(
                "groupId" to groupDef.groupId,
                "groupName" to groupDef.groupName,
                "inviterMemberId" to currentMemberId,
                "timestamp" to System.currentTimeMillis().toString()
            )
            
            val inviteJson = json.encodeToString(inviteData)
            val inviteCode = Base64.getEncoder().encodeToString(inviteJson.toByteArray())
            
            Result.success(inviteCode)
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate invite code")
            Result.failure(e)
        }
    }
    
    /**
     * Sends a join request to a family using an invite code
     */
    suspend fun sendJoinRequest(inviteCode: String, displayName: String): Result<Unit> {
        return try {
            // Decode invite code
            val inviteJson = String(Base64.getDecoder().decode(inviteCode))
            val inviteData = json.decodeFromString<Map<String, String>>(inviteJson)
            
            val groupId = inviteData["groupId"]
                ?: return Result.failure(IllegalArgumentException("Invalid invite code"))
            val inviterMemberId = inviteData["inviterMemberId"]
                ?: return Result.failure(IllegalArgumentException("Invalid invite code"))
            
            // Create join request
            val joinRequest = JoinRequest(
                requestId = UUID.randomUUID().toString(),
                requesterId = currentMemberId ?: return Result.failure(IllegalStateException("No member ID")),
                displayName = displayName,
                ed25519PublicKey = cryptoProvider.getEd25519PublicKey(),
                x25519PublicKey = cryptoProvider.getX25519PublicKey(),
                groupId = groupId,
                timestampMs = System.currentTimeMillis()
            )
            
            // Send to inviter's join request topic
            val topic = MqttConfig.getJoinRequestTopic(inviterMemberId)
            val payload = json.encodeToString(joinRequest)
            
            val message = MqttMessage(payload.toByteArray()).apply {
                qos = MqttConfig.DEFAULT_QOS
            }
            
            mqttClient?.publish(topic, message)?.waitForCompletion(5000)
            
            Timber.i("Sent join request to $inviterMemberId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send join request")
            Result.failure(e)
        }
    }
    
    /**
     * Approves a pending join request and adds the member to the group
     */
    suspend fun approveJoinRequest(request: JoinRequest): Result<Unit> {
        return try {
            val stateManager = groupStateManager
                ?: return Result.failure(IllegalStateException("No group state manager"))
            
            // Create new member
            val newMember = FamilyMember(
                memberId = request.requesterId,
                displayName = request.displayName,
                ed25519PublicKey = request.ed25519PublicKey.toHexString(),
                x25519PublicKey = request.x25519PublicKey.toHexString(),
                addedAtEpochMs = System.currentTimeMillis()
            )
            
            // Add member to group — build the exact approval message GroupStateManager expects
            // and sign it with the local Ed25519 key.
            val inviterMemberId = currentMemberId ?: return Result.failure(IllegalStateException("No current member ID"))
            val currentGroup = stateManager.groupDefinition.value
                ?: return Result.failure(IllegalStateException("No group definition"))
            val approvalMessage = "ADD:${currentGroup.groupId}:${currentGroup.version}:${newMember.ed25519PublicKey}"
                .toByteArray(Charsets.UTF_8)
            val signature = cryptoProvider.signMessage(approvalMessage)
            stateManager.addMember(newMember, signature, inviterMemberId)
            
            // Remove from pending requests
            _pendingJoinRequests.update { current ->
                current.filter { it.requestId != request.requestId }
            }
            
            // Notify via sync manager
            val groupDef = stateManager.groupDefinition.value
            if (groupDef != null) {
                groupSyncManager?.broadcastGroupUpdate(groupDef, ChangeType.MEMBER_ADDED, newMember.memberId)
            }
            
            Timber.i("Approved join request from ${request.displayName}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to approve join request")
            Result.failure(e)
        }
    }
    
    /**
     * Rejects a pending join request
     */
    suspend fun rejectJoinRequest(request: JoinRequest): Result<Unit> {
        return try {
            // Remove from pending requests
            _pendingJoinRequests.update { current ->
                current.filter { it.requestId != request.requestId }
            }
            
            Timber.i("Rejected join request from ${request.displayName}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to reject join request")
            Result.failure(e)
        }
    }
    
    fun cleanup() {
        scope.cancel()
    }
    
    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
