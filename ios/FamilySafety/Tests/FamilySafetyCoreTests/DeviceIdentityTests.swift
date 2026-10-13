import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// Android 2.2 (PR #31) on iOS: the signed device ID in presence, 640-byte padding, and the
/// clone detector, mirroring Android's DeviceIdentityTest.
final class DeviceIdentityTests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000

    func test_signingStringVector() {
        XCTAssertEqual(PresenceUpdate.deviceSigningPayload(memberId: "alice", timestamp: 1_700_000_000_000, deviceId: "00ff"),
                       "presence-device:v1:alice:1700000000000:00ff")
    }

    func test_onlineAndOfflinePadToExactly640() throws {
        let id = try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data("pad".utf8))))
        let device = String(repeating: "ab", count: 16)
        for online in [true, false] {
            let p = try PresenceUpdate.signed(memberId: id.memberId, isOnline: online, timestamp: now, ed25519SecretKey64: id.ed25519SecretKey64, deviceId: device)
            let inner = String(decoding: try JSONEncoder().encode(p), as: UTF8.self)
            let size = try JSONEncoder().encode(EnvelopePadding.build(type: .presenceUpdate, payload: inner)).count
            XCTAssertEqual(size, 640, "online=\(online)")
        }
    }

    func test_deviceIdCountsOnlyWhenItsSignatureVerifies() throws {
        let a = try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data("a".utf8))))
        let b = try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data("b".utf8))))
        let signed = try PresenceUpdate.signed(memberId: a.memberId, isOnline: true, timestamp: now, ed25519SecretKey64: a.ed25519SecretKey64, deviceId: "11")
        XCTAssertEqual(signed.verifiedDeviceId(publicKey: a.ed25519PublicKey), "11")
        XCTAssertNil(signed.verifiedDeviceId(publicKey: b.ed25519PublicKey))
        let forged = PresenceUpdate(memberId: a.memberId, isOnline: true, timestamp: now, signature: signed.signature,
                                    deviceId: "22", deviceSignature: signed.deviceSignature)
        XCTAssertNil(forged.verifiedDeviceId(publicKey: a.ed25519PublicKey))
    }

    func test_twoPhonesOnlineAtOnceIsAClone_onceInSixHours() {
        var d = CloneDetector()
        XCTAssertFalse(d.observe(memberId: "m", deviceId: "A", isOnline: true, now: now))
        XCTAssertTrue(d.observe(memberId: "m", deviceId: "B", isOnline: true, now: now + 60_000))
        XCTAssertFalse(d.observe(memberId: "m", deviceId: "A", isOnline: true, now: now + 120_000), "cooldown")
    }

    func test_reinstallIsNotAClone() {
        var d = CloneDetector()
        _ = d.observe(memberId: "m", deviceId: "A", isOnline: true, now: now)
        XCTAssertFalse(d.observe(memberId: "m", deviceId: "A", isOnline: false, now: now + 60_000)) // last-will
        XCTAssertFalse(d.observe(memberId: "m", deviceId: "B", isOnline: true, now: now + 180_000))
    }

    func test_anotherDevicesOfflineProvesNothing_andAStaleSightingIsASwitch() {
        var d = CloneDetector()
        _ = d.observe(memberId: "m", deviceId: "A", isOnline: true, now: now)
        XCTAssertFalse(d.observe(memberId: "m", deviceId: "B", isOnline: false, now: now + 1_000))
        XCTAssertFalse(d.observe(memberId: "m", deviceId: "B", isOnline: true, now: now + CloneDetector.window + 5_000))
    }

    func test_ownTopicOnlyAnotherOnlineInstallAlerts() {
        XCTAssertTrue(CloneDetector.ownPresenceIsAnotherDevice(isOnline: true, deviceId: "B", myDeviceId: "A"))
        XCTAssertFalse(CloneDetector.ownPresenceIsAnotherDevice(isOnline: false, deviceId: "B", myDeviceId: "A"))
        XCTAssertFalse(CloneDetector.ownPresenceIsAnotherDevice(isOnline: true, deviceId: "A", myDeviceId: "A"))
    }
}
