import Foundation

public enum ReplicationDataType: String, Codable {
    case locationHistory = "LOCATION_HISTORY"
    case chatMessages = "CHAT_MESSAGES"
}

/// Inside a per-peer E2EE envelope, on `replication/request` (§6.6).
public struct ReplicationRequest: Codable, Equatable {
    public let requestId: String
    public let requesterId: String
    public let dataType: ReplicationDataType
    public let targetMemberId: String?
    public let conversationId: String?
    public let afterTimestamp: Int64
    public let limit: Int
    public let timestamp: Int64

    public init(
        requestId: String, requesterId: String, dataType: ReplicationDataType,
        targetMemberId: String? = nil, conversationId: String? = nil,
        afterTimestamp: Int64, limit: Int, timestamp: Int64
    ) {
        self.requestId = requestId
        self.requesterId = requesterId
        self.dataType = dataType
        self.targetMemberId = targetMemberId
        self.conversationId = conversationId
        self.afterTimestamp = afterTimestamp
        self.limit = limit
        self.timestamp = timestamp
    }
}

/// A backfilled chat message, shaped for replication rather than live delivery — carries
/// its own `status`/`isOutgoing` since it arrives out of the normal receipt flow.
public struct ReplicatedMessage: Codable, Equatable {
    public let messageId: String
    public let conversationId: String
    public let senderId: String
    public let recipientId: String?
    public let content: String
    public let messageType: ChatMessageType
    public let status: DeliveryStatus
    public let timestamp: Int64
    public let isOutgoing: Bool
    public let replyToMessageId: String?
    /// The author's signature, passed on unchanged (3.2, F24).
    public let authorSig: String?

    public init(
        messageId: String, conversationId: String, senderId: String, recipientId: String? = nil,
        content: String, messageType: ChatMessageType, status: DeliveryStatus, timestamp: Int64,
        isOutgoing: Bool, replyToMessageId: String? = nil, authorSig: String? = nil
    ) {
        self.authorSig = authorSig
        self.messageId = messageId
        self.conversationId = conversationId
        self.senderId = senderId
        self.recipientId = recipientId
        self.content = content
        self.messageType = messageType
        self.status = status
        self.timestamp = timestamp
        self.isOutgoing = isOutgoing
        self.replyToMessageId = replyToMessageId
    }
}

/// Inside a per-peer E2EE envelope, on `replication/data` (§6.6).
public struct ReplicationResponse: Codable, Equatable {
    public let requestId: String
    public let senderId: String
    public let dataType: ReplicationDataType
    /// LocationUpdate-shaped, but never wrapped in a MessageEnvelope here.
    public let locations: [LocationUpdate]
    public let messages: [ReplicatedMessage]
    public let hasMore: Bool
    public let newestTimestamp: Int64?
    public let timestamp: Int64

    public init(
        requestId: String, senderId: String, dataType: ReplicationDataType,
        locations: [LocationUpdate] = [], messages: [ReplicatedMessage] = [],
        hasMore: Bool, newestTimestamp: Int64? = nil, timestamp: Int64
    ) {
        self.requestId = requestId
        self.senderId = senderId
        self.dataType = dataType
        self.locations = locations
        self.messages = messages
        self.hasMore = hasMore
        self.newestTimestamp = newestTimestamp
        self.timestamp = timestamp
    }
}

public struct LocationHistorySummary: Codable, Equatable {
    public let memberId: String
    public let oldestTimestamp: Int64
    public let newestTimestamp: Int64
    public let count: Int

    public init(memberId: String, oldestTimestamp: Int64, newestTimestamp: Int64, count: Int) {
        self.memberId = memberId
        self.oldestTimestamp = oldestTimestamp
        self.newestTimestamp = newestTimestamp
        self.count = count
    }
}

public struct ChatHistorySummary: Codable, Equatable {
    public let conversationId: String
    public let oldestTimestamp: Int64
    public let newestTimestamp: Int64
    public let count: Int

    public init(conversationId: String, oldestTimestamp: Int64, newestTimestamp: Int64, count: Int) {
        self.conversationId = conversationId
        self.oldestTimestamp = oldestTimestamp
        self.newestTimestamp = newestTimestamp
        self.count = count
    }
}

/// Inside a per-recipient E2EE envelope, on `replication/announce` (§6.6). A capability
/// summary, not an event — self-heals on the next tick.
public struct DataAvailabilityAnnouncement: Codable, Equatable {
    public let announcerId: String
    public let locationDataSummary: [LocationHistorySummary]
    public let chatDataSummary: [ChatHistorySummary]
    public let timestamp: Int64

    public init(
        announcerId: String, locationDataSummary: [LocationHistorySummary] = [],
        chatDataSummary: [ChatHistorySummary] = [], timestamp: Int64
    ) {
        self.announcerId = announcerId
        self.locationDataSummary = locationDataSummary
        self.chatDataSummary = chatDataSummary
        self.timestamp = timestamp
    }
}


// Android's kotlinx JSON omits any field still equal to its default (encodeDefaults = false),
// so these decode a missing field as Android's default instead of failing.

extension ReplicationRequest {
    private enum CodingKeys: String, CodingKey {
        case requestId, requesterId, dataType, targetMemberId, conversationId, afterTimestamp, limit, timestamp
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(requestId: try c.decode(String.self, forKey: .requestId),
                  requesterId: try c.decode(String.self, forKey: .requesterId),
                  dataType: try c.decode(ReplicationDataType.self, forKey: .dataType),
                  targetMemberId: try c.decodeIfPresent(String.self, forKey: .targetMemberId),
                  conversationId: try c.decodeIfPresent(String.self, forKey: .conversationId),
                  afterTimestamp: try c.decodeIfPresent(Int64.self, forKey: .afterTimestamp) ?? 0,
                  limit: try c.decodeIfPresent(Int.self, forKey: .limit) ?? 500,
                  timestamp: try c.decodeIfPresent(Int64.self, forKey: .timestamp) ?? 0)
    }
}

extension ReplicationResponse {
    private enum CodingKeys: String, CodingKey {
        case requestId, senderId, dataType, locations, messages, hasMore, newestTimestamp, timestamp
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(requestId: try c.decode(String.self, forKey: .requestId),
                  senderId: try c.decode(String.self, forKey: .senderId),
                  dataType: try c.decode(ReplicationDataType.self, forKey: .dataType),
                  locations: try c.decodeIfPresent([LocationUpdate].self, forKey: .locations) ?? [],
                  messages: try c.decodeIfPresent([ReplicatedMessage].self, forKey: .messages) ?? [],
                  hasMore: try c.decodeIfPresent(Bool.self, forKey: .hasMore) ?? false,
                  newestTimestamp: try c.decodeIfPresent(Int64.self, forKey: .newestTimestamp),
                  timestamp: try c.decodeIfPresent(Int64.self, forKey: .timestamp) ?? 0)
    }
}

extension DataAvailabilityAnnouncement {
    private enum CodingKeys: String, CodingKey { case announcerId, locationDataSummary, chatDataSummary, timestamp }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(announcerId: try c.decode(String.self, forKey: .announcerId),
                  locationDataSummary: try c.decodeIfPresent([LocationHistorySummary].self, forKey: .locationDataSummary) ?? [],
                  chatDataSummary: try c.decodeIfPresent([ChatHistorySummary].self, forKey: .chatDataSummary) ?? [],
                  timestamp: try c.decodeIfPresent(Int64.self, forKey: .timestamp) ?? 0)
    }
}
