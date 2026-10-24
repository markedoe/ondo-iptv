import Foundation

/// Turns a flat M3U list into Live / Movies / Series with categories.
public enum M3UContentBuilder {
    public static func build(from playlist: M3UPlaylist, playlistHeaders: HTTPHeaders = .none) -> PlaylistContent {
        let defaultCatchup = catchupInfo(from: playlist.headerAttributes, url: nil)

        var liveCategories = OrderedCategories(type: .live)
        var movieCategories = OrderedCategories(type: .movie)
        var seriesCategories = OrderedCategories(type: .series)
        var channels: [Channel] = []
        var movies: [Movie] = []
        var seriesByKey: [String: Series] = [:]
        var seriesOrder: [String] = []
        var usedIDs = Set<String>()
        /// The next suffix to try per base id: many entries with one address stay linear.
        var nextSuffix: [String: Int] = [:]
        var normalizedSeriesNames: [String: String] = [:]

        func uniqueID(_ base: String) -> String {
            var counter = nextSuffix[base] ?? 1
            var candidate = counter == 1 ? base : "\(base)-\(counter)"
            while !usedIDs.insert(candidate).inserted {
                counter += 1
                candidate = "\(base)-\(counter)"
            }
            nextSuffix[base] = counter + 1
            return candidate
        }

        var classifier = ContentClassifier()
        for entry in playlist.entries {
            let type = classifier.classify(entry)
            let headers = entry.headers.isEmpty ? nil : entry.headers
            switch type {
            case .live:
                let category = liveCategories.category(named: entry.groupTitle)
                let id = uniqueID("c" + StableHash.hex(entry.url.absoluteString))
                var catchup = catchupInfo(from: entry.attributes, url: entry.url) ?? defaultCatchup
                if catchup?.days == 0 { catchup = nil }
                channels.append(Channel(
                    id: id,
                    name: entry.title,
                    number: entry.channelNumber,
                    logoURL: entry.logo,
                    categoryID: category.id,
                    streamURL: entry.url,
                    epgID: entry.tvgID,
                    epgName: entry.tvgName,
                    catchup: catchup,
                    headers: headers,
                    xtreamStreamID: XtreamURLBuilder.streamID(fromLiveURL: entry.url)
                ))

            case .movie:
                let category = movieCategories.category(named: entry.groupTitle)
                let id = uniqueID("m" + StableHash.hex(entry.url.absoluteString))
                let (cleanName, year) = TitleParser.splitYear(entry.title)
                movies.append(Movie(
                    id: id,
                    name: cleanName,
                    posterURL: entry.logo,
                    categoryID: category.id,
                    streamURL: entry.url,
                    year: year,
                    headers: headers,
                    containerExtension: ContentClassifier.fileExtension(of: entry.url).nilIfBlank
                ))

            case .series:
                let category = seriesCategories.category(named: entry.groupTitle)
                let parsed = TitleParser.parseEpisode(entry.title)
                let seriesName = parsed?.seriesName ?? entry.tvgName ?? entry.title
                let normalizedName: String
                if let cached = normalizedSeriesNames[seriesName] {
                    normalizedName = cached
                } else {
                    normalizedName = TextNormalizer.normalize(seriesName)
                    normalizedSeriesNames[seriesName] = normalizedName
                }
                let key = category.id + "|" + normalizedName
                var series = seriesByKey[key] ?? {
                    let (cleanName, year) = TitleParser.splitYear(seriesName)
                    return Series(id: "s" + StableHash.hex(key), name: cleanName, coverURL: entry.logo, categoryID: category.id, year: year)
                }()
                if seriesByKey[key] == nil { seriesOrder.append(key) }
                let season = parsed?.season ?? 1
                let number = parsed?.episode ?? (series.episodes.filter { $0.season == season }.count + 1)
                series.episodes.append(Episode(
                    id: uniqueID("e" + StableHash.hex(entry.url.absoluteString)),
                    title: parsed?.episodeTitle ?? entry.title,
                    season: season,
                    number: number,
                    streamURL: entry.url,
                    posterURL: entry.logo,
                    headers: headers
                ))
                if series.coverURL == nil { series.coverURL = entry.logo }
                seriesByKey[key] = series
            }
        }

        let series: [Series] = seriesOrder.compactMap { key in
            guard var item = seriesByKey[key] else { return nil }
            item.episodes.sort { ($0.season, $0.number) < ($1.season, $1.number) }
            return item
        }

        return PlaylistContent(
            liveCategories: liveCategories.all,
            channels: channels,
            movieCategories: movieCategories.all,
            movies: movies,
            seriesCategories: seriesCategories.all,
            series: series,
            providerEPGURLs: playlist.epgURLs
        )
    }

    /// Reads `catchup` / `catchup-type`, `catchup-days` / `timeshift` / `tvg-rec` / `tv_archive_duration`, and
    /// `catchup-source`. A type without a number of days means a week.
    static func catchupInfo(from attributes: [String: String], url: URL?) -> CatchupInfo? {
        let typeValue = (attributes["catchup"] ?? attributes["catchup-type"])?.lowercased().nilIfBlank
        let daysValue = attributes["catchup-days"] ?? attributes["timeshift"] ?? attributes["tvg-rec"]
            ?? attributes["tv_archive_duration"]
        let source = attributes["catchup-source"]?.nilIfBlank

        guard typeValue != nil || daysValue != nil || source != nil else { return nil }
        let days = daysValue.flatMap { Int($0.trimmingCharacters(in: .whitespaces)) } ?? (typeValue != nil ? 7 : 0)

        let mode: CatchupInfo.Mode
        switch typeValue {
        case "append": mode = .append
        case "shift", "timeshift": mode = .shift
        case "flussonic", "flussonic-hls", "flussonic-ts", "fs": mode = .flussonic
        case "xc", "xtream": mode = .xtream
        case "default":
            mode = source == nil ? fallbackMode(for: url) : .default
        case nil:
            // Only `timeshift=` / `tvg-rec=`: the usual meaning is "shift", unless it is an Xtream URL.
            mode = source != nil ? .default : fallbackMode(for: url)
        default:
            mode = source != nil ? .default : fallbackMode(for: url)
        }
        return CatchupInfo(mode: mode, days: days, source: source)
    }

    private static func fallbackMode(for url: URL?) -> CatchupInfo.Mode {
        if let url, XtreamURLBuilder.parseLiveURL(url) != nil { return .xtream }
        return .shift
    }
}

/// Keeps categories in first-seen order.
struct OrderedCategories {
    let type: ContentType
    private(set) var all: [MediaCategory] = []
    private var byName: [String: MediaCategory] = [:]

    init(type: ContentType) { self.type = type }

    mutating func category(named rawName: String?) -> MediaCategory {
        // An English name of IPTVCore's own: the app shows it in its language (PlaceholderNames).
        let name = rawName?.nilIfBlank ?? "Uncategorized"
        if let existing = byName[name] { return existing }
        let category = MediaCategory(id: "\(type.rawValue)-" + StableHash.hex(name), name: name, type: type)
        byName[name] = category
        all.append(category)
        return category
    }
}

/// Decides whether an M3U entry is a live channel, a movie or a series episode.
/// Keeps a per-group cache: big playlists repeat the same few hundred groups.
public struct ContentClassifier {
    static let videoExtensions: Set<String> = ["mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg", "divx", "ogv"]
    static let streamExtensions: Set<String> = ["m3u8", "ts", "", "php", "mpd"]
    static let movieGroupKeywords = ["vod", "movie", "film", "cinema", "cinéma", "peliculas", "películas", "filme"]
    static let seriesGroupKeywords = ["series", "série", "serie", "tv show", "tvshow", "shows", "saison", "season", "temporada", "staffel"]

    private struct GroupHints {
        var series: Bool
        var movie: Bool
    }

    private var groupCache: [String: GroupHints] = [:]

    public init() {}

    public static func classify(_ entry: M3UEntry) -> ContentType {
        var classifier = ContentClassifier()
        return classifier.classify(entry)
    }

    private mutating func hints(for group: String?) -> GroupHints {
        let key = group ?? ""
        if let cached = groupCache[key] { return cached }
        let lower = key.lowercased()
        let hints = GroupHints(
            series: Self.seriesGroupKeywords.contains { lower.contains($0) },
            movie: Self.movieGroupKeywords.contains { lower.contains($0) }
        )
        groupCache[key] = hints
        return hints
    }

    public mutating func classify(_ entry: M3UEntry) -> ContentType {
        // Explicit hints.
        if let type = (entry.attributes["tvg-type"] ?? entry.attributes["type"])?.lowercased() {
            if type.contains("movie") || type == "vod" { return .movie }
            if type.contains("series") { return .series }
            if type.contains("live") { return .live }
        }

        // Xtream style paths.
        let urlString = entry.url.absoluteString
        if Self.contains(urlString, "/series/") { return .series }
        if Self.contains(urlString, "/movie/") || Self.contains(urlString, "/movies/") { return .movie }
        if Self.contains(urlString, "/live/") { return .live }

        let ext = Self.fileExtension(of: entry.url)
        let group = hints(for: entry.groupTitle)

        if Self.videoExtensions.contains(ext) {
            if group.series || TitleParser.parseEpisode(entry.title) != nil { return .series }
            return .movie
        }
        // Streams (m3u8/ts/extensionless): live unless the group clearly says otherwise.
        if Self.streamExtensions.contains(ext) {
            if group.series && TitleParser.parseEpisode(entry.title) != nil { return .series }
            if group.movie && entry.duration > 0 { return .movie }
            return .live
        }
        if group.series && TitleParser.parseEpisode(entry.title) != nil { return .series }
        if group.movie { return .movie }
        return .live
    }

    /// Lowercased extension of the URL path, computed without Foundation path APIs (hot path).
    public static func fileExtension(of url: URL) -> String {
        let utf8 = Array(url.absoluteString.utf8)
        var end = utf8.count
        if let query = utf8.firstIndex(where: { $0 == 0x3F || $0 == 0x23 }) { end = query } // ? or #
        // Start at the path: the host's name is not an extension ("http://example.com" has none).
        var pathStart = -1
        if let colon = utf8[..<end].firstIndex(of: 0x3A), colon + 2 < end, utf8[colon + 1] == 0x2F, utf8[colon + 2] == 0x2F {
            guard let slash = utf8[(colon + 3)..<end].firstIndex(of: 0x2F) else { return "" }
            pathStart = slash
        }
        var index = end - 1
        while index > pathStart {
            let c = utf8[index]
            if c == 0x2E { // '.'
                let ext = utf8[(index + 1)..<end]
                guard ext.count <= 5 else { return "" }
                return String(decoding: ext, as: UTF8.self).lowercased()
            }
            if c == 0x2F { return "" } // '/'
            index -= 1
        }
        return ""
    }

    /// Byte substring search (Foundation's `contains` is slow on Linux).
    static func contains(_ haystack: String, _ needle: StaticString) -> Bool {
        let n = needle.utf8CodeUnitCount
        let p = needle.utf8Start
        var copy = haystack
        return copy.withUTF8 { h in
            guard h.count >= n else { return false }
            var i = 0
            while i <= h.count - n {
                if h[i] == p[0] {
                    var j = 1
                    while j < n && h[i + j] == p[j] { j += 1 }
                    if j == n { return true }
                }
                i += 1
            }
            return false
        }
    }
}

public enum TitleParser {
    public struct EpisodeInfo: Hashable, Sendable {
        public var seriesName: String
        public var season: Int
        public var episode: Int
        public var episodeTitle: String
    }

    // "Name S01E02", "Name S01 E02", "Name - S1.E2 - Title", "Name 1x02"
    // Literal patterns: compiling them cannot fail at runtime.
    // swiftlint:disable force_try
    private static let seasonEpisode = #"^(.*?)[\s._\-|:]*\bS(\d{1,2})[\s._\-]*E(\d{1,4})\b(.*)$"#
    private static let crossNotation = #"^(.*?)[\s._\-|:]*\b(\d{1,2})x(\d{1,3})\b(.*)$"#
    private static let spelledOut = #"^(.*?)[\s._\-|:]*\b(?:Saison|Season|Temporada|Staffel)\s*(\d{1,2})[\s._\-,]*"#
        + #"(?:[EÉ]pisode|Episode|Ep\.?|Folge|Capitulo)\s*(\d{1,4})\b(.*)$"#
    private static let patterns: [NSRegularExpression] = [seasonEpisode, crossNotation, spelledOut].map {
        try! NSRegularExpression(pattern: $0, options: [.caseInsensitive])
    }
    // swiftlint:enable force_try

    public static func parseEpisode(_ title: String) -> EpisodeInfo? {
        // Cheap pre-check before running regexes (hot path on big playlists).
        guard mayContainEpisodeMarker(title) else { return nil }
        let range = NSRange(title.startIndex..., in: title)
        for regex in patterns {
            guard let match = regex.firstMatch(in: title, range: range), match.numberOfRanges >= 5,
                  let nameRange = Range(match.range(at: 1), in: title),
                  let seasonRange = Range(match.range(at: 2), in: title),
                  let episodeRange = Range(match.range(at: 3), in: title),
                  let season = Int(title[seasonRange]),
                  let episode = Int(title[episodeRange])
            else { continue }
            let name = cleanSeriesName(String(title[nameRange]))
            guard !name.isEmpty else { continue }
            var rest = ""
            if let restRange = Range(match.range(at: 4), in: title) {
                // Strip leading separators only: "... - Down the Drain..." keeps its ellipsis.
                let separators = CharacterSet(charactersIn: " -–|:._")
                rest = String(String(title[restRange]).unicodeScalars.drop(while: { separators.contains($0) }))
                    .trimmingCharacters(in: .whitespaces)
            }
            // An English name of IPTVCore's own: the app shows it in its language (PlaceholderNames).
            let episodeTitle = rest.isEmpty
                ? "Episode \(episode)"
                : rest
            return EpisodeInfo(seriesName: name, season: season, episode: episode, episodeTitle: episodeTitle)
        }
        return nil
    }

    /// True when the title has `S<digit>`, `<digit>x<digit>` or a season word.
    static func mayContainEpisodeMarker(_ title: String) -> Bool {
        let bytes = Array(title.utf8)
        func isDigit(_ b: UInt8) -> Bool { b >= 0x30 && b <= 0x39 }
        for i in 0..<bytes.count {
            let b = bytes[i] | 0x20 // ASCII lowercase
            if b == 0x73, i + 1 < bytes.count, isDigit(bytes[i + 1]) { return true } // s1
            if b == 0x78, i > 0, i + 1 < bytes.count, isDigit(bytes[i - 1]), isDigit(bytes[i + 1]) { return true } // 1x2
        }
        let lower = title.lowercased()
        return ["season", "saison", "temporada", "staffel"].contains { lower.contains($0) }
    }

    private static func cleanSeriesName(_ name: String) -> String {
        name.trimmingCharacters(in: CharacterSet(charactersIn: " -–|:._([")).trimmingCharacters(in: .whitespaces)
    }

    // swiftlint:disable:next force_try
    private static let yearRegex = try! NSRegularExpression(pattern: #"^(.*?)[\s\-_.]*[\(\[]?((?:19|20)\d{2})[\)\]]?\s*$"#)

    /// "Lighthouse (2010)" -> ("Lighthouse", 2010)
    public static func splitYear(_ title: String) -> (String, Int?) {
        guard title.utf8.count >= 4, title.utf8.contains(where: { $0 >= 0x30 && $0 <= 0x39 }) else { return (title, nil) }
        let range = NSRange(title.startIndex..., in: title)
        guard let match = yearRegex.firstMatch(in: title, range: range),
              let nameRange = Range(match.range(at: 1), in: title),
              let yearRange = Range(match.range(at: 2), in: title),
              let year = Int(title[yearRange])
        else { return (title, nil) }
        let name = String(title[nameRange]).trimmingCharacters(in: .whitespaces)
        return name.isEmpty ? (title, year) : (name, year)
    }
}
