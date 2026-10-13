#if DEBUG
import FamilySafetyCore
import Foundation

/// Debug builds only: lets two Simulators run create → invite → join → approve against the
/// live broker without taps, driven by launch environment (SIMCTL_CHILD_FS_TEST_*), and
/// prints each step to the console. Compiled out of release builds.
///
///   FS_TEST_NAME=Alice            make a fresh identity with this name if none is saved
///   FS_TEST_CREATE=Fam            create a family with this name when there is none
///   FS_TEST_INVITE=1              print a fresh invite code once in a family
///   FS_TEST_JOIN=<code>           join with this invite code when there is no family
///   FS_TEST_APPROVE=1             approve every verified join request as it arrives
@MainActor
enum TestDriver {
    static let env = ProcessInfo.processInfo.environment
    private static var invited = false
    private static var acted = false

    static func log(_ line: String) { print("[test] \(line)") }

    /// Before load: make an identity if asked to.
    static func prepareProfile(_ model: AppModel) {
        guard let name = env["FS_TEST_NAME"], model.profile == nil, model.phase == .welcome,
              let words = try? Bip39.generate12WordMnemonic() else { return }
        do { try model.saveProfile(mnemonic: words.joined(separator: " "), displayName: name); log("identity \(name) created") } catch {
            log("identity failed: \(error)")
        }
    }

    static func observe(_ model: AppModel, state: NodeState) {
        log("state \(describe(state))")
        switch state {
        case .noFamily where !acted:
            acted = true
            if let name = env["FS_TEST_CREATE"] { Task { await model.createFamily(name: name) } }
            if let code = env["FS_TEST_JOIN"] { Task { let ok = await model.join(code: code); log("join started: \(ok) \(model.lastError ?? "")") } }
        case .member where env["FS_TEST_INVITE"] != nil && !invited:
            invited = true
            Task { if let code = await model.issueInvite() { log("invite \(code)") } }
        default: break
        }
    }

    static func observe(_ model: AppModel, prompts: [JoinPrompt]) {
        for p in prompts {
            log("prompt \(p.displayName) code \(p.fingerprint)")
            if env["FS_TEST_APPROVE"] != nil { Task { await model.approve(p); log("approved \(p.displayName)") } }
        }
    }

    private static func describe(_ state: NodeState) -> String {
        switch state {
        case .member(let g): return "member of \(g.groupName) v\(g.version): " + g.members.map(\.displayName).sorted().joined(separator: ", ")
        case .joining(let f, let i, let fp): return "joining \(f) via \(i), code \(fp)"
        default: return "\(state)"
        }
    }
}
#endif
