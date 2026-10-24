import Foundation

/// Chat history back-fill (spec §6.6, Android ReplicationManager): announce → request → response,
/// never pushed. Pure rules and bookkeeping; the node sends what this returns.
///
/// Location history is not held on iOS yet, so nothing is announced or served for it, and
/// location requests go unanswered (Android answers nothing when it has nothing either).
public struct ChatReplication: Sendable {
    public static let pageLimit = 500
    /// Pages followed per gap (E7), so a hostile `hasMore` can't loop us.
    public static let maxPages = 20
    public static let requestTimeoutMs: Int64 = 30_000
    public static let announceIntervalMs: Int64 = 30 * 60_000
    public static let minFullSyncIntervalMs: Int64 = 15 * 60_000

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

    // MARK: Scoping (§6.6 "Scoping rules")

    /// The two members of a private conversation, or nil for the group chat.
    public static func directParticipants(_ conversationId: String) -> [String]? {
        let parts = conversationId.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        return parts.count == 2 && parts.allSatisfy { !$0.isEmpty } ? parts : nil
    }

    public static func mayHold(_ conversationId: String, _ memberId: String) -> Bool {
        directParticipants(conversationId)?.contains(memberId) ?? true
    }

    /// `mayHold`, and a non-private conversation must be this family's.
    public static func mayShare(_ conversationId: String, _ memberId: String, groupId: String) -> Bool {
        guard mayHold(conversationId, memberId) else { return false }
        return directParticipants(conversationId) != nil || conversationId == groupId
    }

    // MARK: Outgoing

    /// What we hold, told to one peer: only conversations that peer may hold.
    public func announcement(for peerId: String, book: ChatBook, groupId: String, now: Int64) -> DataAvailabilityAnnouncement {
        let summaries = book.summaries().filter { Self.mayShare($0.conversationId, peerId, groupId: groupId) }
        return DataAvailabilityAnnouncement(announcerId: me, chatDataSummary: summaries, timestamp: now)
    }

    /// On (re)connect, at most every 15 minutes: ask for anything newer in each conversation we
    /// already hold. Group chat goes to every peer, a private one to the other participant only.
    public mutating func fullSync(book: ChatBook, peers: [String], groupId: String, now: Int64,
                                  newId: () -> String = { UUID().uuidString.lowercased() }) -> [(peerId: String, request: ReplicationRequest)] {
        if let last = lastFullSyncMs, now - last < Self.minFullSyncIntervalMs { return [] }
        lastFullSyncMs = now
        var out: [(String, ReplicationRequest)] = []
        for conv in book.conversationIds where Self.mayShare(conv, me, groupId: groupId) {
            let sources = Self.directParticipants(conv).map { p in peers.filter(p.contains) } ?? peers
            for peer in sources {
                let r = request(conv, after: book.newestTimestamp(conv) ?? 0, now: now, id: newId())
                track(r, to: peer, now: now, page: 1)
                out.append((peer, r))
            }
        }
        return out
    }

    /// A peer told us what it holds: ask it for what's newer than ours, once per conversation at a time.
    public mutating func handle(_ a: DataAvailabilityAnnouncement, from peerId: String, book: ChatBook, groupId: String, now: Int64,
                                newId: () -> String = { UUID().uuidString.lowercased() }) -> [ReplicationRequest] {
        guard a.announcerId == peerId else { return [] }
        expire(now: now)
        var out: [ReplicationRequest] = []
        for s in a.chatDataSummary {
            guard Self.mayShare(s.conversationId, me, groupId: groupId), Self.mayShare(s.conversationId, peerId, groupId: groupId) else { continue }
            let ours = book.newestTimestamp(s.conversationId)
            guard ours.map({ s.newestTimestamp > $0 }) ?? true, !hasPending(s.conversationId, now: now) else { continue }
            let r = request(s.conversationId, after: ours ?? 0, now: now, id: newId())
            track(r, to: peerId, now: now, page: 1)
            out.append(r)
        }
        return out
    }

    /// A peer asked us. Refused when it may not hold the conversation; nil when we have nothing newer.
    public func respond(to r: ReplicationRequest, from peerId: String, book: ChatBook, groupId: String, now: Int64) -> ReplicationResponse? {
        guard r.requesterId == peerId, r.dataType == .chatMessages, let conv = r.conversationId,
              Self.mayShare(conv, peerId, groupId: groupId) else { return nil }
        let limit = min(max(r.limit, 1), Self.pageLimit)
        let after = book.conversation(conv).filter { $0.timestamp > r.afterTimestamp }
        let page = Array(after.prefix(limit))
        guard !page.isEmpty else { return nil }
        return ReplicationResponse(requestId: r.requestId, senderId: me, dataType: .chatMessages,
                                   messages: page.map(Self.replicated), hasMore: after.count > limit,
                                   newestTimestamp: page.map(\.timestamp).max(), timestamp: now)
    }

    // MARK: Incoming

    public struct Received {
        public let messages: [ChatMessage]
        /// The next page to ask the same peer for, if the answer said there was more.
        public let next: ReplicationRequest?
    }

    /// What to keep from a response. Each message must be one both we and the sender may hold,
    /// named for a roster member, and carry its author's signature unless that author still runs
    /// a build before protocol 4 (3.4). Unsolicited responses (older Android's `realtime-…` pushes)
    /// are filtered the same way.
    public mutating func handle(_ response: ReplicationResponse, from sender: FamilyMember, roster: GroupDefinition,
                                authorVersion: (String) -> Int?, now: Int64,
                                newId: () -> String = { UUID().uuidString.lowercased() }) -> Received {
        let asked = pending.removeValue(forKey: response.requestId)
        guard response.dataType == .chatMessages, response.senderId == sender.memberId else { return Received(messages: [], next: nil) }
        let kept: [ChatMessage] = response.messages.compactMap { m in
            guard Self.mayShare(m.conversationId, me, groupId: roster.groupId),
                  Self.mayShare(m.conversationId, sender.memberId, groupId: roster.groupId),
                  let author = roster.findMember(byId: m.senderId) else { return nil }
            let message = ChatMessage(messageId: m.messageId, conversationId: m.conversationId, senderId: m.senderId,
                                      recipientId: m.recipientId, content: m.content, messageType: m.messageType,
                                      timestamp: m.timestamp, replyToMessageId: m.replyToMessageId, authorSig: m.authorSig,
                                      isOutgoing: m.senderId == me, status: m.status, isRead: m.senderId == me)
            guard AuthorSignatures.accept(authorSig: m.authorSig, authorEd25519Hex: author.ed25519PublicKey,
                                          message: message.signingString,
                                          acceptUnsigned: AuthorSignatures.acceptUnsigned(fromAuthorVersion: authorVersion(m.senderId)))
            else { return nil }
            return message
        }
        var next: ReplicationRequest?
        if let asked, asked.peerId == sender.memberId, response.hasMore, asked.page < Self.maxPages,
           let newest = response.newestTimestamp, newest > asked.request.afterTimestamp, let conv = asked.request.conversationId {
            let r = request(conv, after: newest, now: now, id: newId())
            track(r, to: sender.memberId, now: now, page: asked.page + 1)
            next = r
        }
        return Received(messages: kept, next: next)
    }

    // MARK: Helpers

    static func replicated(_ m: ChatMessage) -> ReplicatedMessage {
        ReplicatedMessage(messageId: m.messageId, conversationId: m.conversationId, senderId: m.senderId,
                          recipientId: m.recipientId, content: m.content, messageType: m.messageType, status: m.status,
                          timestamp: m.timestamp, isOutgoing: m.isOutgoing, replyToMessageId: m.replyToMessageId,
                          authorSig: m.authorSig)
    }

    private func request(_ conv: String, after: Int64, now: Int64, id: String) -> ReplicationRequest {
        ReplicationRequest(requestId: id, requesterId: me, dataType: .chatMessages, conversationId: conv,
                           afterTimestamp: after, limit: Self.pageLimit, timestamp: now)
    }

    private mutating func track(_ r: ReplicationRequest, to peerId: String, now: Int64, page: Int) {
        pending[r.requestId] = Pending(request: r, peerId: peerId, sentAtMs: now, page: page)
    }

    private func hasPending(_ conv: String, now: Int64) -> Bool {
        pending.values.contains { now - $0.sentAtMs < Self.requestTimeoutMs && $0.request.conversationId == conv }
    }

    private mutating func expire(now: Int64) {
        pending = pending.filter { now - $0.value.sentAtMs < Self.requestTimeoutMs }
    }
}

extension ChatBook {
    public var conversationIds: Set<String> { Set(messages.values.map(\.conversationId)) }

    public func newestTimestamp(_ conversationId: String) -> Int64? {
        messages.values.filter { $0.conversationId == conversationId }.map(\.timestamp).max()
    }

    public func summaries() -> [ChatHistorySummary] {
        Dictionary(grouping: messages.values, by: \.conversationId).map { conv, ms in
            ChatHistorySummary(conversationId: conv, oldestTimestamp: ms.map(\.timestamp).min() ?? 0,
                               newestTimestamp: ms.map(\.timestamp).max() ?? 0, count: ms.count)
        }.sorted { $0.conversationId < $1.conversationId }
    }
}
