import Foundation

/// Crash alerts this phone raised, kept until every member's phone confirms (spec §6.3
/// "Emergency (crash) alerts", Android EmergencyOutboxEntity). On the wire an alert is an
/// ordinary group chat message; what differs is that the sender owes one copy per member and
/// keeps sending it, with the same messageId and timestamp, until that member's signed
/// DELIVERED or READ arrives, for up to 24 hours.
public struct EmergencyOutbox: Codable, Equatable, Sendable {
    /// A day-old crash alert is no longer news anyone can act on.
    public static let giveUpAfterMs: Int64 = 24 * 60 * 60 * 1000
    /// How long a finished alert stays, so its status can still be shown.
    public static let keepMs: Int64 = 7 * 24 * 60 * 60 * 1000

    /// The wait before the next attempt, after `attemptsMade`: 15 s, 30 s, then every 60 s.
    public static func delayAfter(attemptsMade: Int) -> Int64 {
        let delays: [Int64] = [15_000, 30_000, 60_000]
        return delays[min(max(attemptsMade - 1, 0), delays.count - 1)]
    }

    public struct Copy: Codable, Equatable, Sendable {
        public let recipientId: String
        public internal(set) var attempts = 0
        /// When it's next due; nil once it needs nothing more.
        public internal(set) var nextAtMs: Int64?
        public internal(set) var deliveredAtMs: Int64?
        public internal(set) var left = false
    }

    public struct Alert: Codable, Equatable, Identifiable, Sendable {
        public let message: ChatMessage
        public internal(set) var copies: [String: Copy]
        public var id: String { message.messageId }
        public var raisedAtMs: Int64 { message.timestamp }
    }

    /// What the alert screen says about one member's copy.
    public enum CopyStatus: Equatable, Sendable {
        /// No attempt yet.
        case sending
        /// Sent, no receipt, within 24 hours.
        case waiting
        /// A signed DELIVERED or READ from them.
        case received
        /// 24 hours passed without a receipt.
        case notReached
        /// They left the family while the alert was out; no more resends.
        case leftFamily
    }

    public private(set) var alerts: [String: Alert] = [:]

    public init() {}

    /// Stores a new alert, one copy per recipient, all due at once. Store before the first send.
    public mutating func add(_ message: ChatMessage, recipients: [String]) {
        let copies = recipients.map { Copy(recipientId: $0, nextAtMs: message.timestamp) }
        alerts[message.messageId] = Alert(message: message, copies: Dictionary(uniqueKeysWithValues: copies.map { ($0.recipientId, $0) }))
    }

    public func isAlert(_ messageId: String) -> Bool { alerts[messageId] != nil }

    /// The copies to send now. `everything` (on a reconnect) takes every copy still owed, due or not.
    public func due(now: Int64, everything: Bool = false) -> [(alert: Alert, recipientId: String)] {
        alerts.values.filter { now - $0.raisedAtMs < Self.giveUpAfterMs }
            .sorted { $0.raisedAtMs < $1.raisedAtMs }
            .flatMap { alert in
                alert.copies.values
                    .filter { c in c.deliveredAtMs == nil && !c.left && c.nextAtMs.map { everything || $0 <= now } == true }
                    .sorted { $0.recipientId < $1.recipientId }
                    .map { (alert, $0.recipientId) }
            }
    }

    /// When the next copy falls due, or nil when nothing is owed.
    public func nextDueAtMs(now: Int64) -> Int64? {
        alerts.values.filter { now - $0.raisedAtMs < Self.giveUpAfterMs }
            .flatMap { $0.copies.values }
            .filter { $0.deliveredAtMs == nil && !$0.left }
            .compactMap(\.nextAtMs).min()
    }

    /// A copy went out. Delivery is judged only by the receipt, so the next attempt is scheduled anyway.
    public mutating func recordAttempt(alertId: String, recipientId: String, now: Int64) {
        guard var copy = alerts[alertId]?.copies[recipientId] else { return }
        copy.attempts += 1
        copy.nextAtMs = now + Self.delayAfter(attemptsMade: copy.attempts)
        alerts[alertId]?.copies[recipientId] = copy
    }

    /// They're no longer in the family: stop trying them.
    public mutating func markLeft(alertId: String, recipientId: String) {
        alerts[alertId]?.copies[recipientId]?.left = true
        alerts[alertId]?.copies[recipientId]?.nextAtMs = nil
    }

    /// A DELIVERED or READ for `alertId`, already verified as signed by `memberId` with
    /// `recipientId == memberId`. Settles that member's copy only. Returns whether it changed.
    @discardableResult
    public mutating func markDelivered(alertId: String, by memberId: String, now: Int64) -> Bool {
        guard let copy = alerts[alertId]?.copies[memberId], copy.deliveredAtMs == nil else { return false }
        alerts[alertId]?.copies[memberId]?.deliveredAtMs = now
        alerts[alertId]?.copies[memberId]?.nextAtMs = nil
        return true
    }

    public func status(_ copy: Copy, of alert: Alert, now: Int64) -> CopyStatus {
        if copy.deliveredAtMs != nil { return .received }
        if copy.left { return .leftFamily }
        if now - alert.raisedAtMs >= Self.giveUpAfterMs { return .notReached }
        return copy.attempts == 0 ? .sending : .waiting
    }

    /// The newest alert raised in the last 24 hours, for the "alert out" banner.
    public func current(now: Int64) -> Alert? {
        alerts.values.filter { now - $0.raisedAtMs < Self.giveUpAfterMs }.max { $0.raisedAtMs < $1.raisedAtMs }
    }

    public mutating func prune(now: Int64) {
        alerts = alerts.filter { now - $0.value.raisedAtMs < Self.keepMs }
    }
}
