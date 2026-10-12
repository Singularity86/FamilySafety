import CryptoKit
import Foundation

/// Join v2: a join request only the person who saw the invite can make (§8.2–§8.4, review F13,
/// Android remediation 2.1).
///
/// In v1 the request travelled as plaintext, retained on the inviter's topic, so anyone on the
/// broker could replace it with one carrying their own keys under a familiar name. v2 adds a
/// one-time secret to the QR that never touches the broker, an HMAC under it, encryption to the
/// inviter's key, and a six-digit fingerprint both phones show.
public enum JoinV2 {
    public static let version = "2"
    public static let secretBytes = 32
    /// Long enough to send a code by message, short enough to go stale.
    public static let inviteLifetimeMs: Int64 = 24 * 60 * 60 * 1000

    public static func mac(secret: [UInt8], message: [UInt8]) -> [UInt8] {
        Array(HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: secret)))
    }

    /// Constant-time (CryptoKit's `isValidAuthenticationCode`).
    public static func macMatches(_ presented: [UInt8], secret: [UInt8], message: [UInt8]) -> Bool {
        HMAC<SHA256>.isValidAuthenticationCode(presented, authenticating: message, using: SymmetricKey(data: secret))
    }

    /// Six digits for a requester's keys: SHA-256(ed25519 ‖ x25519), first four bytes as a
    /// big-endian unsigned number, mod 1,000,000, zero-padded.
    public static func fingerprint(ed25519: [UInt8], x25519: [UInt8]) -> String {
        let digest = Array(SHA256.hash(data: Data(ed25519 + x25519)))
        let n = digest.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        let digits = String(n % 1_000_000)
        return String(repeating: "0", count: 6 - digits.count) + digits
    }

    /// `"123 456"`, as both phones display it.
    public static func displayFingerprint(_ digits: String) -> String {
        "\(digits.prefix(3)) \(digits.suffix(3))"
    }

    public static func sha256Hex(_ text: String) -> String {
        Hex.encode(Array(SHA256.hash(data: Data(text.utf8))))
    }
}

public enum JoinV2Error: Error, Equatable {
    /// The reason reads as a sentence for the joiner ("Invalid invite code — …").
    case invalidInvite(String)
}

/// The v2 part of an invite, as the joiner reads it from the QR.
public struct ScannedInviteV2: Equatable {
    public let inviteId: String
    public let secret: [UInt8]
    public let inviterEd25519Hex: String
    public let inviterX25519Hex: String
    public let expiresAt: Int64

    /// Nil for a v1 invite (an older inviter), which gets a v1 request. Throws for a v2 invite
    /// that is incomplete, expired, or whose keys aren't the inviter's: the QR is the root of
    /// trust, and `inviterMemberId` (a hash of the signing key) pins it.
    public static func from(_ invite: InviteCode, now: Int64) throws -> ScannedInviteV2? {
        guard invite.v == JoinV2.version else { return nil }
        func field(_ value: String?, _ name: String) throws -> String {
            guard let value, !value.isEmpty else { throw JoinV2Error.invalidInvite("Invalid invite code — missing \(name)") }
            return value
        }
        let ed = try field(invite.inviterEd25519, "inviterEd25519")
        guard MemberIdDerivation.deriveMemberId(fromEd25519PublicKeyHex: ed) == invite.inviterMemberId else {
            throw JoinV2Error.invalidInvite("Invalid invite code — the inviter's keys don't match")
        }
        guard let expiresAt = Int64(try field(invite.expiresAt, "expiresAt")) else {
            throw JoinV2Error.invalidInvite("Invalid invite code — bad expiry")
        }
        guard now <= expiresAt else {
            throw JoinV2Error.invalidInvite("Invalid invite code — this invite has expired. Ask for a new one.")
        }
        guard let secret = Data(base64Encoded: try field(invite.inviteSecret, "inviteSecret")).map(Array.init),
              secret.count == JoinV2.secretBytes else {
            throw JoinV2Error.invalidInvite("Invalid invite code — bad secret")
        }
        let x = try field(invite.inviterX25519, "inviterX25519")
        guard Hex.decode(x)?.count == 32 else { throw JoinV2Error.invalidInvite("Invalid invite code — bad inviter key") }
        return ScannedInviteV2(inviteId: try field(invite.inviteId, "inviteId"), secret: secret,
                               inviterEd25519Hex: ed.lowercased(), inviterX25519Hex: x.lowercased(), expiresAt: expiresAt)
    }
}

/// Published retained on the inviter's `join_request` topic in place of a plaintext request.
public struct JoinRequestV2Message: Codable, Equatable {
    public var v: Int = 2
    public let inviteId: String
    /// Hex. Needed to open the envelope; checked against the request inside it.
    public let requesterEd25519: String
    public let requesterX25519: String
    /// §3 envelope from the requester to the inviter, wrapping a `JoinRequestV2Payload`.
    public let encryptedRequest: String

    public init(inviteId: String, requesterEd25519: String, requesterX25519: String, encryptedRequest: String) {
        self.inviteId = inviteId
        self.requesterEd25519 = requesterEd25519
        self.requesterX25519 = requesterX25519
        self.encryptedRequest = encryptedRequest
    }

    private enum CodingKeys: String, CodingKey { case v, inviteId, requesterEd25519, requesterX25519, encryptedRequest }

    /// `v` is a Kotlin default, which kotlinx omits unless `encodeDefaults` is on, so it may be absent.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        v = try c.decodeIfPresent(Int.self, forKey: .v) ?? 2
        inviteId = try c.decode(String.self, forKey: .inviteId)
        requesterEd25519 = try c.decode(String.self, forKey: .requesterEd25519)
        requesterX25519 = try c.decode(String.self, forKey: .requesterX25519)
        encryptedRequest = try c.decode(String.self, forKey: .encryptedRequest)
    }
}

public struct JoinRequestV2Payload: Codable, Equatable {
    /// Carries `inviteId` and a signature over `signingBytesV2()`.
    public let request: JoinRequest
    /// Hex HMAC-SHA256 under the invite secret, over `signingBytesV2()`.
    public let mac: String

    public init(request: JoinRequest, mac: String) {
        self.request = request
        self.mac = mac
    }
}

/// What the inviter keeps for each invite it hands out. Never leaves the phone.
public struct PendingInvite: Codable, Equatable, Sendable {
    public let inviteId: String
    /// Base64 of the 32-byte secret.
    public let secret: String
    public let expiresAt: Int64
    public var used: Bool

    public init(inviteId: String, secret: String, expiresAt: Int64, used: Bool = false) {
        self.inviteId = inviteId
        self.secret = secret
        self.expiresAt = expiresAt
        self.used = used
    }
}

/// The inviter's invites: a fresh one per invite-screen open, single use, 24 h, pruned and capped
/// so reopening the screen can't grow the store without bound.
public struct InviteBook: Codable, Equatable, Sendable {
    public static let cap = 20
    public static let closedCap = 200
    public private(set) var invites: [PendingInvite] = []
    /// Requests already approved or rejected, so a broker redelivery is ignored (§8.4).
    public private(set) var closedRequestIds: [String] = []

    public init() {}

    private enum CodingKeys: String, CodingKey { case invites, closedRequestIds }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        invites = try c.decodeIfPresent([PendingInvite].self, forKey: .invites) ?? []
        closedRequestIds = try c.decodeIfPresent([String].self, forKey: .closedRequestIds) ?? []
    }

    public func isClosed(_ requestId: String) -> Bool { closedRequestIds.contains(requestId) }

    public mutating func close(_ requestId: String) {
        guard !isClosed(requestId) else { return }
        closedRequestIds.append(requestId)
        if closedRequestIds.count > Self.closedCap { closedRequestIds.removeFirst(closedRequestIds.count - Self.closedCap) }
    }

    /// A new v2 invite for `group`, recorded here. The caller persists the book (it holds
    /// secrets: Keychain, this-device-only, excluded from backup) before showing the QR.
    public mutating func issue(group: GroupDefinition, inviter: FamilySafeIdentity, inviterName: String, now: Int64) throws -> InviteCode {
        guard !inviter.memberId.isEmpty else { throw JoinV2Error.invalidInvite("no inviter member ID") }
        try SodiumRaw.ensureInitialized()
        let secret = SodiumRaw.randomBytes(JoinV2.secretBytes)
        let invite = PendingInvite(inviteId: Hex.encode(SodiumRaw.randomBytes(8)),
                                   secret: Data(secret).base64EncodedString(),
                                   expiresAt: now + JoinV2.inviteLifetimeMs)
        prune(now: now)
        invites.append(invite)
        if invites.count > Self.cap { invites.removeFirst(invites.count - Self.cap) }
        return InviteCode(groupId: group.groupId, groupName: group.groupName, inviterMemberId: inviter.memberId,
                          inviterName: inviterName, timestamp: String(now),
                          v: JoinV2.version, inviteId: invite.inviteId, inviteSecret: invite.secret,
                          inviterEd25519: inviter.ed25519PublicKeyHex, inviterX25519: inviter.x25519PublicKeyHex,
                          expiresAt: String(invite.expiresAt))
    }

    public func find(_ inviteId: String) -> PendingInvite? {
        invites.first { $0.inviteId == inviteId }
    }

    /// Approving or rejecting a request through an invite uses it up.
    public mutating func markUsed(_ inviteId: String) {
        guard let i = invites.firstIndex(where: { $0.inviteId == inviteId }) else { return }
        invites[i].used = true
    }

    public mutating func prune(now: Int64) {
        invites.removeAll { $0.used || now > $0.expiresAt }
    }
}

/// The inviter's checks on a v2 request, in the order §8.4 step 0 lists them. Any failure
/// means the request is dropped silently.
public enum JoinV2Verifier {
    public enum Result: Equatable {
        case verified(JoinRequest)
        case rejected(String)
    }

    public static func verify(
        message: JoinRequestV2Message,
        now: Int64,
        invites: InviteBook,
        inviterCrypto: E2EECrypto
    ) -> Result {
        // 1. A stored invite, unexpired and unused.
        guard let invite = invites.find(message.inviteId) else { return .rejected("unknown invite") }
        guard !invite.used else { return .rejected("invite already used") }
        guard now <= invite.expiresAt else { return .rejected("invite expired") }

        // 2. Before opening it: the envelope's plaintext sender is the key's own member ID.
        guard let ed = Hex.decode(message.requesterEd25519), ed.count == 32,
              let x = Hex.decode(message.requesterX25519), x.count == 32 else {
            return .rejected("requester keys are not valid")
        }
        let derivedId = MemberIdDerivation.deriveMemberId(fromEd25519PublicKeyBytes: ed)
        let envelope = Data(message.encryptedRequest.utf8)
        guard EncryptedEnvelope.peekSenderMemberId(json: envelope) == derivedId else {
            return .rejected("envelope sender is not the requester's key")
        }

        // 3. Decrypts, with its signature verified, under the keys it names.
        guard let json = try? inviterCrypto.decrypt(envelopeJSON: envelope, senderMemberId: derivedId,
                                                   senderX25519PublicKey: x, senderEd25519PublicKey: ed),
              let payload = try? JSONDecoder().decode(JoinRequestV2Payload.self, from: Data(json.utf8)) else {
            return .rejected("request does not open with the keys it names")
        }
        let request = payload.request

        // 4. The keys and invite inside equal the ones outside.
        guard Data(base64Encoded: request.ed25519PublicKey).map(Array.init) == ed,
              Data(base64Encoded: request.x25519PublicKey).map(Array.init) == x else {
            return .rejected("keys inside the request differ from the keys outside")
        }
        guard request.inviteId == message.inviteId else { return .rejected("invite mismatch") }

        // 5. The member ID is the key's.
        guard request.requesterId == derivedId else { return .rejected("member ID does not match the key") }

        // 6. Signed over signingBytesV2.
        guard let signed = request.signingBytesV2(),
              let signatureHex = request.signature, let signature = Hex.decode(signatureHex),
              (try? SodiumRaw.ensureInitialized()) != nil,
              SodiumRaw.signVerifyDetached(signature: signature, message: signed, publicKey: ed) else {
            return .rejected("signature does not verify")
        }

        // 7. Made with this invite's secret.
        guard let secret = Data(base64Encoded: invite.secret).map(Array.init),
              let presented = Hex.decode(payload.mac),
              JoinV2.macMatches(presented, secret: secret, message: signed) else {
            return .rejected("not made with this invite's secret")
        }
        return .verified(request)
    }
}
