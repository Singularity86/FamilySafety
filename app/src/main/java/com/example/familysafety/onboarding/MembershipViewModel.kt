package com.example.familysafety.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.familysafety.group.AndroidKeyStoreLocalKeyStore
import com.example.familysafety.group.EncryptedGroupStatePersistence
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.MembershipState
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
    @ApplicationContext private val context: Context
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
        val joinRequestJson = prefs.getString(KEY_PENDING_JOIN_REQUEST_JSON, null) ?: return

        listenerJob?.cancel()
        listenerJob = viewModelScope.launch {
            listenForApproval(memberId, inviterMemberId, joinRequestJson)
        }
    }

    private suspend fun listenForApproval(
        memberId: String,
        inviterMemberId: String,
        joinRequestJson: String
    ) {
        withContext(Dispatchers.IO) {
            val clientId = "familysafe_pending_${memberId.take(8)}_${System.currentTimeMillis()}"
            val mqttClient = MqttAsyncClient(MqttConfig.BROKER_URL, clientId, MemoryPersistence())
            val approvalDeferred = CompletableDeferred<GroupDefinition?>()

            mqttClient.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Timber.w("Pending approval MQTT lost: ${cause?.message}")
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic?.endsWith("/join_approval") == true) {
                        try {
                            val groupDef = json.decodeFromString<GroupDefinition>(
                                String(message!!.payload, Charsets.UTF_8)
                            )
                            Timber.i("Approval received — group=${groupDef.groupName}")
                            approvalDeferred.complete(groupDef)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to decode approval message")
                        }
                    }
                }
            })

            try {
                val options = MqttConnectOptions().apply {
                    isCleanSession = false
                    connectionTimeout = 30
                    keepAliveInterval = 30
                    isAutomaticReconnect = true
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
