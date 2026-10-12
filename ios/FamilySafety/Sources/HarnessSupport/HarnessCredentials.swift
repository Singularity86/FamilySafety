import FamilySafetyCore
import Foundation

/// Broker credentials for the manual live-broker harnesses. Never logged, never written to disk.
///
/// MQTT_USERNAME / MQTT_PASSWORD win when both are set. Otherwise the macOS Keychain entry with
/// service "FamilySafety-MQTT" is used, stored once with:
///   security add-generic-password -U -s FamilySafety-MQTT -a <broker-username> -w
/// It is read through /usr/bin/security (the tool that created it) so the Keychain's access
/// list allows it without a prompt.
public enum HarnessCredentials {
    public static let keychainService = "FamilySafety-MQTT"

    public static func load() -> BrokerCredentials? {
        let env = ProcessInfo.processInfo.environment
        if let username = env["MQTT_USERNAME"], let password = env["MQTT_PASSWORD"] {
            return BrokerCredentials(username: username, password: password)
        }
        #if os(macOS)
        guard let attributes = security(["find-generic-password", "-s", keychainService]),
              let username = account(in: attributes),
              let password = security(["find-generic-password", "-s", keychainService, "-w"])?
                  .trimmingCharacters(in: .newlines),
              !password.isEmpty
        else { return nil }
        return BrokerCredentials(username: username, password: password)
        #else
        return nil
        #endif
    }

    public static let missingMessage =
        "No broker credentials. Set MQTT_USERNAME and MQTT_PASSWORD, or store them once with:\n" +
        "  security add-generic-password -U -s \(keychainService) -a <broker-username> -w"

    #if os(macOS)
    private static func security(_ arguments: [String]) -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/security")
        process.arguments = arguments
        let out = Pipe()
        process.standardOutput = out
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return nil }
        let data = out.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { return nil }
        return String(decoding: data, as: UTF8.self)
    }

    /// The `"acct"<blob>="name"` line of `security find-generic-password` output.
    private static func account(in attributes: String) -> String? {
        for line in attributes.split(separator: "\n") where line.contains("\"acct\"<blob>=\"") {
            let value = line.split(separator: "=", maxSplits: 1).last.map(String.init) ?? ""
            let trimmed = value.trimmingCharacters(in: CharacterSet(charactersIn: "\""))
            return trimmed.isEmpty ? nil : trimmed
        }
        return nil
    }
    #endif
}
