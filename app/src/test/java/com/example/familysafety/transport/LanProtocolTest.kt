package com.example.familysafety.transport

import com.example.familysafety.core.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** 4.1: the LAN channel's handshake, frame cipher, names and topic rule. */
class LanProtocolTest {

    private val alice = "a".repeat(32)
    private val bob = "b".repeat(32)
    private val mallory = "c".repeat(32)
    private val group = "group-1"
    private val familyKey = "11".repeat(32)

    // Stand-in for X25519: both ends of a pair derive the same secret; nobody else does.
    private fun pairSecret(x: String, y: String): ByteArray =
        LanProtocol.hmac("pair".toByteArray(), listOf(x, y).sorted().joinToString("|"))

    private var counter = 0
    private fun nonce(): String = "%064x".format(++counter)

    private fun party(me: String, roster: Set<String>, secrets: (String, String) -> ByteArray = ::pairSecret) =
        LanHandshake(me, group, { id -> if (id in roster && id != me) secrets(me, id) else null }, ::nonce)

    private fun handshake(client: LanHandshake, server: LanHandshake, serverId: String): Pair<LanHandshake.Session?, LanHandshake.Session?> {
        val pending = client.clientHello(serverId)
        val (welcome, serverPending) = server.serverRespond(pending.hello) ?: return null to null
        val (confirm, clientSession) = client.clientFinish(pending, welcome) ?: return null to null
        return clientSession to server.serverFinish(serverPending, confirm)
    }

    @Test
    fun `two members complete the handshake and exchange a frame`() {
        val (c, s) = handshake(party(alice, setOf(alice, bob)), party(bob, setOf(alice, bob)), bob)
        assertEquals(bob, c!!.peerId)
        assertEquals(alice, s!!.peerId)

        val sealed = c.cipher.seal("hello".toByteArray())
        assertArrayEquals("hello".toByteArray(), s.cipher.open(sealed))
        val ack = s.cipher.seal("ack".toByteArray())
        assertArrayEquals("ack".toByteArray(), c.cipher.open(ack))
    }

    @Test
    fun `an outsider not in the roster is refused at hello`() {
        val server = party(bob, setOf(alice, bob))
        val hello = party(mallory, setOf(mallory, bob)).clientHello(bob).hello
        assertNull(server.serverRespond(hello))
    }

    @Test
    fun `an outsider claiming a member's ID can't confirm without that member's key`() {
        // Mallory says she is Alice but holds a different secret.
        val forged = party(alice, setOf(alice, bob)) { _, _ -> "not-the-real-secret".toByteArray() }
        val (c, s) = handshake(forged, party(bob, setOf(alice, bob)), bob)
        assertNull(c)
        assertNull(s)
    }

    @Test
    fun `a device advertising a member's name can't pass as them (F21)`() {
        // The client dialled Bob; the device answering doesn't hold Bob's key.
        val impostor = party(bob, setOf(alice, bob)) { _, _ -> "impostor".toByteArray() }
        val client = party(alice, setOf(alice, bob))
        val pending = client.clientHello(bob)
        val (welcome, _) = impostor.serverRespond(pending.hello)!!
        assertNull(client.clientFinish(pending, welcome))
    }

    @Test
    fun `a hello addressed to someone else is refused`() {
        val hello = party(alice, setOf(alice, bob)).clientHello(mallory).hello
        assertNull(party(bob, setOf(alice, bob, mallory)).serverRespond(hello))
    }

    @Test
    fun `a welcome from someone other than the member dialled is refused`() {
        val client = party(alice, setOf(alice, bob, mallory))
        val pending = client.clientHello(bob)
        val (welcome, _) = party(mallory, setOf(alice, bob, mallory)).serverRespond(pending.hello.copy(to = mallory))!!
        assertNull(client.clientFinish(pending, welcome))
    }

    @Test
    fun `altered, replayed and reordered frames don't open`() {
        val (c, s) = handshake(party(alice, setOf(alice, bob)), party(bob, setOf(alice, bob)), bob)
        val first = c!!.cipher.seal("one".toByteArray())
        val second = c.cipher.seal("two".toByteArray())

        val altered = first.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(s!!.cipher.open(altered))
        assertNull(s.cipher.open(second)) // out of order
        assertNotNull(s.cipher.open(first))
        assertNull(s.cipher.open(first)) // replayed
        assertNotNull(s.cipher.open(second))
    }

    @Test
    fun `a frame from one session doesn't open in another`() {
        val roster = setOf(alice, bob)
        val (c1, _) = handshake(party(alice, roster), party(bob, roster), bob)
        val (_, s2) = handshake(party(alice, roster), party(bob, roster), bob)
        assertNull(s2!!.cipher.open(c1!!.cipher.seal("x".toByteArray())))
    }

    @Test
    fun `a session's frames don't open in the reverse direction`() {
        val (c, _) = handshake(party(alice, setOf(alice, bob)), party(bob, setOf(alice, bob)), bob)
        val sealed = c!!.cipher.seal("loop".toByteArray())
        assertNull(c.cipher.open(sealed))
    }

    @Test
    fun `service names change every day and name no one to an outsider`() {
        val day = 20_000L
        val today = LanProtocol.serviceName(familyKey, alice, day)
        val tomorrow = LanProtocol.serviceName(familyKey, alice, day + 1)
        assertNotEquals(today, tomorrow)
        assertFalse(today.contains(alice.take(8)))
        assertTrue(today.startsWith("fs-"))
        assertEquals(3 + LanProtocol.NAME_TAG_HEX, today.length)
        // Another family's key gives a different name for the same person.
        assertNotEquals(today, LanProtocol.serviceName("22".repeat(32), alice, day))
    }

    @Test
    fun `members recognise each other's names across midnight and after NSD renames`() {
        val now = 20_000L * LanProtocol.DAY_MS + 1_000
        val members = listOf(alice, bob)
        val yesterday = LanProtocol.serviceName(familyKey, bob, 19_999L)
        assertEquals(bob, LanProtocol.memberForServiceName(yesterday, members, listOf(familyKey), now))
        val renamed = LanProtocol.serviceName(familyKey, bob, 20_000L) + " (2)"
        assertEquals(bob, LanProtocol.memberForServiceName(renamed, members, listOf(familyKey), now))
        val old = LanProtocol.serviceName(familyKey, bob, 19_990L)
        assertNull(LanProtocol.memberForServiceName(old, members, listOf(familyKey), now))
        assertNull(LanProtocol.memberForServiceName("familysafety-${bob.take(16)}", members, listOf(familyKey), now))
    }

    @Test
    fun `only our inboxes and our family's topics are accepted`() {
        assertTrue(LanProtocol.topicAllowed("familysafe/$bob/chat", bob, group))
        assertTrue(LanProtocol.topicAllowed("familysafe/group/$group/files/manifest", bob, group))
        assertFalse(LanProtocol.topicAllowed("familysafe/$alice/chat", bob, group))
        assertFalse(LanProtocol.topicAllowed("familysafe/group/other/files/manifest", bob, group))
        assertFalse(LanProtocol.topicAllowed("familysafe/$bob", bob, group))
    }

    @Test
    fun `handshake vectors`() {
        val transcript = LanProtocol.transcript("g", alice, bob, "01".repeat(32), "02".repeat(32))
        assertEquals("FSLAN:v1:g:$alice:$bob:${"01".repeat(32)}:${"02".repeat(32)}", transcript)
        val key = LanProtocol.sessionKey(ByteArray(32) { 7 }, transcript)
        with(LanProtocol) {
            assertEquals(LAN_VECTOR_KEY, key.toHex())
            assertEquals(LAN_VECTOR_NAME, serviceName("11".repeat(32), alice, 20_000L))
        }
        assertEquals(LAN_VECTOR_MAC_SERVER, LanProtocol.confirmation(key, "server", transcript))
        assertEquals(LAN_VECTOR_MAC_CLIENT, LanProtocol.confirmation(key, "client", transcript))
        // First client→server data frame of that session: nonce ‖ ciphertext ‖ tag.
        val frameJson = Json { encodeDefaults = true }.encodeToString(LanFrame(id = 1, topic = "t", payload = "p"))
        assertEquals("""{"id":1,"topic":"t","payload":"p","ack":null}""", frameJson)
        with(LanProtocol) {
            assertEquals(
                LAN_VECTOR_FRAME,
                LanCipher(key, DIR_CLIENT, DIR_SERVER).seal(frameJson.toByteArray()).toHex()
            )
        }
        // Encodings the iOS side must match.
        val json = Json { encodeDefaults = true }
        assertEquals(
            """{"t":"hello","v":1,"from":"$alice","to":"$bob","n":"${"01".repeat(32)}"}""",
            json.encodeToString(LanHello(from = alice, to = bob, n = "01".repeat(32)))
        )
    }

    private companion object {
        // Cross-checked with .NET HMACSHA256; also in IOS_PORT_SPEC.md §12.
        const val LAN_VECTOR_KEY = "3e85d124f9191602c8f35b0fef098250aa2bab3d94f68b9e6f7ef089166f59e5"
        const val LAN_VECTOR_NAME = "fs-2c6f0117d77fd614"
        const val LAN_VECTOR_MAC_SERVER = "d0479d400fdbc95ac7ed8da1812603ff647d9a049a8fe60530f809f8b36cbe6c"
        const val LAN_VECTOR_MAC_CLIENT = "66c1c3724f6e4afdb62777bd822cc5324d2a39857958f74daf26196af0ac4112"
        const val LAN_VECTOR_FRAME = "430000000000000000000000a6e74f814e78af42ec857bc19d99b85588548969880991b9fec78cb4ce4e00a71c185fad41626121f1037a3dd0455a428ad37befc13d373c1d723c06c6"
    }
}
