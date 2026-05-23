package com.example.familysafety.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurityEventRepository @Inject constructor() {

    data class DecryptStats(
        val failureCount: Int = 0,
        val lastFailureMs: Long = 0L
    )

    private val _decryptFailures = MutableStateFlow<Map<String, DecryptStats>>(emptyMap())
    val decryptFailures: StateFlow<Map<String, DecryptStats>> = _decryptFailures.asStateFlow()

    fun recordDecryptFailure(senderId: String) {
        synchronized(this) {
            val current = _decryptFailures.value.toMutableMap()
            val prev = current[senderId] ?: DecryptStats()
            current[senderId] = prev.copy(
                failureCount = prev.failureCount + 1,
                lastFailureMs = System.currentTimeMillis()
            )
            _decryptFailures.value = current
        }
    }

    fun clearFailures(senderId: String) {
        synchronized(this) {
            val current = _decryptFailures.value.toMutableMap()
            current.remove(senderId)
            _decryptFailures.value = current
        }
    }

    fun clearAll() {
        _decryptFailures.value = emptyMap()
    }
}
