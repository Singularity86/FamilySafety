import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// B2: live location (spec §6.1, §6.2, §4) and Android's publish policy.
final class LocationTests: XCTestCase {
    private func id(_ tag: String) throws -> FamilySafeIdentity {
        try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data(tag.utf8))))
    }
    private func member(_ i: FamilySafeIdentity) -> FamilyMember {
        FamilyMember(memberId: i.memberId, displayName: "x", ed25519PublicKey: i.ed25519PublicKeyHex,
                     x25519PublicKey: i.x25519PublicKeyHex, addedAtEpochMs: 1)
    }
    private func fix(_ who: String, lat: Double = 37.7749, lon: Double = -122.4194, acc: Float = 10, t: Int64 = 1_800_000_000_000) -> LocationUpdate {
        LocationUpdate(memberId: who, latitude: lat, longitude: lon, accuracy: acc, timestamp: t, speed: 1.5, bearing: 90)
    }

    func test_roundTrip_signedPaddedAndBoundToTheSender() throws {
        let dana = try id("dana"), sam = try id("sam")
        let body = try LocationCodec.plaintext(for: fix(dana.memberId), identity: dana)
        XCTAssertEqual(body.utf8.count % 512, 0, "padded to the 512-byte grid")
        let read = LocationCodec.read(body, sender: member(dana))
        XCTAssertEqual(read?.latitude, 37.7749)
        XCTAssertNotNil(read?.authorSig)
        XCTAssertNil(LocationCodec.read(body, sender: member(sam)), "memberId must be the authenticated sender (F23)")
    }

    func test_badAuthorSignatureIsDropped_unsignedIsTakenLive() throws {
        let dana = try id("dana")
        let forged = LocationUpdate(memberId: dana.memberId, latitude: 1, longitude: 2, accuracy: 3, timestamp: 4,
                                    authorSig: String(repeating: "00", count: 64))
        let unsigned = LocationUpdate(memberId: dana.memberId, latitude: 1, longitude: 2, accuracy: 3, timestamp: 4)
        func wrap(_ f: LocationUpdate) throws -> String {
            let inner = String(decoding: try JSONEncoder().encode(f), as: UTF8.self)
            return String(decoding: try JSONEncoder().encode(try EnvelopePadding.build(type: .locationUpdate, payload: inner)), as: UTF8.self)
        }
        XCTAssertNil(LocationCodec.read(try wrap(forged), sender: member(dana)))
        XCTAssertNotNil(LocationCodec.read(try wrap(unsigned), sender: member(dana)))
    }

    func test_publishPolicy_matchesAndroid() {
        let a = fix("d")
        XCTAssertTrue(LocationPublishPolicy.isRedundant(previous: a, next: fix("d", lat: 37.7750, t: a.timestamp + 30_000)), "~11 m is jitter")
        XCTAssertFalse(LocationPublishPolicy.isRedundant(previous: a, next: fix("d", lat: 37.7760, t: a.timestamp + 30_000)), "~120 m is travel")
        XCTAssertFalse(LocationPublishPolicy.isRedundant(previous: a, next: fix("d", t: a.timestamp + 5 * 60_000)), "5 minutes of silence")
        XCTAssertFalse(LocationPublishPolicy.isRedundant(previous: fix("d", acc: 100), next: fix("d", acc: 20, t: a.timestamp + 1)), "much more accurate")
        XCTAssertTrue(LocationPublishPolicy.isRedundant(previous: a, next: fix("d", lat: 37.78, acc: 2000, t: a.timestamp + 1)), "a 2 km cell fix 600 m away is noise")
        XCTAssertTrue(LocationPublishPolicy.isRedundant(previous: a, next: fix("d", t: a.timestamp)), "not newer")
    }

    func test_book_keepsTheNewestPerMember() {
        var book = LocationBook()
        XCTAssertTrue(book.accept(fix("d", t: 10)))
        XCTAssertFalse(book.accept(fix("d", lat: 1, t: 9)))
        XCTAssertTrue(book.accept(fix("d", lat: 2, t: 11)))
        XCTAssertEqual(book.latest["d"]?.latitude, 2)
        book.keepOnly(["x"])
        XCTAssertTrue(book.latest.isEmpty)
    }

    func test_endToEnd_sealedSequencedAndReplayRefused() async throws {
        let dana = try id("dana"), riley = try id("riley")
        let g = GroupDefinition(groupId: "g", groupName: "F", createdAtEpochMs: 1, creatorMemberId: dana.memberId,
                                members: [member(dana), member(riley)], version: 1)
        let danaStore = GroupStateStore(localMemberId: dana.memberId, persistence: InMemoryGroupStatePersistence(initial: g))
        let rileyStore = GroupStateStore(localMemberId: riley.memberId, persistence: InMemoryGroupStatePersistence(initial: g))
        try await danaStore.load(); try await rileyStore.load()
        actor Null: GroupSyncOutbox { func send(topic: String, payload: Data, qos: Int, retained: Bool) async {} }
        let danaEngine = GroupSyncEngine(identity: dana, store: danaStore, outbox: Null(), now: { 1_800_000_000_000 })
        let rileyEngine = GroupSyncEngine(identity: riley, store: rileyStore, outbox: Null(), now: { 1_800_000_000_000 })
        await danaEngine.recordSighting(memberId: riley.memberId, protocolVersion: 5)
        let sealed = await danaEngine.sealForPeer(try LocationCodec.plaintext(for: fix(dana.memberId), identity: dana), to: member(riley))
        let opened = await rileyEngine.openFromPeer(try XCTUnwrap(sealed))
        XCTAssertEqual(opened.flatMap { LocationCodec.read($0.body, sender: $0.sender) }?.memberId, dana.memberId)
        let again = await rileyEngine.openFromPeer(sealed!)
        XCTAssertNil(again, "the replay window refuses the same envelope twice")
    }
}
