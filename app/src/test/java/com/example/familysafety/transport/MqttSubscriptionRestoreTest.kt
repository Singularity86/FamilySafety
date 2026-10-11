package com.example.familysafety.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Re-subscribing makes the broker replay every matching retained message — the vault
 * container alone is ~175 KB — so a reconnect must skip SUBSCRIBE exactly when the broker
 * still holds the subscriptions we want, and never otherwise.
 */
class MqttSubscriptionRestoreTest {

    private val broker = "ssl://broker.example:8883"
    private val me = "a".repeat(32)
    private val peer = "b".repeat(32)
    private val group = "g1"

    private fun topics(vararg members: String) =
        MqttTransport.subscriptionFilters(me, group, members.toList())

    /** MQTT topic-filter matching: `+` is one level, a trailing `#` is any remainder. */
    private fun matches(filter: String, topic: String): Boolean {
        val f = filter.split("/")
        val t = topic.split("/")
        for (i in f.indices) {
            if (f[i] == "#") return true
            if (i >= t.size) return false
            if (f[i] != "+" && f[i] != t[i]) return false
        }
        return f.size == t.size
    }

    @Test
    fun `fingerprint ignores topic order`() {
        val t = topics(peer)
        assertEquals(
            MqttTransport.subscriptionFingerprint(broker, t),
            MqttTransport.subscriptionFingerprint(broker, t.reversed())
        )
    }

    @Test
    fun `fingerprint changes when a member joins or the broker changes`() {
        val before = MqttTransport.subscriptionFingerprint(broker, topics())
        assertNotEquals(before, MqttTransport.subscriptionFingerprint(broker, topics(peer)))
        assertNotEquals(before, MqttTransport.subscriptionFingerprint("ssl://other:8883", topics()))
    }

    @Test
    fun `kept session with the same topics skips resubscribe`() {
        val fp = MqttTransport.subscriptionFingerprint(broker, topics(peer))
        assertFalse(MqttTransport.shouldResubscribe(sessionPresent = true, storedFingerprint = fp, currentFingerprint = fp))
    }

    @Test
    fun `a fresh session always resubscribes`() {
        val fp = MqttTransport.subscriptionFingerprint(broker, topics(peer))
        assertTrue(MqttTransport.shouldResubscribe(sessionPresent = false, storedFingerprint = fp, currentFingerprint = fp))
    }

    @Test
    fun `kept session with a changed or unknown topic set resubscribes`() {
        val old = MqttTransport.subscriptionFingerprint(broker, topics())
        val now = MqttTransport.subscriptionFingerprint(broker, topics(peer))
        assertTrue(MqttTransport.shouldResubscribe(true, old, now))
        assertTrue(MqttTransport.shouldResubscribe(true, null, now))
    }

    @Test
    fun `wildcards cover every own and group topic`() {
        val wildcards = listOf(MqttConfig.getOwnWildcardTopic(me), MqttConfig.getGroupWildcardTopic(group))
        val concrete = MqttTransport.ownTopicFilters(me, group).map { it.replace("#", "f1/0") }
        assertEquals(21, concrete.size)
        concrete.forEach { topic ->
            assertTrue("$topic is not covered", wildcards.any { matches(it, topic) })
        }
    }

    @Test
    fun `wildcards do not reach another member's inbox or another group`() {
        val wildcards = listOf(MqttConfig.getOwnWildcardTopic(me), MqttConfig.getGroupWildcardTopic(group))
        val foreign = MqttTransport.ownTopicFilters(peer, "g2").map { it.replace("#", "f1/0") }
        foreign.forEach { topic ->
            assertFalse("$topic leaks in", wildcards.any { matches(it, topic) })
        }
    }

    @Test
    fun `a family of nine fits the broker's 10-subscription quota`() {
        val peers = (1..8).map { it.toString().repeat(32) }
        assertEquals(10, MqttTransport.subscriptionFilters(me, group, peers).size)
        assertEquals(listOf(MqttConfig.getPresenceTopic(peer)), MqttTransport.memberTopicFilters(peer))
    }

    @Test
    fun `legacy filters include every one-by-one topic older builds held`() {
        val legacy = MqttTransport.legacyTopicFilters(me, group, listOf(peer))
        assertTrue(MqttTransport.ownTopicFilters(me, group).all { it in legacy })
        assertTrue(MqttConfig.getLocationTopic(peer) in legacy)
        assertTrue(MqttConfig.getPresenceTopic(peer) in legacy)
    }
}
