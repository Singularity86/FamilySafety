package com.example.familysafety.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control-plane classification decides which offline queue a message waits in, and the
 * two queues have different eviction pressure. Getting a topic on the wrong side is silent:
 * everything still works until an outage, and then the wrong message is the one dropped.
 */
class MqttConfigTest {

    private val member = "52318daf60269d8fb37528f8db191d84"
    private val group = "e8dfaa6f-415c-4226-98d2-4859edc4f272"

    @Test
    fun `group state and membership topics are control plane`() {
        assertTrue(MqttConfig.isControlPlaneTopic(MqttConfig.getGroupSyncInboxTopic(member)))
        assertTrue(MqttConfig.isControlPlaneTopic(MqttConfig.getSyncRequestTopic(member)))
        assertTrue(MqttConfig.isControlPlaneTopic(MqttConfig.getJoinRequestTopic(member)))
        assertTrue(MqttConfig.isControlPlaneTopic(MqttConfig.getJoinApprovalTopic(member)))
        assertTrue(MqttConfig.isControlPlaneTopic(MqttConfig.getGroupAckTopic(group)))
    }

    @Test
    fun `everyday traffic is not control plane`() {
        // Each of these is either superseded by the next one of its kind, or replicated
        // between devices and backfilled. Losing one costs something recoverable.
        assertFalse(MqttConfig.isControlPlaneTopic(MqttConfig.getLocationInboxTopic(member)))
        assertFalse(MqttConfig.isControlPlaneTopic(MqttConfig.getPresenceTopic(member)))
        assertFalse(MqttConfig.isControlPlaneTopic(MqttConfig.getChatTopic(member)))
        assertFalse(MqttConfig.isControlPlaneTopic(MqttConfig.getChatReceiptTopic(member)))
        assertFalse(MqttConfig.isControlPlaneTopic(MqttConfig.getChatReadTopic(member)))
        assertFalse(MqttConfig.isControlPlaneTopic(MqttConfig.getFileManifestTopic(group)))
        assertFalse(MqttConfig.isControlPlaneTopic(MqttConfig.getFileChunkTopic(group, "f1", 0)))
        assertFalse(MqttConfig.isControlPlaneTopic(MqttConfig.getFileAvailabilityTopic(member)))
        assertFalse(MqttConfig.isControlPlaneTopic(MqttConfig.getVaultContainerTopic(group)))
        assertFalse(MqttConfig.isControlPlaneTopic(MqttConfig.getVaultChunkTopic(group, "f1", 0)))
        assertFalse(MqttConfig.isControlPlaneTopic(MqttConfig.getReplicationDataTopic(member)))
        assertFalse(MqttConfig.isControlPlaneTopic(MqttConfig.getReplicationRequestTopic(member)))
        assertFalse(MqttConfig.isControlPlaneTopic(MqttConfig.getReplicationAnnounceInboxTopic(member)))
    }

    @Test
    fun `the group ack rule does not catch every topic ending in ack`() {
        // The rule is deliberately "under /group/ AND ending in /ack" rather than the
        // suffix alone, so a future per-member topic called something/ack is not silently
        // promoted into the queue that must not overflow.
        assertFalse(MqttConfig.isControlPlaneTopic("familysafe/$member/chat/ack"))
        assertTrue(MqttConfig.isControlPlaneTopic("familysafe/group/$group/ack"))
    }

    @Test
    fun `the in-flight window is larger than a family-sized fan-out but not unbounded`() {
        // Large enough that one event fanned out to a family, plus a burst of chunks,
        // cannot fill it. Small enough that MemoryPersistence is not holding megabytes:
        // at a 32 KB chunk this bounds retained buffers to roughly one megabyte.
        assertTrue(MqttConfig.MAX_INFLIGHT > 10)
        assertTrue(MqttConfig.MAX_INFLIGHT <= 64)
    }

    @Test
    fun `control queue is bounded separately from bulk`() {
        assertTrue(MqttConfig.MAX_PENDING_CONTROL > 0)
        assertTrue(MqttConfig.MAX_PENDING_BULK > MqttConfig.MAX_PENDING_CONTROL)
    }
}
