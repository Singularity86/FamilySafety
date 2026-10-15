import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// 3.1 on iOS (Android ProfileGuardTest, PR #35): signed strings, who may change a profile or
/// the subscription, replay of an older signed profile, immutables, and keeping unknown fields.
final class ProfileGuardTests: XCTestCase {
    private func identity(_ tag: String) throws -> FamilySafeIdentity {
        try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data(tag.utf8))))
    }
    private func member(_ id: FamilySafeIdentity, _ name: String) -> FamilyMember {
        FamilyMember(memberId: id.memberId, displayName: name, ed25519PublicKey: id.ed25519PublicKeyHex,
                     x25519PublicKey: id.x25519PublicKeyHex, addedAtEpochMs: 1)
    }

    func test_vectorsMatchAndroid() {
        let dana = FamilyMember(memberId: "dana", displayName: "Dana", ed25519PublicKey: "", x25519PublicKey: "", addedAtEpochMs: 1,
                                avatarHash: "ab", colorHue: 120.5, profileUpdatedAtMs: 100)
        XCTAssertEqual(String(decoding: ProfileGuard.profileMessage(groupId: "g", member: dana), as: UTF8.self), "PROFILE:v1:g:dana:0:100:Dana:ab:12050")
        let bare = FamilyMember(memberId: "dana", displayName: "Dana", ed25519PublicKey: "", x25519PublicKey: "", addedAtEpochMs: 1)
        XCTAssertEqual(String(decoding: ProfileGuard.profileMessage(groupId: "g", member: bare), as: UTF8.self), "PROFILE:v1:g:dana:0::Dana::")
        XCTAssertEqual(String(decoding: ProfileGuard.subscriptionMessage(groupId: "g", subscriberMemberId: "dana", confirmedAtEpochMs: 100), as: UTF8.self),
                       "SUBSCRIPTION:v1:g:dana:100")
    }

    func test_signedNewerRenameRelayedByAnotherMemberIsTaken_andAnOlderReplayIsNot() throws {
        let dana = try identity("dana"), relay = try identity("relay")
        let v1 = try ProfileGuard.signed(member(dana, "Dana"), groupId: "g", identity: dana, now: 100)
        let current = GroupDefinition(groupId: "g", groupName: "F", createdAtEpochMs: 1, creatorMemberId: relay.memberId,
                                      members: [member(relay, "R"), v1], version: 2)
        let renamed = try ProfileGuard.signed(v1.withProfile(displayName: "D", avatarHash: nil, colorHue: nil, updatedAtMs: nil, signature: nil),
                                              groupId: "g", identity: dana, now: 200)
        let relayed = current.with(members: [member(relay, "R"), renamed])
        let taken = GroupStateStore.guarded(relayed, current: current, updater: relay.memberId)
        XCTAssertEqual(taken.findMember(byId: dana.memberId)?.displayName, "D")

        // A stale phone re-serving the older, validly signed "Dana" can't roll the rename back.
        let replay = GroupStateStore.guarded(current, current: taken, updater: relay.memberId)
        XCTAssertEqual(replay.findMember(byId: dana.memberId)?.displayName, "D")
    }

    func test_unsignedRenameByAnotherMemberKeepsOurs_butTheMemberThemselvesMayRename() throws {
        let dana = try identity("dana"), relay = try identity("relay")
        let current = GroupDefinition(groupId: "g", groupName: "F", createdAtEpochMs: 1, creatorMemberId: relay.memberId,
                                      members: [member(relay, "R"), member(dana, "Dana")], version: 2)
        let forged = current.with(members: [member(relay, "R"), member(dana, "Mallory")])
        XCTAssertEqual(GroupStateStore.guarded(forged, current: current, updater: relay.memberId).findMember(byId: dana.memberId)?.displayName, "Dana")
        XCTAssertEqual(GroupStateStore.guarded(forged, current: current, updater: dana.memberId).findMember(byId: dana.memberId)?.displayName, "Mallory")
    }

    func test_joinDetailsAndASetFileKeyAreImmutable() throws {
        let a = try identity("a")
        let current = GroupDefinition(groupId: "g", groupName: "F", createdAtEpochMs: 1, creatorMemberId: a.memberId,
                                      members: [member(a, "A")], version: 1, fileEncryptionKey: String(repeating: "1", count: 64))
        let moved = FamilyMember(memberId: a.memberId, displayName: "A", ed25519PublicKey: a.ed25519PublicKeyHex,
                                 x25519PublicKey: a.x25519PublicKeyHex, addedAtEpochMs: 999)
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: current.with(members: [moved], version: 2,
            previousStateHash: .some(current.computeStateHash())), updaterMemberId: a.memberId, concurrent: false)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .joinDetailsChanged)
        }
        XCTAssertThrowsError(try GroupTransitionValidator.validate(current: current, remote: current.with(version: 2,
            previousStateHash: .some(current.computeStateHash()), fileEncryptionKey: .some(String(repeating: "2", count: 64))),
            updaterMemberId: a.memberId, concurrent: false)) {
            XCTAssertEqual($0 as? GroupTransitionRejection, .fileKeyChanged)
        }
        // A state without a key keeps ours.
        let keyless = current.with(fileEncryptionKey: .some(nil))
        XCTAssertEqual(GroupStateStore.guarded(keyless, current: current, updater: a.memberId).fileEncryptionKey, current.fileEncryptionKey)
    }

    func test_unknownFieldsAndKeyringSurviveARelay() throws {
        let json = #"""
        {"groupId":"g","groupName":"F","createdAtEpochMs":1,"creatorMemberId":"m","version":3,
         "members":[{"memberId":"m","displayName":"M","ed25519PublicKey":"aa","x25519PublicKey":"bb","addedAtEpochMs":1,
                     "futureMemberField":{"x":[1,2]}}],
         "groupKeys":[{"kid":"k","epoch":1,"key":"cc"}],"futureGroupField":"keep me"}
        """#
        let group = try JSONDecoder().decode(GroupDefinition.self, from: Data(json.utf8))
        let again = try JSONDecoder().decode(GroupDefinition.self, from: try JSONEncoder().encode(group))
        XCTAssertEqual(again, group)
        XCTAssertEqual(again.extras["futureGroupField"], .string("keep me"))
        XCTAssertEqual(again.members.first?.extras["futureMemberField"], .object(["x": .array([.number(1), .number(2)])]))
        XCTAssertEqual(again.groupKeys, [GroupKey(kid: "k", epoch: 1, key: "cc")])
        XCTAssertEqual(again.computeStateHash(), group.computeStateHash())
    }
}
