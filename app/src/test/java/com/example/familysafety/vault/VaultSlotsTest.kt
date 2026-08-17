package com.example.familysafety.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The vault's central claim is negative: that nothing distinguishes a correct code from an
 * incorrect one, a real vault from a decoy, or an occupied slot from filler. A negative claim
 * is only worth anything if it is tested, so these tests try to tell those apart and fail if
 * they can.
 *
 * Argon2id is not exercised here — it needs libsodium's native library, which a JVM test does
 * not have. That is fine: derivation turns a code into 32 bytes and nothing else, so these
 * tests supply the 32 bytes directly and cover everything downstream of it.
 */
class VaultSlotsTest {

    private val random = SecureRandom()

    private fun key(seed: Byte) = ByteArray(32) { (it + seed).toByte() }

    private fun payload(
        fileId: String = "11111111-2222-3333-4444-555555555555",
        name: String = "insurance.pdf",
        state: String = VAULT_STATE_ACTIVE,
        revision: Long = 0
    ) = VaultSlotPayload(
        fileId = fileId,
        name = name,
        mimeType = "application/pdf",
        sizeBytes = 24_576,
        contentHash = "ab".repeat(32),
        contentKeyHex = "cd".repeat(32),
        chunkCount = 1,
        addedAt = 1_700_000_000_000,
        addedBy = "f".repeat(32),
        state = state,
        revision = revision
    )

    @Test
    fun anyCodeOpensAVault_andAWrongOneIsSimplyEmpty() {
        val familyKey = key(1)
        val strangerKey = key(9)

        var container = VaultSlots.randomContainer(random)
        container = VaultSlots.withSlot(container, 5, VaultSlots.seal(payload(), familyKey, random))

        assertEquals(1, VaultSlots.openAll(container, familyKey).size)
        // Not an error, not a signal, not a difference of any kind the caller could act on.
        assertTrue(VaultSlots.openAll(container, strangerKey).isEmpty())
    }

    @Test
    fun realDecoyAndFillerSlotsAreTheSameSize() {
        val realKey = key(1)
        val decoyKey = key(2)

        val real = VaultSlots.seal(payload(name = "a-very-long-document-name-for-a-passport.pdf"), realKey, random)
        val decoy = VaultSlots.seal(payload(fileId = "x", name = "b.pdf"), decoyKey, random)
        val tombstone = VaultSlots.seal(payload(state = VAULT_STATE_RECLAIMED), realKey, random)
        val filler = VaultSlots.slotAt(VaultSlots.randomContainer(random), 0)

        // If any of these differed, counting bytes would count vaults.
        assertEquals(VaultSlots.SLOT_SIZE, real.size)
        assertEquals(VaultSlots.SLOT_SIZE, decoy.size)
        assertEquals(VaultSlots.SLOT_SIZE, tombstone.size)
        assertEquals(VaultSlots.SLOT_SIZE, filler.size)
    }

    @Test
    fun twoVaultsCoexistAndNeitherSeesTheOther() {
        val familyKey = key(1)
        val decoyKey = key(2)

        var container = VaultSlots.randomContainer(random)
        VaultSlots.allocate(container, familyKey, "real-1").forEach {
            container = VaultSlots.withSlot(container, it, VaultSlots.seal(payload(fileId = "real-1"), familyKey, random))
        }
        VaultSlots.allocate(container, decoyKey, "decoy-1").forEach {
            container = VaultSlots.withSlot(
                container, it,
                VaultSlots.seal(payload(fileId = "decoy-1", name = "utility-bill.pdf"), decoyKey, random)
            )
        }

        val family = VaultSlots.openAll(container, familyKey)
        val decoy = VaultSlots.openAll(container, decoyKey)

        assertEquals(listOf("real-1"), family.map { it.fileId })
        assertEquals(listOf("decoy-1"), decoy.map { it.fileId })
    }

    @Test
    fun containerSizeNeverChanges() {
        var container = VaultSlots.randomContainer(random)
        assertEquals(VaultSlots.CONTAINER_BYTES, container.size)

        repeat(20) { i ->
            val k = key(1)
            VaultSlots.allocate(container, k, "f$i").forEach { slot ->
                container = VaultSlots.withSlot(container, slot, VaultSlots.seal(payload(fileId = "f$i"), k, random))
            }
        }

        // Size is the one thing an adversary can measure without a key. It must not move.
        assertEquals(VaultSlots.CONTAINER_BYTES, container.size)
    }

    @Test
    fun aSlotSealedUnderOneKeyDoesNotOpenUnderAnother() {
        val sealed = VaultSlots.seal(payload(), key(1), random)
        assertNull(VaultSlots.open(sealed, key(2)))
        assertNotNull(VaultSlots.open(sealed, key(1)))
    }

    @Test
    fun randomBytesNeverOpen() {
        val k = key(1)
        var opened = 0
        repeat(200) {
            val junk = ByteArray(VaultSlots.SLOT_SIZE).also { b -> random.nextBytes(b) }
            if (VaultSlots.open(junk, k) != null) opened++
        }
        assertEquals("GCM must reject filler", 0, opened)
    }

    @Test
    fun itemsAreWrittenToSeveralSlots_andTheNewestRevisionWins() {
        val k = key(1)
        var container = VaultSlots.randomContainer(random)

        val slots = VaultSlots.allocate(container, k, "f1")
        assertEquals(VaultSlots.REPLICAS, slots.size)
        slots.forEach {
            container = VaultSlots.withSlot(container, it, VaultSlots.seal(payload(revision = 1), k, random))
        }

        // One copy is updated; the other is left stale, as it would be if a write were
        // interrupted or another vault clobbered a slot.
        container = VaultSlots.withSlot(
            container, slots.first(),
            VaultSlots.seal(payload(state = VAULT_STATE_RECLAIMED, revision = 2), k, random)
        )

        val item = VaultSlots.openAll(container, k).single()
        assertEquals(VAULT_STATE_RECLAIMED, item.state)
        assertEquals(slots.sorted(), item.slots)
    }

    @Test
    fun losingOneReplicaDoesNotLoseTheDocument() {
        val k = key(1)
        var container = VaultSlots.randomContainer(random)
        val slots = VaultSlots.allocate(container, k, "f1")
        slots.forEach {
            container = VaultSlots.withSlot(container, it, VaultSlots.seal(payload(), k, random))
        }

        // Another vault, which cannot see this one, takes one of its slots.
        val clobbered = ByteArray(VaultSlots.SLOT_SIZE).also { random.nextBytes(it) }
        container = VaultSlots.withSlot(container, slots.first(), clobbered)

        assertEquals(1, VaultSlots.openAll(container, k).size)
    }

    @Test
    fun allocateReusesTheSlotsAnItemAlreadyHas() {
        val k = key(1)
        var container = VaultSlots.randomContainer(random)
        val first = VaultSlots.allocate(container, k, "f1")
        first.forEach {
            container = VaultSlots.withSlot(container, it, VaultSlots.seal(payload(), k, random))
        }

        // Rewriting must land on the same slots, or every edit consumes fresh ones and the
        // container fills up after a handful of changes.
        assertEquals(first, VaultSlots.allocate(container, k, "f1", existing = first))
    }

    @Test
    fun allocateDoesNotTakeASlotThisVaultIsUsing() {
        val k = key(1)
        var container = VaultSlots.randomContainer(random)
        val taken = VaultSlots.allocate(container, k, "f1")
        taken.forEach {
            container = VaultSlots.withSlot(container, it, VaultSlots.seal(payload(fileId = "f1"), k, random))
        }

        val next = VaultSlots.allocate(container, k, "f2")
        assertTrue("a second document must not overwrite the first", next.none { it in taken })
    }

    @Test
    fun slotOrderIsADeterministicPermutation() {
        val a = VaultSlots.slotOrder(key(1))
        val b = VaultSlots.slotOrder(key(1))
        val other = VaultSlots.slotOrder(key(2))

        assertEquals(a, b)
        assertEquals(VaultSlots.SLOT_COUNT, a.size)
        assertEquals("every slot exactly once", VaultSlots.SLOT_COUNT, a.toSet().size)
        assertEquals((0 until VaultSlots.SLOT_COUNT).toSet(), a.toSet())
        assertNotEquals("different codes must not walk the same path", a, other)
    }

    @Test
    fun paddingMakesEveryPayloadTheSameLength() {
        val short = VaultSlots.pad(payload(name = "a.pdf"))
        val long = VaultSlots.pad(payload(name = "medical-records-2019-through-2024-scanned.pdf"))
        val tombstone = VaultSlots.pad(payload(state = VAULT_STATE_RECLAIMED))

        assertEquals(VaultSlots.PAYLOAD_BYTES, short.size)
        assertEquals(VaultSlots.PAYLOAD_BYTES, long.size)
        assertEquals(VaultSlots.PAYLOAD_BYTES, tombstone.size)
    }

    @Test
    fun anOverlongNameIsTrimmedRatherThanThrowing() {
        val fitted = VaultSlots.fitName(payload(name = "x".repeat(500)))

        assertTrue(fitted.name.length < 500)
        // The real assertion: it now fits, so sealing it cannot blow up at the point a user
        // is trying to store a document.
        assertEquals(VaultSlots.PAYLOAD_BYTES, VaultSlots.pad(fitted).size)
    }

    @Test
    fun twoSealsOfTheSamePayloadDiffer() {
        val k = key(1)
        val a = VaultSlots.seal(payload(), k, random)
        val b = VaultSlots.seal(payload(), k, random)

        // Independent nonces, so the two replicas of one item are not recognisable as copies
        // of each other by anyone without the key.
        assertFalse(a.contentEquals(b))
    }

    private fun assertNotNull(value: Any?) = assertTrue(value != null)
}
