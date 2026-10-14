import Foundation

/// A group of channels / movies / series ("group-title" in M3U, category in Xtream).
public struct MediaCategory: Identifiable, Codable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var type: ContentType

    public init(id: String, name: String, type: ContentType) {
        self.id = id
        self.name = name
        self.type = type
    }

    /// Heuristic used by parental control to lock adult groups by default.
    public var looksAdult: Bool { AdultContentDetector.isAdult(name) }
}

/// How past programmes of a live channel can be replayed.
public struct CatchupInfo: Codable, Hashable, Sendable {
    public enum Mode: String, Codable, Hashable, Sendable {
        /// `catchup-source` is a full URL template.
        case `default`
        /// `catchup-source` is appended to the live URL.
        case append
        /// `?utc={start}&lutc={now}` appended to the live URL (the `utc` / `lutc` convention).
        case shift
        /// Flussonic `timeshift_abs` / `index-{start}-{duration}` URLs.
        case flussonic
        /// Xtream Codes `/timeshift/` URLs.
        case xtream
    }

    public var mode: Mode
    /// How many days back the provider keeps the archive.
    public var days: Int
    public var source: String?

    public init(mode: Mode, days: Int, source: String? = nil) {
        self.mode = mode
        self.days = days
        self.source = source
    }
}

public struct Channel: Identifiable, Codable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var number: Int?
    public var logoURL: URL?
    public var categoryID: String
    public var streamURL: URL
    /// `tvg-id` / Xtream `epg_channel_id`.
    public var epgID: String?
    /// `tvg-name`, sometimes a better key for guide matching than the display name.
    public var epgName: String?
    public var catchup: CatchupInfo?
    public var headers: HTTPHeaders?
    public var xtreamStreamID: Int?

    public init(
        id: String,
        name: String,
        number: Int? = nil,
        logoURL: URL? = nil,
        categoryID: String,
        streamURL: URL,
        epgID: String? = nil,
        epgName: String? = nil,
        catchup: CatchupInfo? = nil,
        headers: HTTPHeaders? = nil,
        xtreamStreamID: Int? = nil
    ) {
        self.id = id
        self.name = name
        self.number = number
        self.logoURL = logoURL
        self.categoryID = categoryID
        self.streamURL = streamURL
        self.epgID = epgID
        self.epgName = epgName
        self.catchup = catchup
        self.headers = headers
        self.xtreamStreamID = xtreamStreamID
    }

    public var hasCatchup: Bool { (catchup?.days ?? 0) > 0 }
}

public struct Movie: Identifiable, Codable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var posterURL: URL?
    public var categoryID: String
    public var streamURL: URL
    public var rating: Double?
    public var year: Int?
    public var addedAt: Date?
    public var plot: String?
    public var headers: HTTPHeaders?
    public var xtreamStreamID: Int?
    public var containerExtension: String?

    public init(
        id: String,
        name: String,
        posterURL: URL? = nil,
        categoryID: String,
        streamURL: URL,
        rating: Double? = nil,
        year: Int? = nil,
        addedAt: Date? = nil,
        plot: String? = nil,
        headers: HTTPHeaders? = nil,
        xtreamStreamID: Int? = nil,
        containerExtension: String? = nil
    ) {
        self.id = id
        self.name = name
        self.posterURL = posterURL
        self.categoryID = categoryID
        self.streamURL = streamURL
        self.rating = rating
        self.year = year
        self.addedAt = addedAt
        self.plot = plot
        self.headers = headers
        self.xtreamStreamID = xtreamStreamID
        self.containerExtension = containerExtension
    }
}

public struct Episode: Identifiable, Codable, Hashable, Sendable {
    public var id: String
    public var title: String
    public var season: Int
    public var number: Int
    public var streamURL: URL
    public var posterURL: URL?
    public var plot: String?
    public var durationSeconds: Int?
    public var releaseDate: String?
    public var rating: Double?
    public var headers: HTTPHeaders?

    public init(
        id: String,
        title: String,
        season: Int,
        number: Int,
        streamURL: URL,
        posterURL: URL? = nil,
        plot: String? = nil,
        durationSeconds: Int? = nil,
        releaseDate: String? = nil,
        rating: Double? = nil,
        headers: HTTPHeaders? = nil
    ) {
        self.id = id
        self.title = title
        self.season = season
        self.number = number
        self.streamURL = streamURL
        self.posterURL = posterURL
        self.plot = plot
        self.durationSeconds = durationSeconds
        self.releaseDate = releaseDate
        self.rating = rating
        self.headers = headers
    }

    /// "S01E05"
    public var code: String {
        "S" + String(format: "%02d", season) + "E" + String(format: "%02d", number)
    }
}

public struct Series: Identifiable, Codable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var coverURL: URL?
    public var categoryID: String
    public var plot: String?
    public var rating: Double?
    public var year: Int?
    public var genre: String?
    public var addedAt: Date?
    public var xtreamSeriesID: Int?
    /// Episodes are known up-front for M3U playlists; Xtream series load them on demand.
    public var episodes: [Episode]

    public init(
        id: String,
        name: String,
        coverURL: URL? = nil,
        categoryID: String,
        plot: String? = nil,
        rating: Double? = nil,
        year: Int? = nil,
        genre: String? = nil,
        addedAt: Date? = nil,
        xtreamSeriesID: Int? = nil,
        episodes: [Episode] = []
    ) {
        self.id = id
        self.name = name
        self.coverURL = coverURL
        self.categoryID = categoryID
        self.plot = plot
        self.rating = rating
        self.year = year
        self.genre = genre
        self.addedAt = addedAt
        self.xtreamSeriesID = xtreamSeriesID
        self.episodes = episodes
    }
}

public struct Season: Identifiable, Codable, Hashable, Sendable {
    public var id: Int { number }
    public var number: Int
    public var name: String
    public var coverURL: URL?
    public var overview: String?
    public var episodes: [Episode]

    public init(number: Int, name: String, coverURL: URL? = nil, overview: String? = nil, episodes: [Episode]) {
        self.number = number
        self.name = name
        self.coverURL = coverURL
        self.overview = overview
        self.episodes = episodes
    }
}

/// Rich metadata shown on the movie / series detail screens.
public struct MediaDetails: Codable, Hashable, Sendable {
    public var title: String
    public var originalTitle: String?
    public var plot: String?
    public var cast: String?
    public var director: String?
    public var genre: String?
    public var releaseDate: String?
    public var durationSeconds: Int?
    public var rating: Double?
    public var posterURL: URL?
    public var backdropURL: URL?
    public var trailerYouTubeID: String?
    public var tmdbID: String?

    public init(
        title: String,
        originalTitle: String? = nil,
        plot: String? = nil,
        cast: String? = nil,
        director: String? = nil,
        genre: String? = nil,
        releaseDate: String? = nil,
        durationSeconds: Int? = nil,
        rating: Double? = nil,
        posterURL: URL? = nil,
        backdropURL: URL? = nil,
        trailerYouTubeID: String? = nil,
        tmdbID: String? = nil
    ) {
        self.title = title
        self.originalTitle = originalTitle
        self.plot = plot
        self.cast = cast
        self.director = director
        self.genre = genre
        self.releaseDate = releaseDate
        self.durationSeconds = durationSeconds
        self.rating = rating
        self.posterURL = posterURL
        self.backdropURL = backdropURL
        self.trailerYouTubeID = trailerYouTubeID
        self.tmdbID = tmdbID
    }
}

public struct SeriesDetails: Codable, Hashable, Sendable {
    public var info: MediaDetails
    public var seasons: [Season]

    public init(info: MediaDetails, seasons: [Season]) {
        self.info = info
        self.seasons = seasons
    }
}

/// The user's changes to a playlist channel.
public struct ChannelEdit: Codable, Hashable, Sendable {
    public var name: String?
    public var number: Int?

    public init(name: String? = nil, number: Int? = nil) {
        self.name = name
        self.number = number
    }

    public var isEmpty: Bool { name == nil && number == nil }
}

/// Everything a playlist contains, as cached on disk.
public struct PlaylistContent: Codable, Hashable, Sendable {
    public var liveCategories: [MediaCategory]
    public var channels: [Channel]
    public var movieCategories: [MediaCategory]
    public var movies: [Movie]
    public var seriesCategories: [MediaCategory]
    public var series: [Series]
    /// Guide URLs advertised by the source (M3U `url-tvg`, Xtream `xmltv.php`).
    public var providerEPGURLs: [URL]
    public var account: XtreamAccountInfo?
    public var fetchedAt: Date

    public init(
        liveCategories: [MediaCategory] = [],
        channels: [Channel] = [],
        movieCategories: [MediaCategory] = [],
        movies: [Movie] = [],
        seriesCategories: [MediaCategory] = [],
        series: [Series] = [],
        providerEPGURLs: [URL] = [],
        account: XtreamAccountInfo? = nil,
        fetchedAt: Date = Date()
    ) {
        self.liveCategories = liveCategories
        self.channels = channels
        self.movieCategories = movieCategories
        self.movies = movies
        self.seriesCategories = seriesCategories
        self.series = series
        self.providerEPGURLs = providerEPGURLs
        self.account = account
        self.fetchedAt = fetchedAt
    }

    public static let empty = PlaylistContent()

    public var isEmpty: Bool { channels.isEmpty && movies.isEmpty && series.isEmpty }

    public func categories(for type: ContentType) -> [MediaCategory] {
        switch type {
        case .live: return liveCategories
        case .movie: return movieCategories
        case .series: return seriesCategories
        }
    }

    public func count(for type: ContentType) -> Int {
        switch type {
        case .live: return channels.count
        case .movie: return movies.count
        case .series: return series.count
        }
    }
}

public enum AdultContentDetector {
    private static let keywords = ["xxx", "adult", "adulte", "18+", "+18", "porn", "erotic", "érotique", "for adults"]

    public static func isAdult(_ text: String) -> Bool {
        let lower = text.lowercased()
        return keywords.contains { lower.contains($0) }
    }
}

extension PlaylistContent {
    /// Channels renamed / renumbered by the user. A renamed channel keeps its original name for guide matching.
    public func applying(_ edits: [String: ChannelEdit]) -> PlaylistContent {
        guard !edits.isEmpty else { return self }
        var copy = self
        for index in copy.channels.indices {
            guard let edit = edits[copy.channels[index].id] else { continue }
            if let name = edit.name, !name.isEmpty, name != copy.channels[index].name {
                copy.channels[index].epgName = copy.channels[index].epgName ?? copy.channels[index].name
                copy.channels[index].name = name
            }
            if let number = edit.number {
                copy.channels[index].number = number
            }
        }
        return copy
    }
}
