package com.example.familysafety.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/** One incoming message, from the broker or a LAN session. */
data class InboundMessage(
    val topic: String,
    val payload: String,
    /** The broker's saved copy, delivered on (re)subscribe rather than sent just now. */
    val retained: Boolean = false,
    /** The member an authenticated LAN session proved to be; null from the broker. */
    val lanSender: String? = null
)

/**
 * The kind of traffic a topic carries. Each lane is handled in arrival order, one message at a
 * time, and lanes run side by side, so a burst of file chunks can't hold up chat or presence.
 * Capacities bound what a flood can queue (F16); [FILES] is the largest because a transfer
 * delivers chunks in bursts, and a dropped chunk is fetched again by chunk repair.
 */
enum class InboundLane(val capacity: Int) {
    PRESENCE(256),
    LOCATION(256),
    CHAT(256),
    /** Roster updates, refresh requests and removal votes. */
    SYNC(64),
    /** Separate from [SYNC]: a sync handler may wait on acks, which must not queue behind it. */
    ACKS(256),
    JOIN(32),
    REPLICATION(64),
    FILES(512),
    VAULT(256),
    OTHER(64);

    companion object {
        /** Mirrors the routing in [UnifiedTransportManager]: by topic suffix. */
        fun forTopic(topic: String): InboundLane = when {
            PresenceTopics.isPresenceTopic(topic) -> PRESENCE
            topic.endsWith("/location_inbox") || topic.endsWith("/location") -> LOCATION
            topic.endsWith("/chat") || topic.endsWith("/chat/receipt") || topic.endsWith("/chat/read") -> CHAT
            topic.endsWith("/group_ack") || (topic.contains("/group/") && topic.endsWith("/ack")) -> ACKS
            topic.endsWith("/group_sync") || topic.endsWith("/sync_request") || topic.endsWith("/removal_vote") -> SYNC
            topic.endsWith("/join_request") -> JOIN
            topic.contains("/replication/") -> REPLICATION
            topic.contains("/files/") -> FILES
            topic.contains("/vault/") -> VAULT
            else -> OTHER
        }
    }
}

/**
 * Bounded, ordered queues for incoming messages (Phase 5, E5/F16). Every message used to start
 * its own coroutine: unbounded, unordered, and a flood from outside meant as many concurrent
 * signature checks and decryptions as it had messages. Now [offer] never blocks the transport's
 * thread, a full lane drops the message, and each lane's [handle] runs one message at a time.
 */
class InboundQueues(
    scope: CoroutineScope,
    private val handle: suspend (InboundMessage) -> Unit
) {
    private val lanes = InboundLane.entries.associateWith { Channel<InboundMessage>(it.capacity) }
    private val dropped = InboundLane.entries.associateWith { AtomicLong() }

    init {
        lanes.forEach { (lane, channel) ->
            scope.launch {
                for (message in channel) {
                    try {
                        handle(message)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One bad message must not stop the lane.
                        Timber.e(e, "Inbound ${lane.name} handler failed on ${message.topic}")
                    }
                }
            }
        }
    }

    /** Queues [message] on its lane; false if the lane is full and it was dropped. */
    fun offer(message: InboundMessage): Boolean {
        val lane = InboundLane.forTopic(message.topic)
        if (lanes.getValue(lane).trySend(message).isSuccess) return true
        val n = dropped.getValue(lane).incrementAndGet()
        if (n == 1L || n % 100 == 0L) Timber.w("Inbound ${lane.name} queue full — dropped $n so far")
        return false
    }

    fun droppedCount(lane: InboundLane): Long = dropped.getValue(lane).get()
}
