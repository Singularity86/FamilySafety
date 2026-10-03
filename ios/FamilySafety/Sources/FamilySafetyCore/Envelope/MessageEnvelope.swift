import Foundation

/// The inner wrapper for location/presence payloads (§6.1) — NOT the E2EE envelope in §3.
/// `payload` is double-encoded: a JSON string containing the serialized inner object.
public enum EnvelopeType: String, Codable {
    case locationUpdate = "location_update"
    case presenceUpdate = "presence_update"
}

public struct MessageEnvelope: Codable, Equatable {
    public let type: EnvelopeType
    public let payload: String
    /// Meaningless filler, ignored on receive. Absent from senders predating Android
    /// 1.12.1 — decode as optional defaulting to "".
    public let pad: String

    public init(type: EnvelopeType, payload: String, pad: String = "") {
        self.type = type
        self.payload = payload
        self.pad = pad
    }

    private enum CodingKeys: String, CodingKey { case type, payload, pad }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        type = try c.decode(EnvelopeType.self, forKey: .type)
        payload = try c.decode(String.self, forKey: .payload)
        pad = try c.decodeIfPresent(String.self, forKey: .pad) ?? ""
    }
}

/// Builds a length-padded `MessageEnvelope` per §6.1. Message length survives encryption,
/// so an unpadded sender leaks real behaviour (online/offline, movement) through byte
/// count alone — padding rounds every message of a given type onto the same fixed grid.
public enum EnvelopePadding {
    /// Bytes-per-bucket for both location and presence (§6.1; presence was 256 before the
    /// §6.2 signature was added — it no longer fits, so both types share 512 now).
    public static let target = 512

    /// 1. Serialize with `pad` empty and measure UTF-8 bytes.
    /// 2. Round up to the next multiple of `target`.
    /// 3. Re-serialize with `pad` = `.` repeated (target − unpadded) times — `.` needs no
    ///    JSON escaping, so N characters add exactly N bytes.
    ///
    /// Pad the plaintext, before encryption — padding ciphertext achieves nothing.
    public static func build(type: EnvelopeType, payload: String, target: Int = EnvelopePadding.target) throws -> MessageEnvelope {
        let unpadded = MessageEnvelope(type: type, payload: payload, pad: "")
        let unpaddedSize = try JSONEncoder().encode(unpadded).count
        let rounded = ((unpaddedSize + target - 1) / target) * target
        let padLength = rounded - unpaddedSize
        return MessageEnvelope(type: type, payload: payload, pad: String(repeating: ".", count: padLength))
    }
}
