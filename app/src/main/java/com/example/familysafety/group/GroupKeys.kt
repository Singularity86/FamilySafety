package com.example.familysafety.group

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One generation of the family's shared key (remediation 3.3, F17).
 *
 * @property kid first 8 bytes of SHA-256(key bytes), as 16 lowercase hex. Derived from the key,
 *   so a kid can't be paired with different key bytes.
 * @property epoch how many removals this key postdates: a key made after the n-th tombstone
 *   has epoch n.
 * @property key 32 random bytes, as 64 lowercase hex. Never log or display.
 */
@Serializable
data class GroupKey(val kid: String, val epoch: Int, val key: String)

/**
 * The family's keyring: [GroupDefinition.fileEncryptionKey] as epoch 0, plus a new key for every
 * removal.
 *
 * Someone who leaves or is removed keeps every key they ever held, so without rotation they could
 * go on opening sealed presence, the retained manifest and every new file. After a removal a
 * remaining member generates the next key and sends it in a group sync, which is encrypted to
 * current members only. Old keys stay in the ring, so everything published before stays readable.
 *
 * Pure, so the rules can be tested without a group manager.
 */
object GroupKeys {

    /**
     * Protocol version every counted member must advertise before anyone rotates. A member on an
     * older build can't read a rotated key's traffic at all, so rotating early would cut them off.
     */
    const val ROTATION_PROTOCOL_VERSION = 4

    fun kidOf(keyHex: String): String =
        MessageDigest.getInstance("SHA-256").digest(keyHex.hexToBytes())
            .copyOfRange(0, 8).joinToString("") { "%02x".format(it) }

    fun newKey(epoch: Int): GroupKey {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return GroupKey(kidOf(key), epoch, key)
    }

    /** Every key this family has had, epoch 0 first. Empty for a family created before 1.12.0. */
    fun ring(def: GroupDefinition): List<GroupKey> {
        val base = def.fileEncryptionKey?.takeIf { it.isNotBlank() }?.lowercase()
            ?.let { listOf(GroupKey(kidOf(it), 0, it)) }.orEmpty()
        return base + def.groupKeys.filter { it.epoch > 0 }
    }

    /**
     * The key to encrypt with: the highest epoch, ties broken by the lowest kid, so phones that
     * rotated at the same moment all settle on the same key.
     */
    fun current(def: GroupDefinition): GroupKey? =
        ring(def).sortedWith(compareByDescending<GroupKey> { it.epoch }.thenBy { it.kid }).firstOrNull()

    /**
     * The key a message stamped with [kid] was sealed under. An absent kid means
     * [GroupDefinition.fileEncryptionKey], which is what every message carried before rotation.
     * Null when this phone doesn't hold that key.
     */
    fun keyHexFor(def: GroupDefinition, kid: String?): String? =
        if (kid == null) def.fileEncryptionKey?.takeIf { it.isNotBlank() }
        else ring(def).firstOrNull { it.kid.equals(kid, ignoreCase = true) }?.key

    /** The kid to stamp on outgoing traffic, or null when it is the epoch-0 key (stay byte-compatible). */
    fun stampFor(def: GroupDefinition): String? = current(def)?.takeIf { it.epoch > 0 }?.kid

    /** True when a removal has happened since the newest key was made. */
    fun rotationDue(def: GroupDefinition): Boolean {
        val newest = current(def) ?: return false
        return def.removedMemberIds.size > newest.epoch
    }

    /** [def] with a fresh key for its current tombstone count added. */
    fun rotated(def: GroupDefinition): GroupDefinition =
        def.copy(groupKeys = def.groupKeys + newKey(def.removedMemberIds.size))

    /** Union of both keyrings. Entries are never dropped, or older files would stop opening. */
    fun union(a: List<GroupKey>, b: List<GroupKey>): List<GroupKey> =
        (a + b).distinctBy { it.kid.lowercase() }.sortedWith(compareBy<GroupKey> { it.epoch }.thenBy { it.kid })

    /**
     * Why [remote]'s keyring can't be accepted on top of [current], or null if it can. Existing
     * entries are immutable; a new one must be genuine (kid matches key) and no newer than the
     * removals in the same state, so a member can't keep minting keys.
     */
    fun rejectReason(current: GroupDefinition, remote: GroupDefinition): String? {
        val known = current.groupKeys.associateBy { it.kid.lowercase() }
        for (k in remote.groupKeys) {
            if (!k.key.matches(HEX_64)) return "malformed group key"
            if (kidOf(k.key) != k.kid.lowercase()) return "group key id doesn't match its key"
            val existing = known[k.kid.lowercase()]
            if (existing != null) {
                if (existing.epoch != k.epoch) return "group key epoch changed"
                continue
            }
            if (k.epoch < 1 || k.epoch > remote.removedMemberIds.size) return "group key epoch out of range"
        }
        return null
    }

    /**
     * [def] as it may be sent to [recipientId]. Someone outside the roster gets no rotated key at
     * all, whatever path the state takes to reach them. Tombstones are a set, so "keys newer than
     * their removal" can't be worked out; the keys before it they held anyway, and losing them
     * costs a removed member nothing they're owed.
     */
    fun forRecipient(def: GroupDefinition, recipientId: String): GroupDefinition =
        if (def.members.any { it.memberId == recipientId }) def else def.copy(groupKeys = emptyList())

    /** Seen-by-receive-time record of what a member's presence advertised. */
    data class Sighting(val protocolVersion: Int, val seenAtMs: Long)

    /** A member silent this long no longer holds the family back (a lost phone never returns). */
    const val GATE_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * Whether every member who counts advertises at least [required].
     *
     * Counted: anyone seen within [GATE_WINDOW_MS]. A member never seen blocks the gate until this
     * phone has been watching for the whole window, so a fresh install doesn't open it before it
     * has heard from anyone.
     */
    fun everyoneAtLeast(
        memberIds: Collection<String>,
        selfId: String,
        sightings: Map<String, Sighting>,
        observingSinceMs: Long,
        nowMs: Long,
        required: Int
    ): Boolean = memberIds.filter { it != selfId }.all { id ->
        val s = sightings[id]
        when {
            s == null -> nowMs - observingSinceMs >= GATE_WINDOW_MS
            nowMs - s.seenAtMs > GATE_WINDOW_MS -> true
            else -> s.protocolVersion >= required
        }
    }

    private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
