import Foundation

/// Topic builders mirroring MqttConfig.kt exactly (§5). Kept dependency-free (no CocoaMQTT
/// import) so topic-name logic is testable without a broker.
public enum Topics {
    /// `familysafe_{memberId}_{deviceId}` since 2.2b: one session per install, so two installs of
    /// one identity both stay online (clone detection works) and, since the device ID is never
    /// public, nobody can kick a member off by connecting under their ID. Without a device ID
    /// (the harnesses) it's the legacy `familysafe_{memberId}`.
    public static func generateClientId(memberId: String, deviceId: String? = nil) -> String {
        guard let deviceId else { return "familysafe_\(memberId)" }
        return "familysafe_\(memberId)_\(deviceId)"
    }

    /// Legacy sender-keyed location topic — subscribe only, never publish (§5).
    public static func location(memberId: String) -> String {
        "familysafe/\(memberId)/location"
    }

    public static func locationInbox(memberId: String) -> String {
        "familysafe/\(memberId)/location_inbox"
    }

    public static func presence(memberId: String) -> String {
        "familysafe/\(memberId)/presence"
    }

    public static func groupAck(groupId: String) -> String {
        "familysafe/group/\(groupId)/ack"
    }

    public static func syncRequest(memberId: String) -> String {
        "familysafe/\(memberId)/sync_request"
    }

    public static func groupSyncInbox(memberId: String) -> String {
        "familysafe/\(memberId)/group_sync"
    }

    public static func removalVote(memberId: String) -> String {
        "familysafe/\(memberId)/removal_vote"
    }

    public static func joinRequest(inviterMemberId: String) -> String {
        "familysafe/\(inviterMemberId)/join_request"
    }

    public static func joinApproval(joinerMemberId: String) -> String {
        "familysafe/\(joinerMemberId)/join_approval"
    }

    public static func replicationRequest(memberId: String) -> String {
        "familysafe/\(memberId)/replication/request"
    }

    public static func replicationData(memberId: String) -> String {
        "familysafe/\(memberId)/replication/data"
    }

    public static func replicationAnnounceInbox(memberId: String) -> String {
        "familysafe/\(memberId)/replication/announce"
    }

    public static func chat(memberId: String) -> String {
        "familysafe/\(memberId)/chat"
    }

    public static func chatReceipt(memberId: String) -> String {
        "familysafe/\(memberId)/chat/receipt"
    }

    public static func chatRead(memberId: String) -> String {
        "familysafe/\(memberId)/chat/read"
    }

    public static func fileManifest(groupId: String) -> String {
        "familysafe/group/\(groupId)/files/manifest"
    }

    public static func fileChunk(groupId: String, fileId: String, chunkIndex: Int) -> String {
        "familysafe/group/\(groupId)/files/chunk/\(fileId)/\(chunkIndex)"
    }

    public static func fileChunkWildcard(groupId: String) -> String {
        "familysafe/group/\(groupId)/files/chunk/#"
    }

    public static func fileRequest(memberId: String) -> String {
        "familysafe/\(memberId)/files/request"
    }

    public static func fileRepair(memberId: String) -> String {
        "familysafe/\(memberId)/files/repair"
    }

    public static func fileAvailability(memberId: String) -> String {
        "familysafe/\(memberId)/files/availability"
    }

    public static func vaultContainer(groupId: String) -> String {
        "familysafe/group/\(groupId)/vault/container"
    }

    public static func vaultChunk(groupId: String, fileId: String, chunkIndex: Int) -> String {
        "familysafe/group/\(groupId)/vault/chunk/\(fileId)/\(chunkIndex)"
    }

    public static func vaultChunkWildcard(groupId: String) -> String {
        "familysafe/group/\(groupId)/vault/chunk/#"
    }

    public static func vaultRepair(memberId: String) -> String {
        "familysafe/\(memberId)/vault/repair"
    }

    /// Every topic under one member's id (§5). It also matches our own retained `presence`,
    /// which a presence handler must drop when the sender is us.
    public static func ownWildcard(memberId: String) -> String {
        "familysafe/\(memberId)/#"
    }

    /// Every group-wide topic: ack, file manifest and chunks, vault container and chunks.
    public static func groupWildcard(groupId: String) -> String {
        "familysafe/group/\(groupId)/#"
    }

    /// Every own and group topic, one by one (§5). No longer subscribed individually — the
    /// two wildcards cover all of them — but kept as the list they must cover, and as the
    /// filters an older build's kept session may still hold (see `legacySubscriptionTopics`).
    public static func ownSubscriptionTopics(memberId: String, groupId: String? = nil) -> [String] {
        var topics = [
            chat(memberId: memberId),
            locationInbox(memberId: memberId),
            chatReceipt(memberId: memberId),
            chatRead(memberId: memberId),
            replicationRequest(memberId: memberId),
            replicationData(memberId: memberId),
            replicationAnnounceInbox(memberId: memberId),
            joinRequest(inviterMemberId: memberId),
            joinApproval(joinerMemberId: memberId),
            syncRequest(memberId: memberId),
            removalVote(memberId: memberId),
            groupSyncInbox(memberId: memberId),
            fileRequest(memberId: memberId),
            fileRepair(memberId: memberId),
            fileAvailability(memberId: memberId),
            vaultRepair(memberId: memberId)
        ]
        if let groupId {
            topics.append(groupAck(groupId: groupId))
            topics.append(fileManifest(groupId: groupId))
            topics.append(fileChunkWildcard(groupId: groupId))
            topics.append(vaultContainer(groupId: groupId))
            topics.append(vaultChunkWildcard(groupId: groupId))
        }
        return topics
    }

    /// What builds before the wildcards subscribed one by one. A kept session still holds
    /// whichever the broker granted, and they count against the 10-subscription quota (§4),
    /// so they are unsubscribed before the wildcards go in.
    public static func legacySubscriptionTopics(memberId: String, groupId: String?, peerMemberIds: [String]) -> [String] {
        ownSubscriptionTopics(memberId: memberId, groupId: groupId) + peerMemberIds
            .filter { $0 != memberId }
            .flatMap { [location(memberId: $0), presence(memberId: $0)] }
    }

    /// Whether `topic` carries group membership/identity rather than ordinary traffic —
    /// the set whose loss is *permanent*, not merely inconvenient (§4). Chat and files are
    /// deliberately excluded: both are replicated/backfilled, so a dropped one recovers.
    public static func isControlPlaneTopic(_ topic: String) -> Bool {
        topic.hasSuffix("/group_sync") ||
            topic.hasSuffix("/sync_request") ||
            topic.hasSuffix("/join_request") ||
            topic.hasSuffix("/join_approval") ||
            topic.hasSuffix("/removal_vote") ||
            (topic.contains("/group/") && topic.hasSuffix("/ack"))
    }
}
