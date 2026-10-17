import Foundation

/// The sequenced envelope body of Android 3.4 (F16, A6), dormant until the family speaks
/// protocol 4. Inside a §3 envelope the plaintext becomes `{"seq": Int64, "ts": ms, "body": "<original>"}`.
public struct SequencedBody: Codable, Equatable, Sendable {
    public let seq: Int64
    public let ts: Int64
    public let body: String

    public init(seq: Int64, ts: Int64, body: String) {
        self.seq = seq
        self.ts = ts
        self.body = body
    }

    /// Nil unless `plaintext` is exactly a sequenced body (integer seq, integer ts, string body).
    public static func parse(_ plaintext: String) -> SequencedBody? {
        guard let data = plaintext.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let seq = object["seq"] as? NSNumber, CFNumberIsFloatType(seq) == false,
              let ts = object["ts"] as? NSNumber, CFNumberIsFloatType(ts) == false,
              let body = object["body"] as? String else { return nil }
        return SequencedBody(seq: seq.int64Value, ts: ts.int64Value, body: body)
    }
}

/// The sender side: one counter for all recipients, seeded from the clock so a reinstall needs
/// no saved state. seq = max(last + 1, nowMs × 1000).
public struct SequenceCounter: Codable, Equatable, Sendable {
    public private(set) var last: Int64 = 0
    public init() {}

    public mutating func next(nowMs: Int64) -> Int64 {
        last = max(last + 1, nowMs &* 1000)
        return last
    }
}

/// The receiver side, per sender. Accepts each sequence number once, tolerates reordering within
/// a 256-entry window, and refuses anything too old or from the future.
///
/// Persisted whole (floor, seen and hwm), not only the high-water mark: MQTT orders only per
/// topic, so a lower seq on one topic can legitimately arrive after a higher one on another, and
/// a restart in between must not drop it.
public struct ReplayWindow: Codable, Equatable, Sendable {
    public enum Verdict: Equatable, Sendable {
        case accept
        case tooOld
        /// The sender's clock is more than a day ahead of ours. Dropped so one wrong clock can't
        /// push this sender's floor into the future and silence them after they fix it (owner's
        /// decision, 2026-10-14).
        case fromTheFuture
        case replay
        /// A plain body after a sequenced one from the same sender (a downgrade). Surface it.
        case unsequencedAfterSequenced
    }

    public static let maxAgeMs: Int64 = 7 * 24 * 60 * 60 * 1000
    public static let maxAheadMs: Int64 = 24 * 60 * 60 * 1000
    public static let seenCap = 256

    private var floor: [String: Int64] = [:]
    private var seen: [String: [Int64]] = [:]
    public private(set) var hwm: [String: Int64] = [:]

    public init() {}

    public func hasSequenced(_ sender: String) -> Bool { hwm[sender] != nil }

    public mutating func check(sender: String, body: SequencedBody?, nowMs: Int64) -> Verdict {
        guard let body else { return hasSequenced(sender) ? .unsequencedAfterSequenced : .accept }
        if body.ts < nowMs - Self.maxAgeMs { return .tooOld }
        if body.ts > nowMs + Self.maxAheadMs || body.seq > (nowMs + Self.maxAheadMs) &* 1000 { return .fromTheFuture }
        var window = seen[sender] ?? []
        if let f = floor[sender], body.seq <= f { return .replay }
        if window.contains(body.seq) { return .replay }
        window.append(body.seq)
        if window.count > Self.seenCap {
            window.sort()
            let evicted = window.removeFirst()
            floor[sender] = max(floor[sender] ?? evicted, evicted)
        }
        seen[sender] = window
        hwm[sender] = max(hwm[sender] ?? body.seq, body.seq)
        return .accept
    }
}
