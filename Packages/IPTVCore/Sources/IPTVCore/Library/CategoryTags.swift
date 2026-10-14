import Foundation

/// The code many providers put at the start of their category names, most often a country or a language:
/// "|PT| Filmes", "PT | Filmes", "FR: Films", "[DE] Filme", "EN - Movies", "🇵🇹 Filmes". Hiding a tag hides every
/// category that carries it, those the provider adds later included.
public enum CategoryTags {
    /// Quality and generic words found before the real tag ("4K |FR| Films", "VOD - FR - Action"): skipped.
    static let skipped: Set<String> = [
        "4K", "8K", "UHD", "FHD", "HD", "SD", "HEVC", "H265", "H264", "HDR",
        "VOD", "TV", "LIVE", "ALL", "NEW", "TOP", "MOVIES", "MOVIE", "FILMS", "FILM", "SERIES", "SERIE", "SHOWS", "CINEMA",
    ]
    private static let openers: Set<Character> = ["|", "[", "(", "{", "<", "«", "┃", "│"]
    private static let closers: Set<Character> = ["|", "]", ")", "}", ">", "»", "┃", "│"]
    private static let separators: Set<Character> = [
        "|", ":", "-", "–", "—", "▎", "│", "┃", "»", "•", "/", "►", "▶", "➤", "_", "]", ")", "}", ">",
    ]

    /// The tag of a category name, uppercased ("PT"), or nil when the name does not start with one.
    public static func tag(of name: String) -> String? {
        var rest = Substring(name)
        // Quality or generic words first, then the tag.
        for _ in 0..<3 {
            guard let (token, remainder) = leadingToken(of: rest) else { return nil }
            if !skipped.contains(token) { return token }
            rest = remainder
        }
        return nil
    }

    /// Tags carried by at least two of these categories, and the categories that carry each one.
    public static func detect(in categories: [MediaCategory]) -> [String: [MediaCategory]] {
        var found: [String: [MediaCategory]] = [:]
        for category in categories {
            if let tag = tag(of: category.name) { found[tag, default: []].append(category) }
        }
        return found.filter { $0.value.count >= 2 }
    }

    /// One token and what follows it, or nil.
    private static func leadingToken(of text: Substring) -> (String, Substring)? {
        var rest = text.drop { $0.isWhitespace }
        if let flag = flagCode(at: rest) {
            rest = rest.dropFirst().drop { $0.isWhitespace || separators.contains($0) }
            return (flag, rest)
        }
        var bracketed = false
        while let first = rest.first, openers.contains(first) {
            bracketed = true
            rest = rest.dropFirst().drop { $0.isWhitespace }
        }
        let token = readToken(rest)
        guard token.count >= 2, token.count <= 6, token.contains(where: \.isLetter) else { return nil }
        var after = rest.dropFirst(token.count).drop { $0.isWhitespace }
        guard let next = after.first else { return nil }
        if bracketed {
            guard closers.contains(next) else { return nil }
        } else {
            // Without brackets, only an upper-case code followed by a separator is a tag ("Action - Drama" is not).
            guard token == token.uppercased(), separators.contains(next) else { return nil }
        }
        after = after.drop { $0.isWhitespace || separators.contains($0) }
        guard !after.isEmpty else { return nil }
        return (token.uppercased(), after)
    }

    /// Letters and digits, with inner hyphens between upper-case parts ("EX-YU").
    private static func readToken(_ text: Substring) -> String {
        var token = ""
        var index = text.startIndex
        while index < text.endIndex {
            let character = text[index]
            if character.isASCII && (character.isLetter || character.isNumber) {
                token.append(character)
            } else if character == "-", !token.isEmpty, token == token.uppercased(),
                      text.index(after: index) < text.endIndex,
                      let following = text[text.index(after: index)...].first,
                      following.isASCII, following.isUppercase {
                token.append(character)
            } else {
                break
            }
            index = text.index(after: index)
        }
        return token
    }

    /// "🇵🇹" (two regional indicator symbols) → "PT".
    private static func flagCode(at text: Substring) -> String? {
        guard let first = text.first else { return nil }
        let scalars = Array(first.unicodeScalars)
        guard scalars.count == 2, scalars.allSatisfy({ (0x1F1E6...0x1F1FF).contains($0.value) }) else { return nil }
        return String(String.UnicodeScalarView(scalars.compactMap { Unicode.Scalar($0.value - 0x1F1E6 + 65) }))
    }
}
