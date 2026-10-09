import CryptoKit
import Foundation

/// When a fresh connection may skip SUBSCRIBE (§4, since Android 1.15.0).
///
/// The session is persistent (`cleanSession = false`), so after a reconnect the broker still
/// holds every subscription. Re-sending SUBSCRIBE is not free: MQTT obliges the broker to
/// replay every retained message matching a re-subscribed filter — the vault container
/// (~175 KB), the file manifest and a presence per member — on every reconnect.
public enum SubscriptionPolicy {
    /// Order-independent digest of a subscription set on a given broker. Matches Android's
    /// `subscriptionFingerprint`: SHA-256 over `brokerUrl` + the sorted, de-duplicated topics,
    /// joined by "\n", lowercase hex.
    public static func fingerprint(brokerUrl: String, topics: [String]) -> String {
        let canonical = ([brokerUrl] + Array(Set(topics)).sorted()).joined(separator: "\n")
        return SHA256.hash(data: Data(canonical.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// Only a session the broker kept, holding exactly the filters last confirmed, may skip.
    public static func shouldResubscribe(
        sessionPresent: Bool,
        storedFingerprint: String?,
        currentFingerprint: String
    ) -> Bool {
        !sessionPresent || storedFingerprint != currentFingerprint
    }
}

/// Where the last confirmed subscription fingerprint lives.
public protocol SubscriptionFingerprintStore: AnyObject {
    var fingerprint: String? { get set }
}

public final class UserDefaultsFingerprintStore: SubscriptionFingerprintStore {
    private let defaults: UserDefaults
    private let key = "mqtt_session.subscribed_fingerprint"
    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }
    public var fingerprint: String? {
        get { defaults.string(forKey: key) }
        set { defaults.set(newValue, forKey: key) }
    }
}
