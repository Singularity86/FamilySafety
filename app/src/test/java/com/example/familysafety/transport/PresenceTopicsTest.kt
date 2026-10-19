package com.example.familysafety.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4.2: presence under the group topic, so a family of any size needs two subscriptions. */
class PresenceTopicsTest {

    private val me = "a".repeat(32)
    private val group = "11111111-2222-3333-4444-555555555555"
    private val peers = (1..11).map { "%032x".format(it) }

    @Test
    fun `before the switch presence goes to both topics and the will stays on the old one`() {
        assertEquals(
            listOf("familysafe/group/$group/presence/$me", "familysafe/$me/presence"),
            PresenceTopics.publishTopics(me, group, switched = false)
        )
        assertEquals("familysafe/$me/presence", PresenceTopics.willTopic(me, group, switched = false))
        assertEquals(2 + peers.size, MqttTransport.subscriptionFilters(me, group, peers, presenceSwitched = false).size)
    }

    @Test
    fun `after the switch a family of twelve uses two subscriptions`() {
        assertEquals(listOf("familysafe/group/$group/presence/$me"), PresenceTopics.publishTopics(me, group, switched = true))
        assertEquals("familysafe/group/$group/presence/$me", PresenceTopics.willTopic(me, group, switched = true))
        val filters = MqttTransport.subscriptionFilters(me, group, peers, presenceSwitched = true)
        assertEquals(listOf("familysafe/$me/#", "familysafe/group/$group/#"), filters)
        assertTrue(filters.size <= MqttTransport.BROKER_SUBSCRIPTION_QUOTA)
    }

    @Test
    fun `the family wildcard covers the group presence topic`() {
        val topic = PresenceTopics.group(group, peers[0])
        assertTrue(topic.startsWith(MqttConfig.getGroupWildcardTopic(group).removeSuffix("#")))
    }

    @Test
    fun `the sender is read from either topic, and only for our own group`() {
        assertEquals(peers[0], PresenceTopics.senderOf("familysafe/${peers[0]}/presence", group))
        assertEquals(peers[0], PresenceTopics.senderOf("familysafe/group/$group/presence/${peers[0]}", group))
        assertNull(PresenceTopics.senderOf("familysafe/group/other-group/presence/${peers[0]}", group))
        assertNull(PresenceTopics.senderOf("familysafe/group/$group/presence/", group))
        assertNull(PresenceTopics.senderOf("familysafe/group/$group/presence/${peers[0]}", null))
        assertNull(PresenceTopics.senderOf("familysafe/group/presence", group))
        assertNull(PresenceTopics.senderOf("familysafe/${peers[0]}/chat", group))
    }

    @Test
    fun `presence topics are recognised and others aren't`() {
        assertTrue(PresenceTopics.isPresenceTopic("familysafe/$me/presence"))
        assertTrue(PresenceTopics.isPresenceTopic("familysafe/group/$group/presence/$me"))
        assertFalse(PresenceTopics.isPresenceTopic("familysafe/group/$group/files/manifest"))
        assertFalse(PresenceTopics.isPresenceTopic("familysafe/$me/location_inbox"))
    }

    @Test
    fun `the switch needs protocol 5, above what iOS 4 advertises`() {
        assertEquals(5, PresenceTopics.PROTOCOL_VERSION)
        assertTrue(MessageProtocol.PROTOCOL_VERSION >= PresenceTopics.PROTOCOL_VERSION)
    }
}
