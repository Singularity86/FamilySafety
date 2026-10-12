package com.example.familysafety.transport

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F15: one identity in use on two phones is noticed, without breaking older peers. */
class DeviceIdentityTest {

    private val min = 60_000L

    @Test
    fun `two devices for one member within the window are announced`() {
        val d = CloneDetector()
        assertFalse(d.observe("alice", "phone-a", 0))
        assertTrue(d.observe("alice", "phone-b", 5 * min))
    }

    @Test
    fun `the same device, or a switch after the window, is not`() {
        val d = CloneDetector()
        d.observe("alice", "phone-a", 0)
        assertFalse(d.observe("alice", "phone-a", 1 * min))
        // Old phone silent for over ten minutes, then a new one: a switch, not a clone.
        assertFalse(d.observe("alice", "phone-b", 30 * min))
    }

    @Test
    fun `alternating devices are announced once per cooldown`() {
        val d = CloneDetector()
        d.observe("alice", "a", 0)
        assertTrue(d.observe("alice", "b", 1 * min))
        assertFalse(d.observe("alice", "a", 2 * min))
        assertFalse(d.observe("alice", "b", 3 * min))
        // After the cooldown, a fresh overlap is announced again.
        val later = CloneDetector.COOLDOWN_MS + 10 * min
        d.observe("alice", "a", later)
        assertTrue(d.observe("alice", "b", later + 1 * min))
    }

    @Test
    fun `members are tracked separately`() {
        val d = CloneDetector()
        d.observe("alice", "a", 0)
        assertFalse(d.observe("bob", "b", 1 * min))
    }

    @Test
    fun `device signing payload is the documented string`() {
        assertEquals(
            "presence-device:v1:alice:1700000000000:00ff",
            String(MessageProtocol.deviceSigningPayload("alice", 1_700_000_000_000L, "00ff"))
        )
    }

    @Test
    fun `presence from an older build still decodes, with no device id`() {
        val old = """{"memberId":"alice","isOnline":true,"timestamp":1,"signature":"ab","protocolVersion":3}"""
        val update = Json { ignoreUnknownKeys = true }.decodeFromString<MessageProtocol.PresenceUpdate>(old)
        assertNull(update.deviceId)
        assertNull(update.deviceSignature)
    }

    @Test
    fun `online and offline presence with a device id pad to the same size`() {
        // Padding hides online from offline by length; the new fields must not push one of
        // them into the next bucket.
        val sig = { _: ByteArray -> ByteArray(64) { 1 } }
        val online = MessageProtocol.encodePresenceUpdate("m".repeat(32), true, sig, deviceId = "d".repeat(32))
        val offline = MessageProtocol.encodePresenceUpdate("m".repeat(32), false, sig, deviceId = "d".repeat(32))
        assertEquals(online.toByteArray().size, offline.toByteArray().size)
        assertEquals(MessageProtocol.PRESENCE_PADDED_BYTES, online.toByteArray().size)
    }
}
