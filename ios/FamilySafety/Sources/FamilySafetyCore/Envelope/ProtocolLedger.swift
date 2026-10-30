import Foundation

/// What this phone knows about the family's protocol (3.3, 3.4), persisted together: what each
/// member's authenticated presence advertised and when, our sequence counter, and the replay
/// window per sender. Kept in one slot so a restart restores all of it.
public struct ProtocolLedger: Codable, Equatable, Sendable {
    public var sightings: [String: PeerSighting] = [:]
    /// When this install started listening; a member never heard from blocks the rotation gate
    /// until a full window has passed (spec §6.5).
    public var observingSinceMs: Int64
    public var counter = SequenceCounter()
    public var window = ReplayWindow()
    /// The Ed25519 key (hex) of the identity the replay windows belong to; nil if saved before
    /// this was kept.
    public private(set) var owner: String?

    public init(observingSinceMs: Int64) { self.observingSinceMs = observingSinceMs }

    /// Call when this phone takes on `ed25519Hex`. Windows describe what one identity has been
    /// sent, so another identity's (or ones saved before owners were kept) are dropped: a phone
    /// rejoining under a new identity otherwise refused its inviter's bare approval for good
    /// (Android #92). The same identity restored from its phrase keeps them; the counter always
    /// stays. Returns whether anything changed.
    @discardableResult
    public mutating func belong(to ed25519Hex: String) -> Bool {
        let key = ed25519Hex.lowercased()
        guard owner != key else { return false }
        window = ReplayWindow()
        owner = key
        return true
    }

    /// The replay window is per (member, Ed25519 key), never per ID alone (§3.1).
    public static func windowKey(_ member: FamilyMember) -> String {
        member.memberId + ":" + member.ed25519PublicKey.lowercased()
    }

    /// Whether `member` can read a sequenced body: they advertise ≥ 4, or have sent us one.
    public func speaksSequenced(_ member: FamilyMember) -> Bool {
        (sightings[member.memberId]?.protocolVersion ?? 0) >= 4 || window.hasSequenced(Self.windowKey(member))
    }

    /// Members to surface as "needs to update": below `ProtocolVersion.minCompatible`, or whose
    /// envelopes were refused as a downgrade (§6.2 rule 3).
    public func outdated(_ group: GroupDefinition, downgraded: Set<String>) -> Set<String> {
        Set(group.members.compactMap { m in
            if downgraded.contains(m.memberId) { return m.memberId }
            guard let s = sightings[m.memberId] else { return nil }
            return s.protocolVersion < ProtocolVersion.minCompatible ? m.memberId : nil
        })
    }
}
