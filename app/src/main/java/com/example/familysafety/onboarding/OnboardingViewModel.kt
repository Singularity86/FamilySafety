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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
                    // Log the disconnect but DO NOT complete the deferred with null.
                    // The Paho client will auto-reconnect if keepAliveInterval is set;
                    // once reconnected it will re-deliver any retained approval message.
                    // withTimeoutOrNull handles the overall deadline.
                    Timber.w("joinFamily: MQTT connection lost — ${cause?.message}. Waiting for reconnect.")
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic?.endsWith("/join_approval") == true) {
                        try {
                            val payload = String(message!!.payload, Charsets.UTF_8)
                            Timber.i("joinFamily: approval message arrived (${payload.length} bytes)")
                            val groupDef = json.decodeFromString<GroupDefinition>(payload)
                            Timber.i("joinFamily: decoded GroupDefinition — group=${groupDef.groupName}, members=${groupDef.members.size}")
                            approvalDeferred.complete(groupDef)
                        } catch (e: Exception) {
                            Timber.e(e, "joinFamily: failed to decode GroupDefinition from approval message")
                        }
                    }
                }
            })

            // 5. Connect, subscribe, and send the join request (blocking IO calls)
            withContext(Dispatchers.IO) {
                val options = MqttConnectOptions().apply {
                    isCleanSession = false   // false = broker re-delivers missed messages on reconnect
                    connectionTimeout = 30
                    keepAliveInterval = 30
                    isAutomaticReconnect = true
                }
                Timber.i("joinFamily: connecting to broker…")
                mqttClient.connect(options).waitForCompletion(30_000)
                Timber.i("joinFamily: connected")

                val approvalTopic = MqttConfig.getJoinApprovalTopic(memberId)
                mqttClient.subscribe(approvalTopic, MqttConfig.DEFAULT_QOS).waitForCompletion(5_000)
                Timber.i("joinFamily: subscribed to $approvalTopic")

                val joinRequestBytes = json.encodeToString(joinRequest).toByteArray(Charsets.UTF_8)
                val joinMsg = MqttMessage(joinRequestBytes).apply {
                    qos = MqttConfig.DEFAULT_QOS
                    isRetained = true  // retained so inviter receives it even if their app reconnects
                }

                val requestTopic = MqttConfig.getJoinRequestTopic(inviterMemberId)
                mqttClient.publish(requestTopic, joinMsg).waitForCompletion(5_000)
                Timber.i("joinFamily: join request published (retained) to $requestTopic")
            }

            // 6. Wait for the inviter to approve (suspends, does not block a thread).
            // 5 minutes gives the inviter time to see the notification and tap Approve.
            // Re-publish the join request every 30 s so a brief inviter disconnect is recovered.
            Timber.i("joinFamily: waiting up to 5 minutes for approval…")
            val republishJob = viewModelScope.launch(Dispatchers.IO) {
                val joinRequestBytes = json.encodeToString(joinRequest).toByteArray(Charsets.UTF_8)
                val requestTopic = MqttConfig.getJoinRequestTopic(inviterMemberId)
                delay(30_000)
                while (isActive && !approvalDeferred.isCompleted) {
                    try {
                        val msg = MqttMessage(joinRequestBytes).apply {
                            qos = MqttConfig.DEFAULT_QOS; isRetained = true
                        }
                        mqttClient.publish(requestTopic, msg).waitForCompletion(5_000)
                        Timber.d("joinFamily: re-published join request")
                    } catch (e: Exception) {
                        Timber.w("joinFamily: re-publish failed — ${e.message}")
                    }
                    delay(30_000)
                }
            }
            val groupDefinition = withTimeoutOrNull(5 * 60_000L) { approvalDeferred.await() }
            republishJob.cancel()
            if (groupDefinition == null) Timber.w("joinFamily: timed out waiting for approval")

            // 7. Disconnect the one-time client
            withContext(Dispatchers.IO) {
                try { mqttClient.disconnect().waitForCompletion(3_000) } catch (_: Exception) {}
            }

            // 8. Save group state and complete onboarding
            if (groupDefinition != null) {
                Timber.i("joinFamily: received approval — saving group '${groupDefinition.groupName}'")
                val persistence = EncryptedGroupStatePersistence.getInstance(context)
                persistence.saveGroupDefinition(groupDefinition)
                saveOnboardingComplete()
                true
            } else {
                Timber.e("joinFamily: no approval received — returning false")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "joinFamily: unexpected exception")
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
