import Foundation

/// Timing/sizing constants and pure calculations from §4. Mirrors MqttConfig.kt +
/// MqttTransport.kt's connect-bounding constants exactly, so iOS and Android apply the
/// same backoff/timeout shape even though nothing here crosses the wire.
public enum TransportTiming {
    public static let keepAliveSeconds: UInt16 = 30
    public static let connectionTimeoutSeconds: TimeInterval = 30

    /// Android wraps Paho's own 30 s timeout in a slightly longer one of its own — the
    /// library's timeout was observed not to fire when the process is throttled
    /// mid-handshake, which left a client "connecting" forever (§4).
    public static let connectAttemptTimeout: TimeInterval = connectionTimeoutSeconds + 5

    public static let reconnectBaseDelay: TimeInterval = 5
    public static let reconnectMaxDelay: TimeInterval = 5 * 60

    /// Paho's default of 10 unacknowledged QoS-1 publishes is reachable in ordinary use,
    /// since every message is published once per recipient (§4).
    public static let maxInflight: UInt = 32

    /// Exponential backoff: 5 s, 10 s, 20 s, 40 s … capped at 5 minutes. `attempt` is
    /// 1-based (the first retry after a disconnect is attempt 1).
    public static func reconnectDelay(attempt: Int) -> TimeInterval {
        guard attempt >= 1 else { return reconnectBaseDelay }
        let cappedExponent = min(attempt - 1, 20) // guard against overflow on runaway input
        let factor = Double(1 << cappedExponent)
        return min(reconnectBaseDelay * factor, reconnectMaxDelay)
    }

    /// A "connecting" state older than a full retry cycle cannot be a live attempt, since
    /// every attempt is bounded by `connectAttemptTimeout` — treat it as stale and
    /// releasable rather than blocking every other reconnect path forever (§4).
    public static func isConnectingStale(connectingSince: Date, now: Date) -> Bool {
        now.timeIntervalSince(connectingSince) > 3 * connectAttemptTimeout + 10
    }
}
