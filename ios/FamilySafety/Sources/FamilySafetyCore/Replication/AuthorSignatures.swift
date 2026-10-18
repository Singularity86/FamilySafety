import CryptoKit
import Foundation

/// What a location fix's or chat message's author signs (3.2, F24, spec §6.2 "Author
/// signatures"), so history can be passed on by anyone and still be traced to whoever wrote
/// it. Ports Android AuthorSignatures.kt.
public enum AuthorSignatures {
    public static func location(_ l: LocationUpdate) -> String {
        "LOC:v1:\(l.memberId):\(l.timestamp):\(fixed(l.latitude, 1e7)):\(fixed(l.longitude, 1e7)):" +
            "\(fixed(Double(l.accuracy), 100)):\(l.speed.map { fixed(Double($0), 100) } ?? ""):" +
            "\(l.bearing.map { fixed(Double($0), 100) } ?? "")"
    }

    /// - Parameter conversationId: the stored one: the group id for group chat, `idA:idB`
    ///   (sorted) for a private one, even though a 1:1 payload carries nil.
    public static func chatMessage(messageId: String, conversationId: String, senderId: String, timestamp: Int64,
                                   messageType: ChatMessageType, replyToMessageId: String?, content: String) -> String {
        let digest = SHA256.hash(data: Data(content.utf8)).map { String(format: "%02x", $0) }.joined()
        return "CHAT:v1:\(messageId):\(conversationId):\(senderId):\(timestamp):\(messageType.rawValue):" +
            "\(replyToMessageId ?? ""):\(digest)"
    }

    /// Fixed point, half rounded *up* in double precision: floor(x × k + 0.5). Not `.rounded()`,
    /// which rounds negative halves away from zero.
    static func fixed(_ value: Double, _ scale: Double) -> String {
        String(Int64((value * scale + 0.5).rounded(.down)))
    }

    public static func sign(_ message: String, identity: FamilySafeIdentity) throws -> String {
        try SodiumRaw.ensureInitialized()
        return Hex.encode(try SodiumRaw.signDetached(message: Array(message.utf8), secretKey: identity.ed25519SecretKey64))
    }

    /// Whether to keep an item. Its author must be in the roster (`authorEd25519Hex`), and a
    /// signature, if present, must verify. An unsigned item is kept only when `acceptUnsigned`.
    public static func accept(authorSig: String?, authorEd25519Hex: String?, message: String, acceptUnsigned: Bool) -> Bool {
        guard let authorEd25519Hex, let key = Hex.decode(authorEd25519Hex) else { return false }
        guard let authorSig else { return acceptUnsigned }
        guard let sig = Hex.decode(authorSig), (try? SodiumRaw.ensureInitialized()) != nil else { return false }
        return SodiumRaw.signVerifyDetached(signature: sig, message: Array(message.utf8), publicKey: key)
    }

    /// Unsigned replicated history by an author who advertises protocol 4 is dropped (3.4):
    /// they sign everything, so it's most likely forged. Live items with no signature are
    /// still taken (Android parity).
    public static func acceptUnsigned(fromAuthorVersion version: Int?) -> Bool { (version ?? 0) < 4 }
}
