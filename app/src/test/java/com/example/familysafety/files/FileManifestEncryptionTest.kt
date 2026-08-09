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
 * The manifest carries file names, MIME types, exact sizes, uploader and timestamps, and
 * was published as retained plaintext — so it was served to any broker client on
 * subscribe. These tests pin the wire shape and the crypto, mirroring
 * SharedFileRepository's helpers, which cannot be instantiated in a JVM test because the
 * repository pulls in Android and Room.
 */
class FileManifestEncryptionTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val padBytes = 1024
    private val nonceBytes = 12
    private val gcmTagBits = 128

    private val groupKey: ByteArray = ByteArray(32) { it.toByte() }

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

    private fun padManifest(manifest: FileManifest): FileManifest {
        val bare = json.encodeToString(manifest.copy(pad = "")).toByteArray(Charsets.UTF_8).size
        val target = ((bare + padBytes - 1) / padBytes) * padBytes
        return manifest.copy(pad = ".".repeat(target - bare))
    }

    private fun sharedFile(name: String, size: Long = 1024) = SharedFile(
        fileId = "id-$name",
        name = name,
        mimeType = "application/pdf",
        sizeBytes = size,
        contentHash = MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
            .joinToString("") { "%02x".format(it) },
        uploaderMemberId = "m".repeat(32),
        uploadedAt = 1_786_000_000_000L,
        chunkCount = 1
    )

    private fun manifestOf(vararg names: String) = FileManifest(
        groupId = "group-1",
        files = names.map { sharedFile(it) },
        version = 1_786_000_000_000L
    )

    private fun publish(manifest: FileManifest): String {
        val wrapped = EncryptedFileManifest(
            keyVersion = FILE_KEY_VERSION_GROUP_SECRET,
            data = Base64.getEncoder().encodeToString(
                encrypt(json.encodeToString(padManifest(manifest)).toByteArray(), groupKey)
            )
        )
        return json.encodeToString(wrapped)
    }

    @Test
    fun fileNamesDoNotAppearOnTheWire() {
        val onWire = publish(manifestOf("custody_agreement.pdf", "passport_scan.jpg"))

        assertFalse("file name leaked", onWire.contains("custody_agreement"))
        assertFalse("file name leaked", onWire.contains("passport_scan"))
        assertFalse("mime type leaked", onWire.contains("application/pdf"))
        assertFalse("uploader leaked", onWire.contains("m".repeat(32)))
    }

    @Test
    fun encryptedManifestRoundTrips() {
        val original = manifestOf("a.pdf", "b.jpg")
        val wrapped = json.decodeFromString<EncryptedFileManifest>(publish(original))
        val decoded = json.decodeFromString<FileManifest>(
            String(decrypt(Base64.getDecoder().decode(wrapped.data), groupKey))
        )

        assertEquals(original.groupId, decoded.groupId)
        assertEquals(2, decoded.files.size)
        assertEquals("a.pdf", decoded.files[0].name)
        assertEquals("b.jpg", decoded.files[1].name)
    }

    @Test
    fun wrongKeyCannotDecrypt() {
        val wrapped = json.decodeFromString<EncryptedFileManifest>(publish(manifestOf("secret.pdf")))
        val wrongKey = ByteArray(32) { (it + 1).toByte() }

        val result = runCatching {
            decrypt(Base64.getDecoder().decode(wrapped.data), wrongKey)
        }
        assertTrue("GCM must reject a wrong key", result.isFailure)
    }

    @Test
    fun manifestSizeDoesNotRevealFileCount() {
        val one = publish(manifestOf("a.pdf")).length
        val three = publish(manifestOf("a.pdf", "b.pdf", "c.pdf")).length

        assertEquals("small manifests must share a padding bucket", one, three)
    }

    @Test
    fun paddingRoundsUpInsteadOfLeakingExactSize() {
        val padded = padManifest(manifestOf("a.pdf", "b.pdf"))
        val size = json.encodeToString(padded).toByteArray(Charsets.UTF_8).size

        assertEquals("expected a multiple of $padBytes, got $size", 0, size % padBytes)
    }

    @Test
    fun legacyPlaintextManifestStillParses() {
        // A retained plaintext manifest from before the upgrade can outlive it, and older
        // senders keep publishing this shape, so the fallback path must keep working.
        val legacy = json.encodeToString(manifestOf("old.pdf").copy(pad = ""))

        assertNull(
            "plaintext must not parse as the encrypted wrapper",
            runCatching { json.decodeFromString<EncryptedFileManifest>(legacy) }.getOrNull()
        )
        val parsed = json.decodeFromString<FileManifest>(legacy)
        assertEquals("old.pdf", parsed.files.single().name)
    }

    @Test
    fun encryptedManifestDoesNotParseAsPlaintext() {
        // The two shapes must be distinguishable, or the receive path would pick wrong.
        val encrypted = publish(manifestOf("a.pdf"))

        assertNull(
            "encrypted wrapper must not parse as a bare manifest",
            runCatching { json.decodeFromString<FileManifest>(encrypted) }.getOrNull()
        )
        assertNotNull(runCatching { json.decodeFromString<EncryptedFileManifest>(encrypted) }.getOrNull())
    }
}
