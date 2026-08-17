package com.example.familysafety.files

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Documents are stored as the encrypted blob and nothing else.
 *
 * Before this, a completed download was decrypted into a plaintext `content` file that then
 * stayed on external storage permanently — a SQLCipher-encrypted database describing insurance
 * cards and passports lying unencrypted beside it. The uploader's own copy was written in the
 * clear before it was ever encrypted for sending.
 *
 * [ChunkedFileStore] is pure JVM, so the real ingest, verify and retransmit paths run here
 * against the same AES-256-GCM the repository uses.
 */
class AtRestEncryptionTest {

    private lateinit var root: File
    private lateinit var store: ChunkedFileStore

    private val chunkSize = 1024
    private val nonceBytes = 12
    private val gcmTagBits = 128
    private val key = ByteArray(32) { (it * 7).toByte() }

    private fun stride() = store.strideFor(chunkSize, nonceBytes, gcmTagBits)

    private fun encrypt(plain: ByteArray): ByteArray {
        val nonce = ByteArray(nonceBytes).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(gcmTagBits, nonce))
        return nonce + cipher.doFinal(plain)
    }

    private fun decrypt(slot: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(gcmTagBits, slot.copyOfRange(0, nonceBytes))
        )
        return cipher.doFinal(slot.copyOfRange(nonceBytes, slot.size))
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "fs-atrest-${System.nanoTime()}")
        root.mkdirs()
        store = ChunkedFileStore { root }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun ingestThenVerify_roundTripsExactly() = runTest {
        val source = ByteArray(chunkSize * 3 + 137) { (it % 251).toByte() }

        val ingest = store.encryptInto(
            source.inputStream(), "f1", chunkSize, stride()
        ) { encrypt(it) }

        assertNotNull(ingest)
        assertEquals(source.size.toLong(), ingest!!.sizeBytes)
        assertEquals(4, ingest.chunkCount)
        assertEquals(sha256(source), ingest.contentHash)

        val out = ByteArrayOutputStream()
        val verified = store.decryptTo("f1", ingest.chunkCount, stride(), out) { decrypt(it) }

        assertEquals(ingest.contentHash, verified)
        assertArrayEquals(source, out.toByteArray())
    }

    @Test
    fun ingest_neverWritesPlaintextToDisk() = runTest {
        // A recognisable run that would survive verbatim in any plaintext copy.
        val marker = "POLICY-NUMBER-8842".toByteArray()
        val source = ByteArray(chunkSize * 2).also { marker.copyInto(it, 300) }

        val ingest = store.encryptInto(source.inputStream(), "f2", chunkSize, stride()) { encrypt(it) }
        assertNotNull(ingest)

        assertFalse("the pre-encryption content file must not exist", store.contentFile("f2").exists())

        val onDisk = root.walkTopDown().filter { it.isFile }.toList()
        assertTrue("something should have been written", onDisk.isNotEmpty())
        onDisk.forEach { file ->
            val bytes = file.readBytes()
            assertFalse(
                "plaintext found in ${file.name}",
                indexOfSubsequence(bytes, marker) >= 0
            )
        }
    }

    @Test
    fun storedSlots_retransmitVerbatim() = runTest {
        // Publishing reads slots back and sends them unchanged, so what a peer receives is
        // byte-identical to what the uploader hashed. This is also what lets repair answer a
        // request without ever holding plaintext.
        val source = ByteArray(chunkSize * 2 + 5) { it.toByte() }
        val ingest = store.encryptInto(source.inputStream(), "f3", chunkSize, stride()) { encrypt(it) }!!

        for (i in 0 until ingest.chunkCount) {
            val slot = store.readChunk("f3", i, stride())
            assertNotNull("chunk $i should be readable", slot)
            val plain = decrypt(slot!!)
            val from = i * chunkSize
            val to = minOf(from + chunkSize, source.size)
            assertArrayEquals(source.copyOfRange(from, to), plain)
        }
    }

    @Test
    fun lastShortChunk_isNotPaddedOnReadBack() = runTest {
        val source = ByteArray(chunkSize + 3) { 9 }
        val ingest = store.encryptInto(source.inputStream(), "f4", chunkSize, stride()) { encrypt(it) }!!
        assertEquals(2, ingest.chunkCount)

        val tail = decrypt(store.readChunk("f4", 1, stride())!!)
        assertEquals(3, tail.size)
    }

    @Test
    fun emptyFile_isStillOneChunk() = runTest {
        // Zero chunks would mean nothing to publish and a receiver waiting for ever.
        val ingest = store.encryptInto(ByteArray(0).inputStream(), "f5", chunkSize, stride()) { encrypt(it) }!!

        assertEquals(1, ingest.chunkCount)
        assertEquals(0L, ingest.sizeBytes)
        assertEquals(sha256(ByteArray(0)), ingest.contentHash)

        val out = ByteArrayOutputStream()
        assertEquals(ingest.contentHash, store.decryptTo("f5", 1, stride(), out) { decrypt(it) })
        assertEquals(0, out.size())
    }

    @Test
    fun ingest_replacesAnyEarlierBlob() = runTest {
        // Re-encrypting a file — the legacy at-rest migration, or a re-upload — must not leave
        // a longer previous blob's tail behind, which would decrypt into trailing garbage.
        store.encryptInto(ByteArray(chunkSize * 4).inputStream(), "f6", chunkSize, stride()) { encrypt(it) }
        val second = ByteArray(chunkSize) { 1 }
        val ingest = store.encryptInto(second.inputStream(), "f6", chunkSize, stride()) { encrypt(it) }!!

        assertEquals(1, ingest.chunkCount)
        val out = ByteArrayOutputStream()
        store.decryptTo("f6", 1, stride(), out) { decrypt(it) }
        assertArrayEquals(second, out.toByteArray())
    }

    @Test
    fun wrongKey_failsToVerify() = runTest {
        val ingest = store.encryptInto(
            ByteArray(chunkSize).inputStream(), "f7", chunkSize, stride()
        ) { encrypt(it) }!!

        val other = ByteArray(32) { 42 }
        val hash = store.decryptTo("f7", ingest.chunkCount, stride(), ByteArrayOutputStream()) { slot ->
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(other, "AES"),
                GCMParameterSpec(gcmTagBits, slot.copyOfRange(0, nonceBytes))
            )
            cipher.doFinal(slot.copyOfRange(nonceBytes, slot.size))
        }

        assertNull("GCM must reject the wrong key rather than return bytes", hash)
    }

    @Test
    fun fullBitmap_matchesOneBuiltChunkByChunk() {
        // The uploader marks every chunk held at once; a receiver arrives at the same bitmap
        // one chunk at a time. If those two disagree, an uploaded file looks incomplete.
        for (count in intArrayOf(1, 7, 8, 9, 3200)) {
            var built: ByteArray? = null
            for (i in 0 until count) built = ChunkBitmap.withBitSet(built, i, count)

            assertArrayEquals("chunkCount=$count", built, ChunkBitmap.full(count))
            assertEquals(count, ChunkBitmap.count(ChunkBitmap.full(count), count))
            assertTrue(ChunkBitmap.isComplete(ChunkBitmap.full(count), count))
        }
    }

    private fun indexOfSubsequence(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
