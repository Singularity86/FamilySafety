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
// Dependencies for later phases are intentionally NOT pinned here yet — add them when
// that phase starts, so version choices are made against the Xcode/Swift toolchain
// actually being used:
//   Phase 2 (Transport): CocoaMQTT — https://github.com/emqx/CocoaMQTT
//   Phase 6 (Storage):   GRDB.swift (+ SQLCipher) — https://github.com/groue/GRDB.swift
//     GRDB's SQLCipher integration is version-sensitive (SPM trait / build flag setup
//     differs by release) — read GRDB's current README before pinning a version.

let package = Package(
    name: "FamilySafety",
    platforms: [
        .iOS(.v16)
    ],
    products: [
        .library(name: "FamilySafetyCore", targets: ["FamilySafetyCore"])
    ],
    dependencies: [
        .package(url: "https://github.com/jedisct1/swift-sodium.git", from: "0.9.1")
    ],
    targets: [
        .target(
            name: "FamilySafetyCore",
            dependencies: [
                .product(name: "Clibsodium", package: "swift-sodium")
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
        )
    ]
)
