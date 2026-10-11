import FamilySafetyCore
import HarnessSupport
import Foundation

setvbuf(stdout, nil, _IONBF, 0)

// Manual live-broker check of the resubscribe-skip (IOS_PORT_SPEC.md §4, Android 1.15.0).
//
// A retained message sits on Bob's presence topic. Alice subscribes to it, disconnects,
// reconnects (same client id, cleanSession=false) and calls restoreSubscriptions again.
//   Phase 1  first subscribe            -> the retained message MUST be replayed
//   Phase 2  reconnect, same topic set  -> MUST be skipped: sessionPresent=true, 0 replays
//   Phase 3  control: forget fingerprint -> MUST replay again (proves the counter works)
//
// Uses the fixed VectorTests identities (not family members) and a throwaway in-memory
// fingerprint store. Credentials come from MQTT_USERNAME / MQTT_PASSWORD, else the macOS
// Keychain (see HarnessCredentials); never written to disk or logged. Run:  swift run ReconnectHarness

final class MemoryStore: SubscriptionFingerprintStore { var fingerprint: String?; var resetSession = false }

final class Counter: MqttTransportDelegate {
    private let lock = NSLock()
    private var counts: [String: Int] = [:]
    func transport(_ t: MqttTransport, didChangeConnectionState state: MqttConnectionState) {}
    func transport(_ t: MqttTransport, didReceiveMessage payload: Data, onTopic topic: String) {
        lock.lock(); counts[topic, default: 0] += 1; lock.unlock()
    }
    func count(_ topic: String) -> Int { lock.lock(); defer { lock.unlock() }; return counts[topic, default: 0] }
    func reset() { lock.lock(); counts = [:]; lock.unlock() }
}

guard let creds = HarnessCredentials.load() else {
    print(HarnessCredentials.missingMessage); exit(1)
}
let alice = try FamilySafeKeyDerivation.deriveIdentity(
    mnemonic: "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
let bob = try FamilySafeKeyDerivation.deriveIdentity(
    mnemonic: "legal winner thank year wave sausage worth useful legal winner thank yellow")
let bobPresence = Topics.presence(memberId: bob.memberId)
let will = Data("will".utf8)
let marker = Data("reconnect-harness-retained".utf8)

// Bob plants the retained message.
let bobT = MqttTransport(memberId: bob.memberId, credentials: creds)
try await bobT.connect(willPayload: will)
bobT.publish(topic: bobPresence, payload: marker, qos: .qos1, retained: true)
try await Task.sleep(nanoseconds: 1_500_000_000)
print("Bob planted a retained message on \(bobPresence)")

let counter = Counter()
let store = MemoryStore()
let aliceT = MqttTransport(memberId: alice.memberId, credentials: creds)
aliceT.fingerprintStore = store
aliceT.delegate = counter

var failures = 0
func check(_ ok: Bool, _ label: String) { print(ok ? "PASS  \(label)" : "FAIL  \(label)"); if !ok { failures += 1 } }

func phase(_ name: String, reconnect: Bool) async throws -> (granted: Bool, replays: Int, sessionPresent: Bool) {
    if reconnect {
        aliceT.disconnect()
        try await Task.sleep(nanoseconds: 1_000_000_000)
    }
    counter.reset()
    try await aliceT.connect(willPayload: will)
    let granted = await aliceT.restoreSubscriptions(groupId: nil, peerMemberIds: [bob.memberId])
    try await Task.sleep(nanoseconds: 3_000_000_000)
    if !granted { print("[\(name)] refused filters: \(aliceT.lastSubscribeFailures)") }
    let r = (granted, counter.count(bobPresence), aliceT.sessionPresent)
    print("[\(name)] sessionPresent=\(r.2) subscribeOK=\(r.0) retainedReplays=\(r.1)")
    return r
}

let p1 = try await phase("1 first subscribe", reconnect: false)
check(p1.granted, "phase 1: SUBACK granted every filter")
check(p1.replays >= 1, "phase 1: retained message replayed on first subscribe")

let p2 = try await phase("2 reconnect", reconnect: true)
check(p2.sessionPresent, "phase 2: broker reported session present (patched CocoaMQTT)")
check(p2.replays == 0, "phase 2: SUBSCRIBE skipped, no retained replay")

store.fingerprint = nil
let p3 = try await phase("3 control (fingerprint forgotten)", reconnect: true)
check(p3.replays >= 1, "phase 3: control resubscribe replays again")

bobT.clearRetained(topic: bobPresence)
try await Task.sleep(nanoseconds: 1_000_000_000)
aliceT.disconnect(); bobT.disconnect()
print(failures == 0 ? "ALL PASS" : "\(failures) FAILED")
exit(failures == 0 ? 0 : 1)
