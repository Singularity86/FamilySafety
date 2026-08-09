package com.example.familysafety.transport

import com.example.familysafety.location.MemberLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message length is a side channel that survives encryption, so these tests assert on
 * encoded byte counts rather than on content.
 *
 * The leaks being pinned down here were both observed on a live broker: presence was
 * 136 bytes online and 137 offline ("true" vs "false"), and location payloads grew when
 * speed and bearing were present, which marks a device as moving.
 */
class MessageProtocolPaddingTest {

    private fun bytesOf(s: String) = s.toByteArray(Charsets.UTF_8).size

    private fun location(
        speed: Float? = null,
        bearing: Float? = null,
        memberId: String = "a".repeat(32),
        latitude: Double = 37.7749,
        longitude: Double = -122.4194
    ) = MemberLocation(
        memberId = memberId,
        latitude = latitude,
        longitude = longitude,
        accuracy = 12.5f,
        timestamp = 1_786_000_000_000L,
        speed = speed,
        bearing = bearing
    )

    @Test
    fun presence_onlineAndOffline_encodeToIdenticalLength() {
        val online = MessageProtocol.encodePresenceUpdate("b".repeat(32), true)
        val offline = MessageProtocol.encodePresenceUpdate("b".repeat(32), false)

        assertEquals(
            "online and offline presence must not be distinguishable by length",
            bytesOf(online),
            bytesOf(offline)
        )
    }

    @Test
    fun presence_isPaddedToTheFixedSize() {
        val encoded = MessageProtocol.encodePresenceUpdate("c".repeat(32), true)
        assertEquals(256, bytesOf(encoded))
    }

    @Test
    fun location_movingAndStationary_encodeToIdenticalLength() {
        val stationary = MessageProtocol.encodeLocationUpdate(location())
        val moving = MessageProtocol.encodeLocationUpdate(
            location(speed = 27.4f, bearing = 183.6f)
        )

        assertEquals(
            "presence of speed/bearing must not be readable from payload length",
            bytesOf(stationary),
            bytesOf(moving)
        )
    }

    @Test
    fun location_isPaddedToTheFixedSize() {
        val encoded = MessageProtocol.encodeLocationUpdate(location(speed = 5f, bearing = 90f))
        assertEquals(512, bytesOf(encoded))
    }

    @Test
    fun location_lengthDoesNotVaryWithCoordinatePrecision() {
        // A coordinate that serializes short vs one that serializes long: without padding
        // this alone changes the payload size.
        val short = MessageProtocol.encodeLocationUpdate(location(latitude = 1.0, longitude = 2.0))
        val long = MessageProtocol.encodeLocationUpdate(
            location(latitude = 37.77491234567, longitude = -122.41945678901)
        )

        assertEquals(bytesOf(short), bytesOf(long))
    }

    @Test
    fun paddedEnvelopesStillRoundTrip() {
        val encoded = MessageProtocol.encodeLocationUpdate(location(speed = 3f, bearing = 45f))
        val envelope = MessageProtocol.decodeEnvelope(encoded)
        assertEquals("location_update", envelope.type)

        val decoded = MessageProtocol.decodeLocationUpdate(envelope.payload)
        assertEquals(37.7749, decoded.latitude, 0.00001)
        assertEquals(3f, decoded.speed)
        assertEquals(45f, decoded.bearing)
    }

    @Test
    fun presenceRoundTripsThroughPadding() {
        val encoded = MessageProtocol.encodePresenceUpdate("d".repeat(32), false)
        val envelope = MessageProtocol.decodeEnvelope(encoded)
        assertEquals("presence_update", envelope.type)

        val decoded = MessageProtocol.decodePresenceUpdate(envelope.payload)
        assertEquals("d".repeat(32), decoded.memberId)
        assertEquals(false, decoded.isOnline)
    }

    @Test
    fun envelopeWithoutPadFieldStillDecodes() {
        // Older senders emit no "pad" key at all; receivers must tolerate that.
        val legacy = """{"type":"presence_update","payload":"{\"memberId\":\"x\",""" +
            """\"isOnline\":true,\"timestamp\":1}"}"""
        val envelope = MessageProtocol.decodeEnvelope(legacy)

        assertEquals("presence_update", envelope.type)
        assertEquals("", envelope.pad)
        assertEquals(true, MessageProtocol.decodePresenceUpdate(envelope.payload).isOnline)
    }

    @Test
    fun oversizedPayloadRoundsUpInsteadOfLeakingItsLength() {
        // A member ID far longer than real ones pushes the envelope past one bucket.
        val huge = MessageProtocol.encodeLocationUpdate(location(memberId = "z".repeat(700)))
        val size = bytesOf(huge)

        assertTrue("expected a multiple of 512, got $size", size % 512 == 0)
        assertTrue("expected to overflow the first bucket, got $size", size > 512)
    }
}
