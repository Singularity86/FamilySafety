package com.example.familysafety.transport

import com.example.familysafety.location.MemberLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F23: the sender is the member whose signature verified on decrypt, and a location update
 * may only be about that member. Before this, any family member could move anyone's pin.
 */
class LocationSenderBindingTest {

    private val alice = "a".repeat(32)
    private val bob = "b".repeat(32)

    private fun payloadFor(memberId: String): String {
        val envelope = MessageProtocol.encodeLocationUpdate(
            MemberLocation(
                memberId = memberId,
                latitude = 37.7749,
                longitude = -122.4194,
                accuracy = 10.0f,
                timestamp = System.currentTimeMillis()
            )
        )
        return MessageProtocol.decodeEnvelope(envelope).payload
    }

    @Test
    fun `a member's own location is accepted`() {
        assertEquals(alice, MqttTransport.acceptedLocation(alice, payloadFor(alice))?.memberId)
    }

    @Test
    fun `a location signed by Bob that names Alice is dropped`() {
        assertNull(MqttTransport.acceptedLocation(senderId = bob, payloadJson = payloadFor(alice)))
    }

    @Test
    fun `an undecodable location is dropped`() {
        assertNull(MqttTransport.acceptedLocation(alice, "not json"))
    }
}
