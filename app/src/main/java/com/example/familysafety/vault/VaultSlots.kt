package com.example.familysafety.vault

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The container format, and the reason a wrong code cannot be told from a right one.
 *
 * The container is a fixed-size array of fixed-size slots, created full of `SecureRandom`
 * bytes whether or not anyone ever sets a code. A slot holding a document is AES-256-GCM
 * ciphertext; a slot holding nothing is random bytes; the two are the same width and, without
 * the key, the same thing. Opening a vault means trying to decrypt **every** slot and keeping
 * whichever ones authenticate.
 *
 * Three properties follow, and all three are the point:
 *
 * - **Every code works.** The family code authenticates the real slots, a decoy code
 *   authenticates the decoy slots, and any other code authenticates nothing and yields an
 *   empty vault. There is no error to report, and reporting one would prove a correct code
 *   exists.
 * - **Nothing records that a vault was ever created.** There is no setup flag, no count, no
 *   header — so there is no state to find and no question to answer about it.
 * - **The number of vaults cannot be counted**, because real, decoy and filler slots are
 *   mutually indistinguishable.
 *
 * ## The cost of that, stated plainly
 *
 * Indistinguishable slots cannot be allocated safely. A device holding only the decoy code
 * cannot tell a free slot from one belonging to the family vault, so writing to one vault can
 * overwrite a slot belonging to a vault whose code this device does not know. That is
 * inherent — the property that makes the decoy credible is the same property that makes
 * allocation blind. Two things blunt it: the container is large relative to what a family
 * stores ([SLOT_COUNT] slots for at most [MAX_ITEMS] documents per vault), and every item is
 * written to [REPLICAS] slots, so losing one copy to a collision does not lose the document.
 */
object VaultSlots {

    /**
     * Slots in the container. Large relative to what anyone stores, so blind allocation
     * rarely contends. Costs 128 KiB regardless of use, which is the price of the container
     * being the same size for a family that has a vault and one that does not.
     */
    const val SLOT_COUNT = 256

    /** Bytes per slot: nonce + ciphertext + tag. Every slot is exactly this wide. */
    const val SLOT_SIZE = 512

    const val CONTAINER_BYTES = SLOT_COUNT * SLOT_SIZE

    /** Documents one code may hold. Two codes at capacity still fit the container. */
    const val MAX_ITEMS = 64

    /**
     * Slots each item is written to.
     *
     * Redundancy against blind allocation: another vault overwriting one copy leaves the
     * other, so a collision costs redundancy rather than the document.
     */
    const val REPLICAS = 2

    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /** Plaintext room in a slot, after the nonce and tag. Every payload is padded to this. */
    const val PAYLOAD_BYTES = SLOT_SIZE - NONCE_BYTES - GCM_TAG_BITS / 8

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A container full of random bytes — what every device starts with, and never announces. */
    fun randomContainer(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(CONTAINER_BYTES).also { random.nextBytes(it) }

    fun slotAt(container: ByteArray, index: Int): ByteArray {
        require(index in 0 until SLOT_COUNT) { "slot $index out of range" }
        val from = index * SLOT_SIZE
        return container.copyOfRange(from, from + SLOT_SIZE)
    }

    fun withSlot(container: ByteArray, index: Int, slot: ByteArray): ByteArray {
        require(index in 0 until SLOT_COUNT) { "slot $index out of range" }
        require(slot.size == SLOT_SIZE) { "a slot must be exactly $SLOT_SIZE bytes" }
        return container.copyOf().also { slot.copyInto(it, index * SLOT_SIZE) }
    }

    /**
     * Seal a payload into exactly one slot's worth of bytes.
     *
     * Padding happens before encryption and targets a constant, so a slot holding a long
     * filename is the same size as one holding a short one, and a tombstone is the same size
     * as a document.
     */
    fun seal(payload: VaultSlotPayload, key: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val plaintext = pad(payload)
        require(plaintext.size == PAYLOAD_BYTES) {
            "payload is ${plaintext.size} bytes, must be exactly $PAYLOAD_BYTES"
        }
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    /**
     * Try to open a slot.
     *
     * Null means "not sealed under this key", which covers a slot belonging to another code
     * and a slot that is simply random filler. The caller cannot tell those apart, and must
     * not try to.
     */
    fun open(slot: ByteArray, key: ByteArray): VaultSlotPayload? {
        if (slot.size != SLOT_SIZE) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, slot.copyOfRange(0, NONCE_BYTES))
            )
            val plain = cipher.doFinal(slot.copyOfRange(NONCE_BYTES, slot.size))
            // Padding lives inside the JSON, so the plaintext parses as it stands.
            json.decodeFromString<VaultSlotPayload>(String(plain, Charsets.UTF_8))
        }.getOrNull()
    }

    /**
     * Everything in the container that opens under [key], newest revision per item.
     *
     * An item written to several slots appears several times; the highest revision wins, which
     * is what makes a partly-overwritten container heal rather than show a stale copy.
     */
    fun openAll(container: ByteArray, key: ByteArray): List<VaultItem> {
        val bySlot = ArrayList<Pair<Int, VaultSlotPayload>>()
        for (i in 0 until SLOT_COUNT) {
            open(slotAt(container, i), key)?.let { bySlot.add(i to it) }
        }
        return bySlot
            .groupBy { it.second.fileId }
            .map { (_, copies) ->
                val newest = copies.maxByOrNull { it.second.revision }!!.second
                VaultItem(
                    fileId = newest.fileId,
                    name = newest.name,
                    mimeType = newest.mimeType,
                    sizeBytes = newest.sizeBytes,
                    contentHash = newest.contentHash,
                    contentKeyHex = newest.contentKeyHex,
                    chunkCount = newest.chunkCount,
                    addedAt = newest.addedAt,
                    addedBy = newest.addedBy,
                    state = newest.state,
                    slots = copies.map { it.first }.sorted()
                )
            }
            .sortedByDescending { it.addedAt }
    }

    /**
     * The order this key walks the container in, as a permutation of every slot index.
     *
     * Derived from the key, so a vault keeps returning to its own slots instead of scattering
     * new writes across the container and colliding with its neighbours more often than it
     * needs to. Deterministic across devices, so two phones holding the same code agree about
     * where an item belongs without coordinating.
     */
    fun slotOrder(key: ByteArray): List<Int> {
        // Fisher-Yates driven by a SHA-256 stream over the key, rather than a seeded
        // java.util.Random, whose sequence is a JDK implementation detail and could differ
        // between platforms — this has to match on iOS.
        val order = (0 until SLOT_COUNT).toMutableList()
        var block = MessageDigest.getInstance("SHA-256").digest(key + "slots".toByteArray())
        var offset = 0
        for (i in SLOT_COUNT - 1 downTo 1) {
            if (offset + 4 > block.size) {
                block = MessageDigest.getInstance("SHA-256").digest(block)
                offset = 0
            }
            var v = 0L
            for (b in 0 until 4) v = (v shl 8) or (block[offset + b].toLong() and 0xff)
            offset += 4
            val j = (v % (i + 1)).toInt()
            val tmp = order[i]; order[i] = order[j]; order[j] = tmp
        }
        return order
    }

    /**
     * Slots to write [fileId] into: the ones it already occupies, topped up from this key's
     * order with slots that do not currently open under this key.
     *
     * "Does not open under this key" is the only test available. It is exactly the point at
     * which another vault's slot can be taken, and there is no way to do better without
     * revealing that the other vault exists.
     */
    fun allocate(
        container: ByteArray,
        key: ByteArray,
        fileId: String,
        existing: List<Int> = emptyList()
    ): List<Int> {
        val chosen = existing.filter { it in 0 until SLOT_COUNT }.toMutableList()
        if (chosen.size >= REPLICAS) return chosen.take(REPLICAS)

        for (index in slotOrder(key)) {
            if (chosen.size >= REPLICAS) break
            if (index in chosen) continue
            val occupant = open(slotAt(container, index), key)
            // Ours and holding something else: leave it alone. Ours and holding this very
            // item: reuse it. Not ours: it is free as far as anything here can determine.
            if (occupant != null && occupant.fileId != fileId) continue
            chosen.add(index)
        }
        return chosen
    }

    /**
     * Pad a payload out to exactly [PAYLOAD_BYTES].
     *
     * Returns the JSON as UTF-8 with `.` filler in [VaultSlotPayload.pad]. Throws if the
     * payload cannot fit, which a caller must prevent by bounding the name — a slot that
     * cannot hold its own contents is a bug, not a runtime condition to absorb.
     */
    fun pad(payload: VaultSlotPayload): ByteArray {
        val bare = json.encodeToString(payload.copy(pad = "")).toByteArray(Charsets.UTF_8).size
        val room = PAYLOAD_BYTES - bare
        require(room >= 0) { "vault slot payload is $bare bytes, over the $PAYLOAD_BYTES limit" }
        // '.' needs no JSON escaping, so N characters add exactly N bytes.
        return json.encodeToString(payload.copy(pad = ".".repeat(room))).toByteArray(Charsets.UTF_8)
    }

    /** The longest name that still fits a slot, given everything else the payload carries. */
    fun fitName(payload: VaultSlotPayload): VaultSlotPayload {
        var candidate = payload
        while (true) {
            val size = json.encodeToString(candidate.copy(pad = "")).toByteArray(Charsets.UTF_8).size
            if (size <= PAYLOAD_BYTES || candidate.name.length <= 1) return candidate
            val over = size - PAYLOAD_BYTES
            val trimTo = (candidate.name.length - maxOf(over, 1)).coerceAtLeast(1)
            candidate = candidate.copy(name = candidate.name.take(trimTo))
        }
    }
}
