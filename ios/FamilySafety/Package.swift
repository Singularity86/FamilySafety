// swift-tools-version:5.10
import PackageDescription

// FamilySafety iOS — Swift Package scaffold.
//
// Open this folder directly in Xcode (File > Open… on `ios/FamilySafety/`) — SwiftPM
// packages don't need an .xcodeproj. Phase 0 (see ../IOS_PORT_SPEC.md §14) only needs
// FamilySafetyCore + the swift-sodium package's "Clibsodium" product; run its tests
// with `swift test` once a Swift toolchain / Xcode is available (this was scaffolded
// on Windows, which has neither, so `swift build` / `swift test` have not been run yet
// — do that first on a Mac, before writing any further phases).
//
// We depend on swift-sodium purely for its "Clibsodium" product (a vendored libsodium
// binary with a Swift module map) and call the raw C functions directly — see
// SodiumRaw.swift. This sidesteps swift-sodium's higher-level Swift wrapper API, whose
// exact overloads could not be verified against a live compiler while scaffolding.
//
// Dependencies for later phases are added when that phase starts, so version choices are
// made against the Xcode/Swift toolchain actually being used:
//   Phase 2 (Transport): CocoaMQTT — https://github.com/emqx/CocoaMQTT — ADDED.
//   Phase 6 (Storage):   GRDB.swift (+ SQLCipher) — https://github.com/groue/GRDB.swift
//     GRDB's SQLCipher integration is version-sensitive (SPM trait / build flag setup
//     differs by release) — read GRDB's current README before pinning a version.

let package = Package(
    name: "FamilySafety",
    platforms: [
        .iOS(.v16),
        // `swift test` runs the test target on the HOST platform (macOS), not iOS —
        // this is what CI actually exercises. Without a macOS floor here, SwiftPM
        // falls back to an old default deployment target and CryptoKit's SHA256 (which
        // needs macOS 10.15+) fails to type-check. The app itself still only ships iOS.
        .macOS(.v13)
    ],
    products: [
        .library(name: "FamilySafetyCore", targets: ["FamilySafetyCore"])
    ],
    dependencies: [
        .package(url: "https://github.com/jedisct1/swift-sodium.git", from: "0.9.1"),
        .package(url: "https://github.com/emqx/CocoaMQTT.git", from: "2.1.6")
    ],
    targets: [
        .target(
            name: "FamilySafetyCore",
            dependencies: [
                .product(name: "Clibsodium", package: "swift-sodium"),
                .product(name: "CocoaMQTT", package: "CocoaMQTT")
            ],
            resources: [
                // .copy (not .process) so the wordlist is embedded byte-for-byte —
                // it must stay identical to the Android app's bundled copy.
                .copy("Resources/bip39_english.txt")
            ]
        ),
        .testTarget(
            name: "FamilySafetyCoreTests",
            dependencies: ["FamilySafetyCore"]
        ),
        // Manual, throwaway two-process harness for the §14 Phase 2 live-broker
        // acceptance test (two clients seeing each other's presence flip online/offline
        // over the real broker, LWT observed on an abrupt kill). NOT part of the app;
        // takes broker credentials from MQTT_USERNAME/MQTT_PASSWORD env vars only — never
        // reads or writes them to disk. Run with `swift run PresenceHarness`.
        .executableTarget(
            name: "PresenceHarness",
            dependencies: ["FamilySafetyCore"]
        )
    ]
)
