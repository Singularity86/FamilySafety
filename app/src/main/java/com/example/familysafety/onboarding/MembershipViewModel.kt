package com.example.familysafety.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.AndroidKeyStoreLocalKeyStore
import com.example.familysafety.group.EncryptedGroupStatePersistence
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.MembershipState
import com.example.familysafety.invite.JoinApprovalMessage
import com.example.familysafety.invite.JoinApprovalValidator
import com.example.familysafety.invite.JoinRejectionMessage
import com.example.familysafety.invite.JoinRejectionPayload
import com.example.familysafety.invite.JoinRequest
import com.example.familysafety.transport.MqttConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MembershipViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val e2eeManager: E2EEManager
) : ViewModel() {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _membershipState = MutableStateFlow(readInitialState())
    val membershipState: StateFlow<MembershipState> = _membershipState.asStateFlow()

    // Emits once when the joiner is approved — MainActivity collects this to trigger restart.
    private val _approvedEvent = MutableSharedFlow<Unit>(replay = 0)
    val approvedEvent: SharedFlow<Unit> = _approvedEvent.asSharedFlow()

    private var listenerJob: Job? = null

    init {
        if (_membershipState.value is MembershipState.PendingApproval) {
            resumeApprovalListener()
        }
    }

    private fun readInitialState(): MembershipState {
        if (prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)) {
            return try {
                if (AndroidKeyStoreLocalKeyStore(context).isInitialized()) {
                    MembershipState.Approved
                } else {
                    prefs.edit().remove(KEY_ONBOARDING_COMPLETE).apply()
                    MembershipState.Unauthenticated
                }
            } catch (e: Exception) {
                prefs.edit().remove(KEY_ONBOARDING_COMPLETE).apply()
                MembershipState.Unauthenticated
            }
        }
        val familyName = prefs.getString(KEY_PENDING_FAMILY_NAME, null)
        val inviterName = prefs.getString(KEY_PENDING_INVITER_NAME, null)
        return if (familyName != null) {
            MembershipState.PendingApproval(
                invitedByName = inviterName ?: "",
                familyName = familyName
            )
        } else {
            MembershipState.Unauthenticated
        }
    }

    fun setPendingApproval(
        familyName: String,
        inviterName: String,
        memberId: String,
        inviterMemberId: String,
        groupId: String,
        joinRequestJson: String
    ) {
        prefs.edit()
            .putString(KEY_PENDING_FAMILY_NAME, familyName)
            .putString(KEY_PENDING_INVITER_NAME, inviterName)
            .putString(KEY_PENDING_MY_MEMBER_ID, memberId)
            .putString(KEY_PENDING_INVITER_MEMBER_ID, inviterMemberId)
            .putString(KEY_PENDING_GROUP_ID, groupId)
            .putString(KEY_PENDING_JOIN_REQUEST_JSON, joinRequestJson)
            .apply()
        _membershipState.value = MembershipState.PendingApproval(inviterName, familyName)
        resumeApprovalListener()
    }

    /** Called by ApprovedScreen (button tap or auto-delay) to trigger the process restart. */
    fun confirmRestart() {
        viewModelScope.launch { _approvedEvent.emit(Unit) }
    }

    fun cancelPending() {
        listenerJob?.cancel()
        clearPendingPrefs()
        _membershipState.value = MembershipState.Unauthenticated
    }

    /** Called from RejectedScreen once the user has seen the notice. */
    fun acknowledgeRejection() {
        _membershipState.value = MembershipState.Unauthenticated
    }

    private fun clearPendingPrefs() {
        prefs.edit()
            .remove(KEY_PENDING_FAMILY_NAME)
            .remove(KEY_PENDING_INVITER_NAME)
            .remove(KEY_PENDING_MY_MEMBER_ID)
            .remove(KEY_PENDING_INVITER_MEMBER_ID)
            .remove(KEY_PENDING_GROUP_ID)
            .remove(KEY_PENDING_JOIN_REQUEST_JSON)
            .apply()
    }

    private fun resumeApprovalListener() {
        val memberId = prefs.getString(KEY_PENDING_MY_MEMBER_ID, null) ?: return
        val inviterMemberId = prefs.getString(KEY_PENDING_INVITER_MEMBER_ID, null) ?: return
        val groupId = prefs.getString(KEY_PENDING_GROUP_ID, null) ?: return
        val joinRequestJson = prefs.getString(KEY_PENDING_JOIN_REQUEST_JSON, null) ?: return

        listenerJob?.cancel()
        listenerJob = viewModelScope.launch {
            listenForApproval(memberId, inviterMemberId, groupId, joinRequestJson)
        }
    }

    /**
     * Decrypt and authenticate an approval payload. The only trust anchor a
     * pending joiner has is the QR invite it scanned: [expectedInviterMemberId]
     * is the SHA-256 hash of the inviter's Ed25519 key, so a key that hashes to
     * it — and a signature that verifies under it — proves the approval came
     * from the person whose code we scanned. Returns null (and keeps listening)
     * for anything that fails: on a public broker, arbitrary parties can write
     * to our approval topic.
     */
    private fun verifyApproval(
        payload: String,
        myMemberId: String,
        expectedInviterMemberId: String,
        expectedGroupId: String
    ): GroupDefinition? {
        val approval = try {
            json.decodeFromString<JoinApprovalMessage>(payload)
        } catch (e: Exception) {
            Timber.w("Ignoring approval payload that is not a JoinApprovalMessage")
            return null
        }

        // Anchor check before doing any crypto: the signing key must hash to the
        // member ID embedded in the QR invite.
        if (JoinApprovalValidator.deriveMemberId(approval.inviterEd25519PublicKey)
            != expectedInviterMemberId
        ) {
            Timber.w("Ignoring approval whose signing key does not match the scanned invite")
            return null
        }

        val groupDefJson = try {
            e2eeManager.decryptMessage(
                encryptedMessageJson = approval.encryptedGroupDefinition,
                senderX25519PublicKey = approval.inviterX25519PublicKey.hexToBytes(),
                senderEd25519PublicKey = approval.inviterEd25519PublicKey.hexToBytes()
            )
        } catch (e: Exception) {
            Timber.w(e, "Ignoring approval that failed decryption/signature verification")
            return null
        }

        val groupDef = try {
            json.decodeFromString<GroupDefinition>(groupDefJson)
        } catch (e: Exception) {
            Timber.w(e, "Ignoring approval with undecodable group definition")
            return null
        }

        val rejection = JoinApprovalValidator.validate(
            groupDefinition = groupDef,
            expectedGroupId = expectedGroupId,
            expectedInviterMemberId = expectedInviterMemberId,
            inviterEd25519PublicKeyHex = approval.inviterEd25519PublicKey,
            myMemberId = myMemberId
        )
        if (rejection != null) {
            Timber.w("Ignoring approval: $rejection")
            return null
        }
        return groupDef
    }

    /**
     * Decrypt and authenticate a rejection. Same trust anchor as the approval:
     * the signing key must hash to the scanned invite's inviterMemberId, and the
     * inner payload must name OUR pending request — a replayed rejection cannot
     * cancel a different (future) join attempt.
     */
    private fun verifyRejection(
        payload: String,
        myMemberId: String,
        expectedInviterMemberId: String,
        expectedRequestId: String?
    ): Boolean {
        if (expectedRequestId == null) return false
        val rejection = try {
            json.decodeFromString<JoinRejectionMessage>(payload)
        } catch (e: Exception) {
            return false
        }

        if (JoinApprovalValidator.deriveMemberId(rejection.inviterEd25519PublicKey)
            != expectedInviterMemberId
        ) {
            Timber.w("Ignoring rejection whose signing key does not match the scanned invite")
            return false
        }

        val rejectionJson = try {
            e2eeManager.decryptMessage(
                encryptedMessageJson = rejection.encryptedRejection,
                senderX25519PublicKey = rejection.inviterX25519PublicKey.hexToBytes(),
                senderEd25519PublicKey = rejection.inviterEd25519PublicKey.hexToBytes()
            )
        } catch (e: Exception) {
            Timber.w(e, "Ignoring rejection that failed decryption/signature verification")
            return false
        }

        val rejectionPayload = try {
            json.decodeFromString<JoinRejectionPayload>(rejectionJson)
        } catch (e: Exception) {
            return false
        }
        if (rejectionPayload.requestId != expectedRequestId ||
            rejectionPayload.joinerMemberId != myMemberId
        ) {
            Timber.w("Ignoring rejection for a different request or joiner")
            return false
        }
        return true
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private suspend fun listenForApproval(
        memberId: String,
        inviterMemberId: String,
        groupId: String,
        joinRequestJson: String
    ) {
        withContext(Dispatchers.IO) {
            val clientId = "familysafe_pending_${memberId.take(8)}_${System.currentTimeMillis()}"
            val mqttClient = MqttAsyncClient(MqttConfig.BROKER_URL, clientId, MemoryPersistence())
            // Completes with the group definition on approval, or null on rejection.
            val approvalDeferred = CompletableDeferred<GroupDefinition?>()
            val pendingRequestId = try {
                json.decodeFromString<JoinRequest>(joinRequestJson).requestId
            } catch (e: Exception) {
                Timber.w(e, "Could not parse pending join request — rejection matching disabled")
                null
            }

            mqttClient.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Timber.w("Pending approval MQTT lost: ${cause?.message}")
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic?.endsWith("/join_approval") == true) {
                        try {
                            val payload = String(message!!.payload, Charsets.UTF_8)
                            if (payload.isEmpty()) return // retained-clear tombstone
                            val groupDef = verifyApproval(
                                payload = payload,
                                myMemberId = memberId,
                                expectedInviterMemberId = inviterMemberId,
                                expectedGroupId = groupId
                            )
                            if (groupDef != null) {
                                Timber.i("Verified approval received — group=${groupDef.groupName}")
                                approvalDeferred.complete(groupDef)
                            } else if (verifyRejection(payload, memberId, inviterMemberId, pendingRequestId)) {
                                Timber.i("Verified rejection received")
                                approvalDeferred.complete(null)
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to process approval message")
                        }
                    }
                }
            })

            try {
                val options = MqttConnectOptions().apply {
                    // Clean session: the client ID is unique per listen, so a
                    // persistent session would never be resumed — it would only
                    // litter the broker. The approval/rejection are retained
                    // messages, so nothing is lost between listens.
                    isCleanSession = true
                    connectionTimeout = 30
                    keepAliveInterval = 30
                    isAutomaticReconnect = true

                    // Broker authentication (absent = anonymous)
                    val broker = com.example.familysafety.transport.BrokerConfig.getCurrentBroker()
                    broker.username?.let { userName = it }
                    broker.password?.let { password = it.toCharArray() }
                }
                mqttClient.connect(options).waitForCompletion(30_000)

                val approvalTopic = MqttConfig.getJoinApprovalTopic(memberId)
                mqttClient.subscribe(approvalTopic, MqttConfig.DEFAULT_QOS).waitForCompletion(5_000)
                Timber.i("Pending approval listener: subscribed to $approvalTopic")

                // Re-publish the join request every 30 s so the inviter always has it,
                // even if their MQTT connection briefly dropped.
                val requestTopic = MqttConfig.getJoinRequestTopic(inviterMemberId)
                val republishJob = launch {
                    while (isActive && !approvalDeferred.isCompleted) {
                        try {
                            val msg = MqttMessage(joinRequestJson.toByteArray(Charsets.UTF_8)).apply {
                                qos = MqttConfig.DEFAULT_QOS
                                isRetained = true
                            }
                            mqttClient.publish(requestTopic, msg).waitForCompletion(5_000)
                            Timber.d("Re-published join request")
                        } catch (e: Exception) {
                            Timber.w("Re-publish failed: ${e.message}")
                        }
                        delay(30_000)
                    }
                }

                val groupDefinition = approvalDeferred.await()
                republishJob.cancel()

                if (groupDefinition != null) {
                    val persistence = EncryptedGroupStatePersistence.getInstance(context)
                    persistence.saveGroupDefinition(groupDefinition)
                    prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).commit()
                    clearPendingPrefs()
                    // Show the approval confirmation screen first; restart fires when the
                    // user taps "Open Now" or after a 3-second auto-delay (confirmRestart()).
                    _membershipState.value = MembershipState.ApprovalReceived(groupDefinition.groupName)
                    Timber.i("Onboarding complete — showing approval screen before restart")
                } else {
                    // Rejected. Clear our retained join request (the republish loop may
                    // have re-retained it after the inviter cleared it) and the retained
                    // rejection itself, so neither lingers on the broker.
                    try {
                        val clear = { t: String ->
                            mqttClient.publish(
                                t,
                                MqttMessage(ByteArray(0)).apply {
                                    qos = MqttConfig.DEFAULT_QOS
                                    isRetained = true
                                }
                            ).waitForCompletion(3_000)
                        }
                        clear(requestTopic)
                        clear(approvalTopic)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to clear retained messages after rejection")
                    }
                    val familyName = prefs.getString(KEY_PENDING_FAMILY_NAME, null) ?: ""
                    clearPendingPrefs()
                    _membershipState.value = MembershipState.Rejected(familyName)
                    Timber.i("Join request was declined — returning to onboarding")
                }
            } catch (e: Exception) {
                Timber.e(e, "Approval listener error")
            } finally {
                try { mqttClient.disconnect().waitForCompletion(3_000) } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private const val PREFS = "familysafety_prefs"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_PENDING_FAMILY_NAME = "pending_family_name"
        private const val KEY_PENDING_INVITER_NAME = "pending_inviter_name"
        private const val KEY_PENDING_MY_MEMBER_ID = "pending_my_member_id"
        private const val KEY_PENDING_INVITER_MEMBER_ID = "pending_inviter_member_id"
        private const val KEY_PENDING_GROUP_ID = "pending_group_id"
        private const val KEY_PENDING_JOIN_REQUEST_JSON = "pending_join_request_json"
    }
}
