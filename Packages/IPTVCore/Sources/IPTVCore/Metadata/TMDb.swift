import Foundation

/// One entry of TMDb's trending list.
public struct TMDbItem: Decodable, Hashable, Sendable {
    public enum MediaType: String, Decodable, Sendable {
        case movie
        case tv
        case person
        case unknown

        public init(from decoder: Decoder) throws {
            self = MediaType(rawValue: (try? decoder.singleValueContainer().decode(String.self)) ?? "") ?? .unknown
        }
    }

    public var id: Int
    public var mediaType: MediaType
    public var title: String
    public var originalTitle: String?
    public var posterPath: String?
    public var backdropPath: String?
    public var overview: String?
    public var year: Int?
    public var voteAverage: Double?
    /// How many TMDb users rated it.
    public var voteCount: Int?

    enum CodingKeys: String, CodingKey {
        case id, title, name, overview
        case mediaType = "media_type"
        case originalTitle = "original_title"
        case originalName = "original_name"
        case posterPath = "poster_path"
        case backdropPath = "backdrop_path"
        case releaseDate = "release_date"
        case firstAirDate = "first_air_date"
        case voteAverage = "vote_average"
        case voteCount = "vote_count"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        mediaType = (try? c.decodeIfPresent(MediaType.self, forKey: .mediaType)) ?? .unknown
        title = (try? c.decodeIfPresent(String.self, forKey: .title)) ?? (try? c.decodeIfPresent(String.self, forKey: .name)) ?? ""
        originalTitle = (try? c.decodeIfPresent(String.self, forKey: .originalTitle)) ?? (try? c.decodeIfPresent(String.self, forKey: .originalName))
        posterPath = try? c.decodeIfPresent(String.self, forKey: .posterPath)
        backdropPath = try? c.decodeIfPresent(String.self, forKey: .backdropPath)
        overview = try? c.decodeIfPresent(String.self, forKey: .overview)
        let date = (try? c.decodeIfPresent(String.self, forKey: .releaseDate)) ?? (try? c.decodeIfPresent(String.self, forKey: .firstAirDate))
        year = date.flatMap { Int($0.prefix(4)) }
        voteAverage = try? c.decodeIfPresent(Double.self, forKey: .voteAverage)
        voteCount = try? c.decodeIfPresent(Int.self, forKey: .voteCount)
    }

    public var posterURL: URL? {
        posterPath.flatMap { URL(string: "https://image.tmdb.org/t/p/w342" + $0) }
    }

    public var backdropURL: URL? {
        backdropPath.flatMap { URL(string: "https://image.tmdb.org/t/p/w1280" + $0) }
    }
}

/// Minimal TMDb client. Accepts a v3 API key or a v4 read access token.
public struct TMDbClient: Sendable {
    public let credential: String
    public let http: HTTPClient
    public var language: String
    /// Spaces the requests out (the background prefetch); nil sends them at once.
    public var pacer: RequestPacer?

    public init(credential: String, http: HTTPClient, language: String = "en-US", pacer: RequestPacer? = nil) {
        self.credential = credential.trimmingCharacters(in: .whitespacesAndNewlines)
        self.http = http
        self.language = language
        self.pacer = pacer
    }

    /// The viewer's language as TMDb names a video's: "fr" for "fr-FR".
    var videoLanguage: String {
        String(language.split(separator: "-").first ?? "en").lowercased()
    }

    private var usesBearerToken: Bool { credential.count > 40 }

    /// GET on the v3 API: the key goes in the query, a v4 token in the Authorization header (never in the address).
    private func get(_ path: String, query: [URLQueryItem] = []) async throws -> Data {
        var components = URLComponents(string: "https://api.themoviedb.org/3/" + path)!
        var items = query + [URLQueryItem(name: "language", value: language)]
        if !usesBearerToken { items.append(URLQueryItem(name: "api_key", value: credential)) }
        components.queryItems = items
        let headers = usesBearerToken ? ["Authorization": "Bearer \(credential)", "Accept": "application/json"] : ["Accept": "application/json"]
        try await pacer?.wait()
        return try await http.data(from: components.url!, headers: headers)
    }

    /// Asks TMDb whether it accepts the key or token (`/authentication`), before it is saved: false when TMDb
    /// refuses it (401); a network failure, or an answer that is not TMDb's (`{"success": …}`), throws.
    public func isAccepted() async throws -> Bool {
        do {
            let data = try await get("authentication")
            struct Answer: Decodable { var success: Bool }
            return try JSONDecoder().decodeCheckingKeys(Answer.self, from: data).success
        } catch HTTPClientError.badStatus(let status) where status == 401 {
            return false
        }
    }

    public func trending(window: String = "week") async throws -> [TMDbItem] {
        let data = try await get("trending/all/\(window)")
        struct Page: Decodable { var results: [TMDbItem] }
        return try JSONDecoder().decodeCheckingKeys(Page.self, from: data).results.filter { $0.mediaType == .movie || $0.mediaType == .tv }
    }

    /// The backdrop TMDb has for a movie or a series whose provider gave none that loads: by its TMDb id when the
    /// provider gave one, otherwise the search result with the same title (and a year one apart at most, when
    /// known). Nil rather than another title's picture.
    public func backdropURL(of type: ContentType, tmdbID: String?, title: String, year: Int?) async throws -> URL? {
        let kind = type == .series ? "tv" : "movie"
        if let id = tmdbID.flatMap({ Int($0.trimmingCharacters(in: .whitespaces)) }), let data = try? await get("\(kind)/\(id)"),
           let url = (try? JSONDecoder().decodeCheckingKeys(TMDbItem.self, from: data))?.backdropURL {
            return url
        }
        return try await sameTitle(kind: kind, title: title, year: year).lazy.compactMap(\.backdropURL).first
    }

    /// The search results with the same title as `title` (its words, `CatalogueMatcher`), and a year one apart at most
    /// when both are known, in TMDb's order.
    private func sameTitle(kind: String, title: String, year: Int?) async throws -> [TMDbItem] {
        let key = CatalogueMatcher.key(title)
        guard !key.isEmpty else { return [] }
        let data = try await get("search/\(kind)", query: [URLQueryItem(name: "query", value: CatalogueMatcher.searchQuery(title))])
        struct Page: Decodable { var results: [TMDbItem] }
        return try JSONDecoder().decodeCheckingKeys(Page.self, from: data).results.filter { item in
            guard [item.title, item.originalTitle].compactMap({ $0 }).map(CatalogueMatcher.key).contains(key) else { return false }
            guard let year, let released = item.year else { return true }
            return CatalogueMatcher.closeYears(year, released)
        }
    }

    /// What TMDb knows of a movie or a series for its page: backdrop, votes, trailer, directors and cast. By the TMDb id
    /// the provider gave, else the first search result with the same title (and a year one apart at most, when known).
    /// Nil when TMDb answered but has no such title (never another title's); throws when TMDb could not be asked.
    public func titleInfo(of type: ContentType, tmdbID: String?, title: String, year: Int?) async throws -> TMDbTitleInfo? {
        let kind = type == .series ? "tv" : "movie"
        if let id = tmdbID.flatMap({ Int($0.trimmingCharacters(in: .whitespaces)) }), id > 0 {
            do {
                return try await titleInfo(kind: kind, id: id)
            } catch HTTPClientError.badStatus(404) {
                // An id TMDb does not know (a panel's mistake): by title.
            }
        }
        guard let found = try await sameTitle(kind: kind, title: title, year: year).first else { return nil }
        return try await titleInfo(kind: kind, id: found.id)
    }

    private func titleInfo(kind: String, id: Int) async throws -> TMDbTitleInfo {
        let series = kind == "tv"
        // A series' `credits` are only its latest season's: its `aggregate_credits` have the cast of every season (a
        // page of an earlier one names them).
        let data = try await get("\(kind)/\(id)", query: [URLQueryItem(name: "append_to_response", value: series ? "aggregate_credits" : "credits")])
        let details = try JSONDecoder().decodeCheckingKeys(TMDbTitleDetails.self, from: data)
        let trailer = try await trailerKey(kind: kind, id: id)
        let credits = series ? details.aggregateCredits : details.credits
        // A series' makers are its creators (`created_by`): the episodes' directors are not the series'.
        var directors = series ? (details.createdBy ?? []) : []
        if directors.isEmpty { directors = (credits?.crew ?? []).filter { $0.job == "Director" } }
        let cast = (credits?.cast ?? []).enumerated()
            .sorted { ($0.element.order ?? Int.max, $0.offset) < ($1.element.order ?? Int.max, $1.offset) }
            .map(\.element)
        return TMDbTitleInfo(
            tmdbID: details.id,
            backdropURL: details.backdropPath.flatMap { URL(string: "https://image.tmdb.org/t/p/w1280" + $0) },
            voteAverage: details.voteAverage,
            voteCount: details.voteCount,
            trailerKey: trailer,
            directors: TMDbCredit.unique(directors),
            cast: Array(TMDbCredit.unique(cast).prefix(series ? TMDbTitleInfo.seriesCastLimit : TMDbTitleInfo.castLimit))
        )
    }

    /// The episodes TMDb knows of season `season` of series `seriesID` (`/tv/{id}/season/{n}`): their number, name,
    /// still and overview, in the viewer's language. One request for a whole season. Empty when TMDb has no such
    /// season (404).
    public func seasonEpisodes(seriesID: Int, season: Int) async throws -> [TMDbEpisode] {
        let data: Data
        do {
            data = try await get("tv/\(seriesID)/season/\(season)")
        } catch HTTPClientError.badStatus(404) {
            return []
        }
        // An episode without its number is left out, not the season.
        struct Entry: Decodable {
            var episode: TMDbEpisode?
            init(from decoder: Decoder) throws { episode = try? TMDbEpisode(from: decoder) }
        }
        struct Page: Decodable { var episodes: [Entry] }
        var seen = Set<Int>()
        return try JSONDecoder().decodeCheckingKeys(Page.self, from: data).episodes.compactMap(\.episode).filter { seen.insert($0.number).inserted }
    }

    /// The YouTube key of the trailer TMDb lists for a title (`TMDbVideo.best`), among its videos in the viewer's
    /// language, in English or in none (`include_video_language`).
    private func trailerKey(kind: String, id: Int) async throws -> String? {
        let language = videoLanguage
        let data = try await get("\(kind)/\(id)/videos", query: [URLQueryItem(name: "include_video_language", value: "\(language),en,null")])
        struct Page: Decodable { var results: [TMDbVideo]? }
        let videos = try JSONDecoder().decodeCheckingKeys(Page.self, from: data).results ?? []
        return TMDbVideo.best(videos, language: language)?.key
    }

    /// The people TMDb finds for a search (actors, directors…), the most popular first.
    public func people(matching query: String) async throws -> [TMDbPerson] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        let data = try await get("search/person", query: [URLQueryItem(name: "query", value: trimmed)])
        struct Page: Decodable { var results: [TMDbPerson] }
        return try JSONDecoder().decodeCheckingKeys(Page.self, from: data).results.sorted { $0.popularity > $1.popularity }
    }

    /// The person TMDb knows best by this name (its most popular match), if any.
    public func person(named name: String) async throws -> TMDbPerson? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let data = try await get("search/person", query: [URLQueryItem(name: "query", value: trimmed)])
        struct Page: Decodable { var results: [TMDbPerson] }
        return try JSONDecoder().decodeCheckingKeys(Page.self, from: data).results.max { $0.popularity < $1.popularity }
    }

    /// Movies and series a person played in or worked on (directing, writing…), newest first, each once.
    public func credits(ofPerson id: Int) async throws -> [TMDbItem] {
        let data = try await get("person/\(id)/combined_credits")
        struct Credits: Decodable {
            var cast: [TMDbItem]?
            var crew: [TMDbItem]?
        }
        let credits = try JSONDecoder().decodeCheckingKeys(Credits.self, from: data)
        var seen = Set<String>()
        return ((credits.cast ?? []) + (credits.crew ?? []))
            .filter { ($0.mediaType == .movie || $0.mediaType == .tv) && seen.insert("\($0.mediaType.rawValue)-\($0.id)").inserted }
            .sorted { ($0.year ?? Int.min) > ($1.year ?? Int.min) }
    }
}

/// A person on TMDb (actor, director…).
public struct TMDbPerson: Decodable, Hashable, Sendable {
    public var id: Int
    public var name: String
    /// "Acting", "Directing"…
    public var department: String?
    public var profilePath: String?
    public var popularity: Double

    enum CodingKeys: String, CodingKey {
        case id, name, popularity
        case department = "known_for_department"
        case profilePath = "profile_path"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? ""
        department = try? c.decodeIfPresent(String.self, forKey: .department)
        profilePath = try? c.decodeIfPresent(String.self, forKey: .profilePath)
        popularity = (try? c.decodeIfPresent(Double.self, forKey: .popularity)) ?? 0
    }

    public var profileURL: URL? {
        profilePath.flatMap { URL(string: "https://image.tmdb.org/t/p/w185" + $0) }
    }
}

/// What TMDb knows of a movie or a series, for its page (`TMDbClient.titleInfo`).
public struct TMDbTitleInfo: Hashable, Sendable {
    /// The cast kept for a title: the first billed.
    public static let castLimit = 20
    /// A series' cast is every season's (`aggregate_credits`): more of it is kept, so that the actors a provider names
    /// for an earlier season find their photos.
    public static let seriesCastLimit = 60

    public var tmdbID: Int
    public var backdropURL: URL?
    public var voteAverage: Double?
    public var voteCount: Int?
    /// The YouTube key of its trailer.
    public var trailerKey: String?
    public var directors: [TMDbCredit]
    public var cast: [TMDbCredit]

    public init(tmdbID: Int, backdropURL: URL? = nil, voteAverage: Double? = nil, voteCount: Int? = nil, trailerKey: String? = nil,
                directors: [TMDbCredit] = [], cast: [TMDbCredit] = []) {
        self.tmdbID = tmdbID
        self.backdropURL = backdropURL
        self.voteAverage = voteAverage
        self.voteCount = voteCount
        self.trailerKey = trailerKey
        self.directors = directors
        self.cast = cast
    }
}

/// A person in a title's credits on TMDb (cast, crew, a series' creators).
public struct TMDbCredit: Decodable, Hashable, Sendable {
    public var id: Int
    public var name: String
    public var profilePath: String?
    /// "Director", "Writer"… for the crew.
    public var job: String?
    /// The billing order, for the cast.
    public var order: Int?

    enum CodingKeys: String, CodingKey {
        case id, name, job, jobs, order
        case profilePath = "profile_path"
    }

    public init(id: Int, name: String, profilePath: String? = nil, job: String? = nil, order: Int? = nil) {
        self.id = id
        self.name = name
        self.profilePath = profilePath
        self.job = job
        self.order = order
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? ""
        profilePath = try? c.decodeIfPresent(String.self, forKey: .profilePath)
        // A series' crew (`aggregate_credits`) names its jobs in a list: "Director" when one of them is.
        struct Job: Decodable { var job: String? }
        let jobs = ((try? c.decodeIfPresent([Job].self, forKey: .jobs)) ?? nil)?.compactMap(\.job) ?? []
        job = (try? c.decodeIfPresent(String.self, forKey: .job)) ?? (jobs.contains("Director") ? "Director" : jobs.first)
        order = try? c.decodeIfPresent(Int.self, forKey: .order)
    }

    public var profileURL: URL? {
        profilePath.flatMap { URL(string: "https://image.tmdb.org/t/p/w185" + $0) }
    }

    /// Each person once (an actor credited twice), without nameless entries, in the order given.
    static func unique(_ credits: [TMDbCredit]) -> [TMDbCredit] {
        var seen = Set<Int>()
        return credits.filter { !$0.name.trimmingCharacters(in: .whitespaces).isEmpty && seen.insert($0.id).inserted }
    }
}

/// A title's page on TMDb with its credits (`append_to_response=credits`).
struct TMDbTitleDetails: Decodable {
    struct Credits: Decodable {
        var cast: [TMDbCredit]?
        var crew: [TMDbCredit]?
    }

    var id: Int
    var backdropPath: String?
    var voteAverage: Double?
    var voteCount: Int?
    var credits: Credits?
    /// A series' cast and crew of every season.
    var aggregateCredits: Credits?
    /// A series' creators: its directors.
    var createdBy: [TMDbCredit]?

    enum CodingKeys: String, CodingKey {
        case id, credits
        case aggregateCredits = "aggregate_credits"
        case backdropPath = "backdrop_path"
        case voteAverage = "vote_average"
        case voteCount = "vote_count"
        case createdBy = "created_by"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        backdropPath = try? c.decodeIfPresent(String.self, forKey: .backdropPath)
        voteAverage = try? c.decodeIfPresent(Double.self, forKey: .voteAverage)
        voteCount = try? c.decodeIfPresent(Int.self, forKey: .voteCount)
        credits = try? c.decodeIfPresent(Credits.self, forKey: .credits)
        aggregateCredits = try? c.decodeIfPresent(Credits.self, forKey: .aggregateCredits)
        createdBy = try? c.decodeIfPresent([TMDbCredit].self, forKey: .createdBy)
    }
}

/// An episode on TMDb (`TMDbClient.seasonEpisodes`): its number in its season, its name, still and overview.
public struct TMDbEpisode: Decodable, Hashable, Sendable {
    public var number: Int
    public var name: String?
    public var stillPath: String?
    public var overview: String?

    enum CodingKeys: String, CodingKey {
        case name, overview
        case number = "episode_number"
        case stillPath = "still_path"
    }

    public init(number: Int, name: String? = nil, stillPath: String? = nil, overview: String? = nil) {
        self.number = number
        self.name = name
        self.stillPath = stillPath
        self.overview = overview
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        number = try c.decode(Int.self, forKey: .number)
        func text(_ key: CodingKeys) -> String? {
            ((try? c.decodeIfPresent(String.self, forKey: key)) ?? nil).flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        }
        name = text(.name)
        stillPath = text(.stillPath)
        overview = text(.overview)
    }

    public var stillURL: URL? {
        stillPath.flatMap { URL(string: "https://image.tmdb.org/t/p/w300" + $0) }
    }
}

/// A video TMDb lists for a movie or a series (trailers, teasers, clips…).
public struct TMDbVideo: Decodable, Hashable, Sendable {
    public var key: String
    public var site: String
    public var type: String
    public var official: Bool
    /// ISO 639-1 language of the video, when TMDb gives one.
    public var language: String?

    enum CodingKeys: String, CodingKey {
        case key, site, type, official
        case language = "iso_639_1"
    }

    public init(key: String, site: String, type: String, official: Bool = false, language: String? = nil) {
        self.key = key
        self.site = site
        self.type = type
        self.official = official
        self.language = language
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = (try? c.decodeIfPresent(String.self, forKey: .key)) ?? ""
        site = (try? c.decodeIfPresent(String.self, forKey: .site)) ?? ""
        type = (try? c.decodeIfPresent(String.self, forKey: .type)) ?? ""
        official = (try? c.decodeIfPresent(Bool.self, forKey: .official)) ?? false
        language = (try? c.decodeIfPresent(String.self, forKey: .language))?.lowercased()
    }

    /// The trailer to show: YouTube only, a trailer before a teaser, in the viewer's `language` before English before
    /// the others, an official one first; TMDb's order otherwise. Nil when there is no trailer or teaser on YouTube.
    public static func best(_ videos: [TMDbVideo], language: String) -> TMDbVideo? {
        let viewer = language.lowercased()
        func rank(_ video: TMDbVideo) -> (Int, Int, Int) {
            (video.type == "Trailer" ? 0 : 1, video.language == viewer ? 0 : video.language == "en" ? 1 : 2, video.official ? 0 : 1)
        }
        let candidates = videos.filter { video in
            video.site.caseInsensitiveCompare("YouTube") == .orderedSame && ["Trailer", "Teaser"].contains(video.type)
                && isYouTubeKey(video.key)
        }
        var best: TMDbVideo?
        for video in candidates where best.map({ rank(video) < rank($0) }) ?? true {
            best = video
        }
        return best
    }

    /// A YouTube video id: 11 letters, digits, `-` or `_`.
    static func isYouTubeKey(_ key: String) -> Bool {
        key.utf8.count == 11 && key.utf8.allSatisfy { byte in
            (0x30...0x39).contains(byte) || (0x41...0x5A).contains(byte) || (0x61...0x7A).contains(byte) || byte == 0x2D || byte == 0x5F
        }
    }
}

public enum TrendingMatch: Hashable, Sendable, Identifiable {
    case movie(Movie, TMDbItem)
    case series(Series, TMDbItem)

    public var id: String {
        switch self {
        case .movie(let movie, _): return "m-" + movie.id
        case .series(let series, _): return "s-" + series.id
        }
    }

    public var trending: TMDbItem {
        switch self {
        case .movie(_, let item), .series(_, let item): return item
        }
    }
}

/// Finds which trending titles exist in the user's catalogue ("FR - Sandstorm (2021) 4K" ↔ "Sandstorm").
public enum CatalogueMatcher {
    static func key(_ title: String) -> String {
        words(of: title).joined()
    }

    /// The words to search a title with: "FR - Sandstorm (2021) 4K" -> "sandstorm".
    static func searchQuery(_ title: String) -> String {
        words(of: title).joined(separator: " ")
    }

    /// Release years one apart at most (release dates differ between countries). Any year compares, however absurd
    /// the one a panel sent.
    static func closeYears(_ a: Int, _ b: Int) -> Bool {
        let difference = a.subtractingReportingOverflow(b)
        return !difference.overflow && (-1...1).contains(difference.partialValue)
    }

    /// Words after or before a title that name a copy's language or version, not the film ("Sandstorm VF", "EN - Sandstorm").
    private static let versionWords: Set<String> = [
        "vf", "vff", "vfq", "vfi", "vo", "vost", "vostfr", "vosta", "stfr", "subfr", "frsub", "truefrench", "french", "english",
        "subbed", "dubbed", "dub", "sub", "dual", "en", "eng", "fr", "es", "esp", "de", "ger", "ita", "pt", "br", "ar", "tr", "nl", "pl",
    ]

    /// Words of a copy's quality or edition, never the film's, in a provider's title ("Sandstorm MULTI 4K").
    private static let qualityWords: Set<String> = [
        "multi", "4k", "uhd", "fhd", "hd", "sd", "hdr", "dv", "hevc", "x265", "h265", "1080p", "720p", "2160p", "vfq", "imax",
    ]

    /// A tag of the provider's, not a word of the film's: a language or version, or a quality. A short one (three
    /// letters at most) only in capitals: "FR" and "VF" are tags, "De" in "De Gaulle" is a word.
    private static func isTag(_ word: String) -> Bool {
        let lower = word.lowercased()
        guard isTagWord(lower) else { return false }
        return (word.utf16.count > 3 && !wordLikeTags.contains(lower)) || word == word.uppercased()
    }

    /// Tags that are also words of titles ("French Kiss", "Johnny English", "Dual"): tags only in capitals, like the
    /// short ones.
    private static let wordLikeTags: Set<String> = ["french", "english", "dubbed", "subbed", "dual"]

    /// Whether a word (lowercase) is one of the providers' tag words, a language, a version or a quality.
    static func isTagWord(_ lower: String) -> Bool {
        versionWords.contains(lower) || qualityWords.contains(lower)
    }

    private static let separators = CharacterSet(charactersIn: " -:|·_.,")

    /// A provider's title as people read it, its case kept: without the provider's tags around the name.
    /// "|FR| Le Transport", "[FR] Le Transport", "FR - Le Transport", "STFR| Le Transport", "Le Transport VFF",
    /// "Le Transport (MULTI) (2021)" -> "Le Transport". The title as it is when nothing would be left of it.
    /// The same rules as Android's `CatalogueMatcher.displayTitle`, with the same tests. TMDb's searches and keys are
    /// made of it too (`key`, `searchQuery`); stored titles stay the provider's.
    public static func displayTitle(_ title: String) -> String {
        var name = title
        // Each pattern only where its first character is (the same result, without a regular expression per title of a
        // whole catalogue: FilmVersions cleans every title).
        if name.contains("[") { name = name.replacingOccurrences(of: #"\[[^\]]*\]"#, with: " ", options: .regularExpression) }
        if name.contains("|") { name = name.replacingOccurrences(of: #"\|[^|\s][^|]{0,23}\|"#, with: " ", options: .regularExpression) }
        if name.contains("(") { name = name.replacingOccurrences(of: #"\(\s*\d{4}\s*\)"#, with: " ", options: .regularExpression) }
        // "(MULTI)", "(VOSTFR 4K)": a group of tags only.
        if name.contains("(") { name = replacingGroups(in: name, pattern: #"\([^)]*\)"#) { group in
            let inner = group.trimmingCharacters(in: CharacterSet(charactersIn: "()"))
            let words = inner.split(whereSeparator: { $0 == " " || $0 == "-" || $0 == "/" })
                .map(String.init).filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            return !words.isEmpty && words.allSatisfy(isTag) ? " " : group
        } }
        // "FR - ", "FR-4K: ", "EN | ", "STFR| " before the name; "ST FR - ", "FR SUB | " are one tag in two words.
        if name.contains(where: { "-:|·".contains($0) }), let match = leadingTag.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)),
           let tagRange = Range(match.range(at: 1), in: name), let whole = Range(match.range, in: name) {
            let words = name[tagRange].split(whereSeparator: { $0 == " " || $0 == "-" || $0 == "+" }).map(String.init)
            if words.allSatisfy(isTag) || isTag(words.joined()) { name = String(name[whole.upperBound...]) }
        }
        var words = name.split(separator: " ", omittingEmptySubsequences: false)
            .map { String($0).trimmingCharacters(in: separators) }
            .filter { !$0.isEmpty }
        while words.count > 1, let last = words.last, isTag(last) { words.removeLast() }
        while words.count > 1, let first = words.first, isTag(first) { words.removeFirst() }
        // The separators inside the name stay: only the words' own edges were trimmed above.
        var cleaned = rebuild(name, words: words).trimmingCharacters(in: separators)
        if cleaned.contains("  ") || cleaned.contains(where: { $0.isWhitespace && $0 != " " }) {
            cleaned = cleaned.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        }
        return cleaned.isEmpty ? title.trimmingCharacters(in: .whitespacesAndNewlines) : cleaned
    }

    private static let leadingTag = try! NSRegularExpression(pattern: #"^\s*([\p{L}\p{N}]{2,8}(?:[-+ ][\p{L}\p{N}]{1,6})?)\s*[-:|·]\s+"#)

    /// `name` from its first kept word to its last (`words`, in order), with what lies between them as it was.
    private static func rebuild(_ name: String, words: [String]) -> String {
        guard let first = words.first, let start = name.range(of: first) else { return words.joined(separator: " ") }
        var end = start.lowerBound
        for word in words {
            guard let found = name.range(of: word, range: end..<name.endIndex) else { return words.joined(separator: " ") }
            end = found.upperBound
        }
        return String(name[start.lowerBound..<end])
    }

    /// `text` with each match of `pattern` replaced by what `transform` makes of it.
    private static func replacingGroups(in text: String, pattern: String, _ transform: (String) -> String) -> String {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return text }
        var result = ""
        var last = text.startIndex
        for match in regex.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let range = Range(match.range, in: text) else { continue }
            result += text[last..<range.lowerBound]
            result += transform(String(text[range]))
            last = range.upperBound
        }
        result += text[last...]
        return result
    }

    private static func words(of title: String) -> [String] {
        // The provider's tags around the name, as `displayTitle` leaves them out ("STFR| The Florist", "Sandstorm VOSTFR"):
        // the same title as TMDb's.
        var name = displayTitle(title)
        // Remove bracketed tags: [MULTI], (VOSTFR)…
        name = name.replacingOccurrences(of: #"\[[^\]]*\]"#, with: " ", options: .regularExpression)
        // A provider's tag between bars, before or after the name: "|FR-4K DV| MobLand", "MobLand |MULTI|". A bar
        // followed by a space only separates parts of the name: "FR | Sandstorm | 4K" keeps "Sandstorm".
        name = name.replacingOccurrences(of: #"\|[^|\s][^|]{0,23}\|"#, with: " ", options: .regularExpression)
        name = TitleParser.splitYear(name).0
        name = name.replacingOccurrences(of: #"\([^)]*\)"#, with: " ", options: .regularExpression)
        return TextNormalizer.channelMatchWords(name)
    }

    public static func match(_ trending: [TMDbItem], movies: [Movie], series: [Series]) -> [TrendingMatch] {
        var movieIndex: [String: [Movie]] = [:]
        for movie in movies { movieIndex[key(movie.name), default: []].append(movie) }
        var seriesIndex: [String: Series] = [:]
        for show in series where seriesIndex[key(show.name)] == nil { seriesIndex[key(show.name)] = show }

        var result: [TrendingMatch] = []
        var used = Set<String>()
        for item in trending {
            let keys = Set([item.title, item.originalTitle].compactMap { $0 }.map(key)).filter { !$0.isEmpty }
            switch item.mediaType {
            case .movie:
                let candidates = keys.flatMap { movieIndex[$0] ?? [] }
                let best = candidates.first { movie in
                    guard let a = movie.year, let b = item.year else { return true }
                    return closeYears(a, b)
                }
                if let best, used.insert("m" + best.id).inserted { result.append(.movie(best, item)) }
            case .tv:
                if let show = keys.lazy.compactMap({ seriesIndex[$0] }).first, used.insert("s" + show.id).inserted {
                    result.append(.series(show, item))
                }
            default:
                break
            }
        }
        return result
    }
}
