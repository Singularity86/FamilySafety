package com.example.familysafety.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.familysafety.group.*
import com.example.familysafety.invite.JoinRequest
import com.example.familysafety.transport.MqttConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
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
     * Join an existing family group using an invite code.
     *
     * Flow:
     * 1. Initialize keys from the mnemonic (derives the same identity each time)
     * 2. Decode the invite code to get groupId and inviterMemberId
     * 3. Open a temporary MQTT connection, subscribe to our join_approval topic
     * 4. Publish a JoinRequest to the inviter's join_request topic
     * 5. Wait up to 60 s for the inviter to approve — they will publish the full
     *    GroupDefinition to our join_approval topic
     * 6. Save the GroupDefinition and mark onboarding complete
     */
    suspend fun joinFamily(inviteCode: String): Boolean {
        return try {
            _isLoading.value = true

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
                ?: return false
            val inviterMemberId = inviteData["inviterMemberId"]
                ?: return false

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

            // 4. Create a one-time MQTT client for this join transaction
            val clientId = "familysafe_join_${memberId}_${System.currentTimeMillis()}"
            val mqttClient = MqttAsyncClient(MqttConfig.BROKER_URL, clientId, MemoryPersistence())
            val approvalDeferred = CompletableDeferred<GroupDefinition?>()

            mqttClient.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    // Complete with null so joinFamily() doesn't hang on disconnect
                    if (!approvalDeferred.isCompleted) approvalDeferred.complete(null)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic?.endsWith("/join_approval") == true) {
                        try {
                            val payload = String(message!!.payload, Charsets.UTF_8)
                            val groupDef = json.decodeFromString<GroupDefinition>(payload)
                            approvalDeferred.complete(groupDef)
                        } catch (e: Exception) {
                            // Not a valid GroupDefinition — ignore
                        }
                    }
                }
            })

            // 5. Connect, subscribe, and send the join request (blocking IO calls)
            withContext(Dispatchers.IO) {
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 30
                    keepAliveInterval = 60
                }
                mqttClient.connect(options).waitForCompletion(30_000)

                mqttClient.subscribe(
                    MqttConfig.getJoinApprovalTopic(memberId),
                    MqttConfig.DEFAULT_QOS
                ).waitForCompletion(5_000)

                val joinMsg = MqttMessage(
                    json.encodeToString(joinRequest).toByteArray(Charsets.UTF_8)
                ).apply { qos = MqttConfig.DEFAULT_QOS }

                mqttClient.publish(
                    MqttConfig.getJoinRequestTopic(inviterMemberId),
                    joinMsg
                ).waitForCompletion(5_000)
            }

            // 6. Wait for the inviter to approve (suspends, does not block a thread)
            val groupDefinition = withTimeoutOrNull(60_000) { approvalDeferred.await() }

            // 7. Disconnect the one-time client
            withContext(Dispatchers.IO) {
                try { mqttClient.disconnect().waitForCompletion(3_000) } catch (_: Exception) {}
            }

            // 8. Save group state and complete onboarding
            if (groupDefinition != null) {
                val persistence = EncryptedGroupStatePersistence.getInstance(context)
                persistence.saveGroupDefinition(groupDefinition)
                saveOnboardingComplete()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        } finally {
            _isLoading.value = false
        }
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
