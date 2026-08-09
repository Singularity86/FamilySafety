package com.example.familysafety.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presence is the only message type outside the encrypted envelope — an MQTT last-will
 * cannot be encrypted — so it is authenticated by signature instead. These tests pin the
 * canonical signing payload, because a cross-platform client that builds it differently
 * produces signatures that silently fail to verify.
 */
class PresenceSigningTest {

    private val memberId = "9d3ece3b6b1b36bf45c0faf997f69a79"

    @Test
    fun signingPayloadHasTheExactCanonicalForm() {
        val payload = MessageProtocol.presenceSigningPayload(memberId, true, 1_786_000_000_000L)

        assertEquals(
            "presence:$memberId:true:1786000000000",
            String(payload, Charsets.UTF_8)
        )
    }

    @Test
    fun onlineAndOfflineProduceDifferentSigningPayloads() {
        val online = MessageProtocol.presenceSigningPayload(memberId, true, 1L)
        val offline = MessageProtocol.presenceSigningPayload(memberId, false, 1L)

        assertNotEquals(String(online), String(offline))
    }

    @Test
    fun timestampIsCoveredBySignature() {
        // If the timestamp were outside the signature, a captured message could be
        // replayed with a fresh one and still verify.
        val a = MessageProtocol.presenceSigningPayload(memberId, true, 1L)
        val b = MessageProtocol.presenceSigningPayload(memberId, true, 2L)

        assertNotEquals(String(a), String(b))
    }

    @Test
    fun memberIdIsCoveredBySignature() {
        val mine = MessageProtocol.presenceSigningPayload(memberId, true, 1L)
        val theirs = MessageProtocol.presenceSigningPayload("f".repeat(32), true, 1L)

        assertNotEquals(String(mine), String(theirs))
    }

    @Test
    fun encodedPresenceCarriesTheSignature() {
        val encoded = MessageProtocol.encodePresenceUpdate(
            memberId,
            isOnline = true,
            sign = { ByteArray(64) { 0x2a } }
        )
        val decoded = MessageProtocol.decodePresenceUpdate(
            MessageProtocol.decodeEnvelope(encoded).payload
        )

        assertEquals("2a".repeat(64), decoded.signature)
    }

    @Test
    fun signatureCoversTheTimestampActuallySent() {
        // The encoder stamps the time itself, so the bytes handed to the signer must match
        // what ends up on the wire — otherwise every signature fails verification.
        var signedOver: String? = null
        val encoded = MessageProtocol.encodePresenceUpdate(
            memberId,
            isOnline = false,
            sign = { bytes -> signedOver = String(bytes); ByteArray(64) }
        )
        val decoded = MessageProtocol.decodePresenceUpdate(
            MessageProtocol.decodeEnvelope(encoded).payload
        )

        assertEquals("presence:$memberId:false:${decoded.timestamp}", signedOver)
    }

    @Test
    fun unsignedEncodingIsStillPossibleForLegacyCallers() {
        val encoded = MessageProtocol.encodePresenceUpdate(memberId, isOnline = true)
        val decoded = MessageProtocol.decodePresenceUpdate(
            MessageProtocol.decodeEnvelope(encoded).payload
        )

        assertNull(decoded.signature)
    }

    @Test
    fun signedPresenceIsStillPaddedToTheFixedSize() {
        // Padding and signing must not interfere: the signature lengthens the payload, and
        // the envelope still has to come out at exactly the padded size.
        val signed = MessageProtocol.encodePresenceUpdate(
            memberId,
            isOnline = true,
            sign = { ByteArray(64) { 0x11 } }
        )

        assertEquals(512, signed.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun signedOnlineAndOfflineRemainIndistinguishableByLength() {
        val online = MessageProtocol.encodePresenceUpdate(
            memberId, true, sign = { ByteArray(64) { 0x11 } }
        )
        val offline = MessageProtocol.encodePresenceUpdate(
            memberId, false, sign = { ByteArray(64) { 0x11 } }
        )

        assertEquals(
            online.toByteArray(Charsets.UTF_8).size,
            offline.toByteArray(Charsets.UTF_8).size
        )
    }

    @Test
    fun signatureIsLowercaseHexOfExpectedWidth() {
        val encoded = MessageProtocol.encodePresenceUpdate(
            memberId, true, sign = { ByteArray(64) { (-1).toByte() } }
        )
        val decoded = MessageProtocol.decodePresenceUpdate(
            MessageProtocol.decodeEnvelope(encoded).payload
        )
        val signature = decoded.signature!!

        assertEquals("Ed25519 signatures are 64 bytes = 128 hex chars", 128, signature.length)
        assertTrue("must be lowercase hex", signature.all { it in "0123456789abcdef" })
    }
}
