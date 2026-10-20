package com.example.familysafety.transport

import com.example.familysafety.core.hexToBytes
import com.example.familysafety.core.toHex
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The local-network channel's wire rules (remediation 4.1, F18–F21), free of sockets and NSD so
 * they can be unit tested and ported to iOS from the spec.
 *
 * Before this, anyone on the same Wi-Fi could open a connection and push frames under any topic,
 * the advertised service name carried half the member ID (trackable across networks), the photo
 * server's token sat in the advertisement, and a device advertising a member's name received
 * their traffic with the sender told it was delivered.
 *
 * Now:
 *  - Service names are an HMAC of the day and the member ID under the family key: unlinkable to
 *    a person and changing daily, but each member can tell which name is whose.
 *  - Every connection opens with a handshake that both sides can complete only with their own
 *    roster X25519 private key, and that binds the family, both member IDs and fresh nonces.
 *  - Every later frame is AES-256-GCM under the session key with a strict per-direction counter,
 *    so frames can't be forged, altered, reordered or replayed.
 *  - Each data frame is acknowledged; a send counts as delivered only once the ack arrives.
 */
object LanProtocol {

    const val VERSION = 1
    const val SERVICE_NAME_PREFIX = "fs-"

    /** Hex characters of the name tag: 8 bytes of HMAC. */
    const val NAME_TAG_HEX = 16

    const val DAY_MS = 86_400_000L

    const val DIR_CLIENT: Byte = 0x43 // 'C': client to server
    const val DIR_SERVER: Byte = 0x53 // 'S': server to client

    fun utcDay(nowMs: Long): Long = Math.floorDiv(nowMs, DAY_MS)

    /** This member's advertised NSD service name for [day] under the family key [groupKeyHex]. */
    fun serviceName(groupKeyHex: String, memberId: String, day: Long): String =
        SERVICE_NAME_PREFIX + hmac(groupKeyHex.hexToBytes(), "FSLAN-NAME:v1:$day:$memberId")
            .copyOfRange(0, NAME_TAG_HEX / 2).toHex()

    /**
     * Which member [serviceName] belongs to, or null. Tried against yesterday, today and tomorrow,
     * so phones whose clocks straddle midnight still find each other, and against every key given,
     * so phones mid-rotation do. NSD's " (2)" renaming after a conflict is ignored.
     */
    fun memberForServiceName(
        serviceName: String,
        memberIds: Collection<String>,
        groupKeyHexes: Collection<String>,
        nowMs: Long
    ): String? {
        if (!serviceName.startsWith(SERVICE_NAME_PREFIX)) return null
        val tag = serviceName.removePrefix(SERVICE_NAME_PREFIX).take(NAME_TAG_HEX)
        if (tag.length != NAME_TAG_HEX) return null
        val today = utcDay(nowMs)
        for (day in today - 1..today + 1) for (key in groupKeyHexes) for (id in memberIds) {
            if (serviceName(key, id, day) == SERVICE_NAME_PREFIX + tag) return id
        }
        return null
    }

    /** What the session key and both confirmations are bound to. Nonces are lowercase hex. */
    fun transcript(groupId: String, clientId: String, serverId: String, clientNonce: String, serverNonce: String): String =
        "FSLAN:v1:$groupId:$clientId:$serverId:$clientNonce:$serverNonce"

    /** Session key: HMAC-SHA256 keyed with the two members' X25519 shared secret (§3's `shared`). */
    fun sessionKey(sharedSecret: ByteArray, transcript: String): ByteArray = hmac(sharedSecret, transcript)

    /** Key confirmation for "server" or "client", hex. */
    fun confirmation(sessionKey: ByteArray, role: String, transcript: String): String =
        hmac(sessionKey, "$role:$transcript").toHex()

    /**
     * Topics a member may send to us over the LAN: our own inboxes and our family's group
     * topics. Anything else is for someone else and is dropped unacknowledged.
     */
    fun topicAllowed(topic: String, myMemberId: String, groupId: String): Boolean =
        topic.startsWith("familysafe/$myMemberId/") || topic.startsWith("familysafe/group/$groupId/")

    fun hmac(key: ByteArray, message: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message.toByteArray(Charsets.UTF_8))
        }

    fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())


}

/** Handshake step 1, client to server, plaintext. */
@Serializable
data class LanHello(val t: String = "hello", val v: Int = LanProtocol.VERSION, val from: String, val to: String, val n: String)

/** Handshake step 2, server to client, plaintext. */
@Serializable
data class LanWelcome(val t: String = "welcome", val v: Int = LanProtocol.VERSION, val from: String, val n: String, val mac: String)

/** Handshake step 3, client to server, plaintext. */
@Serializable
data class LanConfirm(val t: String = "confirm", val mac: String)

/**
 * Inside an encrypted frame. Client to server: a message ([id], [topic], [payload] = the same
 * UTF-8 payload the broker would carry). Server to client: [ack] = the id received.
 */
@Serializable
data class LanFrame(val id: Long = 0, val topic: String? = null, val payload: String? = null, val ack: Long? = null)

/**
 * One direction pair of an authenticated session. Nonce = direction byte, three zero bytes,
 * 8-byte big-endian counter from 0. A received frame must carry exactly the next counter.
 */
class LanCipher(private val key: ByteArray, private val sendDir: Byte, private val receiveDir: Byte) {
    private var sendCounter = 0L
    private var receiveCounter = 0L

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray {
        val nonce = nonce(sendDir, sendCounter++)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        }
        return nonce + c.doFinal(plaintext)
    }

    /** The plaintext, or null if the frame is forged, altered, replayed or out of order. */
    @Synchronized
    fun open(frame: ByteArray): ByteArray? {
        if (frame.size < 12 + 16) return null
        val nonce = frame.copyOfRange(0, 12)
        if (!nonce.contentEquals(nonce(receiveDir, receiveCounter))) return null
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                doFinal(frame, 12, frame.size - 12)
            }.also { receiveCounter++ }
        } catch (e: Exception) {
            null
        }
    }

    private fun nonce(dir: Byte, counter: Long): ByteArray =
        ByteBuffer.allocate(12).put(dir).put(0).put(0).put(0).putLong(counter).array()
}

/**
 * The handshake, as pure steps. [sharedSecretWith] returns the X25519 shared secret with a
 * roster member (§3's `crypto_box_beforenm`), or null for anyone not in the roster or ourselves.
 */
class LanHandshake(
    private val myMemberId: String,
    private val groupId: String,
    private val sharedSecretWith: (memberId: String) -> ByteArray?,
    private val randomNonce: () -> String
) {
    class ClientPending(val hello: LanHello)
    class ServerPending(val clientId: String, val transcript: String, val key: ByteArray)

    /** An authenticated session with [peerId]. */
    class Session(val peerId: String, val cipher: LanCipher)

    fun clientHello(serverId: String): ClientPending =
        ClientPending(LanHello(from = myMemberId, to = serverId, n = randomNonce()))

    /** The server's reply, or null to close: wrong version, not addressed to us, or not a member. */
    fun serverRespond(hello: LanHello): Pair<LanWelcome, ServerPending>? {
        if (hello.t != "hello" || hello.v != LanProtocol.VERSION) return null
        if (hello.to != myMemberId || hello.from == myMemberId) return null
        if (!hello.n.matches(NONCE)) return null
        val shared = sharedSecretWith(hello.from) ?: return null
        val serverNonce = randomNonce()
        val transcript = LanProtocol.transcript(groupId, hello.from, myMemberId, hello.n, serverNonce)
        val key = LanProtocol.sessionKey(shared, transcript)
        val welcome = LanWelcome(from = myMemberId, n = serverNonce, mac = LanProtocol.confirmation(key, "server", transcript))
        return welcome to ServerPending(hello.from, transcript, key)
    }

    /**
     * The client's confirmation and session, or null if the server isn't the member we dialled
     * (F21: a device advertising someone else's name can't produce this).
     */
    fun clientFinish(pending: ClientPending, welcome: LanWelcome): Pair<LanConfirm, Session>? {
        if (welcome.t != "welcome" || welcome.v != LanProtocol.VERSION) return null
        if (welcome.from != pending.hello.to || !welcome.n.matches(NONCE)) return null
        val shared = sharedSecretWith(welcome.from) ?: return null
        val transcript = LanProtocol.transcript(groupId, myMemberId, welcome.from, pending.hello.n, welcome.n)
        val key = LanProtocol.sessionKey(shared, transcript)
        if (!LanProtocol.constantTimeEquals(welcome.mac, LanProtocol.confirmation(key, "server", transcript))) return null
        val confirm = LanConfirm(mac = LanProtocol.confirmation(key, "client", transcript))
        return confirm to Session(welcome.from, LanCipher(key, LanProtocol.DIR_CLIENT, LanProtocol.DIR_SERVER))
    }

    /** The session, or null if the client couldn't prove it holds the claimed member's key. */
    fun serverFinish(pending: ServerPending, confirm: LanConfirm): Session? {
        if (confirm.t != "confirm") return null
        val expected = LanProtocol.confirmation(pending.key, "client", pending.transcript)
        if (!LanProtocol.constantTimeEquals(confirm.mac, expected)) return null
        return Session(pending.clientId, LanCipher(pending.key, LanProtocol.DIR_SERVER, LanProtocol.DIR_CLIENT))
    }

    private companion object {
        val NONCE = Regex("^[0-9a-f]{64}$")
    }
}
