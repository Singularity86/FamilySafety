package com.example.familysafety.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** Phase 5 (E5, F16): incoming messages queued by kind, in order, bounded. */
class InboundQueuesTest {

    private val me = "a".repeat(32)
    private val g = "group-1"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `topics map to the lane of the handler they reach`() {
        val cases = mapOf(
            "familysafe/$me/presence" to InboundLane.PRESENCE,
            "familysafe/group/$g/presence/$me" to InboundLane.PRESENCE,
            "familysafe/$me/location_inbox" to InboundLane.LOCATION,
            "familysafe/$me/chat" to InboundLane.CHAT,
            "familysafe/$me/chat/receipt" to InboundLane.CHAT,
            "familysafe/$me/chat/read" to InboundLane.CHAT,
            "familysafe/$me/group_sync" to InboundLane.SYNC,
            "familysafe/$me/sync_request" to InboundLane.SYNC,
            "familysafe/$me/removal_vote" to InboundLane.SYNC,
            "familysafe/$me/group_ack" to InboundLane.ACKS,
            "familysafe/group/$g/ack" to InboundLane.ACKS,
            "familysafe/$me/join_request" to InboundLane.JOIN,
            "familysafe/$me/replication/data" to InboundLane.REPLICATION,
            "familysafe/group/$g/files/chunk/f/3" to InboundLane.FILES,
            "familysafe/$me/files/repair" to InboundLane.FILES,
            "familysafe/group/$g/vault/container" to InboundLane.VAULT,
            "familysafe/$me/vault/repair" to InboundLane.VAULT,
            "familysafe/$me/avatar/data" to InboundLane.OTHER
        )
        cases.forEach { (topic, lane) -> assertEquals(topic, lane, InboundLane.forTopic(topic)) }
    }

    @Test
    fun `a lane handles its messages one at a time, in arrival order`() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        var running = 0
        var maxRunning = 0
        val done = CompletableDeferred<Unit>()
        val queues = InboundQueues(scope) { m ->
            synchronized(seen) { running++; maxRunning = maxOf(maxRunning, running) }
            Thread.sleep(2)
            seen += m.payload
            synchronized(seen) { running-- }
            if (seen.size == 50) done.complete(Unit)
        }
        repeat(50) { assertTrue(queues.offer(InboundMessage("familysafe/$me/chat", "$it"))) }
        withTimeout(5_000) { done.await() }
        assertEquals((0 until 50).map { "$it" }, seen.toList())
        assertEquals(1, maxRunning)
    }

    @Test
    fun `a stuck lane doesn't hold up the others`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val chatHandled = CompletableDeferred<Unit>()
        val queues = InboundQueues(scope) { m ->
            if (m.topic.endsWith("/group_sync")) release.await() // e.g. a slow roster merge
            if (m.topic.endsWith("/chat")) chatHandled.complete(Unit)
        }
        queues.offer(InboundMessage("familysafe/$me/group_sync", "slow"))
        queues.offer(InboundMessage("familysafe/$me/chat", "hi"))
        withTimeout(2_000) { chatHandled.await() }
        release.complete(Unit)
        Unit
    }

    @Test
    fun `acks are handled while a sync handler waits for them`() = runBlocking {
        val ack = CompletableDeferred<Unit>()
        val queues = InboundQueues(scope) { m ->
            if (m.topic.endsWith("/group_sync")) ack.await()
            if (m.topic.endsWith("/group_ack")) ack.complete(Unit)
        }
        queues.offer(InboundMessage("familysafe/$me/group_sync", "waits for acks"))
        queues.offer(InboundMessage("familysafe/$me/group_ack", "ack"))
        withTimeout(2_000) { ack.await() }
    }

    @Test
    fun `a full lane drops, and other lanes still accept`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val queues = InboundQueues(scope) { release.await() }
        val lane = InboundLane.JOIN
        // One is taken off by the waiting handler, then the buffer fills.
        var accepted = 0
        repeat(lane.capacity + 10) { if (queues.offer(InboundMessage("familysafe/$me/join_request", "$it"))) accepted++ }
        assertTrue(accepted in lane.capacity..lane.capacity + 1)
        assertTrue(queues.droppedCount(lane) >= 9)
        assertTrue(queues.offer(InboundMessage("familysafe/$me/chat", "still fine")))
        release.complete(Unit)
        Unit
    }

    @Test
    fun `a handler that throws doesn't stop its lane`() = runBlocking {
        val second = CompletableDeferred<Unit>()
        val queues = InboundQueues(scope) { m ->
            if (m.payload == "bad") throw IllegalStateException("boom")
            second.complete(Unit)
        }
        queues.offer(InboundMessage("familysafe/$me/chat", "bad"))
        queues.offer(InboundMessage("familysafe/$me/chat", "good"))
        withTimeout(2_000) { second.await() }
        assertFalse(queues.droppedCount(InboundLane.CHAT) > 0)
    }
}
