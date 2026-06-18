package com.example.familysafety.transport

object MqttConfig {
    val BROKER_URL: String
        get() = BrokerConfig.getBrokerUrl()
    
    const val QOS_AT_MOST_ONCE = 0
    const val QOS_AT_LEAST_ONCE = 1
    const val QOS_EXACTLY_ONCE = 2
    
    const val DEFAULT_QOS = QOS_AT_LEAST_ONCE
    
    const val KEEP_ALIVE_MOVING = 30      // seconds — short enough to survive typical NAT idle timeouts
    const val KEEP_ALIVE_STATIONARY = 300 // 5 min — device is still (saves broker pings)
    const val CONNECTION_TIMEOUT = 30
    const val RECONNECT_DELAY_MS = 5000L
    
    fun generateClientId(memberId: String): String {
        return "familysafe_${memberId}"
    }
    
    fun getLocationTopic(memberId: String): String {
        return "familysafe/$memberId/location"
    }
    
    fun getPresenceTopic(memberId: String): String {
        return "familysafe/$memberId/presence"
    }

    fun getMovementTopic(memberId: String): String {
        return "familysafe/$memberId/movement"
    }

    fun getGroupSyncTopic(groupId: String): String {
        return "familysafe/group/$groupId/sync"
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
    
    fun getJoinResponseTopic(inviterMemberId: String, requestId: String): String {
        return "familysafe/$inviterMemberId/join_response/$requestId"
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
     * Topic for broadcasting data availability to all group members.
     * "I have data for memberX from timestamp Y to Z"
     *
     * Legacy plaintext topic, retained for backward compatibility with older
     * peers but no longer used for new announcements (which go per-peer to
     * [getReplicationAnnounceInboxTopic] so they can be E2E-encrypted).
     */
    fun getReplicationAnnounceTopic(groupId: String): String {
        return "familysafe/group/$groupId/replication/announce"
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
}
