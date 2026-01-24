package com.example.familysafety.core

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Centralized error handling and retry logic
 */
object ErrorHandler {
    
    /**
     * Execute operation with exponential backoff retry
     */
    suspend fun <T> withRetry(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 10000,
        factor: Double = 2.0,
        onError: ((Exception, Int) -> Unit)? = null,
        operation: suspend () -> T
    ): Result<T> {
        var currentDelay = initialDelayMs
        var lastException: Exception? = null
        
        repeat(maxAttempts) { attempt ->
            try {
                return Result.success(operation())
            } catch (e: Exception) {
                lastException = e
                onError?.invoke(e, attempt + 1)
                
                if (attempt < maxAttempts - 1) {
                    delay(currentDelay)
                    currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
                }
            }
        }
        
        return Result.failure(lastException ?: Exception("Unknown error"))
    }
    
    /**
     * Execute operation with linear backoff retry
     */
    suspend fun <T> withLinearRetry(
        maxAttempts: Int = 3,
        delayMs: Long = 1000,
        onError: ((Exception, Int) -> Unit)? = null,
        operation: suspend () -> T
    ): Result<T> {
        var lastException: Exception? = null
        
        repeat(maxAttempts) { attempt ->
            try {
                return Result.success(operation())
            } catch (e: Exception) {
                lastException = e
                onError?.invoke(e, attempt + 1)
                
                if (attempt < maxAttempts - 1) {
                    delay(delayMs * (attempt + 1))
                }
            }
        }
        
        return Result.failure(lastException ?: Exception("Unknown error"))
    }
    
    /**
     * Execute operation with timeout
     */
    suspend fun <T> withTimeout(
        timeoutMs: Long,
        onTimeout: (() -> Unit)? = null,
        operation: suspend () -> T
    ): Result<T> {
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                Result.success(operation())
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            onTimeout?.invoke()
            Result.failure(TimeoutException("Operation timed out after ${timeoutMs}ms"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Safe execution with error logging
     */
    suspend fun <T> safely(
        tag: String,
        operation: String,
        fallback: T? = null,
        block: suspend () -> T
    ): T? {
        return try {
            block()
        } catch (e: Exception) {
            Log.e(tag, "Error during $operation: ${e.message}", e)
            fallback
        }
    }
}

/**
 * Custom timeout exception
 */
class TimeoutException(message: String) : Exception(message)

/**
 * Network-related exceptions
 */
sealed class NetworkException(message: String) : Exception(message) {
    class NoConnection(message: String = "No network connection") : NetworkException(message)
    class Timeout(message: String = "Network timeout") : NetworkException(message)
    class ServerError(message: String) : NetworkException(message)
}

/**
 * Crypto-related exceptions
 */
sealed class CryptoException(message: String) : Exception(message) {
    class EncryptionFailed(message: String = "Encryption failed") : CryptoException(message)
    class DecryptionFailed(message: String = "Decryption failed") : CryptoException(message)
    class SignatureFailed(message: String = "Signature verification failed") : CryptoException(message)
    class KeyNotFound(message: String = "Cryptographic key not found") : CryptoException(message)
}

/**
 * Group state exceptions
 */
sealed class GroupStateException(message: String) : Exception(message) {
    class NotInitialized(message: String = "Group not initialized") : GroupStateException(message)
    class VersionConflict(message: String = "Group version conflict") : GroupStateException(message)
    class InvalidState(message: String = "Invalid group state") : GroupStateException(message)
    class MemberNotFound(message: String = "Member not found") : GroupStateException(message)
}
