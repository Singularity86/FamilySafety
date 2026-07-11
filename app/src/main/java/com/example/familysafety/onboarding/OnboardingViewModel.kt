package com.example.familysafety.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.familysafety.group.*
import com.example.familysafety.invite.JoinRequest
import com.example.familysafety.transport.BrokerConfig
import com.example.familysafety.transport.MqttConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import timber.log.Timber
import java.util.*
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _mnemonic = MutableStateFlow<List<String>>(emptyList())
    val mnemonic: StateFlow<List<String>> = _mnemonic.asStateFlow()

    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _familyName = MutableStateFlow("")
    val familyName: StateFlow<String> = _familyName.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun generateMnemonic() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                Bip39.initialize(context)
                val words = Bip39.generate12WordMnemonic()
                _mnemonic.value = words
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setMnemonic(words: List<String>) {
        _mnemonic.value = words
    }

    fun setDisplayName(name: String) {
        _displayName.value = name
    }

    fun setFamilyName(name: String) {
        _familyName.value = name
    }

    suspend fun initializeKeys(): Boolean {
        return try {
            _isLoading.value = true

            val mnemonicString = _mnemonic.value.joinToString(" ")
            val seed = Bip39.mnemonicToSeed(mnemonicString)

            val keyStore = AndroidKeyStoreLocalKeyStore(context)
            // Use the keyStore's initializeFromSeed method which handles key derivation
            keyStore.initializeFromSeed(seed, accountIndex = 0)
            // Persist the recovery phrase so it can be shown from Settings.
            keyStore.storeMnemonic(_mnemonic.value)

            true
        } catch (e: Exception) {
            false
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun createFamily(): Boolean {
        return try {
            _isLoading.value = true

            val keyStore = AndroidKeyStoreLocalKeyStore(context)
            val cryptoProvider = LazysodiumCryptoProvider(keyStore)

            val memberId = cryptoProvider.getMemberId()

            // Create the local member first
            // Use getLocal*PublicKey() methods which return hex-encoded Strings
            val localMember = FamilyMember(
                memberId = memberId,
                displayName = _displayName.value,
                ed25519PublicKey = cryptoProvider.getLocalEd25519PublicKey(),
                x25519PublicKey = cryptoProvider.getLocalX25519PublicKey(),
                addedAtEpochMs = System.currentTimeMillis()
            )

            // Get persistence singleton
            val persistence = EncryptedGroupStatePersistence.getInstance(context)

            val groupStateManager = GroupStateManager(
                localMemberId = memberId,
                persistence = persistence,
                cryptoProvider = cryptoProvider
            )

            // Use the correct createGroup method signature
            val result = groupStateManager.createGroup(
                groupName = _familyName.value,
                localMember = localMember
            )

            when (result) {
                is GroupOperationResult.Success -> {
                    saveOnboardingComplete()
                    true
                }
                is GroupOperationResult.Failure -> {
                    false
                }
            }
        } catch (e: Exception) {
            false
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Submits a join request and returns immediately — does NOT wait for approval.
     * The caller should write [JoinSubmitResult.Submitted] data into [MembershipViewModel]
     * to transition to the pending screen, where a background listener awaits approval.
     */
    suspend fun sendJoinRequest(inviteCode: String): JoinSubmitResult {
        return try {
            _isLoading.value = true

            if (inviteCode.isBlank()) {
                return JoinSubmitResult.Failed("Invite code is empty.")
            }

            // 1. Initialize keys from mnemonic
            val mnemonicString = _mnemonic.value.joinToString(" ")
            val seed = Bip39.mnemonicToSeed(mnemonicString)
            val keyStore = AndroidKeyStoreLocalKeyStore(context)
            keyStore.initializeFromSeed(seed, accountIndex = 0)
            keyStore.storeMnemonic(_mnemonic.value)

            val cryptoProvider = LazysodiumCryptoProvider(keyStore)
            val memberId = cryptoProvider.getMemberId()

            // 2. Decode invite code
            val inviteJson = String(Base64.getDecoder().decode(inviteCode))
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val inviteData = json.decodeFromString<Map<String, String>>(inviteJson)
            val groupId = inviteData["groupId"]
                ?: return JoinSubmitResult.Failed("Invalid invite code — missing groupId")
            val inviterMemberId = inviteData["inviterMemberId"]
                ?: return JoinSubmitResult.Failed("Invalid invite code — missing inviterMemberId")
            val familyName = inviteData["groupName"] ?: "your family"
            val inviterName = inviteData["inviterName"] ?: ""

            // 3. Build the join request
            val joinRequest = JoinRequest(
                requestId = UUID.randomUUID().toString(),
                requesterId = memberId,
                displayName = _displayName.value,
                ed25519PublicKey = cryptoProvider.getEd25519PublicKey(),
                x25519PublicKey = cryptoProvider.getX25519PublicKey(),
                groupId = groupId,
                timestampMs = System.currentTimeMillis()
            )
            val joinRequestJson = json.encodeToString(joinRequest)

            // 4. Connect, subscribe, and publish the join request — then return immediately.
            val clientId = "familysafe_join_${memberId.take(8)}_${System.currentTimeMillis()}"
            val mqttClient = MqttAsyncClient(MqttConfig.BROKER_URL, clientId, MemoryPersistence())

            withContext(Dispatchers.IO) {
                val options = MqttConnectOptions().apply {
                    isCleanSession = false
                    connectionTimeout = 30
                    keepAliveInterval = 30
                    isAutomaticReconnect = false

                    // Broker authentication (absent = anonymous, e.g. public dev broker)
                    val broker = BrokerConfig.getCurrentBroker()
                    broker.username?.let { userName = it }
                    broker.password?.let { password = it.toCharArray() }
                }
                Timber.i("sendJoinRequest: connecting to broker…")
                mqttClient.connect(options).waitForCompletion(30_000)
                Timber.i("sendJoinRequest: connected")

                val approvalTopic = MqttConfig.getJoinApprovalTopic(memberId)
                mqttClient.subscribe(approvalTopic, MqttConfig.DEFAULT_QOS).waitForCompletion(5_000)
                Timber.i("sendJoinRequest: subscribed to $approvalTopic")

                val joinMsg = MqttMessage(joinRequestJson.toByteArray(Charsets.UTF_8)).apply {
                    qos = MqttConfig.DEFAULT_QOS
                    isRetained = true
                }
                val requestTopic = MqttConfig.getJoinRequestTopic(inviterMemberId)
                mqttClient.publish(requestTopic, joinMsg).waitForCompletion(5_000)
                Timber.i("sendJoinRequest: join request published (retained) to $requestTopic")

                // Disconnect the ephemeral client — MembershipViewModel will open its own listener.
                try { mqttClient.disconnect().waitForCompletion(3_000) } catch (_: Exception) {}
            }

            JoinSubmitResult.Submitted(
                familyName = familyName,
                inviterName = inviterName,
                memberId = memberId,
                inviterMemberId = inviterMemberId,
                groupId = groupId,
                joinRequestJson = joinRequestJson
            )
        } catch (e: Exception) {
            Timber.e(e, "sendJoinRequest: unexpected exception")
            JoinSubmitResult.Failed(mapJoinRequestError(e))
        } finally {
            _isLoading.value = false
        }
    }

    private fun mapJoinRequestError(error: Throwable): String {
        val message = error.message.orEmpty().trim()
        if (message.isBlank()) return "Could not send request. Check the invite code and try again."

        return when {
            message.contains("password", ignoreCase = true) ->
                "Could not send request. Check the invite code and try again."
            message.contains("connect", ignoreCase = true) ||
            message.contains("broker", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true) ||
            message.contains("network", ignoreCase = true) ->
                "Unable to connect to server."
            message.contains("invite code", ignoreCase = true) ||
            message.contains("groupId", ignoreCase = true) ||
            message.contains("inviterMemberId", ignoreCase = true) ||
            message.contains("malformed", ignoreCase = true) ->
                message
            else ->
                "Could not send request. Check the invite code and try again."
        }
    }

    sealed class JoinSubmitResult {
        data class Submitted(
            val familyName: String,
            val inviterName: String,
            val memberId: String,
            val inviterMemberId: String,
            val groupId: String,
            val joinRequestJson: String
        ) : JoinSubmitResult()
        data class Failed(val reason: String) : JoinSubmitResult()
    }

    /**
     * Restore an existing account from a mnemonic phrase.
     * Derives the same keys (and therefore the same member ID) as the original
     * account. Does NOT create a new group — the group state will sync from
     * other online family members via MQTT after the main screen loads.
     */
    suspend fun restoreAccount(): Boolean {
        return try {
            _isLoading.value = true
            val success = initializeKeys()
            if (success) {
                saveOnboardingComplete()
            }
            success
        } catch (e: Exception) {
            false
        } finally {
            _isLoading.value = false
        }
    }

    private fun saveOnboardingComplete() {
        val prefs = context.getSharedPreferences("familysafety_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("onboarding_complete", true).commit()
    }
}
