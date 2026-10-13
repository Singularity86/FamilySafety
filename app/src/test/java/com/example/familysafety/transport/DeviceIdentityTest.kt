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
    fun `two devices online for one member within the window are announced`() {
        val d = CloneDetector()
        assertFalse(d.observe("alice", "phone-a", isOnline = true, now = 0))
        assertTrue(d.observe("alice", "phone-b", isOnline = true, now = 5 * min))
    }

    @Test
    fun `A offline (LWT) then B online 3 min later is a reinstall, not a clone`() {
        // The old install's last-will arrives a few minutes after it dies, then the new
        // install comes online. Every reinstall or restore looks like this.
        val d = CloneDetector()
        d.observe("alice", "phone-a", isOnline = true, now = 0)
        assertFalse(d.observe("alice", "phone-a", isOnline = false, now = 3 * min))
        assertFalse(d.observe("alice", "phone-b", isOnline = true, now = 6 * min))
    }

    @Test
    fun `another device's offline proves nothing and is ignored`() {
        val d = CloneDetector()
        d.observe("alice", "phone-a", isOnline = true, now = 0)
        // A stale offline from some other install doesn't end A's sighting...
        assertFalse(d.observe("alice", "phone-z", isOnline = false, now = 1 * min))
        // ...so a second device appearing online is still caught.
        assertTrue(d.observe("alice", "phone-b", isOnline = true, now = 2 * min))
    }

    @Test
    fun `the same device, or a switch after the window, is not announced`() {
        val d = CloneDetector()
        d.observe("alice", "phone-a", isOnline = true, now = 0)
        assertFalse(d.observe("alice", "phone-a", isOnline = true, now = 1 * min))
        // Not heard from for over ten minutes, then a new one: a switch, not a clone.
        assertFalse(d.observe("alice", "phone-b", isOnline = true, now = 30 * min))
    }

    @Test
    fun `alternating devices are announced once per cooldown`() {
        val d = CloneDetector()
        d.observe("alice", "a", true, 0)
        assertTrue(d.observe("alice", "b", true, 1 * min))
        assertFalse(d.observe("alice", "a", true, 2 * min))
        assertFalse(d.observe("alice", "b", true, 3 * min))
        // After the cooldown, a fresh overlap is announced again.
        val later = CloneDetector.COOLDOWN_MS + 10 * min
        d.observe("alice", "a", true, later)
        assertTrue(d.observe("alice", "b", true, later + 1 * min))
    }

    @Test
    fun `members are tracked separately`() {
        val d = CloneDetector()
        d.observe("alice", "a", true, 0)
        assertFalse(d.observe("bob", "b", true, 1 * min))
    }

    @Test
    fun `own topic warns only about another install that is online`() {
        // A restored phone finds its previous install's retained offline on its own topic.
        assertFalse(CloneDetector.ownPresenceIsAnotherDevice(isOnline = false, deviceId = "old", myDeviceId = "me"))
        assertFalse(CloneDetector.ownPresenceIsAnotherDevice(isOnline = true, deviceId = "me", myDeviceId = "me"))
        assertTrue(CloneDetector.ownPresenceIsAnotherDevice(isOnline = true, deviceId = "other", myDeviceId = "me"))
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
