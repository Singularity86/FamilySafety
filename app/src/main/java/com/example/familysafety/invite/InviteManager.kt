package com.example.familysafety.invite

import com.example.familysafety.core.toHex
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
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

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

    /** Replaceable for tests; the app-wide limiter is shared state. */
    internal var joinRequestLimiter: com.example.familysafety.core.RateLimiter =
        com.example.familysafety.core.RateLimiters.joinRequests

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

            val joinRequest = parseJoinRequest(payload) ?: return

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

            // New requests only, per requester (F16). A joiner re-publishes its pending
            // request every 30 seconds until answered, and those re-deliveries must keep
            // arriving. Each new request costs a signature check and a notification, so a
            // flood of them is limited. Requester IDs are self-chosen until join v2 (2.1),
            // so this slows a flood rather than stopping one.
            val alreadyPending = _pendingJoinRequests.value.any { it.requestId == joinRequest.requestId }
            if (!alreadyPending && !joinRequestLimiter.allowRequest(joinRequest.requesterId)) {
                Timber.w("Join request from ${joinRequest.requesterId.take(8)} dropped: rate limited")
                return
            }

            val verdict = RejoinPolicy.evaluate(joinRequest, currentGroup) { message, signature, keyHex ->
                cryptoProvider.verifySignature(message, signature, keyHex)
            }
            when (verdict) {
                is RejoinPolicy.Verdict.Refuse -> {
                    Timber.w("Refusing join request ${joinRequest.requestId}: ${verdict.reason}")
                    return
                }
                RejoinPolicy.Verdict.AutoApprove -> {
                    // A member's own phone, restored from its recovery phrase. It already
                    // belongs here, so nothing is being decided — send the family back.
                    Timber.i("Join request from existing member ${joinRequest.requesterId.take(8)} — restoring without a prompt")
                    approveJoinRequest(joinRequest)
                    // Nobody is asked, so say it happened: a phrase someone else learned
                    // restores exactly like this (F15).
                    val name = currentGroup.findMemberById(joinRequest.requesterId)?.displayName
                        ?: joinRequest.displayName
                    com.example.familysafety.core.IdentityAlerts.memberRestored(context, joinRequest.requesterId, name)
                    return
                }
                RejoinPolicy.Verdict.AskInviter -> Unit
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

    /**
     * A v2 request, verified against the invite it answers, or a v1 request, which can't be
     * verified and is shown to the inviter as unverified. Null when it should be dropped.
     *
     * v1 is still accepted for one release so phones that haven't updated can join; the
     * release after refuses it (F13).
     */
    private fun parseJoinRequest(payload: String): JoinRequest? {
        val v2 = runCatching { json.decodeFromString<JoinRequestV2Message>(payload) }.getOrNull()
        if (v2 == null) {
            return runCatching { json.decodeFromString<JoinRequest>(payload) }
                .onFailure { Timber.w("Undecodable join request — ignoring") }
                .getOrNull()
                ?.also { Timber.w("Join request ${it.requestId} is v1: unverified, shown with a warning") }
        }
        val now = System.currentTimeMillis()
        val result = JoinV2Verifier.verify(
            message = v2,
            now = now,
            findInvite = { inviteSecrets.find(it, now) },
            envelopeSender = { E2EEManager.peekSenderMemberId(it) },
            openEnvelope = { envelope, x25519, ed25519 ->
                e2eeManager.decryptMessage(
                    encryptedMessageJson = envelope,
                    senderX25519PublicKey = x25519,
                    senderEd25519PublicKey = ed25519
                )
            },
            verifySignature = { message, signature, keyHex ->
                cryptoProvider.verifySignature(message, signature, keyHex)
            },
            decodePayload = { json.decodeFromString<JoinRequestV2Payload>(it) }
        )
        return when (result) {
            is JoinV2Verifier.Result.Verified -> result.request
            is JoinV2Verifier.Result.Rejected -> {
                Timber.w("Refusing a v2 join request: ${result.reason}")
                null
            }
        }
    }

    private val inviteSecrets by lazy {
        InviteSecretStore(context.getSharedPreferences(InviteSecretStore.PREFS_NAME, Context.MODE_PRIVATE))
    }

    /** Answering a request, either way, uses up the invite it came through. */
    private fun useUpInvite(request: JoinRequest) {
        request.inviteId?.let { runCatching { inviteSecrets.markUsed(it, System.currentTimeMillis()) } }
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

            // Android 13+ drops notifications without the permission; check rather than rely on it.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(context)
                    .notify(NOTIFICATION_BASE_ID + request.requestId.hashCode(), notification)
            }
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
            Result.success(buildInviteCode(groupDef, currentMemberId ?: ""))
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate invite code")
            Result.failure(e)
        }
    }

    /**
     * The invite code for [groupDef], issued by [inviterMemberId]. Needs nothing from
     * [initialize], so the invite screen can build one as soon as the family has loaded
     * rather than waiting for start-up to reach this manager.
     */
    fun buildInviteCode(groupDef: GroupDefinition, inviterMemberId: String): String {
        // An invite with no inviter can't be answered: the join request has nowhere to go.
        require(inviterMemberId.isNotBlank()) { "This phone's identity hasn't loaded." }
        val inviterName = groupDef.members
            .find { it.memberId == inviterMemberId }
            ?.displayName ?: ""
        val inviteData = mapOf(
            "groupId" to groupDef.groupId,
            "groupName" to groupDef.groupName,
            "inviterMemberId" to inviterMemberId,
            "inviterName" to inviterName,
            "timestamp" to System.currentTimeMillis().toString()
        ) + v2Fields()
        val inviteJson = json.encodeToString(inviteData)
        return Base64.getEncoder().encodeToString(inviteJson.toByteArray())
    }

    /**
     * Join v2 fields: a fresh one-time secret, which never touches the broker, and the
     * inviter's keys, so the joiner can encrypt its request to us and check the keys against
     * inviterMemberId. The v1 fields above stay, so a joiner on an older build still works.
     */
    private fun v2Fields(): Map<String, String> {
        val (invite, secret) = inviteSecrets.create(System.currentTimeMillis())
        return mapOf(
            JoinV2.FIELD_VERSION to JoinV2.VERSION,
            JoinV2.FIELD_INVITE_ID to invite.inviteId,
            JoinV2.FIELD_SECRET to Base64.getEncoder().encodeToString(secret),
            JoinV2.FIELD_INVITER_ED25519 to cryptoProvider.getLocalEd25519PublicKey(),
            JoinV2.FIELD_INVITER_X25519 to cryptoProvider.getLocalX25519PublicKey(),
            JoinV2.FIELD_EXPIRES_AT to invite.expiresAt.toString()
        )
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
                JoinApprovalValidator.deriveMemberId(request.ed25519PublicKey.toHex())
            if (derivedRequesterId != request.requesterId) {
                Timber.e("InviteManager: join request member ID does not match its key — rejecting")
                return Result.failure(IllegalArgumentException("Join request ID/key mismatch"))
            }

            val newMember = FamilyMember(
                memberId = request.requesterId,
                displayName = request.displayName,
                ed25519PublicKey = request.ed25519PublicKey.toHex(),
                x25519PublicKey = request.x25519PublicKey.toHex(),
                addedAtEpochMs = System.currentTimeMillis()
            )

            val inviterMemberId = currentMemberId
                ?: return Result.failure(IllegalStateException("No current member ID"))
            val currentGroup = stateManager.groupDefinition.value
                ?: return Result.failure(IllegalStateException("No group definition"))

            // Checked here as well as on arrival: a request can sit in the pending list
            // from before this check existed, and approving is the step that matters.
            RejoinPolicy.keyConflict(request, currentGroup)?.let { reason ->
                Timber.e("InviteManager: refusing to approve ${request.requesterId.take(8)} — $reason")
                return Result.failure(IllegalArgumentException("Join request keys conflict: $reason"))
            }

            // Signs both keys and the name, not just the signing key: what is being approved
            // is the whole identity the inviter saw. Checked only by this phone's addMember.
            val approvalMessage = GroupStateManager.memberAdditionApprovalMessage(
                currentGroup.groupId, currentGroup.version, newMember
            )
            val signature = cryptoProvider.signMessage(approvalMessage)
            useUpInvite(request)

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
                // Never a rotated key to anyone the state doesn't list as a member (3.3).
                val groupDefJson = json.encodeToString(
                    com.example.familysafety.group.GroupKeys.forRecipient(groupDefForApproval, request.requesterId)
                )
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
            useUpInvite(request)
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
