package com.example.familysafety.security

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Asks for the phone's owner (fingerprint, face, or the screen-lock PIN, pattern or
 * password) before the recovery phrase leaves the keystore.
 *
 * The phrase is the whole identity: whoever reads it can restore it on another phone, and
 * the family would see nothing (F15). Before this, anyone holding an unlocked phone could
 * open Settings and read it.
 */
object DeviceCredentialGate {

    sealed class Result {
        data object Passed : Result()
        /** No screen lock is set, so there is nothing to confirm the owner with. */
        data object NoScreenLock : Result()
        data object Cancelled : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * Strong biometrics or the screen lock. Android 9 and 10 don't accept that pairing, so
     * there it is any enrolled biometric or the screen lock; the screen lock is the floor
     * either way.
     */
    fun allowedAuthenticators(sdkInt: Int = Build.VERSION.SDK_INT): Int =
        if (sdkInt >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** What [BiometricManager.canAuthenticate] means for us; null means go ahead and ask. */
    fun precheck(canAuthenticate: Int): Result? = when (canAuthenticate) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Result.NoScreenLock
        else -> Result.Failed("This phone can't confirm it's you right now.")
    }

    suspend fun confirmOwner(
        activity: FragmentActivity,
        title: String,
        subtitle: String
    ): Result {
        val authenticators = allowedAuthenticators()
        precheck(BiometricManager.from(activity).canAuthenticate(authenticators))?.let { return it }

        return suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(Result.Passed)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!continuation.isActive) return
                        continuation.resume(
                            when (errorCode) {
                                BiometricPrompt.ERROR_USER_CANCELED,
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                BiometricPrompt.ERROR_CANCELED -> Result.Cancelled
                                BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> Result.NoScreenLock
                                else -> Result.Failed(errString.toString())
                            }
                        )
                    }
                    // onAuthenticationFailed is one wrong finger; the prompt stays up for another try.
                }
            )
            // No negative button: with DEVICE_CREDENTIAL allowed, the system provides the
            // way out (and setting one would throw).
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(authenticators)
                    .build()
            )
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    /** The activity behind a Compose context, which may be wrapped. */
    fun activityOf(context: android.content.Context): FragmentActivity? {
        var current: android.content.Context? = context
        while (current is android.content.ContextWrapper) {
            if (current is FragmentActivity) return current
            current = current.baseContext
        }
        return null
    }

    /** User-facing words for a result that isn't [Result.Passed]. */
    fun explain(result: Result): String? = when (result) {
        Result.Passed, Result.Cancelled -> null
        Result.NoScreenLock ->
            "Set a screen lock (PIN, pattern or password) in your phone's settings first. " +
                "The recovery phrase is only shown after the phone confirms it's you."
        is Result.Failed -> result.message
    }
}

/** Backup password rules, kept out of the dialog so they can be tested. */
object BackupPasswordPolicy {
    /**
     * The backup file holds the recovery phrase and can be copied anywhere, so its password
     * is all that stands between a copy and the identity. 16 characters is the floor the
     * review set (F15); a phrase of a few words is the easy way to reach it.
     */
    const val MIN_LENGTH = 16

    /** Null when acceptable, otherwise what to tell the user. */
    fun problem(password: String, confirmation: String): String? = when {
        password.length < MIN_LENGTH ->
            "Use at least $MIN_LENGTH characters. A few unrelated words is easy to remember."
        password != confirmation -> "Passwords do not match"
        else -> null
    }
}
