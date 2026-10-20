import Foundation

/// Something this phone refused that may mean someone outside the family is trying to get in
/// (Roadmap "Long-term: intrusion detection", phase 1). Each one is a drop point that already
/// existed; the log only makes them visible. Never holds message content.
public enum SecurityEventKind: String, Codable, CaseIterable, Sendable {
    case identityInUseElsewhere
    case memberOnTwoPhones
    case joinRequestRejected
    case impersonatedJoin
    case removedMemberActivity
    case forgedSignature
    case forgedControlMessage
    case downgrade
    case rosterUpdateRejected
    case replayRefused
    case unknownSender
    case unknownGroupKey

    public enum Severity: String, Codable, Comparable, Sendable {
        case low, medium, high
        public static func < (a: Self, b: Self) -> Bool { [.low, .medium, .high].firstIndex(of: a)! < [.low, .medium, .high].firstIndex(of: b)! }
    }

    public var severity: Severity {
        switch self {
        case .identityInUseElsewhere, .memberOnTwoPhones, .impersonatedJoin, .removedMemberActivity: return .high
        case .joinRequestRejected, .forgedSignature, .forgedControlMessage, .downgrade, .rosterUpdateRejected: return .medium
        case .replayRefused, .unknownSender, .unknownGroupKey: return .low
        }
    }

    /// Plain words for the screen and the report.
    public var title: String {
        switch self {
        case .identityInUseElsewhere: return "Your account was online on another phone"
        case .memberOnTwoPhones: return "A member was online on two phones at once"
        case .joinRequestRejected: return "A join request failed its checks"
        case .impersonatedJoin: return "A join request used a member's ID with different keys"
        case .removedMemberActivity: return "Someone removed from the family sent something"
        case .forgedSignature: return "A message claiming to be from a member had a bad signature"
        case .forgedControlMessage: return "An unencrypted message was sent in a member's name"
        case .downgrade: return "A member's messages switched to an older, weaker format"
        case .rosterUpdateRejected: return "A family update broke the rules and was refused"
        case .replayRefused: return "An old message was sent again and refused"
        case .unknownSender: return "Someone not in the family sent something"
        case .unknownGroupKey: return "A message used a family key this phone doesn't have"
        }
    }
}

/// One kind of event from one sender, grouped while it keeps happening.
public struct SecurityEvent: Codable, Equatable, Identifiable, Sendable {
    public let kind: SecurityEventKind
    /// The member ID it names, or nil when there is none to name.
    public let memberId: String?
    /// A short machine reason (e.g. "invite already used"); never message content.
    public let detail: String?
    public let firstAtMs: Int64
    public var lastAtMs: Int64
    public var count: Int

    public var id: String { "\(kind.rawValue)|\(memberId ?? "")|\(detail ?? "")|\(firstAtMs)" }
}

/// The phone's security log: newest last, repeats within an hour grouped, kept 30 days, capped.
public struct SecurityLog: Codable, Equatable, Sendable {
    public static let groupWindowMs: Int64 = 60 * 60 * 1000
    public static let keepMs: Int64 = 30 * 24 * 60 * 60 * 1000
    public static let cap = 300

    public private(set) var events: [SecurityEvent] = []

    public init() {}

    public mutating func record(_ kind: SecurityEventKind, memberId: String?, detail: String?, now: Int64) {
        events.removeAll { now - $0.lastAtMs > Self.keepMs }
        if let i = events.lastIndex(where: { $0.kind == kind && $0.memberId == memberId && $0.detail == detail }),
           now - events[i].lastAtMs <= Self.groupWindowMs {
            events[i].lastAtMs = now
            events[i].count += 1
            return
        }
        events.append(SecurityEvent(kind: kind, memberId: memberId, detail: detail, firstAtMs: now, lastAtMs: now, count: 1))
        if events.count > Self.cap { events.removeFirst(events.count - Self.cap) }
    }

    /// A plain-text report the user can send wherever they choose. Member IDs are shortened to
    /// 8 characters and named when they're in the family; no keys, no message content.
    public func report(group: GroupDefinition?, myMemberId: String, appVersion: String, now: Int64) -> String {
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd HH:mm"
        df.timeZone = .current
        func when(_ ms: Int64) -> String { df.string(from: Date(timeIntervalSince1970: Double(ms) / 1000)) }
        func who(_ id: String?) -> String {
            guard let id else { return "—" }
            if let m = group?.findMember(byId: id) { return "\(m.displayName) (\(id.prefix(8)))" }
            if let g = group, g.removedMemberIds.contains(where: { Tombstones.memberId(of: $0) == id }) { return "\(id.prefix(8)), removed member" }
            return "\(id.prefix(8)), not in the family"
        }
        var lines = [
            "FamilySafety security report",
            "Made \(when(now)) (\(TimeZone.current.identifier)) on this phone (\(myMemberId.prefix(8))), app \(appVersion)",
            "Family: \(group?.groupName ?? "none")",
            "Events in the last 30 days: \(events.count)",
            "No messages, locations or keys are included.",
            ""
        ]
        for e in events.reversed() {
            let span = e.firstAtMs == e.lastAtMs ? when(e.firstAtMs) : "\(when(e.firstAtMs)) – \(when(e.lastAtMs))"
            var line = "[\(e.kind.severity.rawValue.uppercased())] \(span) · \(e.kind.title) · from \(who(e.memberId))"
            if let d = e.detail { line += " · \(d)" }
            if e.count > 1 { line += " · ×\(e.count)" }
            lines.append(line)
        }
        if events.isEmpty { lines.append("Nothing unusual recorded.") }
        return lines.joined(separator: "\n")
    }
}

/// Thread-safe holder the sync engine and the node both write to, persisted to a slot.
/// Saves are spaced out so a burst of junk can't turn into a burst of Keychain writes.
public final class SecurityRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var log: SecurityLog
    private let slot: SecretSlot?
    private var lastSaveMs: Int64 = 0
    private let now: @Sendable () -> Int64
    public var onChange: (@Sendable (SecurityLog) -> Void)?

    public init(slot: SecretSlot?, now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.slot = slot
        self.now = now
        self.log = (try? slot?.readJSON(SecurityLog.self)) ?? SecurityLog()
    }

    public func record(_ kind: SecurityEventKind, memberId: String? = nil, detail: String? = nil) {
        lock.lock()
        let t = now()
        let before = log.events.count
        log.record(kind, memberId: memberId, detail: detail, now: t)
        // A new entry is saved at once; a repeat at most every 5 s.
        if log.events.count != before || t - lastSaveMs >= 5_000 {
            try? slot?.writeJSON(log)
            lastSaveMs = t
        }
        let snapshot = log
        lock.unlock()
        onChange?(snapshot)
    }

    public var snapshot: SecurityLog {
        lock.lock(); defer { lock.unlock() }
        return log
    }

    public func flush() {
        lock.lock(); defer { lock.unlock() }
        try? slot?.writeJSON(log)
    }
}
