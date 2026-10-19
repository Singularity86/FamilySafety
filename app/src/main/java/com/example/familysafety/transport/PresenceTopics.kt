package com.example.familysafety.transport

/**
 * Where presence is published and subscribed (remediation 4.2, E6).
 *
 * Presence used to live only at `familysafe/{memberId}/presence`, one filter per other member on
 * top of the two wildcards. EMQX Serverless grants at most 10 subscriptions per connection, so
 * in a family of ten or more the filters past the tenth were refused and those members silently
 * never appeared. Under `familysafe/group/{groupId}/presence/{memberId}` the family wildcard
 * already covers every member's presence: two subscriptions, whatever the family's size.
 *
 * Phones that don't know the group topic still listen on the old one, so the move is
 * family-wide and waits until every member who counts advertises [PROTOCOL_VERSION] (the same
 * 30-day rule as key rotation). Until then presence goes to both topics, the last-will stays on
 * the old one, and each member's old topic stays subscribed.
 *
 * Pure, so the rules can be tested without a broker.
 */
object PresenceTopics {

    /** First protocol version that reads presence from the group topic. */
    const val PROTOCOL_VERSION = 5

    fun legacy(memberId: String): String = MqttConfig.getPresenceTopic(memberId)

    fun group(groupId: String, memberId: String): String = MqttConfig.getGroupPresenceTopic(groupId, memberId)

    /** Topics a live presence update goes to: the group topic always, the old one until the switch. */
    fun publishTopics(memberId: String, groupId: String, switched: Boolean): List<String> =
        if (switched) listOf(group(groupId, memberId)) else listOf(group(groupId, memberId), legacy(memberId))

    /**
     * The last-will's topic. A connection has one will, so it goes where every member still
     * listens: the old topic until the switch, the group topic after it.
     */
    fun willTopic(memberId: String, groupId: String, switched: Boolean): String =
        if (switched) group(groupId, memberId) else legacy(memberId)

    /** Per-member filters beyond the two wildcards: each other member's old topic until the switch. */
    fun peerFilters(otherMemberIds: List<String>, switched: Boolean): List<String> =
        if (switched) emptyList() else otherMemberIds.map { legacy(it) }

    /**
     * The member a presence topic speaks for, or null if it isn't a presence topic of ours:
     * `familysafe/{id}/presence`, or `familysafe/group/{groupId}/presence/{id}` for our group only.
     */
    fun senderOf(topic: String, groupId: String?): String? {
        val parts = topic.split('/')
        return when {
            parts.size == 3 && parts[0] == "familysafe" && parts[2] == "presence" && parts[1] != "group" -> parts[1]
            parts.size == 5 && parts[0] == "familysafe" && parts[1] == "group" && parts[3] == "presence" ->
                parts[4].takeIf { groupId != null && parts[2] == groupId && it.isNotEmpty() }
            else -> null
        }
    }

    fun isPresenceTopic(topic: String): Boolean =
        topic.endsWith("/presence") || topic.split('/').let { it.size == 5 && it[1] == "group" && it[3] == "presence" }
}
