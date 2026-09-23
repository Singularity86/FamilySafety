package com.example.familysafety.billing

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

class GrantCodeTest {

    private lateinit var privateKey: Ed25519PrivateKeyParameters
    private lateinit var publicKeyHex: String
    private val groupId = "group-abc-123"
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        publicKeyHex = privateKey.generatePublicKey().encoded.joinToString("") { "%02x".format(it) }
    }

    private fun mint(groupId: String, expiryEpochMs: Long): String {
        val message = GrantCode.signingMessage(groupId, expiryEpochMs)
        val signer = Ed25519Signer().apply {
            init(true, privateKey)
            update(message, 0, message.size)
        }
        val signatureHex = signer.generateSignature().joinToString("") { "%02x".format(it) }
        return GrantCode.encode(GrantCodePayload(expiryEpochMs, signatureHex))
    }

    @Test
    fun validCodeForTheRightFamilyVerifies() {
        val code = mint(groupId, expiryEpochMs = 0L)
        val result = GrantCode.verify(code, groupId, publicKeyHex, now)
        assertTrue(result is GrantCodeResult.Valid)
        assertEquals(null, (result as GrantCodeResult.Valid).expiresAtEpochMs)
    }

    @Test
    fun sameCodeFailsForADifferentFamily() {
        val code = mint(groupId, expiryEpochMs = 0L)
        val result = GrantCode.verify(code, "some-other-group", publicKeyHex, now)
        assertEquals(GrantCodeResult.BadSignature, result)
    }

    @Test
    fun unexpiredCodeWithFutureExpiryVerifies() {
        val code = mint(groupId, expiryEpochMs = now + 1_000)
        val result = GrantCode.verify(code, groupId, publicKeyHex, now)
        assertTrue(result is GrantCodeResult.Valid)
        assertEquals(now + 1_000, (result as GrantCodeResult.Valid).expiresAtEpochMs)
    }

    @Test
    fun codePastItsExpiryIsExpired() {
        val code = mint(groupId, expiryEpochMs = now - 1_000)
        assertEquals(GrantCodeResult.Expired, GrantCode.verify(code, groupId, publicKeyHex, now))
    }

    @Test
    fun wrongPublicKeyFailsVerification() {
        val code = mint(groupId, expiryEpochMs = 0L)
        val otherKeyHex = Ed25519PrivateKeyParameters(SecureRandom())
            .generatePublicKey().encoded.joinToString("") { "%02x".format(it) }
        assertEquals(GrantCodeResult.BadSignature, GrantCode.verify(code, groupId, otherKeyHex, now))
    }

    @Test
    fun garbageInputIsInvalidFormatNotACrash() {
        assertEquals(GrantCodeResult.InvalidFormat, GrantCode.verify("not base64!!", groupId, publicKeyHex, now))
        assertEquals(GrantCodeResult.InvalidFormat, GrantCode.verify("", groupId, publicKeyHex, now))
    }

    @Test
    fun tamperedPayloadFailsVerification() {
        val code = mint(groupId, expiryEpochMs = 0L)
        val decoded = String(java.util.Base64.getDecoder().decode(code))
        val tampered = decoded.replace("\"expiryEpochMs\":0", "\"expiryEpochMs\":${now + 999_999}")
        val tamperedCode = java.util.Base64.getEncoder().encodeToString(tampered.toByteArray())
        assertEquals(GrantCodeResult.BadSignature, GrantCode.verify(tamperedCode, groupId, publicKeyHex, now))
    }
}
