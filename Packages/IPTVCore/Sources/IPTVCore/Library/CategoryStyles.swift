import Foundation

/// Styles that providers name categories after ("Manga", "Kids", "Documentaries"), in several languages, so that a
/// viewer can hide a whole style at once, in every playlist, those the provider adds later included; and any other
/// word the viewer chooses ("Bollywood"). Only category names are looked at: a movie's own genre ("Animation") would
/// hide far more than meant.
public enum CategoryStyles {
    /// Style -> the words that name it, normalised (`TextNormalizer`): lower case, without accents.
    public static let words: [String: [String]] = [
        "manga": ["manga", "mangas", "anime", "animes"],
        "kids": [
            "kids", "kid", "children", "enfants", "enfant", "jeunesse", "infantil", "infantiles", "ninos", "criancas",
            "kinder", "bambini", "cartoon", "cartoons", "dessins animes", "dessin anime",
        ],
        "animation": ["animation", "animations", "animacion", "animacao", "animazione", "animatie"],
        "documentary": [
            "documentary", "documentaries", "documentaire", "documentaires", "documental", "documentales",
            "documentario", "documentarios", "documentari", "doku", "dokus", "dokumentation",
        ],
        "sport": ["sport", "sports", "deportes", "deporte", "esporte", "esportes", "sportif", "sportifs"],
        "horror": ["horror", "horreur", "terror", "terreur", "epouvante"],
        "music": ["music", "musique", "musica", "musicas", "musik", "concert", "concerts"],
    ]

    /// The order the styles are offered in.
    public static let order = ["manga", "kids", "animation", "documentary", "sport", "horror", "music"]

    /// Phrases holding a style's word without being that style: French "dessins animés" are cartoons, not anime.
    static let exceptions: [String: [String]] = ["manga": ["dessins animes", "dessin anime"]]

    /// Whether a category name is hidden by this rule: a style ("manga") or a word chosen by the viewer.
    public static func matches(_ name: String, rule: String) -> Bool {
        matches(tokens: tokens(of: name), rule: rule)
    }

    /// The first of these rules (in alphabetical order) that hides the category, if any.
    public static func rule(hiding name: String, among rules: some Collection<String>) -> String? {
        guard !rules.isEmpty else { return nil }
        return rule(hidingTokens: tokens(of: name), among: rules)
    }

    /// Same, from the words of a name already split (`tokens(of:)`, kept by callers that ask often).
    public static func rule(hidingTokens words: [Substring], among rules: some Collection<String>) -> String? {
        rules.sorted().first { matches(tokens: words, rule: $0) }
    }

    /// The styles found in these category names, and the categories of each.
    public static func detect(in categories: [MediaCategory]) -> [String: [MediaCategory]] {
        var found: [String: [MediaCategory]] = [:]
        for category in categories {
            let words = tokens(of: category.name)
            for style in order where matches(tokens: words, rule: style) {
                found[style, default: []].append(category)
            }
        }
        return found
    }

    /// A word typed by the viewer as a rule ("  Bollywood " -> "bollywood"), or nil when it holds no letter or digit.
    public static func rule(forWord word: String) -> String? {
        let normalized = TextNormalizer.normalize(word)
        return normalized.isEmpty ? nil : normalized
    }

    /// The normalised words of a category name.
    public static func tokens(of name: String) -> [Substring] {
        TextNormalizer.normalize(name).split(separator: " ")
    }

    static func matches(tokens: [Substring], rule: String) -> Bool {
        var words = tokens
        for exception in exceptions[rule] ?? [] {
            words = removing(exception.split(separator: " "), from: words)
        }
        // A style's words, or the viewer's word and its plural.
        let phrases: [String]
        if let styleWords = Self.words[rule] {
            phrases = styleWords
        } else {
            let word = TextNormalizer.normalize(rule)
            phrases = word.isEmpty ? [] : [word, word + "s"]
        }
        return phrases.contains { contains(words, phrase: $0.split(separator: " ")) }
    }

    private static func contains(_ words: [Substring], phrase: [Substring]) -> Bool {
        guard !phrase.isEmpty, words.count >= phrase.count else { return false }
        return (0...(words.count - phrase.count)).contains { start in
            phrase.indices.allSatisfy { words[start + $0] == phrase[$0] }
        }
    }

    private static func removing(_ phrase: [Substring], from words: [Substring]) -> [Substring] {
        guard !phrase.isEmpty else { return words }
        var result: [Substring] = []
        var index = 0
        while index < words.count {
            if index + phrase.count <= words.count, phrase.indices.allSatisfy({ words[index + $0] == phrase[$0] }) {
                index += phrase.count
            } else {
                result.append(words[index])
                index += 1
            }
        }
        return result
    }
}
