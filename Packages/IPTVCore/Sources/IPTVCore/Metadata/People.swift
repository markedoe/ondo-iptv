import Foundation

/// Names in the cast and director fields providers send ("A, B, C", "A / B", "Name (voice)").
public enum People {
    public static func names(in text: String?) -> [String] {
        guard let text, !text.isEmpty else { return [] }
        var seen = Set<String>()
        return text
            .split(whereSeparator: { ",;/|\n".contains($0) })
            .map { part -> String in
                // A role in brackets is not part of the name.
                let name = part.replacingOccurrences(of: #"\s*[\(\[][^\)\]]*[\)\]]"#, with: "", options: .regularExpression)
                return name.trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: ".…")))
            }
            .filter { name in
                name.count > 1 && name.rangeOfCharacter(from: .letters) != nil && seen.insert(TextNormalizer.normalize(name)).inserted
            }
    }

    /// Whether a cast or director field names this person (accents and case ignored).
    public static func field(_ text: String?, names person: String) -> Bool {
        let wanted = TextNormalizer.normalize(person)
        return !wanted.isEmpty && names(in: text).contains { TextNormalizer.normalize($0) == wanted }
    }
}
