package com.example.familysafety.transport

object MqttConfig {
    val BROKER_URL: String
        get() = BrokerConfig.getBrokerUrl()
    
    const val QOS_AT_MOST_ONCE = 0
    const val QOS_AT_LEAST_ONCE = 1
    const val QOS_EXACTLY_ONCE = 2
    
    const val DEFAULT_QOS = QOS_AT_LEAST_ONCE
    
    const val KEEP_ALIVE_INTERVAL = 60
    const val CONNECTION_TIMEOUT = 30
    const val RECONNECT_DELAY_MS = 5000L
    
    fun generateClientId(memberId: String): String {
        return "familysafe_${memberId}_${System.currentTimeMillis()}"
    }
    
    fun getLocationTopic(memberId: String): String {
        return "familysafe/$memberId/location"
    }
    
    fun getPresenceTopic(memberId: String): String {
        return "familysafe/$memberId/presence"
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
    
    fun getJoinRequestTopic(inviterMemberId: String): String {
        return "familysafe/$inviterMemberId/join_request"
    }
    
    fun getJoinResponseTopic(inviterMemberId: String, requestId: String): String {
        return "familysafe/$inviterMemberId/join_response/$requestId"
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
     */
    fun getReplicationAnnounceTopic(groupId: String): String {
        return "familysafe/group/$groupId/replication/announce"
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
}
