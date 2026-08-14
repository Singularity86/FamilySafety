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
}
