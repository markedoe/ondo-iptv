import Foundation

/// Xtream panels are notoriously inconsistent: the same field can be an Int, a String, a Bool,
/// `null`, an empty array… These helpers never throw for a wrong type, they return `nil`.
extension KeyedDecodingContainer {
    func lenientString(_ key: Key) -> String? {
        if let value = try? decodeIfPresent(String.self, forKey: key) { return value }
        if let value = try? decodeIfPresent(Int.self, forKey: key) { return String(value) }
        if let value = try? decodeIfPresent(Double.self, forKey: key) {
            return value.rounded() == value ? String(Int(value)) : String(value)
        }
        if let value = try? decodeIfPresent(Bool.self, forKey: key) { return value ? "1" : "0" }
        if let values = try? decodeIfPresent([String].self, forKey: key) { return values.first }
        return nil
    }

    func lenientInt(_ key: Key) -> Int? {
        if let value = try? decodeIfPresent(Int.self, forKey: key) { return value }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            let trimmed = value.trimmingCharacters(in: .whitespaces)
            if let int = Int(trimmed) { return int }
            if let double = Double(trimmed) { return Int(double) }
            return nil
        }
        if let value = try? decodeIfPresent(Double.self, forKey: key) { return Int(value) }
        if let value = try? decodeIfPresent(Bool.self, forKey: key) { return value ? 1 : 0 }
        return nil
    }

    func lenientDouble(_ key: Key) -> Double? {
        if let value = try? decodeIfPresent(Double.self, forKey: key) { return value }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return Double(value.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: "."))
        }
        return nil
    }

    func lenientBool(_ key: Key) -> Bool? {
        if let value = try? decodeIfPresent(Bool.self, forKey: key) { return value }
        if let value = lenientInt(key) { return value != 0 }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return ["true", "yes", "active"].contains(value.lowercased())
        }
        return nil
    }

    /// Strings or arrays of strings (`backdrop_path`).
    func lenientStringArray(_ key: Key) -> [String] {
        if let values = try? decodeIfPresent([String].self, forKey: key) { return values }
        if let value = try? decodeIfPresent(String.self, forKey: key), !value.isEmpty { return [value] }
        return []
    }

    /// Unix timestamps as Int or String.
    func lenientDate(_ key: Key) -> Date? {
        guard let seconds = lenientInt(key), seconds > 0 else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(seconds))
    }
}

/// Coding key that accepts any string, for decoding loosely shaped objects.
struct AnyCodingKey: CodingKey, Hashable {
    var stringValue: String
    var intValue: Int?

    init(_ string: String) {
        stringValue = string
        intValue = Int(string)
    }

    init?(stringValue: String) { self.init(stringValue) }

    init?(intValue: Int) {
        stringValue = String(intValue)
        self.intValue = intValue
    }
}

/// Decodes either an array of `T` or skips silently (panels return `{}` / `null` / `false` for empty lists),
/// and drops individual elements that fail to decode instead of failing the whole list.
struct LossyArray<T: Decodable>: Decodable {
    var elements: [T]

    init(from decoder: Decoder) throws {
        guard var container = try? decoder.unkeyedContainer() else {
            // Some panels return an object keyed by index instead of an array.
            if let keyed = try? decoder.container(keyedBy: AnyCodingKey.self) {
                elements = keyed.allKeys
                    .sorted { ($0.intValue ?? 0) < ($1.intValue ?? 0) }
                    .compactMap { try? keyed.decode(T.self, forKey: $0) }
            } else {
                elements = []
            }
            return
        }
        var result: [T] = []
        while !container.isAtEnd {
            if let value = try? container.decode(T.self) {
                result.append(value)
            } else if (try? container.decode(Discard.self)) == nil {
                break
            }
        }
        elements = result
    }

    /// Accepts any JSON value so the container moves past an element `T` could not decode.
    private struct Discard: Decodable {
        init(from decoder: Decoder) throws {}
    }
}
