import Foundation

/// One stored chat message, ours or someone else's (Android `ChatMessageEntity`).
public struct ChatMessage: Codable, Equatable, Identifiable, Sendable {
    public let messageId: String
    /// The group id for group chat, `idA:idB` (sorted) for a private one.
    public let conversationId: String
    public let senderId: String
    /// The other person in a private conversation we sent; nil for group chat and for messages we received.
    public let recipientId: String?
    public let content: String
    public let messageType: ChatMessageType
    public let timestamp: Int64
    public let replyToMessageId: String?
    public let authorSig: String?
    public let isOutgoing: Bool
    public internal(set) var status: DeliveryStatus
    public internal(set) var isRead: Bool

    public var id: String { messageId }

    public init(messageId: String, conversationId: String, senderId: String, recipientId: String?, content: String,
                messageType: ChatMessageType, timestamp: Int64, replyToMessageId: String? = nil, authorSig: String? = nil,
                isOutgoing: Bool, status: DeliveryStatus, isRead: Bool) {
        self.messageId = messageId
        self.conversationId = conversationId
        self.senderId = senderId
        self.recipientId = recipientId
        self.content = content
        self.messageType = messageType
        self.timestamp = timestamp
        self.replyToMessageId = replyToMessageId
        self.authorSig = authorSig
        self.isOutgoing = isOutgoing
        self.status = status
        self.isRead = isRead
    }

    /// What goes on the wire to one recipient. A private message carries no conversation id;
    /// the receiver derives it (§6.3).
    public var payload: ChatMessagePayload {
        ChatMessagePayload(messageId: messageId, content: content, messageType: messageType, timestamp: timestamp,
                           conversationId: recipientId == nil ? conversationId : nil,
                           replyToMessageId: replyToMessageId, authorSig: authorSig)
    }

    var signingString: String {
        AuthorSignatures.chatMessage(messageId: messageId, conversationId: conversationId, senderId: senderId,
                                     timestamp: timestamp, messageType: messageType,
                                     replyToMessageId: replyToMessageId, content: content)
    }
}

/// Every chat message this phone holds, and the rules for changing them (Android ChatRepository).
public struct ChatBook: Codable, Equatable, Sendable {
    /// Android's retention policy.
    public static let retentionMs: Int64 = 90 * 24 * 60 * 60 * 1000
    /// A ceiling so a flood can't grow the store without bound.
    public static let maxMessages = 10_000

    public private(set) var messages: [String: ChatMessage] = [:]

    public init() {}

    /// The id of the private conversation between two members: both ids, sorted, joined by ":".
    public static func privateConversationId(_ a: String, _ b: String) -> String {
        [a, b].sorted().joined(separator: ":")
    }

    public func contains(_ messageId: String) -> Bool { messages[messageId] != nil }

    public func conversation(_ conversationId: String) -> [ChatMessage] {
        messages.values.filter { $0.conversationId == conversationId }
            .sorted { ($0.timestamp, $0.messageId) < ($1.timestamp, $1.messageId) }
    }

    public func unreadCount(_ conversationId: String? = nil) -> Int {
        messages.values.filter { !$0.isOutgoing && !$0.isRead && (conversationId == nil || $0.conversationId == conversationId) }.count
    }

    /// Ours that still need sending: never sent, or failed.
    public var unsent: [ChatMessage] {
        messages.values.filter { $0.isOutgoing && ($0.status == .pending || $0.status == .failed) }
            .sorted { $0.timestamp < $1.timestamp }
    }

    /// Adds a message; false if one with this id is already held (duplicates keep the first copy).
    @discardableResult
    public mutating func insert(_ message: ChatMessage) -> Bool {
        guard messages[message.messageId] == nil else { return false }
        messages[message.messageId] = message
        return true
    }

    public mutating func setStatus(_ messageId: String, _ status: DeliveryStatus) {
        messages[messageId]?.status = status
    }

    /// Applies a receipt already opened from `senderId`'s envelope. Statuses only move forward
    /// (SENT → DELIVERED → READ). Only the addressee of a private message may confirm it; any
    /// member may confirm a group message. Returns whether anything changed.
    @discardableResult
    public mutating func apply(_ receipt: DeliveryReceipt, from senderId: String) -> Bool {
        guard receipt.recipientId == senderId, receipt.status == .delivered || receipt.status == .read,
              let message = messages[receipt.messageId], message.isOutgoing else { return false }
        if let to = message.recipientId, to != senderId { return false }
        guard Self.rank(receipt.status) > Self.rank(message.status) else { return false }
        messages[receipt.messageId]?.status = receipt.status
        return true
    }

    /// Marks a conversation read and returns the incoming messages that weren't, so their
    /// senders can be told (READ receipts).
    public mutating func markRead(_ conversationId: String) -> [ChatMessage] {
        let unread = messages.values.filter { $0.conversationId == conversationId && !$0.isOutgoing && !$0.isRead }
        for m in unread { messages[m.messageId]?.isRead = true }
        return unread
    }

    /// Drops what's past retention, private conversations with people who left, and the oldest
    /// beyond the ceiling.
    public mutating func prune(now: Int64, me: String, members: Set<String>, groupId: String) {
        messages = messages.filter { _, m in
            guard m.timestamp >= now - Self.retentionMs else { return false }
            if m.conversationId == groupId { return true }
            let parts = m.conversationId.split(separator: ":").map(String.init)
            return parts.count == 2 && parts.contains(me) && parts.allSatisfy(members.contains)
        }
        if messages.count > Self.maxMessages {
            let keep = messages.values.sorted { $0.timestamp > $1.timestamp }.prefix(Self.maxMessages)
            messages = Dictionary(uniqueKeysWithValues: keep.map { ($0.messageId, $0) })
        }
    }

    static func rank(_ status: DeliveryStatus) -> Int {
        switch status {
        case .read: return 3
        case .delivered: return 2
        case .sent: return 1
        case .pending, .failed: return 0
        }
    }
}

/// Chat and receipts on the wire (§6.3). Both are plain JSON inside the per-peer E2EE envelope:
/// no `MessageEnvelope` wrapper and no padding, as on Android.
public enum ChatCodec {
    /// A new message from us, signed (3.2). `to` is nil for group chat.
    public static func compose(content: String, type: ChatMessageType = .text, to: String?, groupId: String,
                               identity: FamilySafeIdentity, now: Int64, messageId: String = UUID().uuidString.lowercased(),
                               replyTo: String? = nil) -> ChatMessage {
        let conversationId = to.map { ChatBook.privateConversationId(identity.memberId, $0) } ?? groupId
        let unsigned = ChatMessage(messageId: messageId, conversationId: conversationId, senderId: identity.memberId,
                                   recipientId: to, content: content, messageType: type, timestamp: now,
                                   replyToMessageId: replyTo, isOutgoing: true, status: .pending, isRead: true)
        let sig = try? AuthorSignatures.sign(unsigned.signingString, identity: identity)
        return ChatMessage(messageId: messageId, conversationId: conversationId, senderId: identity.memberId,
                           recipientId: to, content: content, messageType: type, timestamp: now,
                           replyToMessageId: replyTo, authorSig: sig, isOutgoing: true, status: .pending, isRead: true)
    }

    public static func plaintext(_ message: ChatMessage) throws -> String {
        String(decoding: try JSONEncoder().encode(message.payload), as: UTF8.self)
    }

    /// A message opened from `sender`'s envelope, or nil. A group message must name this family's
    /// group (§6.3, 1.15.0); a present author signature must verify (3.2).
    public static func read(_ body: String, sender: FamilyMember, me: String, groupId: String) -> ChatMessage? {
        guard let p = try? JSONDecoder().decode(ChatMessagePayload.self, from: Data(body.utf8)),
              !p.messageId.isEmpty, p.messageId.count <= 128 else { return nil }
        if let c = p.conversationId, c != groupId { return nil }
        let conversationId = p.conversationId ?? ChatBook.privateConversationId(me, sender.memberId)
        let message = ChatMessage(messageId: p.messageId, conversationId: conversationId, senderId: sender.memberId,
                                  recipientId: nil, content: p.content, messageType: p.messageType, timestamp: p.timestamp,
                                  replyToMessageId: p.replyToMessageId, authorSig: p.authorSig,
                                  isOutgoing: false, status: .delivered, isRead: false)
        if p.authorSig != nil {
            guard AuthorSignatures.accept(authorSig: p.authorSig, authorEd25519Hex: sender.ed25519PublicKey,
                                          message: message.signingString, acceptUnsigned: false) else { return nil }
        }
        return message
    }

    public static func receipt(for messageId: String, status: DeliveryStatus, me: String, now: Int64) throws -> String {
        String(decoding: try JSONEncoder().encode(DeliveryReceipt(messageId: messageId, recipientId: me, status: status, timestamp: now)),
               as: UTF8.self)
    }

    public static func readReceipt(_ body: String) -> DeliveryReceipt? {
        try? JSONDecoder().decode(DeliveryReceipt.self, from: Data(body.utf8))
    }
}
