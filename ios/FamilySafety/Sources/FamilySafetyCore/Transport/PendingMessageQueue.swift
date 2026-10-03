import Foundation

public struct PendingMessage: Equatable {
    public let topic: String
    public let payload: Data
    public let qos: Int
    public let retained: Bool
    public let enqueuedAt: Date

    public init(topic: String, payload: Data, qos: Int, retained: Bool, enqueuedAt: Date) {
        self.topic = topic
        self.payload = payload
        self.qos = qos
        self.retained = retained
        self.enqueuedAt = enqueuedAt
    }
}

/// The two offline queues from §4. They're separate because the messages aren't
/// interchangeable: losing a location update costs one stale pin until the next fix,
/// while losing a group-state update leaves a device believing something false about who
/// is in the family, indefinitely. A single age-ordered queue would let a flood of
/// locations during an outage evict exactly the message that must not be lost.
public final class PendingMessageQueue {
    public static let controlCapacity = 64
    public static let bulkCapacity = 200
    public static let expiry: TimeInterval = 60 * 60

    private var control: [PendingMessage] = []
    private var bulk: [PendingMessage] = []
    private let lock = NSLock()

    public init() {}

    /// Enqueues, dropping the OLDEST entry in the same queue if already at capacity — a
    /// flood must not evict a queue's own oldest-but-still-relevant entries in favor of
    /// starving out newer ones silently; capacity is a hard cap either way.
    public func enqueue(_ message: PendingMessage) {
        lock.lock(); defer { lock.unlock() }
        if Topics.isControlPlaneTopic(message.topic) {
            control.append(message)
            if control.count > Self.controlCapacity { control.removeFirst() }
        } else {
            bulk.append(message)
            if bulk.count > Self.bulkCapacity { bulk.removeFirst() }
        }
    }

    /// Drains everything not yet expired and clears both queues — control-plane first,
    /// since it must be drained first and never crowded out by bulk traffic (§4).
    public func drainAll(now: Date) -> [PendingMessage] {
        lock.lock(); defer { lock.unlock() }
        let freshControl = control.filter { now.timeIntervalSince($0.enqueuedAt) <= Self.expiry }
        let freshBulk = bulk.filter { now.timeIntervalSince($0.enqueuedAt) <= Self.expiry }
        control.removeAll()
        bulk.removeAll()
        return freshControl + freshBulk
    }

    public var controlCount: Int {
        lock.lock(); defer { lock.unlock() }
        return control.count
    }

    public var bulkCount: Int {
        lock.lock(); defer { lock.unlock() }
        return bulk.count
    }
}
