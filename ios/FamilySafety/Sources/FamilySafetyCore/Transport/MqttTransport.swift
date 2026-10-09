import CocoaMQTT
import Foundation

/// EMQX Cloud serverless broker (§4). The host/port are NOT secret — they appear in the
/// Android source and this spec. Username/password ARE secret (gitignored on Android) and
/// must be supplied by the caller at runtime; never hardcode them here.
public enum FamilySafetyBroker {
    public static let host = "r161feb1.ala.us-east-1.emqxsl.com"
    public static let port: UInt16 = 8883
}

public struct BrokerCredentials {
    public let username: String
    public let password: String
    public init(username: String, password: String) {
        self.username = username
        self.password = password
    }
}

public enum MqttConnectionState: Equatable {
    case disconnected
    case connecting
    case connected
}

public enum MqttTransportError: Error, Equatable {
    case connectTimedOut
    case alreadyConnecting
    case connectRejected(UInt8)
}

public protocol MqttTransportDelegate: AnyObject {
    func transport(_ transport: MqttTransport, didReceiveMessage payload: Data, onTopic topic: String)
    func transport(_ transport: MqttTransport, didChangeConnectionState state: MqttConnectionState)
}

/// Wraps CocoaMQTT with the session contract in §4: a stable client id + `cleanSession =
/// false` for the broker-side persistent session that provides offline delivery, a
/// single-flight + BOUNDED connect attempt (the library's own timeout was observed not to
/// fire when the process is throttled mid-handshake, which left a client "connecting"
/// forever and blocked every other reconnect path), and app-managed backoff rather than
/// the library's own auto-reconnect.
///
/// NOT yet wired to the group-security/presence layers above it (Phase 3+) — this is the
/// transport primitive only.
public final class MqttTransport: NSObject {
    public weak var delegate: MqttTransportDelegate?

    public let memberId: String
    private let credentials: BrokerCredentials
    private var mqtt: CocoaMQTT?
    private let pending = PendingMessageQueue()

    private let stateLock = NSLock()
    private var connectingSince: Date?
    private var connectContinuation: CheckedContinuation<Void, Error>?
    private var subscribeContinuation: CheckedContinuation<Bool, Never>?

    /// Persisted so a later connect can recognise a subscription set the broker already holds.
    public var fingerprintStore: SubscriptionFingerprintStore = UserDefaultsFingerprintStore()

    /// Whether the broker kept our session on the latest connect (CONNACK session-present),
    /// read from the patched CocoaMQTT in `Vendor/` (see Vendor/PATCHES.md). `false` is the
    /// safe direction: it forces a full resubscribe.
    public private(set) var sessionPresent = false

    /// NSLock's lock()/unlock() are NS_SWIFT_UNAVAILABLE_FROM_ASYNC — calling them
    /// directly inside an `async` function body is a hard error under Swift 6's stricter
    /// concurrency checking. Routing every access through this synchronous, non-async
    /// helper keeps the lock/unlock call sites out of any async function's own frame.
    @discardableResult
    private func withStateLock<T>(_ body: () -> T) -> T {
        stateLock.lock()
        defer { stateLock.unlock() }
        return body()
    }

    public private(set) var connectionState: MqttConnectionState = .disconnected {
        didSet {
            guard oldValue != connectionState else { return }
            delegate?.transport(self, didChangeConnectionState: connectionState)
        }
    }

    public var isConnected: Bool { connectionState == .connected }
    public var clientId: String { Topics.generateClientId(memberId: memberId) }

    public init(memberId: String, credentials: BrokerCredentials) {
        self.memberId = memberId
        self.credentials = credentials
    }

    /// A "connecting" state that has outlived a full retry cycle cannot belong to a live
    /// attempt — every attempt is bounded below. Exposed so a caller (e.g. an
    /// alarm-driven wake-time reconnect) can recover a stuck guard without waiting on a
    /// background task that may itself have been suspended (§4).
    public func recoverStaleConnectGuard(now: Date = Date()) {
        let since = withStateLock { connectingSince }
        guard let since, TransportTiming.isConnectingStale(connectingSince: since, now: now) else { return }
        mqtt?.disconnect()
        mqtt = nil
        withStateLock {
            connectContinuation?.resume(throwing: MqttTransportError.connectTimedOut)
            connectContinuation = nil
            connectingSince = nil
        }
        connectionState = .disconnected
    }

    /// Bounded, single-flight connect (§4). `willPayload` is the pre-built (possibly
    /// sealed + signed) offline `PresenceUpdate` envelope for the last-will — building it
    /// is the presence/crypto layers' job, not transport's; this method only ships it.
    public func connect(willPayload: Data) async throws {
        let alreadyConnecting = withStateLock { () -> Bool in
            guard connectingSince == nil else { return true }
            connectingSince = Date()
            return false
        }
        guard !alreadyConnecting else { throw MqttTransportError.alreadyConnecting }
        connectionState = .connecting

        let client = CocoaMQTT(clientID: clientId, host: FamilySafetyBroker.host, port: FamilySafetyBroker.port)
        client.username = credentials.username
        client.password = credentials.password
        client.enableSSL = true
        client.cleanSession = false
        client.keepAlive = TransportTiming.keepAliveSeconds
        client.autoReconnect = false // app-managed backoff, not the library's (§4)
        client.inflightWindowSize = TransportTiming.maxInflight
        client.willMessage = CocoaMQTTMessage(
            topic: Topics.presence(memberId: memberId),
            payload: [UInt8](willPayload),
            qos: .qos1,
            retained: true
        )
        client.delegate = self
        mqtt = client

        do {
            try await withThrowingTaskGroup(of: Void.self) { group in
                group.addTask { [weak self] in
                    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                        guard let self else { return }
                        self.withStateLock { self.connectContinuation = continuation }
                        _ = client.connect(timeout: TransportTiming.connectionTimeoutSeconds)
                    }
                }
                group.addTask {
                    try await Task.sleep(nanoseconds: UInt64(TransportTiming.connectAttemptTimeout * 1_000_000_000))
                    throw MqttTransportError.connectTimedOut
                }
                try await group.next()
                group.cancelAll()
            }
            withStateLock {
                connectingSince = nil
                connectContinuation = nil
            }
            connectionState = .connected
        } catch {
            // Bound every attempt: on timeout or rejection, abort the client outright
            // rather than leaving it "connecting" forever holding the single-flight guard.
            client.disconnect()
            mqtt = nil
            withStateLock {
                connectContinuation = nil
                connectingSince = nil
            }
            connectionState = .disconnected
            throw error
        }
    }

    public func disconnect() {
        mqtt?.disconnect()
        mqtt = nil
        connectionState = .disconnected
    }

    // MARK: - Publishing

    /// A publish that fails while otherwise connected is backpressure, not a disconnect —
    /// queue it and move on rather than tearing down connection state. Returns `true` if
    /// handed to the live client, `false` if queued for later (§4).
    @discardableResult
    public func publish(topic: String, payload: Data, qos: CocoaMQTTQoS, retained: Bool = false) -> Bool {
        guard isConnected, let mqtt else {
            pending.enqueue(PendingMessage(topic: topic, payload: payload, qos: Int(qos.rawValue), retained: retained, enqueuedAt: Date()))
            return false
        }
        let message = CocoaMQTTMessage(topic: topic, payload: [UInt8](payload), qos: qos, retained: retained)
        _ = mqtt.publish(message)
        return true
    }

    /// Clearing a retained message = publishing a zero-length payload with `retained =
    /// true` to the same topic (§4).
    public func clearRetained(topic: String) {
        publish(topic: topic, payload: Data(), qos: .qos1, retained: true)
    }

    /// Drains the offline queues, control-plane first — call after a successful connect,
    /// per the on-connect sequence in §4.
    public func flushPendingQueue() {
        for message in pending.drainAll(now: Date()) {
            let qos = CocoaMQTTQoS(rawValue: UInt8(message.qos)) ?? .qos1
            publish(topic: message.topic, payload: message.payload, qos: qos, retained: message.retained)
        }
    }

    public var pendingControlCount: Int { pending.controlCount }
    public var pendingBulkCount: Int { pending.bulkCount }

    // MARK: - Subscriptions (§5)

    /// The full filter set with its QoS: own topics, then each other member's legacy
    /// `location` + `presence`, both QoS 0 — a position supersedes itself and the current one
    /// is covered by the outbox retry and heartbeat, not QoS (§4).
    static func subscriptionSet(memberId: String, groupId: String?, peerMemberIds: [String]) -> [(String, CocoaMQTTQoS)] {
        var set: [(String, CocoaMQTTQoS)] = Topics.ownSubscriptionTopics(memberId: memberId, groupId: groupId).map { ($0, .qos1) }
        for peer in peerMemberIds where peer != memberId {
            set.append((Topics.location(memberId: peer), .qos0))
            set.append((Topics.presence(memberId: peer), .qos0))
        }
        return set
    }

    /// Subscribe after a connect — but only when the broker does not already hold our
    /// subscriptions (see `SubscriptionPolicy`). The fingerprint is cleared before subscribing
    /// and stored only once the SUBACK grants every filter, so a crash or refusal mid-way is
    /// never trusted on the next connect. Returns `true` if the subscriptions are in place.
    @discardableResult
    public func restoreSubscriptions(groupId: String?, peerMemberIds: [String]) async -> Bool {
        guard let mqtt else { return false }
        let set = Self.subscriptionSet(memberId: memberId, groupId: groupId, peerMemberIds: peerMemberIds)
        let current = SubscriptionPolicy.fingerprint(
            brokerUrl: "\(FamilySafetyBroker.host):\(FamilySafetyBroker.port)", topics: set.map { $0.0 })
        guard SubscriptionPolicy.shouldResubscribe(
            sessionPresent: sessionPresent, storedFingerprint: fingerprintStore.fingerprint, currentFingerprint: current
        ) else { return true }

        fingerprintStore.fingerprint = nil
        let granted: Bool = await withTaskGroup(of: Bool.self) { group in
            group.addTask { [weak self] in
                await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
                    guard let self else { continuation.resume(returning: false); return }
                    self.withStateLock { self.subscribeContinuation = continuation }
                    mqtt.subscribe(set)
                }
            }
            group.addTask { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(TransportTiming.subscribeTimeout * 1_000_000_000))
                self?.finishSubscribe(false)
                return false
            }
            let first = await group.next() ?? false
            group.cancelAll()
            return first
        }
        if granted { fingerprintStore.fingerprint = current }
        return granted
    }

    private func finishSubscribe(_ granted: Bool) {
        let continuation = withStateLock { () -> CheckedContinuation<Bool, Never>? in
            defer { subscribeContinuation = nil }
            return subscribeContinuation
        }
        continuation?.resume(returning: granted)
    }
}

extension MqttTransport: CocoaMQTTDelegate {
    public func mqtt(_ mqtt: CocoaMQTT, didConnectAck ack: CocoaMQTTConnAck) {
        let continuation = withStateLock { () -> CheckedContinuation<Void, Error>? in
            defer { connectContinuation = nil }
            return connectContinuation
        }
        sessionPresent = ack == .accept && mqtt.sessionPresent
        if ack == .accept {
            continuation?.resume()
        } else {
            continuation?.resume(throwing: MqttTransportError.connectRejected(ack.rawValue))
        }
    }

    public func mqtt(_ mqtt: CocoaMQTT, didReceiveMessage message: CocoaMQTTMessage, id: UInt16) {
        delegate?.transport(self, didReceiveMessage: Data(message.payload), onTopic: message.topic)
    }

    public func mqttDidDisconnect(_ mqtt: CocoaMQTT, withError err: Error?) {
        connectionState = .disconnected
    }

    public func mqtt(_ mqtt: CocoaMQTT, didPublishMessage message: CocoaMQTTMessage, id: UInt16) {}
    public func mqtt(_ mqtt: CocoaMQTT, didPublishAck id: UInt16) {}
    public func mqtt(_ mqtt: CocoaMQTT, didSubscribeTopics success: NSDictionary, failed: [String]) {
        finishSubscribe(failed.isEmpty)
    }
    public func mqtt(_ mqtt: CocoaMQTT, didUnsubscribeTopics topics: [String]) {}
    public func mqttDidPing(_ mqtt: CocoaMQTT) {}
    public func mqttDidReceivePong(_ mqtt: CocoaMQTT) {}
}
