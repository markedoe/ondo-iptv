import Foundation

/// Names in the cast and director fields providers send ("A, B, C", "A / B", "Name (voice)").
public enum People {
    public static func names(in text: String?) -> [String] {
        entries(in: text).map(\.name)
    }

    /// The names with their key (`TextNormalizer.normalize`), each name once.
    static func entries(in text: String?) -> [(name: String, key: String)] {
        guard let text, !text.isEmpty else { return [] }
        var seen = Set<String>()
        var entries: [(name: String, key: String)] = []
        for part in text.split(whereSeparator: { ",;/|\n".contains($0) }) {
            var name = String(part)
            // A role in brackets is not part of the name (the expression only runs where there is one).
            if name.contains("(") || name.contains("[") {
                name = name.replacingOccurrences(of: #"\s*[\(\[][^\)\]]*[\)\]]"#, with: "", options: .regularExpression)
            }
            name = name.trimmingCharacters(in: trimmed)
            guard name.count > 1, name.rangeOfCharacter(from: .letters) != nil else { continue }
            let key = TextNormalizer.normalize(name)
            if seen.insert(key).inserted { entries.append((name, key)) }
        }
        return entries
    }

    private static let trimmed = CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: ".…"))

    /// Whether a cast or director field names this person (accents and case ignored).
    public static func field(_ text: String?, names person: String) -> Bool {
        let wanted = TextNormalizer.normalize(person)
        return !wanted.isEmpty && entries(in: text).contains { $0.key == wanted }
    }
}

/// The actors and directors a catalogue names, for search: series lists give their cast, movie lists do not (a
/// movie's cast is known once its page has been opened).
public struct PeopleIndex: Sendable {
    private var entries: [(key: String, name: String, count: Int)] = []

    public init() {}

    /// From cast and director fields ("Name, Name (role)…"); a name met more often ranks higher.
    public init(fields: [String?]) {
        var byKey: [String: (name: String, count: Int)] = [:]
        for field in fields {
            for (name, key) in People.entries(in: field) where !key.isEmpty {
                byKey[key, default: (name, 0)].count += 1
            }
        }
        entries = byKey
            .map { (key: $0.key, name: $0.value.name, count: $0.value.count) }
            .sorted { $0.count != $1.count ? $0.count > $1.count : $0.key < $1.key }
    }

    public var isEmpty: Bool { entries.isEmpty }

    /// Names in which every word of the query starts a word (accents and case ignored): names that start with the
    /// query first, then the others, the most frequent first. Three letters at least.
    public func names(matching query: String, limit: Int = 20) -> [String] {
        let normalized = TextNormalizer.normalize(query)
        let tokens = normalized.split(separator: " ")
        guard normalized.count >= 3, !tokens.isEmpty else { return [] }
        var leading: [String] = []
        var others: [String] = []
        for entry in entries where leading.count < limit {
            let words = entry.key.split(separator: " ")
            guard tokens.allSatisfy({ token in words.contains { $0.hasPrefix(token) } }) else { continue }
            if entry.key.hasPrefix(normalized) { leading.append(entry.name) } else { others.append(entry.name) }
        }
        return Array((leading + others).prefix(limit))
    }
}
