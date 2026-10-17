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

    public init(observingSinceMs: Int64) { self.observingSinceMs = observingSinceMs }

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
