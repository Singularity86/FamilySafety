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
        MqttTransport.ownTopicFilters(me, group) + members.flatMap { MqttTransport.memberTopicFilters(it) }

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
    fun `own filters still cover every inbox and group topic`() {
        val own = MqttTransport.ownTopicFilters(me, group)
        assertEquals(21, own.size)
        assertTrue(MqttConfig.getLocationInboxTopic(me) in own)
        assertTrue(MqttConfig.getVaultContainerTopic(group) in own)
        assertTrue(MqttConfig.getGroupSyncInboxTopic(me) in own)
        assertEquals(
            listOf(MqttConfig.getLocationTopic(peer), MqttConfig.getPresenceTopic(peer)),
            MqttTransport.memberTopicFilters(peer)
        )
    }
}
