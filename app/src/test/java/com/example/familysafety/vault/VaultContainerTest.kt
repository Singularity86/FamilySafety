package com.example.familysafety.vault

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
import java.io.File
import java.security.SecureRandom

/**
 * The container on disk.
 *
 * What matters here is what a file-system snapshot of the phone would show: a file that is
 * always present, always the same size, and full of bytes that look like every other byte in
 * it. Anything that made a container with a vault look different from one without would
 * defeat the feature no matter how good the cryptography above it was.
 */
class VaultContainerTest {

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var container: VaultContainer

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "fs-vault-${System.nanoTime()}")
        dir.mkdirs()
        file = File(dir, "container.bin")
        container = VaultContainer { file }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun firstReadCreatesAFullSizeContainer() = runTest {
        assertFalse(file.exists())

        val bytes = container.read()

        assertEquals(VaultSlots.CONTAINER_BYTES, bytes.size)
        assertTrue(file.exists())
        assertEquals(VaultSlots.CONTAINER_BYTES.toLong(), file.length())
    }

    @Test
    fun aContainerWithAVaultIsTheSameSizeAsOneWithout() = runTest {
        val empty = container.read()
        val emptyLength = file.length()

        val key = ByteArray(32) { 7 }
        container.update { current ->
            var next = current
            VaultSlots.allocate(current, key, "f1").forEach { slot ->
                next = VaultSlots.withSlot(
                    next, slot,
                    VaultSlots.seal(
                        VaultSlotPayload(
                            fileId = "f1", name = "passport.pdf", mimeType = "application/pdf",
                            sizeBytes = 1024, contentHash = "aa".repeat(32),
                            contentKeyHex = "bb".repeat(32), chunkCount = 1,
                            addedAt = 0, addedBy = "c".repeat(32)
                        ),
                        key
                    )
                )
            }
            next
        }

        assertEquals(emptyLength, file.length())
        assertEquals(VaultSlots.CONTAINER_BYTES, empty.size)
        assertEquals(1, VaultSlots.openAll(container.read(), key).size)
    }

    @Test
    fun theContainerHasNoHeaderToRecogniseItBy() = runTest {
        // Two fresh containers must share no prefix — a magic number, a version byte, anything
        // constant would be a marker saying a vault feature lives here.
        val first = container.read()
        file.delete()
        val second = VaultContainer { file }.read()

        assertFalse(first.copyOfRange(0, 32).contentEquals(second.copyOfRange(0, 32)))
    }

    @Test
    fun updateIsAtomicAcrossReadAndWrite() = runTest {
        val key = ByteArray(32) { 3 }
        container.read()

        val marked = container.update { current ->
            VaultSlots.withSlot(current, 0, ByteArray(VaultSlots.SLOT_SIZE) { 1 })
        }

        assertNotNull(marked)
        assertArrayEquals(ByteArray(VaultSlots.SLOT_SIZE) { 1 }, VaultSlots.slotAt(container.read(), 0))
        assertTrue(VaultSlots.openAll(container.read(), key).isEmpty())
    }

    @Test
    fun aMutationThatReturnsNullChangesNothing() = runTest {
        val before = container.read()

        assertNull(container.update { null })

        assertArrayEquals(before, container.read())
    }

    @Test
    fun aTruncatedContainerIsReplacedRatherThanUsed() = runTest {
        container.read()
        file.writeBytes(ByteArray(128))

        val bytes = container.read()

        // There is nothing to salvage: without a key no part of a container can be told from
        // any other part, so a short one is replaced outright.
        assertEquals(VaultSlots.CONTAINER_BYTES, bytes.size)
        assertEquals(VaultSlots.CONTAINER_BYTES.toLong(), file.length())
    }

    @Test
    fun writeRefusesTheWrongSize() = runTest {
        var threw = false
        try {
            container.write(ByteArray(16))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a short container would leak that fewer slots are in use", threw)
    }

    @Test
    fun theAcceptedVersionSurvivesARestart() = runTest {
        // The bug this pins: the high-water mark used to live in memory only, so it reset to
        // zero on every launch and "ignore anything older" quietly stopped applying. The first
        // container to arrive after a restart was taken whatever its version, which meant a
        // captured old one rolled the vault back and dropped everything added since.
        assertEquals(0L, container.readVersion())

        container.writeVersion(1_700_000_000_000)

        val afterRestart = VaultContainer { file }
        assertEquals(1_700_000_000_000, afterRestart.readVersion())
    }

    @Test
    fun anUnreadableVersionSidecarReadsAsZeroRatherThanThrowing() = runTest {
        container.read()
        File(dir, "container.bin.ver").writeText("not a number")

        // Falling back to zero re-opens the replay window for one message, which is bad;
        // throwing would take out every container update from then on, which is worse.
        assertEquals(0L, container.readVersion())
    }

    @Test
    fun theVersionSidecarIsSeparateFromTheContainer() = runTest {
        container.read()
        container.writeVersion(42)

        // Anything stored inside the container would either cost a slot or change its fixed
        // size, and the size is the one thing an adversary can measure without a key.
        assertEquals(VaultSlots.CONTAINER_BYTES.toLong(), file.length())
        assertEquals(42L, container.readVersion())
    }

    @Test
    fun aWrittenContainerReadsBackExactly() = runTest {
        val payload = VaultSlots.randomContainer(SecureRandom())

        assertTrue(container.write(payload))

        assertArrayEquals(payload, container.read())
    }
}
