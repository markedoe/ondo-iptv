import Foundation

/// An episode's own title as people read it, the same on every screen (its series' page, the player, Up next, Continue
/// Watching, search): TMDb's name for it when known, else the provider's, without the series' name, the provider's tags
/// or its code (`"|EN| Tom Clancy's Jack Ryan S03E02"` names no episode). An episode named by nothing is IPTVCore's
/// `"Episode 2"`, which the app shows in its language (`PlaceholderNames`). Same rules as Android's `EpisodeTitles`.
public enum EpisodeTitles {
    /// What separates a series' name, a code and a title: never a title's own end ("Cat's in the Bag...").
    private static let separators = CharacterSet(charactersIn: " -–—|:_,·")
    /// A title's own last marks, which `CatalogueMatcher.displayTitle` leaves out.
    private static let endMarks = CharacterSet(charactersIn: ".!?…")

    /// "Episode 2", "Épisode 02", "Ep. 2", "E02": a number, not a title. A bare number is one only when it is the
    /// episode's own ("2" for episode 2): "42" or "1984" can be a title.
    // swiftlint:disable:next force_try
    private static let numberOnly = try! NSRegularExpression(
        pattern: #"^((?:episode|épisode|episodio|episódio|epizod|folge|capitulo|capítulo|ep\.?|e)\s*)?0*(\d{1,4})$"#,
        options: [.caseInsensitive]
    )

    private static func isNumberOnly(_ name: String, number: Int) -> Bool {
        guard let match = numberOnly.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)) else { return false }
        if match.range(at: 1).location != NSNotFound { return true }
        return Range(match.range(at: 2), in: name).flatMap { Int(name[$0]) } == number
    }

    /// A history's line of an episode, "S03E02 · <title>": its code, then its title.
    // swiftlint:disable:next force_try
    private static let codeLine = try! NSRegularExpression(pattern: #"^(S\d{2,}E(\d{2,}))\s+·\s+(.*)$"#, options: [.dotMatchesLineSeparators])

    /// IPTVCore's name of episode `number`, in English (`PlaceholderNames`).
    public static func placeholder(_ number: Int) -> String { "\(PlaceholderNames.episodeWord) \(number)" }

    /// Whether `title` is IPTVCore's name of an episode (`placeholder`): no title of its own.
    public static func isPlaceholder(_ title: String, number: Int) -> Bool { title == placeholder(number) }

    /// `title` without its series' name (`seriesName`), the provider's tags around it and its code:
    /// "Copper Valley - S01E01 - Pilot" -> "Pilot", "|EN| Tom Clancy's Jack Ryan S03E02" -> "Episode 2",
    /// "Jack Ryan - The Big Fish" -> "The Big Fish". `placeholder(number)` when nothing of its own is left.
    public static func clean(_ title: String, seriesName: String?, number: Int) -> String {
        var name = title.trimmingCharacters(in: .whitespacesAndNewlines)
        if let parsed = TitleParser.parseEpisode(name) {
            // Nothing after the code: the parser's own "Episode N".
            name = parsed.episodeTitle == placeholder(parsed.episode) ? "" : parsed.episodeTitle
        }
        if !name.isEmpty { name = withoutTags(name) }
        if !name.isEmpty, name.unicodeScalars.allSatisfy({ separators.contains($0) || endMarks.contains($0) || $0 == "[" || $0 == "]" }) {
            name = ""
        }
        let series = seriesName.map { TitleParser.splitYear(CatalogueMatcher.displayTitle($0)).0.trimmingCharacters(in: .whitespaces) } ?? ""
        if !name.isEmpty, !series.isEmpty {
            if CatalogueMatcher.key(name) == CatalogueMatcher.key(series) {
                name = ""
            } else if let prefix = name.range(of: series, options: [.caseInsensitive, .anchored]) {
                let rest = name[prefix.upperBound...]
                if rest.isEmpty || rest.unicodeScalars.first.map(separators.contains) == true {
                    name = String(rest).trimmingCharacters(in: separators)
                }
            }
        }
        if isNumberOnly(name, number: number) { name = "" }
        return name.isEmpty ? placeholder(number) : name
    }

    /// `name` without the provider's tags around it (`CatalogueMatcher.displayTitle`), its own last marks kept.
    private static func withoutTags(_ name: String) -> String {
        let cleaned = CatalogueMatcher.displayTitle(name).trimmingCharacters(in: separators.union(CharacterSet(charactersIn: ".")))
        guard !cleaned.isEmpty, let at = name.range(of: cleaned, options: .backwards) else { return cleaned }
        let marks = name[at.upperBound...].unicodeScalars.prefix { endMarks.contains($0) }
        return cleaned + String(String.UnicodeScalarView(marks))
    }

    /// `episode` as it shows: its title (`clean`), TMDb's name for it first (`tmdb`); its picture the provider's, else
    /// TMDb's still; its plot the provider's, else TMDb's.
    public static func merge(_ episode: Episode, seriesName: String?, tmdb: TMDbEpisode?) -> Episode {
        var result = episode
        let own = clean(episode.title, seriesName: seriesName, number: episode.number)
        let tmdbName = tmdb?.name.map { clean($0, seriesName: seriesName, number: episode.number) }
            .flatMap { isPlaceholder($0, number: episode.number) ? nil : $0 }
        result.title = tmdbName ?? own
        if episode.posterURL == nil, let still = tmdb?.stillURL { result.posterURL = still }
        if episode.plot?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true,
           let overview = tmdb?.overview, !overview.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            result.plot = overview
        }
        return result
    }

    /// Every episode of `details` as it shows (`merge`), with TMDb's episodes of each season (`tmdb`, by season number)
    /// matched by their number in it.
    public static func merge(_ details: SeriesDetails, seriesName: String?, tmdb: [Int: [TMDbEpisode]] = [:]) -> SeriesDetails {
        var result = details
        for index in result.seasons.indices {
            let season = result.seasons[index]
            let known = Dictionary((tmdb[season.number] ?? []).map { ($0.number, $0) }, uniquingKeysWith: { first, _ in first })
            result.seasons[index].episodes = season.episodes.map { merge($0, seriesName: seriesName, tmdb: known[$0.number]) }
        }
        return result
    }

    /// The line Continue Watching shows under a series' name, "S03E02 · <title>", with the episode's title as it shows
    /// (`clean`; `names` for an episode named by nothing): a line kept before reads like a new one. Another text as it is.
    public static func cleanSubtitle(_ subtitle: String, seriesName: String?, names: PlaceholderNames = .english) -> String {
        let range = NSRange(subtitle.startIndex..., in: subtitle)
        guard let match = codeLine.firstMatch(in: subtitle, range: range),
              let code = Range(match.range(at: 1), in: subtitle),
              let digits = Range(match.range(at: 2), in: subtitle), let number = Int(subtitle[digits]),
              let rest = Range(match.range(at: 3), in: subtitle)
        else { return subtitle }
        let title = clean(String(subtitle[rest]), seriesName: seriesName, number: number)
        return "\(subtitle[code]) · " + names.episodeTitle(title, number: number)
    }
}
