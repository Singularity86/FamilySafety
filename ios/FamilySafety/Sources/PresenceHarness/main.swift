import CocoaMQTT
import FamilySafetyCore
import Foundation

// Unbuffer stdout — this process gets killed with SIGKILL/SIGTRAP to test the last-will,
// and a full stdio buffer written to a redirected file is otherwise lost, not flushed.
setvbuf(stdout, nil, _IONBF, 0)

// Manual, throwaway two-process harness for the IOS_PORT_SPEC.md §14 Phase 2 live-broker
// acceptance test: two clients see each other's presence flip online/offline over the
// real broker, with the offline flip observed via LWT on an abrupt kill.
//
// Credentials come ONLY from MQTT_USERNAME / MQTT_PASSWORD env vars — never written to
// or read from disk, never logged.
//
// Run two of these concurrently:
//   ROLE=alice MQTT_USERNAME=... MQTT_PASSWORD=... swift run PresenceHarness
//   ROLE=bob   MQTT_USERNAME=... MQTT_PASSWORD=... swift run PresenceHarness
//
// Neither identity has formed a group, so presence here is plaintext (unsealed) but
// signed — matches what Android does for a device with no fileEncryptionKey yet (§6.2).

func buildPresenceEnvelopeJSON(memberId: String, isOnline: Bool, ed25519SecretKey64: [UInt8]) throws -> Data {
    let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
    let presence = try PresenceUpdate.signed(
        memberId: memberId, isOnline: isOnline, timestamp: timestamp, ed25519SecretKey64: ed25519SecretKey64
    )
    let innerJSON = String(decoding: try JSONEncoder().encode(presence), as: UTF8.self)
    let envelope = try EnvelopePadding.build(type: .presenceUpdate, payload: innerJSON)
    return try JSONEncoder().encode(envelope)
}

final class HarnessDelegate: MqttTransportDelegate {
    private let peerName: String
    private let peerEd25519PublicKey: [UInt8]
    let presenceTopic: String

    init(peerMemberId: String, peerName: String, peerEd25519PublicKey: [UInt8]) {
        self.peerName = peerName
        self.peerEd25519PublicKey = peerEd25519PublicKey
        self.presenceTopic = Topics.presence(memberId: peerMemberId)
    }

    func transport(_ transport: MqttTransport, didChangeConnectionState state: MqttConnectionState) {
        print("[state] \(state)")
    }

    func transport(_ transport: MqttTransport, didReceiveMessage payload: Data, onTopic topic: String) {
        guard topic == presenceTopic else {
            print("[recv] unexpected topic: \(topic)")
            return
        }
        guard !payload.isEmpty else {
            print("[\(peerName)] retained presence cleared (empty payload)")
            return
        }
        do {
            let envelope = try JSONDecoder().decode(MessageEnvelope.self, from: payload)
            guard envelope.type == .presenceUpdate else {
                print("[recv] unexpected envelope type: \(envelope.type)")
                return
            }
            let presence = try JSONDecoder().decode(PresenceUpdate.self, from: Data(envelope.payload.utf8))
            let signatureValid = presence.verifySignature(publicKey: peerEd25519PublicKey)
            let when = Date(timeIntervalSince1970: Double(presence.timestamp) / 1000)
            print("[\(peerName)] isOnline=\(presence.isOnline) protocolVersion=\(presence.protocolVersion) signatureValid=\(signatureValid) at=\(when)")
        } catch {
            print("[recv] failed to decode presence on \(topic): \(error)")
        }
    }
}

let env = ProcessInfo.processInfo.environment
guard let role = env["ROLE"], role == "alice" || role == "bob" else {
    print("Set ROLE=alice or ROLE=bob"); exit(1)
}
guard let username = env["MQTT_USERNAME"], let password = env["MQTT_PASSWORD"] else {
    print("Set MQTT_USERNAME and MQTT_PASSWORD"); exit(1)
}

// Same fixed test identities as VectorTests.swift, so memberIds are known ahead of time.
let aliceMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
let bobMnemonic = "legal winner thank year wave sausage worth useful legal winner thank yellow"

let isAlice = role == "alice"
let me = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: isAlice ? aliceMnemonic : bobMnemonic)
let peer = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: isAlice ? bobMnemonic : aliceMnemonic)
let myName = isAlice ? "Alice" : "Bob"
let peerName = isAlice ? "Bob" : "Alice"

print("[\(myName)] memberId=\(me.memberId)  watching peer(\(peerName))=\(peer.memberId)  pid=\(ProcessInfo.processInfo.processIdentifier)")

let transport = MqttTransport(memberId: me.memberId, credentials: BrokerCredentials(username: username, password: password))
let delegate = HarnessDelegate(peerMemberId: peer.memberId, peerName: peerName, peerEd25519PublicKey: peer.ed25519PublicKey)
transport.delegate = delegate

let willPayload = try buildPresenceEnvelopeJSON(memberId: me.memberId, isOnline: false, ed25519SecretKey64: me.ed25519SecretKey64)

print("[\(myName)] connecting to \(FamilySafetyBroker.host):\(FamilySafetyBroker.port)...")
try await transport.connect(willPayload: willPayload)
print("[\(myName)] connected.")

transport.subscribePeerTopics(peerMemberId: peer.memberId)
print("[\(myName)] subscribed to \(peerName)'s presence + legacy location topics.")

let onlinePayload = try buildPresenceEnvelopeJSON(memberId: me.memberId, isOnline: true, ed25519SecretKey64: me.ed25519SecretKey64)
transport.publish(topic: Topics.presence(memberId: me.memberId), payload: onlinePayload, qos: .qos0, retained: true)
print("[\(myName)] published own online presence (retained, QoS 0).")
print("[\(myName)] waiting for \(peerName)'s presence — kill this process (kill -9 \(ProcessInfo.processInfo.processIdentifier)) to test the last-will.")

// Not dispatchMain() — that blocks the thread with GCD's own run mechanism, which
// collides with the async-main runtime already parking this thread for `await` above.
while true {
    try await Task.sleep(nanoseconds: 3_600_000_000_000)
}
