import Foundation

public enum GroupChangeType: String, Codable {
    case memberAdded = "MEMBER_ADDED"
    case memberRemoved = "MEMBER_REMOVED"
    case nameChanged = "NAME_CHANGED"
    case versionSync = "VERSION_SYNC"
    case conflictResolution = "CONFLICT_RESOLUTION"
    case fullSync = "FULL_SYNC"
}

/// One entry in `GroupSyncMessage.quorumSignatures` (§6.4, §7.5). Each signature is
/// verified independently by `GroupTransitionValidator` — the entries are NOT covered by
/// `GroupSyncMessage.signature` itself.
public struct QuorumSignatureEntry: Codable, Equatable {
    public let voterMemberId: String
    public let signature: String

    public init(voterMemberId: String, signature: String) {
        self.voterMemberId = voterMemberId
        self.signature = signature
    }
}

/// Plaintext inside a per-recipient E2EE envelope, on the `group_sync` topic (§6.4).
public struct GroupSyncMessage: Codable, Equatable {
    public let groupId: String
    public let version: Int64
    public let groupDefinition: GroupDefinition
    public let updaterMemberId: String
    public let changeType: GroupChangeType
    public let changedMemberId: String?
    public let timestamp: Int64
    public let signature: String
    /// Present only on a quorum-authorized removal (Android 1.13.5+). Older Android
    /// builds ignore this field entirely and reject the update as unauthorized.
    public let quorumSignatures: [QuorumSignatureEntry]

    public init(
        groupId: String, version: Int64, groupDefinition: GroupDefinition, updaterMemberId: String,
        changeType: GroupChangeType, changedMemberId: String? = nil, timestamp: Int64, signature: String,
        quorumSignatures: [QuorumSignatureEntry] = []
    ) {
        self.groupId = groupId
        self.version = version
        self.groupDefinition = groupDefinition
        self.updaterMemberId = updaterMemberId
        self.changeType = changeType
        self.changedMemberId = changedMemberId
        self.timestamp = timestamp
        self.signature = signature
        self.quorumSignatures = quorumSignatures
    }

    private enum CodingKeys: String, CodingKey {
        case groupId, version, groupDefinition, updaterMemberId, changeType
        case changedMemberId, timestamp, signature, quorumSignatures
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        groupId = try c.decode(String.self, forKey: .groupId)
        version = try c.decode(Int64.self, forKey: .version)
        groupDefinition = try c.decode(GroupDefinition.self, forKey: .groupDefinition)
        updaterMemberId = try c.decode(String.self, forKey: .updaterMemberId)
        changeType = try c.decode(GroupChangeType.self, forKey: .changeType)
        changedMemberId = try c.decodeIfPresent(String.self, forKey: .changedMemberId)
        timestamp = try c.decode(Int64.self, forKey: .timestamp)
        signature = try c.decode(String.self, forKey: .signature)
        quorumSignatures = try c.decodeIfPresent([QuorumSignatureEntry].self, forKey: .quorumSignatures) ?? []
    }
}

/// Plaintext inside a per-recipient E2EE envelope, on `removal_vote` (§6.4, §7.5).
public struct RemovalVoteMessage: Codable, Equatable {
    public let groupId: String
    public let targetMemberId: String
    public let proposedVersion: Int64
    public let previousStateHash: String
    public let voterMemberId: String
    public let signature: String
    public let timestamp: Int64

    public init(
        groupId: String, targetMemberId: String, proposedVersion: Int64, previousStateHash: String,
        voterMemberId: String, signature: String, timestamp: Int64
    ) {
        self.groupId = groupId
        self.targetMemberId = targetMemberId
        self.proposedVersion = proposedVersion
        self.previousStateHash = previousStateHash
        self.voterMemberId = voterMemberId
        self.signature = signature
        self.timestamp = timestamp
    }

    /// The exact string each voter signs (§7.5). The distinct `REMOVE_QUORUM` prefix stops
    /// a vote being replayed as a different removal message; binding to version and hash
    /// stops a vote cast for one proposal authorizing another.
    public static func signingPayload(groupId: String, targetMemberId: String, proposedVersion: Int64, previousStateHash: String) -> String {
        "REMOVE_QUORUM:\(groupId):\(targetMemberId):\(proposedVersion):\(previousStateHash)"
    }
}

/// Plaintext broadcast on `familysafe/group/{groupId}/ack`.
public struct GroupUpdateAck: Codable, Equatable {
    public let groupId: String
    public let version: Int64
    public let memberId: String
    public let timestamp: Int64

    public init(groupId: String, version: Int64, memberId: String, timestamp: Int64) {
        self.groupId = groupId
        self.version = version
        self.memberId = memberId
        self.timestamp = timestamp
    }
}

/// Plaintext on each peer's `sync_request` topic.
public struct GroupStateRefreshRequest: Codable, Equatable {
    public let groupId: String
    public let requesterMemberId: String
    public let minimumVersion: Int64
    public let changedMemberId: String?
    public let reason: String
    public let timestamp: Int64

    public init(
        groupId: String, requesterMemberId: String, minimumVersion: Int64,
        changedMemberId: String? = nil, reason: String, timestamp: Int64
    ) {
        self.groupId = groupId
        self.requesterMemberId = requesterMemberId
        self.minimumVersion = minimumVersion
        self.changedMemberId = changedMemberId
        self.reason = reason
        self.timestamp = timestamp
    }
}
