package com.example.familysafety.invite

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.familysafety.R
import com.example.familysafety.group.*
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.sync.ChangeType
import com.example.familysafety.sync.GroupSyncManager
import com.example.familysafety.transport.MqttConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import timber.log.Timber
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InviteManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoProvider: LazysodiumCryptoProvider,
    private val e2eeManager: E2EEManager,
    private val transportProvider: com.example.familysafety.transport.TransportProvider
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var groupStateManager: GroupStateManager? = null
    private var groupSyncManager: GroupSyncManager? = null
    private var currentMemberId: String? = null

    private val _pendingJoinRequests = MutableStateFlow<List<JoinRequest>>(emptyList())
    val pendingJoinRequests: StateFlow<List<JoinRequest>> = _pendingJoinRequests.asStateFlow()
    private val closedJoinRequestIds = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val CHANNEL_ID = "join_requests"
        private const val NOTIFICATION_BASE_ID = 2000
        private const val PREFS = "familysafety_prefs"
        private const val KEY_CLOSED_JOIN_REQUEST_IDS = "closed_join_request_ids"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Join Requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when someone wants to join your family group"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
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
            // Clearing a retained message means publishing an empty payload to the topic,
            // so every peer sees the deletion as an inbound message. It is a normal part of
            // the join cleanup, not a malformed request — parsing it threw and logged a
            // full stack trace on every device each time a join completed.
            if (payload.isBlank()) {
                Timber.d("Empty join request payload (retained clear) — ignoring")
                return
            }

            Timber.d("Received join request payload")

            val joinRequest = json.decodeFromString<JoinRequest>(payload)

            if (isJoinRequestClosed(joinRequest.requestId)) {
                Timber.d("Join request ${joinRequest.requestId} already closed, ignoring redelivery")
                return
            }

            // Verify the request is for our current group
            val currentGroup = groupStateManager?.groupDefinition?.value
            if (currentGroup?.groupId != joinRequest.groupId) {
                Timber.w("Join request for different group: ${joinRequest.groupId}")
                return
            }

            // Add to pending requests only if not already present, and track whether it was new
            var isNew = false
            _pendingJoinRequests.update { current ->
                if (current.any { it.requestId == joinRequest.requestId }) {
                    Timber.d("Join request ${joinRequest.requestId} already pending, skipping notification")
                    current
                } else {
                    isNew = true
                    Timber.i("Added join request from ${joinRequest.displayName}")
                    current + joinRequest
                }
            }

            // Only notify for genuinely new requests — not MQTT re-deliveries
            if (isNew) {
                sendJoinRequestNotification(joinRequest)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle join request")
        }
    }

    private fun sendJoinRequestNotification(request: JoinRequest) {
        try {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    // The invite screen lists pending requests with approve/reject
                    // at the top, so the tap lands directly on the decision.
                    putExtra("navigate_to", "invite")
                }
            val pendingIntent = launchIntent?.let {
                PendingIntent.getActivity(
                    context,
                    request.requestId.hashCode(),
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Join request")
                .setContentText("${request.displayName} wants to join your family group")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
                .build()

            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_BASE_ID + request.requestId.hashCode(), notification)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send join request notification")
        }
    }

    /**
     * Generates an invite code for the current family group.
     */
    suspend fun generateInviteCode(): Result<String> {
        return try {
            val groupDef = groupStateManager?.groupDefinition?.value
                ?: return Result.failure(IllegalStateException("No group state available"))

            val inviterName = groupDef.members
                .find { it.memberId == currentMemberId }
                ?.displayName ?: ""
            val inviteData = mapOf(
                "groupId" to groupDef.groupId,
                "groupName" to groupDef.groupName,
                "inviterMemberId" to (currentMemberId ?: ""),
                "inviterName" to inviterName,
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
        // Dismiss immediately so the notification never gets stuck, even if approval fails.
        _pendingJoinRequests.update { current -> current.filter { it.requestId != request.requestId } }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_BASE_ID + request.requestId.hashCode())

        return try {
            val stateManager = groupStateManager
                ?: return Result.failure(IllegalStateException("No group state manager"))

            // The requester's claimed member ID must be the hash of the key they sent,
            // otherwise the roster entry would be internally inconsistent (memberId is
            // used for topic routing while the key is used for crypto).
            val derivedRequesterId =
                JoinApprovalValidator.deriveMemberId(request.ed25519PublicKey.toHexString())
            if (derivedRequesterId != request.requesterId) {
                Timber.e("InviteManager: join request member ID does not match its key — rejecting")
                return Result.failure(IllegalArgumentException("Join request ID/key mismatch"))
            }

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

            // Clear the retained join_request from the broker so it doesn't re-fire on reconnect
            val requestTopic = MqttConfig.getJoinRequestTopic(inviterMemberId)
            // Use broadcast with empty payload to clear retained message on MQTT (if reachable)
            transportProvider.broadcastMessage(
                topic = requestTopic,
                payload = ByteArray(0),
                qos = MqttConfig.DEFAULT_QOS,
                retained = true
            )
            Timber.i("InviteManager: cleared retained join_request from $requestTopic")

            Timber.i("InviteManager: calling addMember for ${request.displayName} (${request.requesterId.take(8)})")
            val addResult = stateManager.addMember(newMember, signature, inviterMemberId)
            val memberAlreadyExisted = addResult is GroupOperationResult.Failure &&
                    addResult.error is GroupError.MemberAlreadyExists
            if (addResult is GroupOperationResult.Failure && !memberAlreadyExisted) {
                Timber.e("InviteManager: addMember failed — ${addResult.error}")
                return Result.failure(IllegalStateException("addMember failed: ${addResult.error}"))
            }
            if (memberAlreadyExisted) {
                Timber.w("InviteManager: member already exists — re-sending approval in case joiner missed it")
            } else {
                Timber.i("InviteManager: addMember succeeded")
                scope.launch {
                    groupSyncManager?.requestGroupStateRefresh(
                        reason = "member_added",
                        minimumVersion = stateManager.groupDefinition.value?.version,
                        changedMemberId = newMember.memberId
                    )
                }
            }

            // Send approval immediately — before broadcastGroupUpdate which can block for
            // up to 30 s waiting for acks, causing the UI to show the approve button too long.
            val groupDefForApproval = stateManager.groupDefinition.value
            if (groupDefForApproval != null) {
                val approvalTopic = MqttConfig.getJoinApprovalTopic(request.requesterId)
                val groupDefJson = json.encodeToString(groupDefForApproval)
                // Encrypt the roster to the joiner's X25519 key and sign with our Ed25519
                // key (inside encryptMessage). The joiner authenticates our Ed25519 key by
                // hashing it against the inviterMemberId from the QR invite they scanned —
                // a plaintext approval on a public broker would let anyone forge a "family".
                val approval = JoinApprovalMessage(
                    inviterEd25519PublicKey = cryptoProvider.getLocalEd25519PublicKey(),
                    inviterX25519PublicKey = cryptoProvider.getLocalX25519PublicKey(),
                    encryptedGroupDefinition = e2eeManager.encryptMessage(
                        plaintext = groupDefJson,
                        recipientMemberId = request.requesterId,
                        recipientX25519PublicKey = request.x25519PublicKey
                    )
                )
                val approvalPayload = json.encodeToString(approval).toByteArray(Charsets.UTF_8)
                Timber.i("InviteManager: publishing encrypted approval to $approvalTopic (${approvalPayload.size} bytes, retained=true)")
                val published = transportProvider.sendMessage(
                    recipientId = request.requesterId,
                    topic = approvalTopic,
                    payload = approvalPayload,
                    qos = MqttConfig.DEFAULT_QOS,
                    retained = true
                )
                if (published) {
                    Timber.i("InviteManager: approval published successfully")
                    markJoinRequestClosed(request.requestId)
                } else {
                    Timber.w("InviteManager: approval queued for delivery on reconnect")
                }
            } else {
                Timber.e("InviteManager: groupDef is null after addMember — cannot send approval")
            }

            // Broadcast updated group state to existing members (fire-and-forget — can take up to 30 s).
            if (!memberAlreadyExisted) {
                val updatedGroupDef = stateManager.groupDefinition.value
                if (updatedGroupDef != null) {
                    scope.launch {
                        groupSyncManager?.broadcastGroupUpdate(
                            updatedGroupDef, ChangeType.MEMBER_ADDED, newMember.memberId
                        )
                    }
                }
            }

            Timber.i("Approved join request from ${request.displayName}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to approve join request")
            Result.failure(e)
        }
    }

    /**
     * Rejects a pending join request and notifies the joiner, so they don't wait
     * on the pending screen (re-publishing their request every 30 s) forever.
     */
    suspend fun rejectJoinRequest(request: JoinRequest): Result<Unit> {
        return try {
            markJoinRequestClosed(request.requestId)
            _pendingJoinRequests.update { current ->
                current.filter { it.requestId != request.requestId }
            }
            NotificationManagerCompat.from(context)
                .cancel(NOTIFICATION_BASE_ID + request.requestId.hashCode())
            // Clear the retained join_request from the broker
            val inviterMemberId = currentMemberId
            if (inviterMemberId != null) {
                val requestTopic = MqttConfig.getJoinRequestTopic(inviterMemberId)
                transportProvider.broadcastMessage(
                    topic = requestTopic,
                    payload = ByteArray(0),
                    qos = MqttConfig.DEFAULT_QOS,
                    retained = true
                )
            }

            // Tell the joiner. Encrypted + signed like the approval; retained so an
            // offline joiner still learns on reconnect. Best effort — local rejection
            // state is already cleared either way.
            try {
                val rejectionPayload = json.encodeToString(
                    JoinRejectionPayload(
                        requestId = request.requestId,
                        joinerMemberId = request.requesterId,
                        timestamp = System.currentTimeMillis()
                    )
                )
                val rejection = JoinRejectionMessage(
                    inviterEd25519PublicKey = cryptoProvider.getLocalEd25519PublicKey(),
                    inviterX25519PublicKey = cryptoProvider.getLocalX25519PublicKey(),
                    encryptedRejection = e2eeManager.encryptMessage(
                        plaintext = rejectionPayload,
                        recipientMemberId = request.requesterId,
                        recipientX25519PublicKey = request.x25519PublicKey
                    )
                )
                transportProvider.sendMessage(
                    recipientId = request.requesterId,
                    topic = MqttConfig.getJoinApprovalTopic(request.requesterId),
                    payload = json.encodeToString(rejection).toByteArray(Charsets.UTF_8),
                    qos = MqttConfig.DEFAULT_QOS,
                    retained = true
                )
                // The requester never became a member — drop their cached shared secret.
                e2eeManager.removeSharedSecret(request.requesterId)
            } catch (e: Exception) {
                Timber.w(e, "Failed to notify joiner of rejection")
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

    private fun isJoinRequestClosed(requestId: String): Boolean {
        if (closedJoinRequestIds.contains(requestId)) return true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_CLOSED_JOIN_REQUEST_IDS, emptySet()) ?: emptySet()
        if (stored.contains(requestId)) {
            closedJoinRequestIds.add(requestId)
            return true
        }
        return false
    }

    private fun markJoinRequestClosed(requestId: String) {
        closedJoinRequestIds.add(requestId)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_CLOSED_JOIN_REQUEST_IDS, emptySet())?.toMutableSet()
            ?: mutableSetOf()
        stored.add(requestId)
        if (stored.size > 200) {
            stored.take(stored.size - 200).forEach { stored.remove(it) }
        }
        prefs.edit().putStringSet(KEY_CLOSED_JOIN_REQUEST_IDS, stored).apply()
    }

}
