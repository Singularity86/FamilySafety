package com.example.familysafety.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The replay window inside every E2EE envelope (remediation 3.4, F16/A6).
 *
 * An envelope's signature proves who sent it, not when. A captured envelope could be published
 * again any number of times and each copy was taken as new: an old roster update made every phone
 * rebroadcast the roster, an old vote or receipt counted again. Now, to a peer that speaks
 * protocol 4, the plaintext inside the envelope is a [SequencedBody] carrying a per-sender
 * sequence number and send time, and the receiver keeps a window of the numbers it has taken.
 *
 * Pure JVM: storage and the clock are passed in.
 */
@Serializable
data class SequencedBody(val seq: Long, val ts: Long, val body: String) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(seq: Long, ts: Long, body: String): String = json.encodeToString(SequencedBody(seq, ts, body))

        /** The sequenced form of [plaintext], or null if it is an unsequenced (pre-3.4) body. */
        fun parse(plaintext: String): SequencedBody? {
            if (!plaintext.trimStart().startsWith("{")) return null
            return try {
                json.decodeFromString<SequencedBody>(plaintext)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** What the window makes of one envelope. Same names as iOS `ReplayWindow.swift`. */
enum class ReplayVerdict { ACCEPT, TOO_OLD, FROM_THE_FUTURE, REPLAY, UNSEQUENCED_AFTER_SEQUENCED }

/** An envelope that authenticated but was refused by the replay window. Not a key problem. */
class ReplayedEnvelopeException(val verdict: ReplayVerdict) : EncryptionException("envelope refused: $verdict")

/**
 * Sequence numbers this phone has taken from one sender: everything at or below [floor] is
 * refused, and [seen] holds the ones above it, at most [EnvelopeReplay.SEEN_CAP]. Taking one past
 * the cap evicts the smallest and raises the floor to it, so a late arrival is still taken as long
 * as fewer than the cap newer ones overtook it.
 */
data class ReplayWindow(val floor: Long, val seen: Set<Long>) {

    /** The window after taking [seq], or null if [seq] must be refused. */
    fun admit(seq: Long): ReplayWindow? {
        if (seq <= floor || seq in seen) return null
        val all = (seen + seq).sorted()
        if (all.size <= EnvelopeReplay.SEEN_CAP) return ReplayWindow(floor, all.toSet())
        val evicted = all.size - EnvelopeReplay.SEEN_CAP
        return ReplayWindow(all[evicted - 1], all.drop(evicted).toSet())
    }

    fun serialize(): String = "$floor|" + seen.sorted().joinToString(",")

    companion object {
        val EMPTY = ReplayWindow(Long.MIN_VALUE, emptySet())

        fun deserialize(text: String): ReplayWindow? = runCatching {
            val floor = text.substringBefore('|').toLong()
            val rest = text.substringAfter('|', "")
            ReplayWindow(floor, if (rest.isEmpty()) emptySet() else rest.split(',').map { it.toLong() }.toSet())
        }.getOrNull()
    }
}

class EnvelopeReplay(
    private val store: Store,
    /** The protocol version [memberId] last advertised in authenticated presence, or null. */
    private val advertisedVersion: (memberId: String) -> Int?
) {
    interface Store {
        fun lastSeq(): Long
        fun setLastSeq(seq: Long)
        /** [senderKey] is the sender's member ID and Ed25519 key; see [senderKey]. */
        fun window(senderKey: String): ReplayWindow?
        fun setWindow(senderKey: String, window: ReplayWindow)
        /** When this phone first took a sequenced body from [senderKey]; null if never, or before this was kept. */
        fun firstSequencedAt(senderKey: String): Long? = null
        fun setFirstSequencedAt(senderKey: String, atMs: Long) {}
    }

    /**
     * Strictly increasing, and seeded from the clock so a reinstalled or restored phone needs
     * nothing saved to stay ahead of what its family has already seen.
     */
    @Synchronized
    fun nextSeq(nowMs: Long): Long {
        val seq = maxOf(store.lastSeq() + 1, nowMs * 1000)
        store.setLastSeq(seq)
        return seq
    }

    /** Whether to send [recipientId] sequenced bodies: they advertise protocol 4 or have sent us one. */
    fun sequenceFor(recipientId: String, recipientEd25519Hex: String?): Boolean =
        (advertisedVersion(recipientId) ?: 0) >= SEQUENCED_PROTOCOL_VERSION ||
            (recipientEd25519Hex != null && hasSequenced(recipientId, recipientEd25519Hex))

    fun seal(plaintext: String, nowMs: Long): String = SequencedBody.encode(nextSeq(nowMs), nowMs, plaintext)

    /** Whether this sender has ever sent us a sequenced body. */
    fun hasSequenced(memberId: String, ed25519Hex: String): Boolean =
        store.window(senderKey(memberId, ed25519Hex)) != null

    /**
     * Whether an unsequenced body (or plaintext control message) from this sender is now a
     * downgrade: they have sequenced to us, and not just now.
     *
     * Just after someone starts sequencing to us, their earlier unsequenced messages can still
     * arrive: a member sends a newcomer several at once while learning that the newcomer reads
     * sequenced bodies, and LAN and broker deliver at different speeds. Refusing those dropped
     * the join's first messages and called the inviter outdated. A sender first seen sequencing
     * before this was kept has no time recorded and gets no grace.
     */
    fun refusesUnsequenced(memberId: String, ed25519Hex: String, nowMs: Long): Boolean =
        refusesUnsequenced(senderKey(memberId, ed25519Hex), nowMs)

    private fun refusesUnsequenced(key: String, nowMs: Long): Boolean {
        if (store.window(key) == null) return false
        val first = store.firstSequencedAt(key) ?: return true
        return nowMs - first > SEQUENCING_GRACE_MS
    }

    /**
     * The body of an authenticated envelope's [plaintext] from [senderId], or a
     * [ReplayedEnvelopeException] if the window refuses it. Call only after the signature has
     * verified under [senderEd25519Hex].
     */
    @Synchronized
    fun open(senderId: String, senderEd25519Hex: String, plaintext: String, nowMs: Long): String {
        val key = senderKey(senderId, senderEd25519Hex)
        val sequenced = SequencedBody.parse(plaintext)
        val window = store.window(key)
        if (sequenced == null) {
            if (refusesUnsequenced(key, nowMs)) throw ReplayedEnvelopeException(ReplayVerdict.UNSEQUENCED_AFTER_SEQUENCED)
            return plaintext
        }
        if (sequenced.ts < nowMs - MAX_AGE_MS) throw ReplayedEnvelopeException(ReplayVerdict.TOO_OLD)
        // A sender whose clock is far ahead would push its window ahead of every message it
        // sends once the clock is fixed, so refuse that now rather than silently later.
        if (sequenced.ts > nowMs + MAX_SKEW_MS || sequenced.seq > (nowMs + MAX_SKEW_MS) * 1000) {
            throw ReplayedEnvelopeException(ReplayVerdict.FROM_THE_FUTURE)
        }
        val next = (window ?: ReplayWindow.EMPTY).admit(sequenced.seq)
            ?: throw ReplayedEnvelopeException(ReplayVerdict.REPLAY)
        store.setWindow(key, next)
        if (window == null) store.setFirstSequencedAt(key, nowMs)
        return sequenced.body
    }

    companion object {
        /** First protocol version whose envelopes are sequenced. */
        const val SEQUENCED_PROTOCOL_VERSION = 4
        const val SEEN_CAP = 256
        const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
        const val MAX_SKEW_MS = 24 * 60 * 60 * 1000L
        /** How long after a sender first sequences to us their unsequenced messages still count. */
        const val SEQUENCING_GRACE_MS = 10 * 60 * 1000L

        /**
         * Windows are kept per member *and key*. The envelope's member ID is a claim checked only
         * against the key the caller looked up; a join request brings its own key, so keyed by ID
         * alone a joiner could name a member and push that member's window past all their traffic.
         */
        fun senderKey(memberId: String, ed25519Hex: String): String = "$memberId:${ed25519Hex.lowercase()}"
    }
}
