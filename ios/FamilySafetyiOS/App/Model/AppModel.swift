import FamilySafetyCore
import Foundation
import SwiftUI

/// The app's one source of UI state. Owns the local profile (recovery phrase + name) and the
/// `FamilyNode`; screens read its published values and call its actions.
@MainActor
final class AppModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        /// No identity yet: onboarding.
        case welcome
        /// Saved data can't be read right now. Nothing has been deleted (F14).
        case storageUnavailable
        case missingBrokerConfig
        case running
    }

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var profile: LocalProfile?
    @Published private(set) var nodeState: NodeState = .noFamily
    @Published private(set) var connection: MqttConnectionState = .disconnected
    @Published private(set) var prompts: [JoinPrompt] = []
    @Published private(set) var presence: [String: PresenceStatus] = [:]
    @Published var lastError: String?
    /// "Signed in on another phone" (2.2). Shown as its own alert.
    @Published var identityNotice: String?

    private var node: FamilyNode?
    private let profileSlot: SecretSlot = KeychainSlot(service: "jibaro.familysafety", account: "profile")
    private let inviteSlot: SecretSlot = KeychainSlot(service: "jibaro.familysafety", account: "invite-book")
    private let pendingJoinSlot: SecretSlot = KeychainSlot(service: "jibaro.familysafety", account: "pending-join")
    private let deviceSlot: SecretSlot = KeychainSlot(service: "jibaro.familysafety", account: "device-id")

    var memberId: String? { try? profile?.identity().memberId }

    func load() {
        guard phase == .loading || phase == .storageUnavailable else { return }
        do {
            guard let saved = try profileSlot.readJSON(LocalProfile.self) else {
                phase = .welcome
                #if DEBUG
                TestDriver.prepareProfile(self)
                #endif
                return
            }
            try startNode(saved)
        } catch {
            #if DEBUG
            print("[app] load failed: \(error)")
            #endif
            phase = .storageUnavailable
        }
    }

    /// Saves a new or restored identity, then starts the family connection.
    func saveProfile(mnemonic: String, displayName: String) throws {
        let profile = LocalProfile(mnemonic: mnemonic, displayName: displayName)
        _ = try profile.identity()
        try profileSlot.writeJSON(profile)
        try startNode(profile)
    }

    func recoveryPhrase() -> [String] {
        profile?.mnemonic.split(separator: " ").map(String.init) ?? []
    }

    func setActive(_ active: Bool) {
        guard let node else { return }
        Task { active ? await node.start() : await node.stop() }
    }

    func createFamily(name: String) async {
        await run { try await $0.createFamily(name: name) }
    }

    func join(code: String) async -> Bool {
        await run { try await $0.join(code: code) }
    }

    func cancelJoin() async {
        await node?.cancelJoin()
    }

    func issueInvite() async -> String? {
        guard let node else { return nil }
        do { return try await node.issueInvite() } catch { lastError = Self.message(error); return nil }
    }

    func approve(_ prompt: JoinPrompt) async { await run { try await $0.approve(requestId: prompt.requestId) } }
    func reject(_ prompt: JoinPrompt) async { await run { try await $0.reject(requestId: prompt.requestId) } }

    // MARK: - Internals

    private func startNode(_ profile: LocalProfile) throws {
        guard let credentials = BrokerSecrets.load() else { phase = .missingBrokerConfig; return }
        self.profile = profile
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let persistence = FileGroupStatePersistence(fileURL: directory.appendingPathComponent("group.state"),
                                                    keyProvider: KeychainGroupStateKeyProvider())
        let node = try FamilyNode(profile: profile, credentials: credentials, groupPersistence: persistence,
                                  inviteSlot: inviteSlot, pendingJoinSlot: pendingJoinSlot, deviceSlot: deviceSlot) { [weak self] event in
            Task { @MainActor in self?.handle(event) }
        }
        self.node = node
        phase = .running
        Task { await node.start() }
    }

    private func handle(_ event: NodeEvent) {
        switch event {
        case .state(let state):
            if state == .storageUnavailable { phase = .storageUnavailable }
            nodeState = state
            #if DEBUG
            TestDriver.observe(self, state: state)
            #endif
        case .connection(let state):
            connection = state
            #if DEBUG
            TestDriver.log("connection \(state)")
            #endif
        case .prompts(let list):
            prompts = list
            #if DEBUG
            TestDriver.observe(self, prompts: list)
            #endif
        case .presence(let map): presence = map
        case .identityAlert(let text):
            identityNotice = text
            #if DEBUG
            TestDriver.log("ALERT \(text)")
            #endif
        }
    }

    @discardableResult
    private func run(_ action: (FamilyNode) async throws -> Void) async -> Bool {
        guard let node else { return false }
        do { try await action(node); return true } catch { lastError = Self.message(error); return false }
    }

    static func message(_ error: Error) -> String {
        switch error {
        case NodeError.invalidCode: return "That isn't an invite code. Ask for a new one and paste all of it."
        case NodeError.alreadyInFamily: return "This phone is already in a family."
        case NodeError.notInFamily: return "Join or create a family first."
        case NodeError.storageUnavailable: return "This phone's storage can't be used right now. Try again in a moment."
        case JoinV2Error.invalidInvite(let reason): return reason
        case JoinDeskError.keyConflict: return "Someone else is using that person's name with different keys. Nothing was approved."
        default: return "Something went wrong: \(error.localizedDescription)"
        }
    }
}
