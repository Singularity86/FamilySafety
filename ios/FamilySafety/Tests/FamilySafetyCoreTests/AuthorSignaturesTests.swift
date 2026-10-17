import XCTest
@testable import FamilySafetyCore

/// §12 "Author signing strings", asserted the same way in Android's AuthorSignaturesTest.
final class AuthorSignaturesTests: XCTestCase {
    func test_location_vectors() {
        let fix = LocationUpdate(memberId: "dana", latitude: 37.7749, longitude: -122.45000005, accuracy: 12.5,
                                 timestamp: 1_700_000_000_000, speed: 3.25, bearing: 270)
        XCTAssertEqual(AuthorSignatures.location(fix), "LOC:v1:dana:1700000000000:377749000:-1224500000:1250:325:27000")
        let still = LocationUpdate(memberId: "dana", latitude: 37.7749, longitude: -122.45000005, accuracy: 12.5,
                                   timestamp: 1_700_000_000_000)
        XCTAssertEqual(AuthorSignatures.location(still), "LOC:v1:dana:1700000000000:377749000:-1224500000:1250::")
    }

    func test_chat_vector() {
        XCTAssertEqual(AuthorSignatures.chatMessage(messageId: "m1", conversationId: "alice:dana", senderId: "dana", timestamp: 5,
                                                    messageType: .text, replyToMessageId: nil, content: "hi"),
                       "CHAT:v1:m1:alice:dana:dana:5:TEXT::8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4")
    }

    func test_float32AccuracyIsWidenedNotParsedAsDouble() throws {
        // 0.015 as float32 widens to 0.01499999…, so hundredths round to 1, not 2 (spec §6.2).
        let decoded = try JSONDecoder().decode(LocationUpdate.self, from: Data(
            #"{"memberId":"d","latitude":0,"longitude":0,"accuracy":0.015,"timestamp":1}"#.utf8))
        XCTAssertTrue(AuthorSignatures.location(decoded).hasSuffix(":1::"))
    }

    func test_signedItemsVerify_andStrictModeDropsUnsigned() throws {
        let dana = try FamilySafeKeyDerivation.deriveIdentity(
            mnemonic: "legal winner thank year wave sausage worth useful legal winner thank yellow")
        let fix = LocationUpdate(memberId: dana.memberId, latitude: 1, longitude: 2, accuracy: 3, timestamp: 4)
        let message = AuthorSignatures.location(fix)
        let sig = try AuthorSignatures.sign(message, identity: dana)
        let key = dana.ed25519PublicKeyHex
        XCTAssertTrue(AuthorSignatures.accept(authorSig: sig, authorEd25519Hex: key, message: message, acceptUnsigned: false))
        XCTAssertFalse(AuthorSignatures.accept(authorSig: sig, authorEd25519Hex: key, message: message + "x", acceptUnsigned: true))
        XCTAssertFalse(AuthorSignatures.accept(authorSig: sig, authorEd25519Hex: nil, message: message, acceptUnsigned: true),
                       "an author outside the roster can't be checked")
        XCTAssertTrue(AuthorSignatures.accept(authorSig: nil, authorEd25519Hex: key, message: message,
                                              acceptUnsigned: AuthorSignatures.acceptUnsigned(fromAuthorVersion: 3)))
        XCTAssertFalse(AuthorSignatures.accept(authorSig: nil, authorEd25519Hex: key, message: message,
                                               acceptUnsigned: AuthorSignatures.acceptUnsigned(fromAuthorVersion: 4)))
    }
}
