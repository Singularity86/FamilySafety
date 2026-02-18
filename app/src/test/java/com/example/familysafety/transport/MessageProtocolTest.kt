package com.example.familysafety.transport

import com.example.familysafety.location.MemberLocation
import org.junit.Assert.*
import org.junit.Test

class MessageProtocolTest {

    private fun createLocation(
        speed: Float? = null,
        bearing: Float? = null
    ) = MemberLocation(
        memberId = "member_001",
        latitude = 37.7749,
        longitude = -122.4194,
        accuracy = 10.0f,
        timestamp = 1700000000000L,
        speed = speed,
        bearing = bearing
    )

    // ── Location encoding/decoding round-trip ───────────────────

    @Test
    fun `encodeLocationUpdate and decode round-trip`() {
        val location = createLocation(speed = 5.0f, bearing = 180.0f)
        val encoded = MessageProtocol.encodeLocationUpdate(location)

        val envelope = MessageProtocol.decodeEnvelope(encoded)
        assertEquals("location_update", envelope.type)

        val decoded = MessageProtocol.decodeLocationUpdate(envelope.payload)
        assertEquals(location.memberId, decoded.memberId)
        assertEquals(location.latitude, decoded.latitude, 0.0001)
        assertEquals(location.longitude, decoded.longitude, 0.0001)
        assertEquals(location.accuracy, decoded.accuracy)
        assertEquals(location.timestamp, decoded.timestamp)
        assertEquals(location.speed, decoded.speed)
        assertEquals(location.bearing, decoded.bearing)
    }

    @Test
    fun `encodeLocationUpdate handles null optional fields`() {
        val location = createLocation(speed = null, bearing = null)
        val encoded = MessageProtocol.encodeLocationUpdate(location)

        val envelope = MessageProtocol.decodeEnvelope(encoded)
        val decoded = MessageProtocol.decodeLocationUpdate(envelope.payload)

        assertNull(decoded.speed)
        assertNull(decoded.bearing)
    }

    @Test
    fun `encodeLocationUpdate produces valid JSON`() {
        val encoded = MessageProtocol.encodeLocationUpdate(createLocation())
        // Should not throw when parsed
        val envelope = MessageProtocol.decodeEnvelope(encoded)
        assertNotNull(envelope.type)
        assertNotNull(envelope.payload)
    }

    // ── Presence encoding/decoding round-trip ───────────────────

    @Test
    fun `encodePresenceUpdate and decode round-trip`() {
        val encoded = MessageProtocol.encodePresenceUpdate("member_001", true)

        val envelope = MessageProtocol.decodeEnvelope(encoded)
        assertEquals("presence_update", envelope.type)

        val decoded = MessageProtocol.decodePresenceUpdate(envelope.payload)
        assertEquals("member_001", decoded.memberId)
        assertTrue(decoded.isOnline)
    }

    @Test
    fun `encodePresenceUpdate offline status`() {
        val encoded = MessageProtocol.encodePresenceUpdate("member_001", false)

        val envelope = MessageProtocol.decodeEnvelope(encoded)
        val decoded = MessageProtocol.decodePresenceUpdate(envelope.payload)
        assertFalse(decoded.isOnline)
    }

    @Test
    fun `presence timestamp is set`() {
        val before = System.currentTimeMillis()
        val encoded = MessageProtocol.encodePresenceUpdate("member_001", true)
        val after = System.currentTimeMillis()

        val envelope = MessageProtocol.decodeEnvelope(encoded)
        val decoded = MessageProtocol.decodePresenceUpdate(envelope.payload)

        assertTrue(decoded.timestamp in before..after)
    }

    // ── Envelope decoding ───────────────────────────────────────

    @Test
    fun `decodeEnvelope parses valid JSON`() {
        val json = """{"type":"test","payload":"hello"}"""
        val envelope = MessageProtocol.decodeEnvelope(json)
        assertEquals("test", envelope.type)
        assertEquals("hello", envelope.payload)
    }

    @Test(expected = Exception::class)
    fun `decodeEnvelope throws on invalid JSON`() {
        MessageProtocol.decodeEnvelope("not json")
    }

    @Test
    fun `decodeEnvelope ignores unknown keys`() {
        val json = """{"type":"test","payload":"hello","extraField":"ignored"}"""
        val envelope = MessageProtocol.decodeEnvelope(json)
        assertEquals("test", envelope.type)
    }

    // ── locationUpdateToMemberLocation ──────────────────────────

    @Test
    fun `locationUpdateToMemberLocation maps all fields`() {
        val update = MessageProtocol.LocationUpdate(
            memberId = "member_001",
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 10.0f,
            timestamp = 1700000000000L,
            speed = 5.0f,
            bearing = 180.0f
        )

        val location = MessageProtocol.locationUpdateToMemberLocation(update)
        assertEquals(update.memberId, location.memberId)
        assertEquals(update.latitude, location.latitude, 0.0001)
        assertEquals(update.longitude, location.longitude, 0.0001)
        assertEquals(update.accuracy, location.accuracy)
        assertEquals(update.timestamp, location.timestamp)
        assertEquals(update.speed, location.speed)
        assertEquals(update.bearing, location.bearing)
    }

    @Test
    fun `locationUpdateToMemberLocation handles null optionals`() {
        val update = MessageProtocol.LocationUpdate(
            memberId = "member_001",
            latitude = 0.0,
            longitude = 0.0,
            accuracy = 1.0f,
            timestamp = 0L
        )

        val location = MessageProtocol.locationUpdateToMemberLocation(update)
        assertNull(location.speed)
        assertNull(location.bearing)
    }
}
