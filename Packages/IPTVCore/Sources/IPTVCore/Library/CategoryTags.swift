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

    /// What providers put around a header's or a title's name before its tag ("-----▼", "---●★|").
    private static let decoration: Set<Character> = ["-", "=", "_", "~", "*", "#", "•", "●", "○", "◆", "★", "☆", "▼", "▲", "►", "◄", "▶", "◀", "|", " "]

    /// The tag a title's or a channel's own name starts with, by the rules of `tag(of:)` ("|PT| Tony Carreira",
    /// "[PT] …", "PT - …", "PT| …", "🇵🇹 …"), decorations before it left out; nil without one. Only a delimited code at
    /// the very start counts: "Portugal: The Man" and "PTSD" carry none. Same rules as the Kotlin `CategoryTags.titleTag`.
    public static func titleTag(of name: String) -> String? {
        tag(of: String(name.drop { $0 != "|" && decoration.contains($0) }))
    }

    /// Whether a title's or a channel's own `name` starts or ends with one of these hidden tags (`titleTag(of:)`,
    /// `trailingTag(of:)`).
    public static func isHidden(name: String, hiddenTags: Set<String>) -> Bool {
        guard !hiddenTags.isEmpty else { return false }
        if let tag = titleTag(of: name), hiddenTags.contains(tag) { return true }
        if let tag = trailingTag(of: name), hiddenTags.contains(tag) { return true }
        return false
    }

    /// What a tag at the end of a name is closed by, and what opens it.
    private static let endClosers: Set<Unicode.Scalar> = [")", "]", "}", ">", "|", "»", "┃", "│"]
    private static let endOpeners: Set<Unicode.Scalar> = ["(", "[", "{", "<", "|", "«", "┃", "│"]
    /// What stands between a name and an unbracketed tag at its end ("La Casa - ESP", "La Casa | ES").
    private static let endSeparators: Set<Unicode.Scalar> = ["-", "–", "—", "|"]
    private static let upperCase: ClosedRange<Unicode.Scalar> = "A"..."Z"
    private static let lowerCase: ClosedRange<Unicode.Scalar> = "a"..."z"
    private static let digits: ClosedRange<Unicode.Scalar> = "0"..."9"

    /// The tag a title's or a channel's own name ends with, uppercased: a code in brackets ("La Casa (ES)", "El Puente
    /// [LAT]", "Os Amigos |PT|") or after a spaced dash or bar ("La Casa - ESP", "La Casa | ES"), years and quality
    /// words after it left out ("El Puente [LAT] (2024)", "(LAT) (4K)"); nil without one. Conservative: an upper-case
    /// code of 2 to 6 letters only, with a name before it; "Quiet Water (2024)", "(4K)", "(Uncut)", "(es)",
    /// "Spider-MAN", "La Casa ES" carry none. Same rules as the Kotlin `CategoryTags.trailingTag`.
    public static func trailingTag(of name: String) -> String? {
        let scalars = name.unicodeScalars
        var end = endOfText(scalars, scalars.endIndex)
        // A year or a quality word ("(2024)", "[HD]") after the tag, at most twice.
        for _ in 0..<3 {
            guard end > scalars.startIndex else { return nil }
            let last: Unicode.Scalar = scalars[scalars.index(before: end)]
            let bracketed: Bool = endClosers.contains(last)
            let tokenEnd = bracketed ? endOfText(scalars, scalars.index(before: end)) : end
            var tokenStart = tokenEnd
            while tokenStart > scalars.startIndex {
                let previous: Unicode.Scalar = scalars[scalars.index(before: tokenStart)]
                guard isASCIILetterOrDigit(previous) || previous == "-" else { break }
                tokenStart = scalars.index(before: tokenStart)
            }
            guard tokenStart < tokenEnd else { return nil }
            let before: String.UnicodeScalarView.Index
            if bracketed {
                let opener = endOfText(scalars, tokenStart)
                guard opener > scalars.startIndex, endOpeners.contains(scalars[scalars.index(before: opener)]) else { return nil }
                before = scalars.index(before: opener)
            } else {
                // "La Casa - ESP": a space, a dash or a bar, a space.
                guard tokenStart > scalars.startIndex, isSpace(scalars[scalars.index(before: tokenStart)]) else { return nil }
                let separator = endOfText(scalars, tokenStart)
                guard separator > scalars.startIndex else { return nil }
                let separatorIndex = scalars.index(before: separator)
                guard separatorIndex > scalars.startIndex, endSeparators.contains(scalars[separatorIndex]),
                      isSpace(scalars[scalars.index(before: separatorIndex)]) else { return nil }
                before = separatorIndex
            }
            let token = String(scalars[tokenStart..<tokenEnd])
            let isYear: Bool = bracketed && token.unicodeScalars.count == 4 && token.unicodeScalars.allSatisfy { digits.contains($0) }
            if isYear || skipped.contains(token) {
                end = endOfText(scalars, before)
                continue
            }
            let units = token.unicodeScalars
            guard (2...6).contains(units.count), units.first != "-", units.last != "-",
                  units.contains(where: { upperCase.contains($0) }),
                  !units.contains(where: { lowerCase.contains($0) }) else { return nil }
            // A name before the tag: "(ES)" alone is no title in Spanish.
            guard scalars[scalars.startIndex..<before].contains(where: { isASCIILetterOrDigit($0) || $0.value >= 128 }) else { return nil }
            return token
        }
        return nil
    }

    /// `end` with the spaces before it left out.
    private static func endOfText(
        _ scalars: String.UnicodeScalarView, _ end: String.UnicodeScalarView.Index
    ) -> String.UnicodeScalarView.Index {
        var index = end
        while index > scalars.startIndex, isSpace(scalars[scalars.index(before: index)]) {
            index = scalars.index(before: index)
        }
        return index
    }

    private static func isSpace(_ scalar: Unicode.Scalar) -> Bool {
        scalar == " " || scalar == "\t" || scalar == "\u{00A0}"
    }

    private static func isASCIILetterOrDigit(_ scalar: Unicode.Scalar) -> Bool {
        upperCase.contains(scalar) || lowerCase.contains(scalar) || digits.contains(scalar)
    }

    /// Whether a title is hidden by these hidden tags whatever its category: its own name starts or ends with one
    /// (`isHidden(name:hiddenTags:)`), or its provider's `plot` is in one of `hiddenLanguages`
    /// (`PlotLanguage.hiddenLanguages`: a hidden language none of whose tags in the catalogue is kept, never English) and
    /// its category's own tag (`categoryTag`, from `tag(of:)`) does not name a language: a category tagged with a
    /// language the viewer kept ("|FR|") keeps its titles, whatever their description. Same rule as the Kotlin
    /// `CategoryTags.isHiddenTitle`.
    public static func isHiddenTitle(
        name: String, plot: String?, categoryTag: String?, hiddenTags: Set<String>, hiddenLanguages: Set<String>
    ) -> Bool {
        guard !hiddenTags.isEmpty else { return false }
        if isHidden(name: name, hiddenTags: hiddenTags) { return true }
        let languages: Set<String> = hiddenLanguages
        guard !languages.isEmpty, let plot, !plot.isEmpty, readsPlots(categoryTag: categoryTag),
              let language = PlotLanguage.detect(plot) else { return false }
        return languages.contains(language)
    }

    /// Whether the titles of a category with this tag (`tag(of:)`) are told by their description's language: no
    /// language tag.
    public static func readsPlots(categoryTag: String?) -> Bool {
        guard let categoryTag else { return true }
        return !PlotLanguage.isLanguageTag(categoryTag)
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
