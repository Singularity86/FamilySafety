import CryptoKit
import XCTest
@testable import FamilySafetyCore

/// B3: chat and receipts (spec §6.3), matching Android ChatRepository.
final class ChatTests: XCTestCase {
    private let groupId = "family-1"

    private func id(_ tag: String) throws -> FamilySafeIdentity {
        try FamilySafeKeyDerivation.deriveIdentity(seed: Array(SHA512.hash(data: Data(tag.utf8))))
    }
    private func member(_ i: FamilySafeIdentity) -> FamilyMember {
        FamilyMember(memberId: i.memberId, displayName: "x", ed25519PublicKey: i.ed25519PublicKeyHex,
                     x25519PublicKey: i.x25519PublicKeyHex, addedAtEpochMs: 1)
    }

    func test_groupMessage_roundTripsSignedWithTheGroupId() throws {
        let ana = try id("ana"), dana = try id("dana")
        let sent = ChatCodec.compose(content: "hi all", to: nil, groupId: groupId, identity: ana, now: 5)
        XCTAssertEqual(sent.conversationId, groupId)
        XCTAssertNotNil(sent.authorSig)
        let body = try ChatCodec.plaintext(sent)
        XCTAssertTrue(body.contains("\"conversationId\":\"\(groupId)\""), "group chat names the group on the wire")
        let got = try XCTUnwrap(ChatCodec.read(body, sender: member(ana), me: dana.memberId, groupId: groupId))
        XCTAssertEqual(got.content, "hi all")
        XCTAssertEqual(got.conversationId, groupId)
        XCTAssertFalse(got.isOutgoing)
    }

    func test_privateMessage_carriesNoConversationId_andTheReceiverDerivesIt() throws {
        let ana = try id("ana"), dana = try id("dana")
        let sent = ChatCodec.compose(content: "just you", to: dana.memberId, groupId: groupId, identity: ana, now: 5)
        let pair = ChatBook.privateConversationId(ana.memberId, dana.memberId)
        XCTAssertEqual(sent.conversationId, pair)
        XCTAssertEqual(pair, [ana.memberId, dana.memberId].sorted().joined(separator: ":"))
        let body = try ChatCodec.plaintext(sent)
        XCTAssertFalse(body.contains(pair), "the private id is derived, not sent")
        let got = try XCTUnwrap(ChatCodec.read(body, sender: member(ana), me: dana.memberId, groupId: groupId))
        XCTAssertEqual(got.conversationId, pair, "and the signature verifies over the derived id")
    }

    func test_signatureMatchesTheSpecVector() {
        let m = ChatMessage(messageId: "m1", conversationId: "alice:dana", senderId: "dana", recipientId: "alice",
                            content: "hi", messageType: .text, timestamp: 5, isOutgoing: true, status: .pending, isRead: true)
        XCTAssertEqual(m.signingString,
                       "CHAT:v1:m1:alice:dana:dana:5:TEXT::8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4")
    }

    func test_badSignature_otherFamily_andAnotherSendersText_areDropped() throws {
        let ana = try id("ana"), dana = try id("dana"), sam = try id("sam")
        let sent = ChatCodec.compose(content: "hi", to: nil, groupId: groupId, identity: ana, now: 5)
        let body = try ChatCodec.plaintext(sent)
        XCTAssertNil(ChatCodec.read(body, sender: member(sam), me: dana.memberId, groupId: groupId),
                     "a signature by Ana doesn't verify as Sam's")
        XCTAssertNil(ChatCodec.read(body, sender: member(ana), me: dana.memberId, groupId: "other-family"))
        let unsigned = ChatMessagePayload(messageId: "u1", content: "old build", messageType: .text, timestamp: 1, conversationId: groupId)
        let unsignedBody = String(decoding: try JSONEncoder().encode(unsigned), as: UTF8.self)
        XCTAssertNotNil(ChatCodec.read(unsignedBody, sender: member(ana), me: dana.memberId, groupId: groupId),
                        "unsigned live chat from an older build is taken, as on Android")
    }

    func test_aForgedChatMessageRecordsExactlyOneForgedSignatureEntry() throws {
        let ana = try id("ana"), dana = try id("dana")
        let sent = ChatCodec.compose(content: "meet at 5", to: nil, groupId: groupId, identity: ana, now: 5)
        // Same signature, altered text: the claimed author is Ana, a roster member.
        let forged = try ChatCodec.plaintext(sent).replacingOccurrences(of: "meet at 5", with: "meet at 6")
        let log = SecurityRecorder(slot: nil)
        XCTAssertNil(ChatCodec.receive(forged, sender: member(ana), me: dana.memberId, groupId: groupId, security: log))
        XCTAssertEqual(log.snapshot.events.map(\.kind), [.forgedSignature])
        XCTAssertEqual(log.snapshot.events.first?.detail, "chat")
        XCTAssertEqual(log.snapshot.events.first?.memberId, ana.memberId)
        // Not logged: genuine, unsigned (an older build), or another family's group message.
        XCTAssertNotNil(ChatCodec.receive(try ChatCodec.plaintext(sent), sender: member(ana), me: dana.memberId, groupId: groupId, security: log))
        let unsigned = ChatMessagePayload(messageId: "u1", content: "old build", messageType: .text, timestamp: 1, conversationId: groupId)
        XCTAssertNotNil(ChatCodec.receive(String(decoding: try JSONEncoder().encode(unsigned), as: UTF8.self),
                                          sender: member(ana), me: dana.memberId, groupId: groupId, security: log))
        XCTAssertNil(ChatCodec.receive(try ChatCodec.plaintext(sent), sender: member(ana), me: dana.memberId, groupId: "other", security: log))
        XCTAssertEqual(log.snapshot.events.count, 1)
    }

    func test_acceptsAndroidsJsonWithNulls() throws {
        let ana = try id("ana"), dana = try id("dana")
        let json = #"{"messageId":"a1","content":"from android","messageType":"TEXT","timestamp":7,"conversationId":null,"replyToMessageId":null,"authorSig":null}"#
        let got = try XCTUnwrap(ChatCodec.read(json, sender: member(ana), me: dana.memberId, groupId: groupId))
        XCTAssertEqual(got.conversationId, ChatBook.privateConversationId(ana.memberId, dana.memberId))
    }

    func test_receipts_onlyFromTheAddressee_forwardOnly() throws {
        let ana = try id("ana")
        var book = ChatBook()
        var priv = ChatCodec.compose(content: "p", to: "dana", groupId: groupId, identity: ana, now: 1)
        priv.status = .sent
        var group = ChatCodec.compose(content: "g", to: nil, groupId: groupId, identity: ana, now: 2)
        group.status = .sent
        book.insert(priv); book.insert(group)

        XCTAssertFalse(book.apply(DeliveryReceipt(messageId: priv.messageId, recipientId: "sam", status: .read, timestamp: 3), from: "sam"),
                       "only the private message's addressee may confirm it")
        XCTAssertFalse(book.apply(DeliveryReceipt(messageId: priv.messageId, recipientId: "dana", status: .read, timestamp: 3), from: "sam"),
                       "the receipt's recipientId must be its signer")
        XCTAssertFalse(book.apply(DeliveryReceipt(messageId: priv.messageId, recipientId: "dana", status: .failed, timestamp: 3), from: "dana"))
        XCTAssertTrue(book.apply(DeliveryReceipt(messageId: priv.messageId, recipientId: "dana", status: .read, timestamp: 3), from: "dana"))
        XCTAssertFalse(book.apply(DeliveryReceipt(messageId: priv.messageId, recipientId: "dana", status: .delivered, timestamp: 4), from: "dana"),
                       "never back from READ to DELIVERED")
        XCTAssertEqual(book.messages[priv.messageId]?.status, .read)
        XCTAssertTrue(book.apply(DeliveryReceipt(messageId: group.messageId, recipientId: "sam", status: .delivered, timestamp: 3), from: "sam"),
                      "any member confirms a group message")
        XCTAssertFalse(book.apply(DeliveryReceipt(messageId: "never-sent", recipientId: "sam", status: .read, timestamp: 3), from: "sam"))
    }

    func test_receiptsForIncomingMessagesAreIgnored() throws {
        let ana = try id("ana"), dana = try id("dana")
        var book = ChatBook()
        let incoming = try XCTUnwrap(ChatCodec.read(try ChatCodec.plaintext(ChatCodec.compose(content: "x", to: nil, groupId: groupId, identity: ana, now: 1)),
                                                    sender: member(ana), me: dana.memberId, groupId: groupId))
        book.insert(incoming)
        XCTAssertFalse(book.apply(DeliveryReceipt(messageId: incoming.messageId, recipientId: "sam", status: .read, timestamp: 2), from: "sam"))
    }

    func test_duplicatesKeepTheFirstCopy_markReadReturnsWhatWasUnread() throws {
        let ana = try id("ana"), dana = try id("dana")
        var book = ChatBook()
        let body = try ChatCodec.plaintext(ChatCodec.compose(content: "x", to: nil, groupId: groupId, identity: ana, now: 1))
        let m = try XCTUnwrap(ChatCodec.read(body, sender: member(ana), me: dana.memberId, groupId: groupId))
        XCTAssertTrue(book.insert(m))
        XCTAssertFalse(book.insert(m))
        XCTAssertEqual(book.unreadCount(groupId), 1)
        XCTAssertEqual(book.markRead(groupId).map(\.messageId), [m.messageId])
        XCTAssertEqual(book.unreadCount(), 0)
        XCTAssertTrue(book.markRead(groupId).isEmpty)
    }

    func test_prune_retentionAndPrivateChatsWithPeopleWhoLeft() throws {
        let ana = try id("ana")
        var book = ChatBook()
        let now: Int64 = 200 * 24 * 60 * 60 * 1000
        book.insert(ChatCodec.compose(content: "old", to: nil, groupId: groupId, identity: ana, now: now - ChatBook.retentionMs - 1))
        let keep = ChatCodec.compose(content: "new", to: nil, groupId: groupId, identity: ana, now: now)
        let withSam = ChatCodec.compose(content: "sam left", to: "sam", groupId: groupId, identity: ana, now: now)
        let withDana = ChatCodec.compose(content: "dana stays", to: "dana", groupId: groupId, identity: ana, now: now)
        book.insert(keep); book.insert(withSam); book.insert(withDana)
        book.prune(now: now, me: ana.memberId, members: [ana.memberId, "dana"], groupId: groupId)
        XCTAssertEqual(Set(book.messages.keys), [keep.messageId, withDana.messageId])
    }

    func test_unsentIncludesPendingAndFailed() throws {
        let ana = try id("ana")
        var book = ChatBook()
        let a = ChatCodec.compose(content: "a", to: nil, groupId: groupId, identity: ana, now: 1)
        let b = ChatCodec.compose(content: "b", to: nil, groupId: groupId, identity: ana, now: 2)
        let c = ChatCodec.compose(content: "c", to: nil, groupId: groupId, identity: ana, now: 3)
        book.insert(a); book.insert(b); book.insert(c)
        book.setStatus(b.messageId, .failed)
        book.setStatus(c.messageId, .sent)
        XCTAssertEqual(book.unsent.map(\.messageId), [a.messageId, b.messageId])
    }

    func test_sealedFileSlot_roundTripsAndIsUnreadableWithoutItsKey() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("chat-\(UUID().uuidString).sealed")
        defer { try? FileManager.default.removeItem(at: url) }
        let key = InMemorySlot()
        let slot = SealedFileSlot(url: url, keySlot: key)
        XCTAssertNil(try slot.read())
        try slot.write(Data("secret chat".utf8))
        XCTAssertEqual(try slot.read(), Data("secret chat".utf8))
        XCTAssertFalse(try Data(contentsOf: url).range(of: Data("secret".utf8)) != nil, "the file holds no plaintext")
        XCTAssertThrowsError(try SealedFileSlot(url: url, keySlot: InMemorySlot()).read(), "no key: an error, not nil")
        try slot.delete()
        XCTAssertNil(try slot.read())
    }
}
