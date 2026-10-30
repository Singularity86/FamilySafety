import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// B6: 30-day location history and its back-fill (spec §6.6), matching Android.
final class LocationHistoryTests: XCTestCase {
    private let day: Int64 = 24 * 60 * 60 * 1000
    private let now: Int64 = 1_800_000_000_000

    private func id(_ tag: String) throws -> FamilySafeIdentity {
        try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data(tag.utf8))))
    }
    private func member(_ i: FamilySafeIdentity) -> FamilyMember {
        FamilyMember(memberId: i.memberId, displayName: "x", ed25519PublicKey: i.ed25519PublicKeyHex,
                     x25519PublicKey: i.x25519PublicKeyHex, addedAtEpochMs: 1)
    }
    private func fix(_ who: String, _ t: Int64, lat: Double = 37.77) -> LocationUpdate {
        LocationUpdate(memberId: who, latitude: lat, longitude: -122.42, accuracy: 10, timestamp: t)
    }
    private func signed(_ i: FamilySafeIdentity, _ t: Int64) throws -> LocationUpdate {
        let u = fix(i.memberId, t)
        return LocationUpdate(memberId: u.memberId, latitude: u.latitude, longitude: u.longitude, accuracy: u.accuracy, timestamp: t,
                              authorSig: try AuthorSignatures.sign(AuthorSignatures.location(u), identity: i))
    }

    func test_store_keepsOnePerTimestamp_inOrder_for30Days() {
        var h = LocationHistory()
        XCTAssertEqual(h.add([fix("d", now - 10), fix("d", now - 20), fix("d", now - 10)], now: now), 2)
        XCTAssertEqual(h.fixes["d"]?.map(\.timestamp), [now - 20, now - 10])
        XCTAssertEqual(h.add([fix("d", now - 31 * day)], now: now), 0, "older than 30 days isn't taken")
        XCTAssertEqual(h.add([fix("d", now + 2 * day)], now: now), 0, "nor a clock far in the future")
        XCTAssertEqual(h.newestTimestamp("d"), now - 10)
        XCTAssertEqual(h.between("d", from: now - 15, to: now).count, 1)
        h.prune(now: now + 30 * day, members: ["d"])
        XCTAssertTrue(h.fixes.isEmpty)
        _ = h.add([fix("d", now), fix("x", now)], now: now)
        h.prune(now: now, members: ["d"])
        XCTAssertEqual(Set(h.fixes.keys), ["d"], "someone who left is forgotten")
    }

    func test_fullSync_asksEachMemberForTheirOwn_andEveryoneForOurs() {
        var r = LocationReplication(me: "me")
        var n = 0
        let asks = r.fullSync(history: LocationHistory(), peers: ["ben", "dana"], now: now, newId: { n += 1; return "r\(n)" })
        XCTAssertEqual(Set(asks.map { "\($0.peerId)>\($0.request.targetMemberId!)" }), ["ben>ben", "dana>dana", "ben>me", "dana>me"])
        XCTAssertTrue(r.fullSync(history: LocationHistory(), peers: ["ben"], now: now + 1).isEmpty, "at most every 15 minutes")
    }

    func test_announcement_requestsOnlyNewer_oncePerMember() {
        var h = LocationHistory()
        _ = h.add([fix("ben", now - 100)], now: now)
        var r = LocationReplication(me: "me")
        let a = DataAvailabilityAnnouncement(announcerId: "dana", locationDataSummary: [
            LocationHistorySummary(memberId: "ben", oldestTimestamp: 1, newestTimestamp: now - 50, count: 9),
            LocationHistorySummary(memberId: "stranger", oldestTimestamp: 1, newestTimestamp: now, count: 9),
            LocationHistorySummary(memberId: "dana", oldestTimestamp: 1, newestTimestamp: now - 200, count: 9)], timestamp: 0)
        let asks = r.handle(a, from: "dana", history: h, members: ["me", "ben", "dana"], now: now)
        XCTAssertEqual(Set(asks.map { $0.targetMemberId! }), ["ben", "dana"], "not someone outside the family")
        XCTAssertEqual(asks.first { $0.targetMemberId == "ben" }?.afterTimestamp, now - 100)
        XCTAssertTrue(r.handle(a, from: "dana", history: h, members: ["me", "ben", "dana"], now: now + 1).isEmpty)
    }

    func test_respond_pagesOneMember() {
        var h = LocationHistory()
        _ = h.add((1...3).map { fix("ben", now - Int64($0)) }, now: now)
        let r = LocationReplication(me: "me")
        let page = r.respond(to: ReplicationRequest(requestId: "q", requesterId: "dana", dataType: .locationHistory, targetMemberId: "ben",
                                                    afterTimestamp: 0, limit: 2, timestamp: 0), from: "dana", history: h,
                             members: ["me", "ben", "dana"], now: now)
        XCTAssertEqual(page?.locations.count, 2)
        XCTAssertEqual(page?.hasMore, true)
        XCTAssertNil(r.respond(to: ReplicationRequest(requestId: "q", requesterId: "dana", dataType: .locationHistory, targetMemberId: "ben",
                                                      afterTimestamp: now, limit: 2, timestamp: 0), from: "dana", history: h,
                               members: ["me", "ben", "dana"], now: now), "nothing newer, nothing sent")
    }

    func test_response_keepsOnlySignedPlausibleFixesByMembers() throws {
        let ana = try id("ana"), ben = try id("ben"), dana = try id("dana")
        let family = GroupDefinition(groupId: "g", groupName: "F", createdAtEpochMs: 1, creatorMemberId: ana.memberId,
                                     members: [ana, ben, dana].map(member), version: 1)
        let good = try signed(ben, now - 5)
        let unsigned = fix(ben.memberId, now - 4)
        let forgedPlace = LocationUpdate(memberId: ben.memberId, latitude: 1, longitude: 2, accuracy: 10, timestamp: now - 3, authorSig: good.authorSig)
        let impossible = LocationUpdate(memberId: ben.memberId, latitude: 200, longitude: 2, accuracy: 10, timestamp: now - 2)
        let outsider = fix("eve", now - 1)
        let response = ReplicationResponse(requestId: "x", senderId: dana.memberId, dataType: .locationHistory,
                                           locations: [good, unsigned, forgedPlace, impossible, outsider], hasMore: false, timestamp: 0)
        var r = LocationReplication(me: ana.memberId)
        XCTAssertEqual(r.handle(response, from: member(dana), roster: family, authorVersion: { _ in 4 }, now: now).fixes, [good],
                       "Dana relays Ben's history; only what Ben signed survives")
        XCTAssertEqual(r.handle(response, from: member(dana), roster: family, authorVersion: { _ in 3 }, now: now).fixes.count, 2,
                       "unsigned history is still taken from a pre-4 author")
    }
}
