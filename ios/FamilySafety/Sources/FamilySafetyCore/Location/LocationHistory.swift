import Foundation

/// Every fix this phone has seen, its own and the family's, for 30 days (Android
/// LocationHistoryRepository). Live fixes and back-filled ones land here alike; one per
/// (member, timestamp).
public struct LocationHistory: Codable, Equatable, Sendable {
    public static let retentionMs: Int64 = 30 * 24 * 60 * 60 * 1000
    /// A ceiling per member (about one fix a minute for 30 days), so a flood can't grow it without bound.
    public static let maxPerMember = 45_000

    /// memberId → fixes, oldest first.
    public private(set) var fixes: [String: [LocationUpdate]] = [:]

    public init() {}

    /// Adds fixes, keeping one per timestamp and the list in order. Returns how many were new.
    @discardableResult
    public mutating func add(_ new: [LocationUpdate], now: Int64) -> Int {
        var added = 0
        for (member, batch) in Dictionary(grouping: new, by: \.memberId) {
            var list = fixes[member] ?? []
            var seen = Set(list.map(\.timestamp))
            for f in batch where !seen.contains(f.timestamp) && f.timestamp >= now - Self.retentionMs && f.timestamp <= now + 24 * 60 * 60 * 1000 {
                list.append(f)
                seen.insert(f.timestamp)
                added += 1
            }
            list.sort { $0.timestamp < $1.timestamp }
            if list.count > Self.maxPerMember { list.removeFirst(list.count - Self.maxPerMember) }
            fixes[member] = list
        }
        return added
    }

    public func newestTimestamp(_ memberId: String) -> Int64? { fixes[memberId]?.last?.timestamp }

    public func after(_ memberId: String, _ timestamp: Int64, limit: Int) -> (page: [LocationUpdate], hasMore: Bool) {
        let later = (fixes[memberId] ?? []).filter { $0.timestamp > timestamp }
        return (Array(later.prefix(limit)), later.count > limit)
    }

    /// One member's fixes between two times, oldest first.
    public func between(_ memberId: String, from: Int64, to: Int64) -> [LocationUpdate] {
        (fixes[memberId] ?? []).filter { $0.timestamp >= from && $0.timestamp < to }
    }

    public func summaries() -> [LocationHistorySummary] {
        fixes.compactMap { member, list in
            guard let first = list.first, let last = list.last else { return nil }
            return LocationHistorySummary(memberId: member, oldestTimestamp: first.timestamp, newestTimestamp: last.timestamp, count: list.count)
        }.sorted { $0.memberId < $1.memberId }
    }

    /// Drops what's past 30 days and anyone no longer in the family.
    public mutating func prune(now: Int64, members: Set<String>) {
        fixes = fixes.filter { members.contains($0.key) }.mapValues { $0.filter { $0.timestamp >= now - Self.retentionMs } }
            .filter { !$0.value.isEmpty }
    }
}

/// Location history back-fill (spec §6.6): a member's history is asked of that member alone, and
/// only our own goes to everyone. Pure; the node sends what this returns.
public struct LocationReplication: Sendable {
    struct Pending: Sendable {
        let request: ReplicationRequest
        let peerId: String
        let sentAtMs: Int64
        let page: Int
    }

    public let me: String
    private var pending: [String: Pending] = [:]
    private var lastFullSyncMs: Int64?

    public init(me: String) { self.me = me }

    /// On connect, at most every 15 minutes: each member's newer history from that member, and ours from everyone.
    public mutating func fullSync(history: LocationHistory, peers: [String], now: Int64,
                                  newId: () -> String = { UUID().uuidString.lowercased() }) -> [(peerId: String, request: ReplicationRequest)] {
        if let last = lastFullSyncMs, now - last < ChatReplication.minFullSyncIntervalMs { return [] }
        lastFullSyncMs = now
        var out: [(String, ReplicationRequest)] = []
        for target in peers + [me] {
            let sources = target == me ? peers : [target]
            for peer in sources {
                let r = request(target, after: history.newestTimestamp(target) ?? 0, now: now, id: newId())
                track(r, to: peer, now: now, page: 1)
                out.append((peer, r))
            }
        }
        return out
    }

    /// A peer's summary: ask it for anything newer than ours, once per member at a time.
    public mutating func handle(_ a: DataAvailabilityAnnouncement, from peerId: String, history: LocationHistory, members: Set<String>,
                                now: Int64, newId: () -> String = { UUID().uuidString.lowercased() }) -> [ReplicationRequest] {
        guard a.announcerId == peerId else { return [] }
        pending = pending.filter { now - $0.value.sentAtMs < ChatReplication.requestTimeoutMs }
        var out: [ReplicationRequest] = []
        for s in a.locationDataSummary where members.contains(s.memberId) {
            let ours = history.newestTimestamp(s.memberId)
            guard ours.map({ s.newestTimestamp > $0 }) ?? true,
                  !pending.values.contains(where: { now - $0.sentAtMs < ChatReplication.requestTimeoutMs && $0.request.targetMemberId == s.memberId })
            else { continue }
            let r = request(s.memberId, after: ours ?? 0, now: now, id: newId())
            track(r, to: peerId, now: now, page: 1)
            out.append(r)
        }
        return out
    }

    public func respond(to r: ReplicationRequest, from peerId: String, history: LocationHistory, members: Set<String>, now: Int64) -> ReplicationResponse? {
        guard r.requesterId == peerId, r.dataType == .locationHistory, let target = r.targetMemberId, members.contains(target) else { return nil }
        let (page, hasMore) = history.after(target, r.afterTimestamp, limit: min(max(r.limit, 1), ChatReplication.pageLimit))
        guard !page.isEmpty else { return nil }
        return ReplicationResponse(requestId: r.requestId, senderId: me, dataType: .locationHistory, locations: page,
                                   hasMore: hasMore, newestTimestamp: page.map(\.timestamp).max(), timestamp: now)
    }

    public struct Received {
        public let fixes: [LocationUpdate]
        public let next: ReplicationRequest?
    }

    /// What to keep: fixes by roster members, plausible, carrying their author's signature unless that
    /// author still runs a build before protocol 4. Signed by the author, so a relay can't invent them.
    public mutating func handle(_ response: ReplicationResponse, from sender: FamilyMember, roster: GroupDefinition,
                                authorVersion: (String) -> Int?, now: Int64,
                                newId: () -> String = { UUID().uuidString.lowercased() }) -> Received {
        let asked = pending.removeValue(forKey: response.requestId)
        guard response.dataType == .locationHistory, response.senderId == sender.memberId else { return Received(fixes: [], next: nil) }
        let kept = response.locations.filter { f in
            guard let author = roster.findMember(byId: f.memberId), (-90...90).contains(f.latitude), (-180...180).contains(f.longitude),
                  f.accuracy >= 0, f.accuracy.isFinite else { return false }
            return AuthorSignatures.accept(authorSig: f.authorSig, authorEd25519Hex: author.ed25519PublicKey,
                                           message: AuthorSignatures.location(f),
                                           acceptUnsigned: AuthorSignatures.acceptUnsigned(fromAuthorVersion: authorVersion(f.memberId)))
        }
        var next: ReplicationRequest?
        if let asked, asked.peerId == sender.memberId, response.hasMore, asked.page < ChatReplication.maxPages,
           let newest = response.newestTimestamp, newest > asked.request.afterTimestamp, let target = asked.request.targetMemberId {
            let r = request(target, after: newest, now: now, id: newId())
            track(r, to: sender.memberId, now: now, page: asked.page + 1)
            next = r
        }
        return Received(fixes: kept, next: next)
    }

    private func request(_ target: String, after: Int64, now: Int64, id: String) -> ReplicationRequest {
        ReplicationRequest(requestId: id, requesterId: me, dataType: .locationHistory, targetMemberId: target,
                           afterTimestamp: after, limit: ChatReplication.pageLimit, timestamp: now)
    }

    private mutating func track(_ r: ReplicationRequest, to peerId: String, now: Int64, page: Int) {
        pending[r.requestId] = Pending(request: r, peerId: peerId, sentAtMs: now, page: page)
    }
}
