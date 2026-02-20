package com.example.familysafety.invite

import com.example.familysafety.group.*
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.sync.ChangeType
import com.example.familysafety.sync.GroupSyncManager
import com.example.familysafety.transport.MqttConfig
import com.example.familysafety.transport.MqttTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
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

    private var mqttTransport: MqttTransport? = null
    private var groupStateManager: GroupStateManager? = null
    private var groupSyncManager: GroupSyncManager? = null
    private var currentMemberId: String? = null

    private val _pendingJoinRequests = MutableStateFlow<List<JoinRequest>>(emptyList())
    val pendingJoinRequests: StateFlow<List<JoinRequest>> = _pendingJoinRequests.asStateFlow()

    /**
     * Wire up MqttTransport for publishing join approvals back to the joiner.
     * Must be called before initialize() to avoid circular dependency.
     */
    fun setMqttTransport(transport: MqttTransport) {
        this.mqttTransport = transport
        Timber.d("MqttTransport wired up to InviteManager")
    }

    /**
     * Initialize InviteManager with references it needs for processing join requests.
     * MqttTransport handles topic subscription; we only need the state references here.
     */
    fun initialize(
        memberId: String,
        groupStateManager: GroupStateManager,
        groupSyncManager: GroupSyncManager
    ) {
        this.currentMemberId = memberId
        this.groupStateManager = groupStateManager
        this.groupSyncManager = groupSyncManager
        Timber.i("InviteManager initialized for member $memberId")
    }

    /**
     * Called by MqttTransport when a join_request message arrives on our inbox topic.
     * Public so MqttTransport can call it without InviteManager owning the MQTT callback.
     */
    suspend fun handleIncomingJoinRequest(payload: String) {
        try {
            Timber.d("Received join request payload")

            val joinRequest = json.decodeFromString<JoinRequest>(payload)

            // Verify the request is for our current group
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
     * Generates an invite code for the current family group.
     */
    suspend fun generateInviteCode(): Result<String> {
        return try {
            val groupDef = groupStateManager?.groupDefinition?.value
                ?: return Result.failure(IllegalStateException("No group state available"))

            val inviteData = mapOf(
                "groupId" to groupDef.groupId,
                "groupName" to groupDef.groupName,
                "inviterMemberId" to (currentMemberId ?: ""),
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
     * Approves a pending join request: adds the member to the group, broadcasts the
     * updated group state, and sends the full GroupDefinition directly to the joiner
     * so they can complete onboarding without waiting for a general sync.
     */
    suspend fun approveJoinRequest(request: JoinRequest): Result<Unit> {
        return try {
            val stateManager = groupStateManager
                ?: return Result.failure(IllegalStateException("No group state manager"))

            val newMember = FamilyMember(
                memberId = request.requesterId,
                displayName = request.displayName,
                ed25519PublicKey = request.ed25519PublicKey.toHexString(),
                x25519PublicKey = request.x25519PublicKey.toHexString(),
                addedAtEpochMs = System.currentTimeMillis()
            )

            val inviterMemberId = currentMemberId
                ?: return Result.failure(IllegalStateException("No current member ID"))
            val currentGroup = stateManager.groupDefinition.value
                ?: return Result.failure(IllegalStateException("No group definition"))

            val approvalMessage = "ADD:${currentGroup.groupId}:${currentGroup.version}:${newMember.ed25519PublicKey}"
                .toByteArray(Charsets.UTF_8)
            val signature = cryptoProvider.signMessage(approvalMessage)
            stateManager.addMember(newMember, signature, inviterMemberId)

            // Remove from pending
            _pendingJoinRequests.update { current ->
                current.filter { it.requestId != request.requestId }
            }

            // Broadcast updated group state to existing members
            val updatedGroupDef = stateManager.groupDefinition.value
            if (updatedGroupDef != null) {
                groupSyncManager?.broadcastGroupUpdate(
                    updatedGroupDef, ChangeType.MEMBER_ADDED, newMember.memberId
                )

                // Send group definition directly to the joiner's join_approval topic
                // so they can complete onboarding immediately.
                val approvalTopic = MqttConfig.getJoinApprovalTopic(request.requesterId)
                val groupDefJson = json.encodeToString(updatedGroupDef)
                mqttTransport?.publishRaw(
                    topic = approvalTopic,
                    payload = groupDefJson.toByteArray(Charsets.UTF_8),
                    qos = MqttConfig.DEFAULT_QOS
                )
                Timber.i("Sent group definition to joiner at $approvalTopic")
            }

            Timber.i("Approved join request from ${request.displayName}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to approve join request")
            Result.failure(e)
        }
    }

    /**
     * Rejects a pending join request.
     */
    suspend fun rejectJoinRequest(request: JoinRequest): Result<Unit> {
        return try {
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
}
