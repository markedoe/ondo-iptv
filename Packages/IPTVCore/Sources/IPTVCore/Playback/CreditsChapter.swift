import Foundation

/// A chapter of a film or an episode, as its file names it: its title and where it starts (seconds).
public struct MediaChapter: Equatable, Sendable {
    public var title: String?
    public var start: Double

    public init(title: String?, start: Double) {
        self.title = title
        self.start = start
    }
}

/// Finds the end credits among a file's chapters: a title is watched once playback reaches them
/// (`HistoryEntry.isFinished`). The same rule as the Kotlin core's `CreditsChapter`, with the same tests.
public enum CreditsChapter {
    /// Only a chapter in this last share of the duration counts, so opening credits never match.
    public static let lastShare = 0.25

    /// Chapter names for end credits, in several languages (compared without case or accents).
    public static let names = [
        "credits", "end credits", "closing credits", "end titles", "ending", "outro",
        "generique", "generique de fin", "abspann", "nachspann", "creditos", "creditos finales", "creditos finais",
        "titoli di coda", "aftiteling", "eindtitels", "napisy koncowe", "titry", "титры", "финальные титры",
        "片尾", "片尾曲", "演职员表", "エンディング", "スタッフロール", "엔딩", "엔딩 크레딧",
    ]

    private static let normalizedNames = names.map(normalized)

    /// Where the end credits start (seconds): the first chapter named for them in the last quarter of `duration`.
    public static func start(in chapters: [MediaChapter], duration: Double) -> Double? {
        guard duration > 0, duration.isFinite else { return nil }
        let earliest = duration * (1 - lastShare)
        return chapters
            .filter { $0.start.isFinite && $0.start >= earliest && $0.start < duration && isCredits($0.title ?? "") }
            .map(\.start)
            .min()
    }

    /// Whether a chapter's title names end credits: one of `names` as whole words, or within the title for scripts
    /// written without spaces (Chinese, Japanese, Korean).
    public static func isCredits(_ title: String) -> Bool {
        let title = normalized(title)
        let words = words(title)
        return normalizedNames.contains { name in
            if name.unicodeScalars.contains(where: isWrittenWithoutSpaces) { return title.contains(name) }
            let nameWords = Self.words(name)
            guard !nameWords.isEmpty, nameWords.count <= words.count else { return false }
            return (0...(words.count - nameWords.count)).contains { Array(words[$0..<$0 + nameWords.count]) == nameWords }
        }
    }

    /// Decomposed, without accents (nonspacing marks), in lower case.
    static func normalized(_ text: String) -> String {
        var scalars = String.UnicodeScalarView()
        scalars.append(contentsOf: text.decomposedStringWithCanonicalMapping.unicodeScalars.filter {
            $0.properties.generalCategory != .nonspacingMark
        })
        return String(scalars).lowercased()
    }

    /// The runs of letters and digits.
    static func words(_ text: String) -> [String] {
        text.split { !($0.isLetter || $0.isNumber) }.map(String.init)
    }

    private static func isWrittenWithoutSpaces(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x1100...0x11FF, 0x3040...0x30FF, 0x3400...0x4DBF, 0x4E00...0x9FFF, 0xAC00...0xD7AF: return true
        default: return false
        }
    }
}
