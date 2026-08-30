package com.example.familysafety.transport

object MqttConfig {
    val BROKER_URL: String
        get() = BrokerConfig.getBrokerUrl()
    
    const val QOS_AT_MOST_ONCE = 0
    const val QOS_AT_LEAST_ONCE = 1
    const val QOS_EXACTLY_ONCE = 2
    
    const val DEFAULT_QOS = QOS_AT_LEAST_ONCE
    
    const val KEEP_ALIVE_SECONDS = 30 // short enough to survive typical NAT idle timeouts
    const val CONNECTION_TIMEOUT = 30
    const val RECONNECT_DELAY_MS = 5000L

    /**
     * How many QoS-1 publishes may be awaiting acknowledgement at once.
     *
     * Paho's default is 10, and everything here is QoS 1 published once per recipient — so a
     * five-member family turns one event into four slots and a single file share fills the
     * window by itself. Ten is reachable in ordinary use, and Paho's response to a full
     * window is to throw immediately.
     *
     * Raised modestly rather than generously on purpose. The client is built with
     * MemoryPersistence, so every unacknowledged message is held in RAM until its PUBACK,
     * and chunks are 32 KB: a deep window is megabytes of retained buffers on a phone. The
     * ceiling being low was never the defect — mistaking it for a dead connection was.
     */
    const val MAX_INFLIGHT = 32

    /**
     * Bounds for the two offline queues. They are separate because the messages are not
     * interchangeable: losing a location update costs one stale pin until the next fix,
     * while losing a group-state update leaves a device holding the wrong family roster
     * with nothing to tell it so. A single queue drops by age, which means a flood of
     * location updates during an outage evicts exactly the message that must not be lost.
     */
    const val MAX_PENDING_CONTROL = 64
    const val MAX_PENDING_BULK = 200

    /**
     * Whether a topic carries group membership or identity rather than ordinary traffic.
     *
     * Deliberately narrow: this is the set whose loss is *permanent*, not merely
     * inconvenient. Chat and files are not here — both are replicated between devices and
     * backfilled, so a dropped one is recoverable. A missed group-state update is not: the
     * device simply believes something false about who is in the family, indefinitely.
     */
    fun isControlPlaneTopic(topic: String): Boolean =
        topic.endsWith("/group_sync") ||
            topic.endsWith("/sync_request") ||
            topic.endsWith("/join_request") ||
            topic.endsWith("/join_approval") ||
            (topic.contains("/group/") && topic.endsWith("/ack"))


    fun generateClientId(memberId: String): String {
        return "familysafe_${memberId}"
    }
    
    /**
     * Legacy shared location topic keyed by SENDER. Deprecated for publishing:
     * every subscriber received all per-recipient ciphertexts and could only
     * decrypt its own, producing spurious decrypt failures. Still subscribed
     * so peers running older builds keep working.
     */
    fun getLocationTopic(memberId: String): String {
        return "familysafe/$memberId/location"
    }

    /**
     * Per-recipient location inbox keyed by RECIPIENT. Each member subscribes to
     * their own inbox; senders publish one ciphertext per recipient here. The
     * sender is identified by the envelope's senderMemberId, not the topic.
     */
    fun getLocationInboxTopic(memberId: String): String {
        return "familysafe/$memberId/location_inbox"
    }
    
    fun getPresenceTopic(memberId: String): String {
        return "familysafe/$memberId/presence"
    }

    fun getGroupAckTopic(groupId: String): String {
        return "familysafe/group/$groupId/ack"
    }
    
    fun getSyncRequestTopic(memberId: String): String {
        return "familysafe/$memberId/sync_request"
    }

    fun getGroupSyncInboxTopic(memberId: String): String {
        return "familysafe/$memberId/group_sync"
    }
    
    fun getJoinRequestTopic(inviterMemberId: String): String {
        return "familysafe/$inviterMemberId/join_request"
    }

    /**
     * Topic the inviter publishes to after approving a join request.
     * The joiner subscribes to this topic and receives the full GroupDefinition.
     */
    fun getJoinApprovalTopic(joinerMemberId: String): String {
        return "familysafe/$joinerMemberId/join_approval"
    }

    // =========================================================================
    // REPLICATION TOPICS
    // =========================================================================

    /**
     * Topic for requesting data sync from a specific peer.
     * Message contains what data we need (timestamp ranges, etc.)
     */
    fun getReplicationRequestTopic(memberId: String): String {
        return "familysafe/$memberId/replication/request"
    }

    /**
     * Topic for sending replicated data to a specific peer.
     * Message contains the actual location/chat data.
     */
    fun getReplicationDataTopic(memberId: String): String {
        return "familysafe/$memberId/replication/data"
    }

    /**
     * Per-peer encrypted announcement inbox. Each peer subscribes to their own
     * inbox; announcers publish one ciphertext per recipient.
     */
    fun getReplicationAnnounceInboxTopic(memberId: String): String {
        return "familysafe/$memberId/replication/announce"
    }

    // =========================================================================
    // CHAT TOPICS
    // =========================================================================

    /**
     * Topic for sending chat messages to a specific member.
     */
    fun getChatTopic(memberId: String): String {
        return "familysafe/$memberId/chat"
    }

    /**
     * Topic for chat delivery receipts.
     */
    fun getChatReceiptTopic(memberId: String): String {
        return "familysafe/$memberId/chat/receipt"
    }

    /**
     * Topic for chat read receipts.
     */
    fun getChatReadTopic(memberId: String): String {
        return "familysafe/$memberId/chat/read"
    }

    // =========================================================================
    // SHARED FILE TOPICS
    // =========================================================================

    /**
     * Manifest broadcast topic — published with retained=true so new subscribers
     * immediately receive the current file list.
     */
    fun getFileManifestTopic(groupId: String): String =
        "familysafe/group/$groupId/files/manifest"

    /**
     * Per-chunk topic for transferring file data.
     */
    fun getFileChunkTopic(groupId: String, fileId: String, chunkIndex: Int): String =
        "familysafe/group/$groupId/files/chunk/$fileId/$chunkIndex"

    /**
     * Wildcard topic to subscribe to all chunks for the group.
     */
    fun getFileChunkWildcardTopic(groupId: String): String =
        "familysafe/group/$groupId/files/chunk/#"

    /**
     * A member publishes here to ask existing members to re-broadcast all files.
     * Used by new members joining who need the full file history.
     */
    fun getFileRequestTopic(memberId: String): String =
        "familysafe/$memberId/files/request"

    /**
     * A member publishes here to ask one specific peer for specific missing chunks.
     *
     * Addressed rather than broadcast, so a gap costs one peer a handful of chunks instead of
     * asking the whole family to re-send everything. Purely additive: peers on older builds
     * never subscribe here, so they simply do not participate in repair.
     */
    fun getFileRepairTopic(memberId: String): String =
        "familysafe/$memberId/files/repair"

    /**
     * A member publishes here to tell one peer which files it holds.
     *
     * Per-recipient and never retained: a retained per-recipient message is the pollution
     * that left stale join approvals sitting on the broker indefinitely.
     */
    fun getFileAvailabilityTopic(memberId: String): String =
        "familysafe/$memberId/files/availability"

    // =========================================================================
    // VAULT TOPICS
    // =========================================================================

    /**
     * The vault container, retained for the group.
     *
     * Every family has one whether or not anyone has set a code, and it is the same size
     * either way, so the topic existing and carrying traffic reveals nothing beyond the fact
     * that the app has the feature. Deliberately not folded into the file manifest: the
     * container is mutable and the manifest describes immutable, content-addressed files.
     */
    fun getVaultContainerTopic(groupId: String): String =
        "familysafe/group/$groupId/vault/container"

    /**
     * Bytes of a vault document, broadcast to the group.
     *
     * Chunks carry an opaque id and nothing else. A device without the code stores them and
     * cannot tell them from any other blob it holds — which is the point, since the documents
     * still have to reach every phone to be useful in an emergency.
     */
    fun getVaultChunkTopic(groupId: String, fileId: String, chunkIndex: Int): String =
        "familysafe/group/$groupId/vault/chunk/$fileId/$chunkIndex"

    fun getVaultChunkWildcardTopic(groupId: String): String =
        "familysafe/group/$groupId/vault/chunk/#"

    /** A request for specific chunks of a vault document, addressed to one peer. */
    fun getVaultRepairTopic(memberId: String): String =
        "familysafe/$memberId/vault/repair"
}
