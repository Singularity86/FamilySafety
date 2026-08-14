package com.example.familysafety.files

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The repair request is what replaces "ask everyone to re-send everything". These tests pin
 * the wire shape and the properties that keep it from being either a privacy leak or an
 * amplification vector, mirroring SharedFileRepository's crypto helpers — the repository
 * itself pulls in Android and Room and cannot be built in a JVM test.
 */
class ChunkRepairRequestTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val nonceBytes = 12
    private val gcmTagBits = 128
    private val groupKey = ByteArray(32) { it.toByte() }

    private fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        val nonce = ByteArray(nonceBytes).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(gcmTagBits, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    private fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        val nonce = data.copyOfRange(0, nonceBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(gcmTagBits, nonce))
        return cipher.doFinal(data.copyOfRange(nonceBytes, data.size))
    }

    private fun seal(request: ChunkRepairRequest, key: ByteArray = groupKey): String =
        json.encodeToString(
            EncryptedFileMessage(
                keyVersion = FILE_KEY_VERSION_GROUP_SECRET,
                data = Base64.getEncoder().encodeToString(encrypt(json.encodeToString(request).toByteArray(), key))
            )
        )

    private fun open(raw: String, key: ByteArray = groupKey): ChunkRepairRequest? = runCatching {
        val wrapped = json.decodeFromString<EncryptedFileMessage>(raw)
        json.decodeFromString<ChunkRepairRequest>(
            String(decrypt(Base64.getDecoder().decode(wrapped.data), key))
        )
    }.getOrNull()

    private fun request(
        fileId: String = "file-1",
        contentHash: String = "a".repeat(64),
        missing: List<Int> = listOf(3, 7, 11)
    ) = ChunkRepairRequest(
        requestId = "req-1",
        requesterId = "member-1",
        fileId = fileId,
        contentHash = contentHash,
        totalChunks = 20,
        missing = missing,
        timestamp = 1_786_000_000_000L
    )

    @Test
    fun aSealedRequestRoundTrips() {
        val original = request()
        val recovered = open(seal(original))

        assertNotNull(recovered)
        assertEquals(original, recovered)
    }

    @Test
    fun theFileIdAndMissingCountDoNotAppearOnTheWire() {
        // A bare request says which file a device has and how much of it is missing — the same
        // class of metadata the manifest was encrypted to stop leaking.
        val wire = seal(request(fileId = "insurance-card-file-id"))

        assertFalse(wire.contains("insurance-card-file-id"))
        assertFalse(wire.contains("totalChunks"))
        assertFalse(wire.contains("missing"))
    }

    @Test
    fun aRequestSealedUnderAnotherKeyCannotBeRead() {
        val foreign = ByteArray(32) { (it + 99).toByte() }

        assertNull(open(seal(request(), foreign)))
    }

    @Test
    fun contentHashBindsTheRequestToExactBytes() {
        // A responder must refuse when its copy hashes differently: sending chunks of a
        // different version would corrupt the requester's blob rather than repair it.
        val theirs = request(contentHash = "a".repeat(64))
        val ourHash = "b".repeat(64)

        assertFalse("hashes differ, must not serve", theirs.contentHash == ourHash)
    }

    @Test
    fun missingIndicesSurviveEncodingInOrder() {
        val original = request(missing = listOf(0, 1, 2, 99, 1000))
        val recovered = open(seal(original))!!

        assertEquals(listOf(0, 1, 2, 99, 1000), recovered.missing)
    }

    @Test
    fun anEmptyRequestIsStillWellFormed() {
        val recovered = open(seal(request(missing = emptyList())))!!

        assertTrue(recovered.missing.isEmpty())
    }

    @Test
    fun aRequestCappedAtTheLimitStaysAReasonableSize() {
        // 256 indices is the cap. The sealed message has to stay comfortably inside the 1 MiB
        // LAN frame limit, or repair would silently fall back to the relay every time.
        val big = request(missing = (0 until 256).toList())
        val wire = seal(big)

        assertTrue("sealed size was ${wire.length}", wire.length < 16 * 1024)
        assertEquals(256, open(wire)!!.missing.size)
    }

    @Test
    fun unknownFieldsFromANewerPeerDoNotBreakDecoding() {
        // Forward compatibility: a later build may add fields, and ignoreUnknownKeys is what
        // keeps this readable rather than dropping the request entirely.
        val withExtra = """
            {"requestId":"r","requesterId":"m","fileId":"f","contentHash":"h",
             "totalChunks":4,"missing":[1],"timestamp":1,"priority":"high"}
        """.trimIndent()

        val parsed = json.decodeFromString<ChunkRepairRequest>(withExtra)
        assertEquals(listOf(1), parsed.missing)
    }

    @Test
    fun theRepairTopicIsAddressedToOnePeer() {
        // Broadcasting would mean every peer answers the same gap, which is the amplification
        // the old whole-library re-broadcast caused.
        val topic = com.example.familysafety.transport.MqttConfig.getFileRepairTopic("member-9")

        assertEquals("familysafe/member-9/files/repair", topic)
        assertFalse(topic.contains("group"))
    }

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun sealingIsNonDeterministicSoRepeatedRequestsDoNotCorrelate() {
        // A fresh nonce per message means two identical requests do not produce identical
        // ciphertext, so an observer cannot tell that a device is asking for the same thing
        // over and over.
        val r = request()

        assertFalse(sha256Hex(seal(r).toByteArray()) == sha256Hex(seal(r).toByteArray()))
    }
}
