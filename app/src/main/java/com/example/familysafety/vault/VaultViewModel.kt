package com.example.familysafety.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Screen state for an open vault.
 *
 * The session — and with it the key — lives in this ViewModel and nowhere else. Closing the
 * vault means dropping the object, not setting a flag, so there is no path where the UI
 * believes the vault is shut while the key is still resident.
 */
@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repository: VaultRepository
) : ViewModel() {

    private val _session = MutableStateFlow<VaultSession?>(null)
    val session: StateFlow<VaultSession?> = _session.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Messages about *actions*, never about codes.
     *
     * Nothing here ever says a code was wrong, because there is no such thing — a code that
     * opens nothing opens an empty vault, and saying otherwise would prove that a code which
     * opens something exists.
     */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var timeoutJob: Job? = null

    companion object {
        /**
         * How long an open vault survives without interaction.
         *
         * Short on purpose. The realistic threat is not a remote attacker but someone holding
         * the unlocked phone, and an open vault left on a table is the whole exposure.
         */
        private const val IDLE_TIMEOUT_MS = 3 * 60 * 1000L
    }

    /**
     * Try a code.
     *
     * Always succeeds. An empty result is presented as an empty vault, in the same way and
     * after the same work as a full one — the derivation cost is identical either way, so this
     * takes the same time whether the code opens anything or not.
     */
    fun openWithCode(code: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                _session.value = repository.open(code)
                restartTimeout()
            } catch (e: Exception) {
                Timber.e(e, "vault open failed")
                _session.value = null
            } finally {
                _busy.value = false
            }
        }
    }

    fun addDocument(uri: Uri, displayName: String, mimeType: String) {
        val current = _session.value ?: return
        viewModelScope.launch {
            _busy.value = true
            repository.add(current, uri, displayName, mimeType)
                .onSuccess { _session.value = it; _message.value = "Added to the vault" }
                .onFailure { _message.value = it.message ?: "Could not add that" }
            _busy.value = false
            restartTimeout()
        }
    }

    fun delete(item: VaultItem) {
        val current = _session.value ?: return
        viewModelScope.launch {
            repository.delete(current, item)
                .onSuccess { _session.value = it; _message.value = "Removed — you can put it back" }
                .onFailure { _message.value = it.message ?: "Could not remove that" }
            restartTimeout()
        }
    }

    fun restore(item: VaultItem) {
        val current = _session.value ?: return
        viewModelScope.launch {
            repository.restore(current, item)
                .onSuccess { _session.value = it; _message.value = "Put back" }
                .onFailure { _message.value = it.message ?: "Could not put that back" }
            restartTimeout()
        }
    }

    fun openDocument(item: VaultItem, context: Context) {
        viewModelScope.launch {
            _busy.value = true
            val file = repository.materialize(item)
            _busy.value = false
            restartTimeout()
            if (file == null) {
                _message.value = "Not on this phone yet — fetching it"
                return@launch
            }
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, item.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e: Exception) {
                _message.value = "No app can open this"
            }
        }
    }

    /**
     * Shut the vault: drop the key, and delete anything decrypted for viewing.
     *
     * Called when the screen stops as well as when the user leaves deliberately, so
     * backgrounding the app is enough to close it.
     */
    fun close() {
        timeoutJob?.cancel()
        timeoutJob = null
        _session.value?.key?.fill(0)
        _session.value = null
        repository.forgetViewCache()
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun restartTimeout() {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(IDLE_TIMEOUT_MS)
            close()
        }
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }
}
