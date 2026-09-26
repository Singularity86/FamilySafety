import SwiftUI

// Throwaway visual harness app — NOT the real FamilySafety iOS app. Exists to run the
// Phase 0-2 code (crypto, group model/validator, wire formats, MQTT transport) inside an
// actual iOS Simulator instance rather than as a bare command-line process. Role and
// broker credentials come from the environment (SIMCTL_CHILD_ROLE / SIMCTL_CHILD_MQTT_*
// when launched via `xcrun simctl launch`), never hardcoded or written to disk.
@main
struct FamilySafetyHarnessApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
