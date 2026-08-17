package com.example.familysafety.vault

import kotlinx.serialization.Serializable

/**
 * One document in a vault, as the app holds it after a code has opened the container.
 *
 * Never persisted in this form. It exists only while a vault is open, and goes away with the
 * key when the screen stops or the session times out.
 */
data class VaultItem(
    val fileId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentHash: String,
    val contentKeyHex: String,
    val chunkCount: Int,
    val addedAt: Long,
    val addedBy: String,
    val state: String,
    /** Which container slots carry this item, so a rewrite lands on the same ones. */
    val slots: List<Int> = emptyList()
) {
    val isActive: Boolean get() = state == VAULT_STATE_ACTIVE
}

/** Visible in the vault. */
const val VAULT_STATE_ACTIVE = "ACTIVE"

/**
 * Deleted, but the slot still holds the record.
 *
 * Deletion is recoverable by decision: the slot is rewritten with the state flipped rather
 * than overwritten with random bytes, so an accident — or a deletion made under pressure —
 * can be undone until the slot is reused for something else. The document's bytes are kept
 * too, for the same reason.
 */
const val VAULT_STATE_RECLAIMED = "RECLAIMED"

/**
 * What is actually sealed into a slot.
 *
 * Metadata only. The document's bytes live in the vault blob store under [fileId], encrypted
 * with [contentKeyHex] — a key that exists nowhere except inside this slot. A device that
 * cannot open the slot holds the bytes and cannot read them, and cannot tell them from any
 * other blob it is storing.
 *
 * [pad] brings every slot to exactly the same plaintext length, so no slot's ciphertext
 * reveals anything by its size — every slot in the container is byte-for-byte the same width
 * whether it holds a document, a tombstone, or nothing at all.
 */
@Serializable
data class VaultSlotPayload(
    val fileId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentHash: String,
    val contentKeyHex: String,
    val chunkCount: Int,
    val addedAt: Long,
    val addedBy: String,
    val state: String = VAULT_STATE_ACTIVE,
    /** Bumped on every rewrite of this item, so the newer of two copies wins. */
    val revision: Long = 0,
    val pad: String = ""
)

/**
 * The container as it crosses the wire: the whole fixed-size blob, versioned and signed.
 *
 * The plan called for the container to sync as an ordinary shared file with no new sync path.
 * That does not work — the file pipeline is content-addressed and treats a completed file as
 * immutable, while the container is rewritten every time anyone adds a document. At
 * [VaultSlots.CONTAINER_BYTES] this fits in a single retained message, so it gets the same
 * treatment as the file manifest instead: retained, versioned, signed by a current member and
 * verified against our own roster.
 *
 * [data] is the container verbatim. It is *not* encrypted here — it is already nothing but
 * AES-GCM ciphertext and random filler, and wrapping it in the group key would only hide from
 * the relay the fact that a container exists, which it can infer from the topic anyway.
 */
@Serializable
data class VaultContainerMessage(
    /** Monotonic; the higher version wins, ties broken by [VaultSlots] merge rules. */
    val version: Long,
    /** Base64 of the whole container. */
    val data: String,
    val signerMemberId: String = "",
    /** Hex Ed25519 over [vaultSigningPayload]. */
    val signature: String = ""
)

/**
 * The bytes a container signature commits to.
 *
 * The version is inside the payload so a valid signature cannot be replayed at a higher
 * version to roll the family back to an older container.
 */
fun vaultSigningPayload(version: Long, data: String): ByteArray =
    "$version|$data".toByteArray(Charsets.UTF_8)

/**
 * A chunk of a vault document.
 *
 * Deliberately not the file subsystem's `FileChunkMessage`. That path requires a row in
 * `shared_files`, which requires the document to appear in the manifest — and a vault
 * document that appears in the manifest is not in a vault. These chunks are stored by any
 * device that receives them, under the id they carry, with no record of what they are.
 */
@Serializable
data class VaultChunkMessage(
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    /** Base64 of `nonce ‖ ciphertext ‖ tag` under the item's own content key. */
    val data: String
)

/** A request for specific chunks of a vault document, addressed to one peer. */
@Serializable
data class VaultChunkRequest(
    val requesterId: String,
    val fileId: String,
    val missing: List<Int>,
    val timestamp: Long = System.currentTimeMillis()
)
