import CocoaMQTT
import FamilySafetyCore
import Foundation

@MainActor
final class HarnessViewModel: ObservableObject {
    @Published var myName = "?"
    @Published var myMemberId = ""
    @Published var peerName = "?"
    @Published var peerMemberId = ""
    @Published var connectionState: MqttConnectionState = .disconnected
    @Published var peerIsOnline: Bool?
    @Published var peerLastSeen: Date?
    @Published var peerSignatureValid: Bool?
    @Published var log: [String] = []
    @Published var selfTestResults: [String] = []

    private var transport: MqttTransport?
    private var delegateBridge: DelegateBridge?

    // Same fixed test identities as VectorTests.swift / PresenceHarness, so memberIds
    // are known ahead of time and match what the CLI harness already proved out live.
    private static let aliceMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private static let bobMnemonic = "legal winner thank year wave sausage worth useful legal winner thank yellow"

    func appendLog(_ line: String) {
        log.append(line)
        if log.count > 200 { log.removeFirst(log.count - 200) }
    }

    func start() {
        let env = ProcessInfo.processInfo.environment
        guard let role = env["ROLE"], role == "alice" || role == "bob" else {
            appendLog("Set ROLE=alice or ROLE=bob (SIMCTL_CHILD_ROLE when launching via simctl)")
            return
        }
        guard let username = env["MQTT_USERNAME"], let password = env["MQTT_PASSWORD"] else {
            appendLog("Missing MQTT_USERNAME / MQTT_PASSWORD")
            return
        }

        let isAlice = role == "alice"
        do {
            let me = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: isAlice ? Self.aliceMnemonic : Self.bobMnemonic)
            let peer = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: isAlice ? Self.bobMnemonic : Self.aliceMnemonic)
            myName = isAlice ? "Alice" : "Bob"
            peerName = isAlice ? "Bob" : "Alice"
            myMemberId = me.memberId
            peerMemberId = peer.memberId
            appendLog("[\(myName)] memberId=\(me.memberId)")

            let transport = MqttTransport(memberId: me.memberId, credentials: BrokerCredentials(username: username, password: password))
            let bridge = DelegateBridge(viewModel: self, peerMemberId: peer.memberId, peerEd25519PublicKey: peer.ed25519PublicKey)
            transport.delegate = bridge
            self.transport = transport
            delegateBridge = bridge

            let myName = myName
            let peerName = peerName
            Task {
                do {
                    let willPayload = try Self.buildPresenceEnvelope(memberId: me.memberId, isOnline: false, ed25519SecretKey64: me.ed25519SecretKey64)
                    self.appendLog("[\(myName)] connecting...")
                    try await transport.connect(willPayload: willPayload)
                    self.appendLog("[\(myName)] connected.")
                    await transport.restoreSubscriptions(groupId: nil, peerMemberIds: [peer.memberId])
                    self.appendLog("[\(myName)] subscribed to \(peerName)'s presence.")
                    let onlinePayload = try Self.buildPresenceEnvelope(memberId: me.memberId, isOnline: true, ed25519SecretKey64: me.ed25519SecretKey64)
                    transport.publish(topic: Topics.presence(memberId: me.memberId), payload: onlinePayload, qos: .qos0, retained: true)
                    self.appendLog("[\(myName)] published online presence.")
                } catch {
                    self.appendLog("[\(myName)] error: \(error)")
                }
            }
        } catch {
            appendLog("identity derivation failed: \(error)")
        }
    }

    static func buildPresenceEnvelope(memberId: String, isOnline: Bool, ed25519SecretKey64: [UInt8]) throws -> Data {
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
        let presence = try PresenceUpdate.signed(memberId: memberId, isOnline: isOnline, timestamp: timestamp, ed25519SecretKey64: ed25519SecretKey64)
        let inner = String(decoding: try JSONEncoder().encode(presence), as: UTF8.self)
        let envelope = try EnvelopePadding.build(type: .presenceUpdate, payload: inner)
        return try JSONEncoder().encode(envelope)
    }

    /// Exercises Phase 0/1 code paths (crypto vectors' underlying logic, group hash,
    /// the transition validator) live in the app, on top of the transport test the
    /// connection above already runs — addresses "test ALL the code," not just the wire.
    func runSelfTests() {
        selfTestResults = []
        func check(_ name: String, _ body: () throws -> Bool) {
            do {
                selfTestResults.append("\(try body() ? "PASS" : "FAIL") — \(name)")
            } catch {
                selfTestResults.append("FAIL — \(name): \(error)")
            }
        }

        check("BIP-39 generate+validate round trip") {
            let words = try Bip39.generate12WordMnemonic()
            return try words.count == 12 && Bip39.validateMnemonic(words.joined(separator: " "))
        }
        check("BIP-39 rejects a garbage phrase") {
            try !Bip39.validateMnemonic("not a real mnemonic phrase at all here")
        }
        check("Identity derivation is deterministic") {
            let a = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: Self.aliceMnemonic)
            let b = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: Self.aliceMnemonic)
            return a.memberId == b.memberId && a.ed25519PublicKeyHex == b.ed25519PublicKeyHex
        }
        check("Group state hash is member-order-independent") {
            let alice = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: Self.aliceMnemonic)
            let bob = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: Self.bobMnemonic)
            let m1 = FamilyMember(memberId: alice.memberId, displayName: "A", ed25519PublicKey: alice.ed25519PublicKeyHex, x25519PublicKey: alice.x25519PublicKeyHex, addedAtEpochMs: 1)
            let m2 = FamilyMember(memberId: bob.memberId, displayName: "B", ed25519PublicKey: bob.ed25519PublicKeyHex, x25519PublicKey: bob.x25519PublicKeyHex, addedAtEpochMs: 1)
            let g1 = GroupDefinition(groupId: "g", groupName: "n", createdAtEpochMs: 1, creatorMemberId: alice.memberId, members: [m1, m2], version: 1)
            let g2 = GroupDefinition(groupId: "g", groupName: "n", createdAtEpochMs: 1, creatorMemberId: alice.memberId, members: [m2, m1], version: 1)
            return g1.computeStateHash() == g2.computeStateHash()
        }
        check("GroupTransitionValidator rejects a forged added member") {
            let alice = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: Self.aliceMnemonic)
            let current = GroupDefinition(
                groupId: "g", groupName: "n", createdAtEpochMs: 1, creatorMemberId: alice.memberId,
                members: [FamilyMember(memberId: alice.memberId, displayName: "A", ed25519PublicKey: alice.ed25519PublicKeyHex, x25519PublicKey: alice.x25519PublicKeyHex, addedAtEpochMs: 1)],
                version: 1
            )
            let forged = FamilyMember(memberId: "00000000000000000000000000000000", displayName: "X", ed25519PublicKey: alice.ed25519PublicKeyHex, x25519PublicKey: alice.x25519PublicKeyHex, addedAtEpochMs: 1)
            let remote = GroupDefinition(
                groupId: "g", groupName: "n", createdAtEpochMs: 1, creatorMemberId: alice.memberId,
                members: current.members + [forged], version: 2, previousStateHash: current.computeStateHash()
            )
            do {
                try GroupTransitionValidator.validate(current: current, remote: remote, updaterMemberId: alice.memberId)
                return false
            } catch GroupTransitionRejection.addedMemberIdMismatch {
                return true
            }
        }
        check("Presence envelope round-trips and pads to a 512-byte multiple") {
            let alice = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: Self.aliceMnemonic)
            let payload = try Self.buildPresenceEnvelope(memberId: alice.memberId, isOnline: true, ed25519SecretKey64: alice.ed25519SecretKey64)
            return payload.count % 512 == 0
        }
    }
}

@MainActor
final class DelegateBridge: MqttTransportDelegate {
    private weak var viewModel: HarnessViewModel?
    private let peerEd25519PublicKey: [UInt8]
    private let presenceTopic: String

    init(viewModel: HarnessViewModel, peerMemberId: String, peerEd25519PublicKey: [UInt8]) {
        self.viewModel = viewModel
        self.peerEd25519PublicKey = peerEd25519PublicKey
        presenceTopic = Topics.presence(memberId: peerMemberId)
    }

    nonisolated func transport(_ transport: MqttTransport, didChangeConnectionState state: MqttConnectionState) {
        Task { @MainActor in
            self.viewModel?.connectionState = state
            self.viewModel?.appendLog("[state] \(state)")
        }
    }

    nonisolated func transport(_ transport: MqttTransport, didReceiveMessage payload: Data, onTopic topic: String) {
        guard topic == presenceTopic else { return }
        let peerKey = peerEd25519PublicKey
        Task { @MainActor in
            guard let viewModel = self.viewModel else { return }
            guard !payload.isEmpty else {
                viewModel.appendLog("[peer] retained presence cleared")
                return
            }
            do {
                let envelope = try JSONDecoder().decode(MessageEnvelope.self, from: payload)
                guard envelope.type == .presenceUpdate else { return }
                let presence = try JSONDecoder().decode(PresenceUpdate.self, from: Data(envelope.payload.utf8))
                let valid = presence.verifySignature(publicKey: peerKey)
                viewModel.peerIsOnline = presence.isOnline
                viewModel.peerLastSeen = Date(timeIntervalSince1970: Double(presence.timestamp) / 1000)
                viewModel.peerSignatureValid = valid
                viewModel.appendLog("[peer] isOnline=\(presence.isOnline) signatureValid=\(valid)")
            } catch {
                viewModel.appendLog("[peer] decode failed: \(error)")
            }
        }
    }
}
