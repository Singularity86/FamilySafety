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
            val derivation = FamilySafeKeyDerivation()
            
            derivation.deriveAndStoreKeys(seed, keyStore)
            
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
            
            val groupDef = GroupDefinition(
                groupId = UUID.randomUUID().toString(),
                groupName = _familyName.value,
                members = listOf(
                    FamilyMember(
                        memberId = memberId,
                        displayName = _displayName.value,
                        ed25519PublicKey = cryptoProvider.getEd25519PublicKey(),
                        x25519PublicKey = cryptoProvider.getX25519PublicKey(),
                        addedAtEpochMs = System.currentTimeMillis()
                    )
                ),
                version = 1,
                creatorMemberId = memberId
            )
            
            val persistence = EncryptedGroupStatePersistence(context, cryptoProvider)
            val groupStateManager = GroupStateManager(
                memberId = memberId,
                persistence = persistence,
                cryptoProvider = cryptoProvider
            )
            
            groupStateManager.createGroup(groupDef)
            
            saveOnboardingComplete()
            
            true
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
