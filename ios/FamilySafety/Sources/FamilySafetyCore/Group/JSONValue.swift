import Foundation

/// Any JSON value, kept verbatim. Roster fields this build doesn't know (added by a newer
/// Android) are held in `extras` and written back out unchanged, so an iOS phone relaying a
/// state never strips another member's signature or a newer field (B1, "keep unknown fields").
public enum JSONValue: Codable, Hashable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([JSONValue].self) { self = .array(v) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let v): try c.encode(v)
        case .number(let v):
            // Integers stay integers (timestamps, epochs), as Android wrote them.
            if v.rounded() == v, abs(v) < 9_007_199_254_740_992 { try c.encode(Int64(v)) } else { try c.encode(v) }
        case .string(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        }
    }
}

/// A coding key for any field name, to read and write `extras`.
struct AnyKey: CodingKey {
    let stringValue: String
    var intValue: Int? { nil }
    init(_ s: String) { stringValue = s }
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { nil }
}

extension Decoder {
    /// Every field not in `known`, as raw JSON.
    func unknownFields(known: Set<String>) throws -> [String: JSONValue] {
        let all = try container(keyedBy: AnyKey.self)
        var extras: [String: JSONValue] = [:]
        for key in all.allKeys where !known.contains(key.stringValue) {
            extras[key.stringValue] = try all.decode(JSONValue.self, forKey: key)
        }
        return extras
    }
}

extension Encoder {
    func encodeExtras(_ extras: [String: JSONValue]) throws {
        guard !extras.isEmpty else { return }
        var c = container(keyedBy: AnyKey.self)
        for (k, v) in extras { try c.encode(v, forKey: AnyKey(k)) }
    }
}
