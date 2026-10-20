import Foundation

/// The presence rules a receiver keeps per member, persisted so the last known state survives a
/// restart (a reconnect that keeps the broker session gets no retained replay, §4).
///
/// Replay check with the sudden-disconnect exception (§6.2): an update older than the newest one
/// accepted is a replay, unless it's offline and its timestamp equals the session start of the
/// member's current session. That is the will of the connection in progress; it's taken once.
public struct PresenceBook: Codable, Equatable, Sendable {
    public private(set) var statuses: [String: PresenceStatus] = [:]
    private var newest: [String: Int64] = [:]
    private var sessionStart: [String: Int64] = [:]
    /// The (group, switched) state whose stale retained copy this phone has cleared (4.2).
    public var clearedStaleFor: String?

    public init() {}

    /// Whether to take `status` from `memberId` (already authenticated). Updates the book.
    public mutating func accept(_ status: PresenceStatus, from memberId: String) -> Bool {
        if let last = newest[memberId], status.timestamp < last {
            guard !status.isOnline, let start = sessionStart[memberId], status.timestamp == start else { return false }
            sessionStart[memberId] = nil
            statuses[memberId] = status
            return true
        }
        newest[memberId] = status.timestamp
        sessionStart[memberId] = status.isOnline ? status.sessionStart : nil
        statuses[memberId] = status
        return true
    }

    /// Drops members who are no longer in the family.
    public mutating func keepOnly(_ memberIds: Set<String>) {
        statuses = statuses.filter { memberIds.contains($0.key) }
        newest = newest.filter { memberIds.contains($0.key) }
        sessionStart = sessionStart.filter { memberIds.contains($0.key) }
    }
}

/// Where presence goes (4.2, spec §5 "Presence switch"). Until every member who counts advertises
/// 5, presence goes to both topics and the will to the old one; after, to the group topic only.
/// The retained copy always sits on the will's topic, so an abrupt death overwrites it.
public enum PresenceTopics {
    public static let protocolVersion = 5

    public static func group(groupId: String, memberId: String) -> String {
        "familysafe/group/\(groupId)/presence/\(memberId)"
    }

    /// Each topic a live update goes to, and whether it's retained there.
    public static func publishTargets(memberId: String, groupId: String?, switched: Bool) -> [(topic: String, retained: Bool)] {
        guard let groupId else { return [(Topics.presence(memberId: memberId), true)] }
        if switched { return [(group(groupId: groupId, memberId: memberId), true)] }
        return [(Topics.presence(memberId: memberId), true), (group(groupId: groupId, memberId: memberId), false)]
    }

    public static func willTopic(memberId: String, groupId: String?, switched: Bool) -> String {
        guard let groupId, switched else { return Topics.presence(memberId: memberId) }
        return group(groupId: groupId, memberId: memberId)
    }

    /// The topic the will isn't on, whose retained copy is cleared once per state.
    public static func staleTopic(memberId: String, groupId: String, switched: Bool) -> String {
        switched ? Topics.presence(memberId: memberId) : group(groupId: groupId, memberId: memberId)
    }

    /// The member a presence topic speaks for, or nil: `familysafe/{id}/presence`, or
    /// `familysafe/group/{groupId}/presence/{id}` for our own group only.
    public static func sender(of topic: String, groupId: String?) -> String? {
        let parts = topic.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        if parts.count == 3, parts[0] == "familysafe", parts[2] == "presence", parts[1] != "group", !parts[1].isEmpty {
            return parts[1]
        }
        if parts.count == 5, parts[0] == "familysafe", parts[1] == "group", parts[3] == "presence",
           let groupId, parts[2] == groupId, !parts[4].isEmpty {
            return parts[4]
        }
        return nil
    }
}
