import Foundation

/// Who may change a member's profile and the family's subscription record (3.1, F25; port of
/// Android's ProfileGuard). Neither is in the state hash, and states are routinely relayed by
/// members other than the author, so the *author* signs: each member their own profile, the
/// subscriber the confirmation. A receiver takes a change only if it's authenticated (from the
/// author directly, or carrying their signature) **and newer** than what it holds; otherwise it
/// keeps its own values and the rest of the update still applies.
public enum ProfileGuard {
    public static func profileMessage(groupId: String, member: FamilyMember) -> [UInt8] {
        let ts = member.profileUpdatedAtMs.map(String.init) ?? ""
        let text = "PROFILE:v1:\(groupId):\(member.memberId):\(member.joinEpoch):\(ts):" +
            "\(member.displayName):\(member.avatarHash ?? ""):\(hueFixed(member.colorHue))"
        return Array(text.utf8)
    }

    public static func subscriptionMessage(groupId: String, subscriberMemberId: String?, confirmedAtEpochMs: Int64?) -> [UInt8] {
        Array("SUBSCRIPTION:v1:\(groupId):\(subscriberMemberId ?? ""):\(confirmedAtEpochMs.map(String.init) ?? "")".utf8)
    }

    /// Hundredths of a degree, rounded half up in double precision after widening the Float
    /// (Kotlin `Math.round(it * 100.0)`), so both platforms sign the same digits.
    public static func hueFixed(_ hue: Float?) -> String {
        guard let hue else { return "" }
        return String(Int64((Double(hue) * 100 + 0.5).rounded(.down)))
    }

    /// `incoming` with any profile or subscription change it isn't entitled to make replaced by
    /// ours. `updaterMemberId` is the authenticated sender, or nil for a merge nobody authored.
    public static func reconcile(current: GroupDefinition, incoming: GroupDefinition, updaterMemberId: String?) -> GroupDefinition {
        let ours = Dictionary(current.members.map { ($0.memberId, $0) }, uniquingKeysWith: { a, _ in a })
        let members = incoming.members.map { member -> FamilyMember in
            guard let mine = ours[member.memberId], mine.joinEpoch == member.joinEpoch, profileDiffers(mine, member) else { return member }
            let theirs = member.profileUpdatedAtMs ?? 0, ourTime = mine.profileUpdatedAtMs ?? 0
            let fromThemselves = updaterMemberId == member.memberId && theirs >= ourTime
            let signedAndNewer = theirs > ourTime &&
                verify(profileMessage(groupId: incoming.groupId, member: member), member.profileSignature, mine.ed25519PublicKey)
            if fromThemselves || signedAndNewer { return member }
            return member.withProfile(displayName: mine.displayName, avatarHash: mine.avatarHash, colorHue: mine.colorHue,
                                      updatedAtMs: mine.profileUpdatedAtMs, signature: mine.profileSignature)
        }
        let differs = incoming.subscriberMemberId != current.subscriberMemberId ||
            incoming.subscriptionConfirmedAtEpochMs != current.subscriptionConfirmedAtEpochMs ||
            incoming.subscriptionSignature != current.subscriptionSignature
        let keepOurs = differs && !subscriptionAccepted(current: current, incoming: incoming, updaterMemberId: updaterMemberId)
        return incoming.with(
            members: members,
            subscriberMemberId: .some(keepOurs ? current.subscriberMemberId : incoming.subscriberMemberId),
            subscriptionConfirmedAtEpochMs: .some(keepOurs ? current.subscriptionConfirmedAtEpochMs : incoming.subscriptionConfirmedAtEpochMs),
            subscriptionSignature: .some(keepOurs ? current.subscriptionSignature : incoming.subscriptionSignature)
        )
    }

    /// Nothing clears a subscription (it ages out), so a record without a subscriber never wins.
    private static func subscriptionAccepted(current: GroupDefinition, incoming: GroupDefinition, updaterMemberId: String?) -> Bool {
        guard let subscriber = incoming.subscriberMemberId, let theirs = incoming.subscriptionConfirmedAtEpochMs else { return false }
        let ours = current.subscriptionConfirmedAtEpochMs ?? -1
        if updaterMemberId == subscriber && theirs >= ours { return true }
        guard theirs > ours, let key = current.findMember(byId: subscriber)?.ed25519PublicKey else { return false }
        return verify(subscriptionMessage(groupId: incoming.groupId, subscriberMemberId: subscriber, confirmedAtEpochMs: theirs),
                      incoming.subscriptionSignature, key)
    }

    private static func verify(_ message: [UInt8], _ signatureHex: String?, _ keyHex: String) -> Bool {
        guard let signatureHex, let sig = Hex.decode(signatureHex), let key = Hex.decode(keyHex),
              (try? SodiumRaw.ensureInitialized()) != nil else { return false }
        return SodiumRaw.signVerifyDetached(signature: sig, message: message, publicKey: key)
    }

    private static func profileDiffers(_ a: FamilyMember, _ b: FamilyMember) -> Bool {
        a.displayName != b.displayName || a.avatarHash != b.avatarHash || a.colorHue != b.colorHue ||
            a.profileUpdatedAtMs != b.profileUpdatedAtMs || a.profileSignature != b.profileSignature
    }

    /// Signs `member`'s current profile as of `now`.
    public static func signed(_ member: FamilyMember, groupId: String, identity: FamilySafeIdentity, now: Int64) throws -> FamilyMember {
        let stamped = member.withProfile(displayName: member.displayName, avatarHash: member.avatarHash, colorHue: member.colorHue,
                                         updatedAtMs: now, signature: nil)
        try SodiumRaw.ensureInitialized()
        let sig = try SodiumRaw.signDetached(message: profileMessage(groupId: groupId, member: stamped), secretKey: identity.ed25519SecretKey64)
        return stamped.withProfile(displayName: stamped.displayName, avatarHash: stamped.avatarHash, colorHue: stamped.colorHue,
                                   updatedAtMs: now, signature: Hex.encode(sig))
    }
}
