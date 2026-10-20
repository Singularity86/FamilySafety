package com.example.familysafety.transport

import android.content.Context
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupStateManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 4.1 end to end: two real [LocalTransport]s over loopback, each with its own roster and keys.
 * [LanProtocolTest] covers the handshake and cipher alone; this covers the transport around
 * them: framing, acks, the topic rule, the roster check per frame, and the size cap.
 */
class LocalTransportLoopbackTest {

    private val alice = "a".repeat(32)
    private val bob = "b".repeat(32)
    private val mallory = "c".repeat(32)
    private val groupId = "group-1"

    private fun member(id: String) = FamilyMember(
        memberId = id,
        displayName = id.take(1),
        ed25519PublicKey = "11".repeat(32),
        x25519PublicKey = "22".repeat(32),
        addedAtEpochMs = 1L
    )

    private fun family(vararg ids: String) = GroupDefinition(
        groupId = groupId,
        groupName = "Test",
        createdAtEpochMs = 1L,
        creatorMemberId = ids.first(),
        members = ids.map(::member).toSet(),
        version = 1L
    )

    // Stand-in for X25519: the two ends of a pair agree; [forge] makes one end disagree.
    private fun secret(x: String, y: String, forge: Boolean = false) =
        LanProtocol.hmac(if (forge) "forged".toByteArray() else "pair".toByteArray(), listOf(x, y).sorted().joinToString("|"))

    private val started = mutableListOf<LocalTransport>()

    private class Peer(val transport: LocalTransport, val roster: MutableStateFlow<GroupDefinition?>, val port: Int) {
        val received = CopyOnWriteArrayList<Triple<String, String, String>>()
    }

    private fun peer(me: String, roster: GroupDefinition, forge: Boolean = false): Peer {
        val flow = MutableStateFlow<GroupDefinition?>(roster)
        val gsm = mockk<GroupStateManager> { every { groupDefinition } returns flow }
        val e2ee = mockk<E2EEManager> {
            every { sharedSecretWith(any(), any()) } answers { secret(me, firstArg(), forge) }
        }
        val transport = LocalTransport(mockk<Context>(relaxed = true), gsm, e2ee)
        val port = transport.listenForTest(me)
        started += transport
        return Peer(transport, flow, port).also { p ->
            transport.onMessageReceived = { topic, payload, sender -> p.received += Triple(topic, payload, sender) }
        }
    }

    private fun Peer.pointAt(memberId: String, other: Peer) =
        transport.setPeerAddressForTest(memberId, InetSocketAddress(InetAddress.getLoopbackAddress(), other.port))

    @After
    fun tearDown() = started.forEach { it.stop() }

    @Test
    fun `a member's message is delivered, acknowledged, and attributed to the session's peer`() = runBlocking {
        val a = peer(alice, family(alice, bob))
        val b = peer(bob, family(alice, bob))
        a.pointAt(bob, b)

        assertTrue(a.transport.send("familysafe/$bob/chat", "hello", bob))
        assertTrue(a.transport.send("familysafe/group/$groupId/files/manifest", "second", bob))

        assertEquals(
            listOf(
                Triple("familysafe/$bob/chat", "hello", alice),
                Triple("familysafe/group/$groupId/files/manifest", "second", alice)
            ),
            b.received.toList()
        )
    }

    @Test
    fun `a phone outside the receiver's roster is refused at the handshake`() = runBlocking {
        val m = peer(mallory, family(mallory, bob))
        val b = peer(bob, family(alice, bob))
        m.pointAt(bob, b)

        assertFalse(m.transport.send("familysafe/$bob/chat", "hi", bob))
        assertTrue(b.received.isEmpty())
    }

    @Test
    fun `a device answering for a member without their key is not sent to (F21)`() = runBlocking {
        val a = peer(alice, family(alice, bob))
        val impostor = peer(bob, family(alice, bob), forge = true)
        a.pointAt(bob, impostor)

        assertFalse(a.transport.send("familysafe/$bob/chat", "secret", bob))
        assertTrue(impostor.received.isEmpty())
    }

    @Test
    fun `a frame for someone else's inbox is dropped without an ack`() = runBlocking {
        val a = peer(alice, family(alice, bob))
        val b = peer(bob, family(alice, bob))
        a.pointAt(bob, b)

        assertFalse(a.transport.send("familysafe/$alice/chat", "misrouted", bob))
        assertTrue(b.received.isEmpty())
    }

    @Test
    fun `a member removed mid-session is cut off at their next frame`() = runBlocking {
        val a = peer(alice, family(alice, bob))
        val b = peer(bob, family(alice, bob))
        a.pointAt(bob, b)
        assertTrue(a.transport.send("familysafe/$bob/chat", "before", bob))

        b.roster.value = family(bob)
        assertFalse(a.transport.send("familysafe/$bob/chat", "after", bob))
        assertEquals(listOf("before"), b.received.map { it.second })
    }

    @Test
    fun `a message over the frame cap goes to the broker instead`() = runBlocking {
        val a = peer(alice, family(alice, bob))
        val b = peer(bob, family(alice, bob))
        a.pointAt(bob, b)

        assertFalse(a.transport.send("familysafe/$bob/chat", "x".repeat(1_048_576), bob))
        assertTrue(b.received.isEmpty())
    }

    @Test
    fun `an unreachable member is not sent to`() = runBlocking {
        val a = peer(alice, family(alice, bob))
        assertFalse(a.transport.isReachable(bob))
        assertFalse(a.transport.send("familysafe/$bob/chat", "hi", bob))
    }
}
