#if DEBUG
import FamilySafetyCore
import Foundation

/// Debug builds only: lets two Simulators run create → invite → join → approve against the
/// live broker without taps, driven by launch environment (SIMCTL_CHILD_FS_TEST_*), and
/// prints each step to the console. Compiled out of release builds.
///
///   FS_TEST_NAME=Alice            make a fresh identity with this name if none is saved
///   FS_TEST_PHRASE="w1 … w12"     use this (throwaway, test-only) phrase instead of a new one
///   FS_TEST_CREATE=Fam            create a family with this name when there is none
///   FS_TEST_INVITE=1              print a fresh invite code once in a family
///   FS_TEST_JOIN=<code>           join with this invite code when there is no family
///   FS_TEST_APPROVE=1             approve every verified join request as it arrives
///   FS_TEST_RENAME=Name           once in a family, rename this person (signed, 3.1)
///   FS_TEST_COLOR=Teal            once in a family, choose this palette colour
///   FS_TEST_SECURITY_REPORT=<sec> once in a family, print the security report after <sec> seconds
///   FS_TEST_PLACE=Name,lat,lon,m  once in a family, watch this place (everyone, arrivals and departures)
///   FS_TEST_UPLOAD=<path>         once in a family and connected, share this file (a host path the Simulator can read)
@MainActor
enum TestDriver {
    static let env = ProcessInfo.processInfo.environment
    private static var invited = false
    private static var acted = false
    private static var edited = false
    private static var reported = false
    private static var uploaded = false
    private static var placed = false

    static func log(_ line: String) { print("[test] \(line)") }

    /// Before load: make an identity if asked to.
    static func prepareProfile(_ model: AppModel) {
        guard let name = env["FS_TEST_NAME"], model.profile == nil, model.phase == .welcome,
              let phrase = env["FS_TEST_PHRASE"] ?? (try? Bip39.generate12WordMnemonic())?.joined(separator: " ") else { return }
        do {
            try model.saveProfile(mnemonic: phrase, displayName: name)
            // Test identities only: printed so a second Simulator can restore the same one.
            log("identity \(name) created, phrase \(phrase)")
        } catch {
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
        case .member where !edited && (env["FS_TEST_RENAME"] != nil || env["FS_TEST_COLOR"] != nil):
            edited = true
            Task {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                if let name = env["FS_TEST_RENAME"] { await model.rename(name); log("renamed to \(name)") }
                if let colour = env["FS_TEST_COLOR"], let c = PersonPalette.colors.first(where: { $0.name == colour }) {
                    await model.setColor(c); log("colour \(colour)")
                }
            }
        case .member where env["FS_TEST_SECURITY_REPORT"] != nil && !reported:
            reported = true
            let delay = UInt64(env["FS_TEST_SECURITY_REPORT"].flatMap(UInt64.init) ?? 10)
            Task {
                try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
                log("security report:\n\(await model.securityReport() ?? "no node")")
            }
        case .member where env["FS_TEST_PLACE"] != nil && !placed:
            placed = true
            let f = env["FS_TEST_PLACE"]!.split(separator: ",").map(String.init)
            if f.count == 4, let lat = Double(f[1]), let lon = Double(f[2]), let r = Double(f[3]) {
                Task { await model.savePlace(Place(name: f[0], latitude: lat, longitude: lon, radiusMeters: r)); log("place \(f[0]) saved") }
            }
        case .member where env["FS_TEST_UPLOAD"] != nil && !uploaded:
            uploaded = true
            Task {
                try? await Task.sleep(nanoseconds: 8_000_000_000)   // let it connect and take the retained manifest
                let path = env["FS_TEST_UPLOAD"]!
                await model.uploadFile(URL(fileURLWithPath: path))
                log("uploaded \(path): \(model.lastError ?? "ok")")
            }
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
