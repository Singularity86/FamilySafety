package com.example.familysafety.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.familysafety.group.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    suspend fun joinFamily(inviteCode: String): Boolean {
        return try {
            _isLoading.value = true
            
            // TODO: Implement join logic
            
            saveOnboardingComplete()
            
            true
        } catch (e: Exception) {
            false
        } finally {
            _isLoading.value = false
        }
    }

    private fun saveOnboardingComplete() {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("onboarding_complete", true).apply()
    }
}
