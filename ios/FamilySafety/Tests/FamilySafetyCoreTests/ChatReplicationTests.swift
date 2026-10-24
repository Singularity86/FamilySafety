import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// B5: chat history back-fill (spec §6.6), matching Android ReplicationManager.
final class ChatReplicationTests: XCTestCase {
    private let g = "family-1"

    private func id(_ tag: String) throws -> FamilySafeIdentity {
        try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data(tag.utf8))))
    }
    private func member(_ i: FamilySafeIdentity) -> FamilyMember {
        FamilyMember(memberId: i.memberId, displayName: "x", ed25519PublicKey: i.ed25519PublicKeyHex,
                     x25519PublicKey: i.x25519PublicKeyHex, addedAtEpochMs: 1)
    }
    private func roster(_ people: [FamilySafeIdentity]) -> GroupDefinition {
        GroupDefinition(groupId: g, groupName: "F", createdAtEpochMs: 1, creatorMemberId: people[0].memberId,
                        members: people.map(member), version: 1)
    }
    private func counter() -> () -> String { var n = 0; return { n += 1; return "r\(n)" } }

    func test_androidOmitsDefaults_andWeStillDecode() throws {
        let req = try JSONDecoder().decode(ReplicationRequest.self, from: Data(
            #"{"requestId":"r","requesterId":"a","dataType":"CHAT_MESSAGES","conversationId":"family-1","afterTimestamp":5}"#.utf8))
        XCTAssertEqual(req.limit, 500)
        XCTAssertNil(req.targetMemberId)
        let resp = try JSONDecoder().decode(ReplicationResponse.self, from: Data(
            #"{"requestId":"r","senderId":"a","dataType":"CHAT_MESSAGES","messages":[]}"#.utf8))
        XCTAssertEqual(resp.locations, [])
        XCTAssertFalse(resp.hasMore)
        let ann = try JSONDecoder().decode(DataAvailabilityAnnouncement.self, from: Data(
            #"{"announcerId":"a","locationDataSummary":[],"chatDataSummary":[{"conversationId":"family-1","oldestTimestamp":1,"newestTimestamp":2,"count":1}]}"#.utf8))
        XCTAssertEqual(ann.chatDataSummary.count, 1)
    }

    func test_scoping() {
        XCTAssertEqual(ChatReplication.directParticipants("a:b"), ["a", "b"])
        XCTAssertNil(ChatReplication.directParticipants(g))
        XCTAssertNil(ChatReplication.directParticipants("a:"))
        XCTAssertTrue(ChatReplication.mayShare(g, "c", groupId: g))
        XCTAssertFalse(ChatReplication.mayShare("old-family", "c", groupId: g), "another family's chat is not shared")
        XCTAssertTrue(ChatReplication.mayShare("a:b", "a", groupId: g))
        XCTAssertFalse(ChatReplication.mayShare("a:b", "c", groupId: g), "a private chat only on its two people")
    }

    func test_announcementToAPeerNamesOnlyWhatTheyMayHold() throws {
        let ana = try id("ana"), ben = try id("ben"), dana = try id("dana")
        var book = ChatBook()
        book.insert(ChatCodec.compose(content: "all", to: nil, groupId: g, identity: ana, now: 1))
        book.insert(ChatCodec.compose(content: "to ben", to: ben.memberId, groupId: g, identity: ana, now: 2))
        let r = ChatReplication(me: ana.memberId)
        XCTAssertEqual(Set(r.announcement(for: ben.memberId, book: book, groupId: g, now: 3).chatDataSummary.map(\.conversationId)),
                       [g, ChatBook.privateConversationId(ana.memberId, ben.memberId)])
        XCTAssertEqual(r.announcement(for: dana.memberId, book: book, groupId: g, now: 3).chatDataSummary.map(\.conversationId), [g],
                       "Dana doesn't even hear that Ana and Ben's thread exists")
    }

    func test_announcementLeadsToOneRequestForWhatsNewer() throws {
        let ana = try id("ana")
        var book = ChatBook()
        book.insert(ChatCodec.compose(content: "old", to: nil, groupId: g, identity: ana, now: 100))
        var r = ChatReplication(me: ana.memberId)
        let newer = DataAvailabilityAnnouncement(announcerId: "ben", chatDataSummary: [
            ChatHistorySummary(conversationId: g, oldestTimestamp: 1, newestTimestamp: 200, count: 3),
            ChatHistorySummary(conversationId: "ben:dana", oldestTimestamp: 1, newestTimestamp: 200, count: 3),
            ChatHistorySummary(conversationId: "other-family", oldestTimestamp: 1, newestTimestamp: 200, count: 3)], timestamp: 1)
        let asked = r.handle(newer, from: "ben", book: book, groupId: g, now: 1000, newId: counter())
        XCTAssertEqual(asked.map(\.conversationId), [g], "not Ben and Dana's private chat, not another family's")
        XCTAssertEqual(asked.first?.afterTimestamp, 100)
        let fromSam = DataAvailabilityAnnouncement(announcerId: "sam", chatDataSummary: newer.chatDataSummary, timestamp: 1)
        XCTAssertTrue(r.handle(fromSam, from: "sam", book: book, groupId: g, now: 2000).isEmpty, "one outstanding request per conversation")
        XCTAssertEqual(r.handle(fromSam, from: "sam", book: book, groupId: g, now: 1000 + ChatReplication.requestTimeoutMs).count, 1, "until it times out")
        let same = DataAvailabilityAnnouncement(announcerId: "ben", chatDataSummary: [
            ChatHistorySummary(conversationId: g, oldestTimestamp: 1, newestTimestamp: 100, count: 1)], timestamp: 1)
        var fresh = ChatReplication(me: ana.memberId)
        XCTAssertTrue(fresh.handle(same, from: "ben", book: book, groupId: g, now: 1).isEmpty, "nothing newer, nothing asked")
        XCTAssertTrue(fresh.handle(newer, from: "sam", book: book, groupId: g, now: 1).isEmpty, "announcerId must be the sender")
    }

    func test_respond_refusesOutsiders_pagesAndSaysWhenNothing() throws {
        let ana = try id("ana"), ben = try id("ben")
        var book = ChatBook()
        for t in 1...3 { book.insert(ChatCodec.compose(content: "m\(t)", to: nil, groupId: g, identity: ana, now: Int64(t))) }
        book.insert(ChatCodec.compose(content: "p", to: ben.memberId, groupId: g, identity: ana, now: 9))
        let r = ChatReplication(me: ana.memberId)
        func ask(_ conv: String, by who: String, after: Int64 = 0, limit: Int = 500) -> ReplicationResponse? {
            r.respond(to: ReplicationRequest(requestId: "q", requesterId: who, dataType: .chatMessages, conversationId: conv,
                                             afterTimestamp: after, limit: limit, timestamp: 0), from: who, book: book, groupId: g, now: 10)
        }
        let page = try XCTUnwrap(ask(g, by: ben.memberId, after: 1, limit: 1))
        XCTAssertEqual(page.messages.map(\.content), ["m2"])
        XCTAssertTrue(page.hasMore)
        XCTAssertEqual(page.newestTimestamp, 2)
        XCTAssertNil(ask(g, by: ben.memberId, after: 3), "nothing newer: no response, as on Android")
        XCTAssertNil(ask(ChatBook.privateConversationId(ana.memberId, ben.memberId), by: "dana"), "not a participant")
        XCTAssertNotNil(ask(ChatBook.privateConversationId(ana.memberId, ben.memberId), by: ben.memberId))
        XCTAssertNil(r.respond(to: ReplicationRequest(requestId: "q", requesterId: "dana", dataType: .chatMessages, conversationId: g,
                                                       afterTimestamp: 0, limit: 5, timestamp: 0), from: ben.memberId, book: book, groupId: g, now: 1),
                     "requesterId must be who asked")
        XCTAssertEqual(page.messages.first?.authorSig, book.conversation(g)[1].authorSig, "the author's signature is passed on unchanged")
    }

    func test_response_keepsSignedAndScoped_dropsTheRest() throws {
        let ana = try id("ana"), ben = try id("ben"), dana = try id("dana")
        let family = roster([ana, ben, dana])
        func rep(_ m: ChatMessage, sig: String?? = .none) -> ReplicatedMessage {
            ReplicatedMessage(messageId: m.messageId, conversationId: m.conversationId, senderId: m.senderId, recipientId: m.recipientId,
                              content: m.content, messageType: m.messageType, status: .delivered, timestamp: m.timestamp,
                              isOutgoing: false, authorSig: sig ?? m.authorSig)
        }
        let good = ChatCodec.compose(content: "signed", to: nil, groupId: g, identity: ben, now: 5)
        let unsigned = ChatCodec.compose(content: "unsigned", to: nil, groupId: g, identity: dana, now: 6)
        let forged = ChatCodec.compose(content: "forged", to: nil, groupId: g, identity: ben, now: 7)
        let theirs = ChatCodec.compose(content: "ben to dana", to: dana.memberId, groupId: g, identity: ben, now: 8)
        let mine = ChatCodec.compose(content: "my lost message", to: nil, groupId: g, identity: ana, now: 9)
        let response = ReplicationResponse(requestId: "x", senderId: ben.memberId, dataType: .chatMessages,
                                           messages: [rep(good), rep(unsigned, sig: .some(nil)),
                                                      rep(forged, sig: .some(String(repeating: "00", count: 64))), rep(theirs), rep(mine)],
                                           hasMore: false, timestamp: 1)
        var r = ChatReplication(me: ana.memberId)
        let v4 = r.handle(response, from: member(ben), roster: family, authorVersion: { _ in 4 }, now: 10)
        XCTAssertEqual(v4.messages.map(\.content), ["signed", "my lost message"],
                       "unsigned history from a protocol-4 author, forged and third-party private ones are dropped")
        XCTAssertTrue(v4.messages.last!.isOutgoing, "our own message comes back as ours")
        XCTAssertFalse(v4.messages.first!.isRead, "a back-filled message from someone else is unread")
        let v3 = r.handle(response, from: member(ben), roster: family, authorVersion: { $0 == dana.memberId ? 3 : 4 }, now: 10)
        XCTAssertEqual(v3.messages.map(\.content), ["signed", "unsigned", "my lost message"], "unsigned is still taken from a pre-4 author")
    }

    func test_pagingFollowsOnlyForwardFromTheAskedPeer_upTo20Pages() throws {
        let ana = try id("ana"), ben = try id("ben")
        let family = roster([ana, ben])
        var r = ChatReplication(me: ana.memberId)
        var book = ChatBook()
        book.insert(ChatCodec.compose(content: "x", to: nil, groupId: g, identity: ana, now: 1))
        let first = r.handle(DataAvailabilityAnnouncement(announcerId: ben.memberId, chatDataSummary: [
            ChatHistorySummary(conversationId: g, oldestTimestamp: 1, newestTimestamp: 9_999, count: 9)], timestamp: 0),
                             from: ben.memberId, book: book, groupId: g, now: 0, newId: counter())[0]
        var asked = first
        var pages = 1
        while true {
            let got = r.handle(ReplicationResponse(requestId: asked.requestId, senderId: ben.memberId, dataType: .chatMessages,
                                                   hasMore: true, newestTimestamp: asked.afterTimestamp + 1, timestamp: 0),
                               from: member(ben), roster: family, authorVersion: { _ in 4 }, now: 0)
            guard let next = got.next else { break }
            XCTAssertEqual(next.afterTimestamp, asked.afterTimestamp + 1)
            asked = next
            pages += 1
        }
        XCTAssertEqual(pages, ChatReplication.maxPages)
        var stuck = ChatReplication(me: ana.memberId)
        let q = stuck.handle(DataAvailabilityAnnouncement(announcerId: ben.memberId, chatDataSummary: [
            ChatHistorySummary(conversationId: g, oldestTimestamp: 1, newestTimestamp: 9_999, count: 9)], timestamp: 0),
                             from: ben.memberId, book: book, groupId: g, now: 0)[0]
        XCTAssertNil(stuck.handle(ReplicationResponse(requestId: q.requestId, senderId: ben.memberId, dataType: .chatMessages, hasMore: true,
                                                      newestTimestamp: q.afterTimestamp, timestamp: 0),
                                  from: member(ben), roster: family, authorVersion: { _ in 4 }, now: 0).next,
                     "a page that didn't move forward isn't followed")
    }

    func test_fullSync_atMostEvery15Minutes_privateOnlyToTheOtherPerson() throws {
        let ana = try id("ana"), ben = try id("ben")
        var book = ChatBook()
        book.insert(ChatCodec.compose(content: "g", to: nil, groupId: g, identity: ana, now: 1))
        book.insert(ChatCodec.compose(content: "p", to: ben.memberId, groupId: g, identity: ana, now: 2))
        var r = ChatReplication(me: ana.memberId)
        let asks = r.fullSync(book: book, peers: [ben.memberId, "dana"], groupId: g, now: 0, newId: counter())
        let pair = ChatBook.privateConversationId(ana.memberId, ben.memberId)
        XCTAssertEqual(Set(asks.map { "\($0.peerId)|\($0.request.conversationId!)" }),
                       ["\(ben.memberId)|\(g)", "dana|\(g)", "\(ben.memberId)|\(pair)"])
        XCTAssertTrue(r.fullSync(book: book, peers: [ben.memberId], groupId: g, now: ChatReplication.minFullSyncIntervalMs - 1).isEmpty)
        XCTAssertFalse(r.fullSync(book: book, peers: [ben.memberId], groupId: g, now: ChatReplication.minFullSyncIntervalMs).isEmpty)
    }
}
