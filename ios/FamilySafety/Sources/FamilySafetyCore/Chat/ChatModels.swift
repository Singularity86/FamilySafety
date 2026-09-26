import Foundation

public enum ChatMessageType: String, Codable {
    case text = "TEXT"
    case location = "LOCATION"
    case system = "SYSTEM"
}

public enum DeliveryStatus: String, Codable {
    case pending = "PENDING"
    case sent = "SENT"
    case delivered = "DELIVERED"
    case read = "READ"
    case failed = "FAILED"
}

/// Plaintext inside the E2EE envelope (§3) on the `chat` topic. `conversationId == nil`
/// means a 1:1 conversation (the peer's memberId); a groupId means group chat, sent as one
/// E2EE copy per member (§6.3).
public struct ChatMessagePayload: Codable, Equatable {
    public let messageId: String
    public let content: String
    public let messageType: ChatMessageType
    public let timestamp: Int64
    public let conversationId: String?
    public let replyToMessageId: String?

    public init(
        messageId: String, content: String, messageType: ChatMessageType, timestamp: Int64,
        conversationId: String? = nil, replyToMessageId: String? = nil
    ) {
        self.messageId = messageId
        self.content = content
        self.messageType = messageType
        self.timestamp = timestamp
        self.conversationId = conversationId
        self.replyToMessageId = replyToMessageId
    }
}

/// `content` for a `.location` message — itself JSON, stored as a string in `content`.
public struct ChatLocationContent: Codable, Equatable {
    public let latitude: Double
    public let longitude: Double

    public init(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }
}

/// Plaintext inside the E2EE envelope. DELIVERED goes on `chat/receipt`, READ on
/// `chat/read` (§6.3). Reject receipts for message IDs never addressed to the receipt's
/// authenticated sender — anti-forgery, enforced by the caller, not this struct.
public struct DeliveryReceipt: Codable, Equatable {
    public let messageId: String
    public let recipientId: String
    public let status: DeliveryStatus
    public let timestamp: Int64

    public init(messageId: String, recipientId: String, status: DeliveryStatus, timestamp: Int64) {
        self.messageId = messageId
        self.recipientId = recipientId
        self.status = status
        self.timestamp = timestamp
    }
}
