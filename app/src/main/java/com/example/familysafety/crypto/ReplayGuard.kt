package com.example.familysafety.crypto

import android.content.Context
import com.example.familysafety.security.SecurityEventKind
import com.example.familysafety.security.SecurityRecorder
import com.example.familysafety.transport.PeerSightings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [EnvelopeReplay] kept across restarts (3.4).
 *
 * The whole window is saved, not only the highest number: MQTT orders messages per topic only, so
 * a sender's group sync can overtake its chat, and a restart in between must still take the chat.
 * Windows are held in memory and written at most once a second, since every envelope moves one.
 */
@Singleton
class ReplayGuard @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sightings = PeerSightings(context)

    private val windows = ConcurrentHashMap<String, ReplayWindow>()
    private val dirty = ConcurrentHashMap.newKeySet<String>()
    private val flushScheduled = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "replay-guard").apply { isDaemon = true }
    }

    private val store = object : EnvelopeReplay.Store {
        override fun lastSeq(): Long = prefs.getLong(KEY_LAST_SEQ, 0L)

        // Committed before the number is used: a reused sequence number would be refused.
        override fun setLastSeq(seq: Long) {
            prefs.edit().putLong(KEY_LAST_SEQ, seq).commit()
        }

        override fun window(senderKey: String): ReplayWindow? =
            windows[senderKey] ?: prefs.getString(WINDOW_PREFIX + senderKey, null)
                ?.let { ReplayWindow.deserialize(it) }
                ?.also { windows[senderKey] = it }

        override fun setWindow(senderKey: String, window: ReplayWindow) {
            windows[senderKey] = window
            dirty.add(senderKey)
            scheduleFlush()
        }
    }

    private val replay = EnvelopeReplay(store) { sightings.snapshot()[it]?.protocolVersion }

    private val _downgradedMembers = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Members whose envelopes were refused for coming unsequenced after sequenced ones: in
     * practice, someone who installed an older build. Surfaced so the family can ask them to
     * update, since otherwise their messages just stop.
     */
    val downgradedMembers: StateFlow<Set<String>> = _downgradedMembers.asStateFlow()

    fun sequenceFor(recipientId: String, recipientEd25519Hex: String?): Boolean =
        replay.sequenceFor(recipientId, recipientEd25519Hex)

    fun seal(plaintext: String): String = replay.seal(plaintext, System.currentTimeMillis())

    fun hasSequenced(memberId: String, ed25519Hex: String): Boolean = replay.hasSequenced(memberId, ed25519Hex)

    /** Whether [memberId] advertises protocol 4: they sign all their history (3.2) and sequence. */
    fun advertisesSequenced(memberId: String): Boolean =
        (sightings.snapshot()[memberId]?.protocolVersion ?: 0) >= EnvelopeReplay.SEQUENCED_PROTOCOL_VERSION

    fun open(senderId: String, senderEd25519Hex: String, plaintext: String): String =
        try {
            replay.open(senderId, senderEd25519Hex, plaintext, System.currentTimeMillis())
                .also { if (senderId in _downgradedMembers.value) _downgradedMembers.update { it - senderId } }
        } catch (e: ReplayedEnvelopeException) {
            if (e.verdict == ReplayVerdict.UNSEQUENCED_AFTER_SEQUENCED) {
                _downgradedMembers.update { it + senderId }
                SecurityRecorder.record(SecurityEventKind.DOWNGRADE, senderId)
            } else {
                SecurityRecorder.record(SecurityEventKind.REPLAY_REFUSED, senderId, e.verdict.securityLogName())
            }
            throw e
        }

    /** The verdict as iOS writes it in the security log, so both apps' reports read the same. */
    private fun ReplayVerdict.securityLogName(): String = when (this) {
        ReplayVerdict.TOO_OLD -> "tooOld"
        ReplayVerdict.FROM_THE_FUTURE -> "fromTheFuture"
        ReplayVerdict.REPLAY -> "replay"
        ReplayVerdict.UNSEQUENCED_AFTER_SEQUENCED -> "unsequencedAfterSequenced"
        ReplayVerdict.ACCEPT -> "accept"
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        writer.schedule({
            flushScheduled.set(false)
            val editor = prefs.edit()
            dirty.toList().forEach { key ->
                dirty.remove(key)
                windows[key]?.let { editor.putString(WINDOW_PREFIX + key, it.serialize()) }
            }
            editor.apply()
        }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val PREFS_NAME = "familysafety_replay_guard"
        const val KEY_LAST_SEQ = "last_seq"
        const val WINDOW_PREFIX = "w:"
        const val FLUSH_DELAY_MS = 1_000L
    }
}
