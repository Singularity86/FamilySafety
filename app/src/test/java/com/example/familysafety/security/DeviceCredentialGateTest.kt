package com.example.familysafety.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F15: the recovery phrase and backup only leave the keystore after the owner is confirmed. */
class DeviceCredentialGateTest {

    @Test
    fun `screen lock is always an accepted way in`() {
        for (sdk in 26..36) {
            assertTrue(DeviceCredentialGate.allowedAuthenticators(sdk) and DEVICE_CREDENTIAL != 0)
        }
    }

    @Test
    fun `strong biometrics on Android 11 and later, any biometric before`() {
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, DeviceCredentialGate.allowedAuthenticators(30))
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, DeviceCredentialGate.allowedAuthenticators(29))
    }

    @Test
    fun `a phone with no screen lock is refused, not waved through`() {
        assertEquals(
            DeviceCredentialGate.Result.NoScreenLock,
            DeviceCredentialGate.precheck(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
        )
        assertNotNull(DeviceCredentialGate.explain(DeviceCredentialGate.Result.NoScreenLock))
    }

    @Test
    fun `any other unavailable state is refused`() {
        for (code in listOf(
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        )) {
            assertTrue(DeviceCredentialGate.precheck(code) is DeviceCredentialGate.Result.Failed)
        }
    }

    @Test
    fun `only a ready phone goes on to the prompt`() {
        assertNull(DeviceCredentialGate.precheck(BiometricManager.BIOMETRIC_SUCCESS))
    }

    @Test
    fun `backup password needs 16 characters`() {
        val fifteen = "a".repeat(15)
        val sixteen = "a".repeat(16)
        assertNotNull(BackupPasswordPolicy.problem(fifteen, fifteen))
        assertNull(BackupPasswordPolicy.problem(sixteen, sixteen))
    }

    @Test
    fun `backup password must match its confirmation`() {
        assertNotNull(BackupPasswordPolicy.problem("a".repeat(16), "b".repeat(16)))
    }
}
