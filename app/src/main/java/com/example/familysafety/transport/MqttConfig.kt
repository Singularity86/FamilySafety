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
}
