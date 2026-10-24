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

    public init(credential: String, http: HTTPClient, language: String = "en-US") {
        self.credential = credential.trimmingCharacters(in: .whitespacesAndNewlines)
        self.http = http
        self.language = language
    }

    private var usesBearerToken: Bool { credential.count > 40 }

    /// GET on the v3 API: the key goes in the query, a v4 token in the Authorization header (never in the address).
    private func get(_ path: String, query: [URLQueryItem] = []) async throws -> Data {
        var components = URLComponents(string: "https://api.themoviedb.org/3/" + path)!
        var items = query + [URLQueryItem(name: "language", value: language)]
        if !usesBearerToken { items.append(URLQueryItem(name: "api_key", value: credential)) }
        components.queryItems = items
        let headers = usesBearerToken ? ["Authorization": "Bearer \(credential)", "Accept": "application/json"] : ["Accept": "application/json"]
        return try await http.data(from: components.url!, headers: headers)
    }

    /// Asks TMDb whether it accepts the key or token (`/authentication`), before it is saved: false when TMDb
    /// refuses it (401); a network failure, or an answer that is not TMDb's (`{"success": …}`), throws.
    public func isAccepted() async throws -> Bool {
        do {
            let data = try await get("authentication")
            struct Answer: Decodable { var success: Bool }
            return try JSONDecoder().decode(Answer.self, from: data).success
        } catch HTTPClientError.badStatus(let status) where status == 401 {
            return false
        }
    }

    public func trending(window: String = "week") async throws -> [TMDbItem] {
        let data = try await get("trending/all/\(window)")
        struct Page: Decodable { var results: [TMDbItem] }
        return try JSONDecoder().decode(Page.self, from: data).results.filter { $0.mediaType == .movie || $0.mediaType == .tv }
    }

    /// The backdrop TMDb has for a movie or a series whose provider gave none that loads: by its TMDb id when the
    /// provider gave one, otherwise the search result with the same title (and a year one apart at most, when
    /// known). Nil rather than another title's picture.
    public func backdropURL(of type: ContentType, tmdbID: String?, title: String, year: Int?) async throws -> URL? {
        let kind = type == .series ? "tv" : "movie"
        if let id = tmdbID.flatMap({ Int($0.trimmingCharacters(in: .whitespaces)) }), let data = try? await get("\(kind)/\(id)"),
           let url = (try? JSONDecoder().decode(TMDbItem.self, from: data))?.backdropURL {
            return url
        }
        let key = CatalogueMatcher.key(title)
        guard !key.isEmpty else { return nil }
        let data = try await get("search/\(kind)", query: [URLQueryItem(name: "query", value: CatalogueMatcher.searchQuery(title))])
        struct Page: Decodable { var results: [TMDbItem] }
        return try JSONDecoder().decode(Page.self, from: data).results.lazy
            .filter { item in
                guard [item.title, item.originalTitle].compactMap({ $0 }).map(CatalogueMatcher.key).contains(key) else { return false }
                guard let year, let released = item.year else { return true }
                return CatalogueMatcher.closeYears(year, released)
            }
            .compactMap(\.backdropURL)
            .first
    }

    /// The people TMDb finds for a search (actors, directors…), the most popular first.
    public func people(matching query: String) async throws -> [TMDbPerson] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        let data = try await get("search/person", query: [URLQueryItem(name: "query", value: trimmed)])
        struct Page: Decodable { var results: [TMDbPerson] }
        return try JSONDecoder().decode(Page.self, from: data).results.sorted { $0.popularity > $1.popularity }
    }

    /// The person TMDb knows best by this name (its most popular match), if any.
    public func person(named name: String) async throws -> TMDbPerson? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let data = try await get("search/person", query: [URLQueryItem(name: "query", value: trimmed)])
        struct Page: Decodable { var results: [TMDbPerson] }
        return try JSONDecoder().decode(Page.self, from: data).results.max { $0.popularity < $1.popularity }
    }

    /// Movies and series a person played in or worked on (directing, writing…), newest first, each once.
    public func credits(ofPerson id: Int) async throws -> [TMDbItem] {
        let data = try await get("person/\(id)/combined_credits")
        struct Credits: Decodable {
            var cast: [TMDbItem]?
            var crew: [TMDbItem]?
        }
        let credits = try JSONDecoder().decode(Credits.self, from: data)
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

    private static func words(of title: String) -> [String] {
        var name = title
        // Remove bracketed tags: [MULTI], (VOSTFR)…
        name = name.replacingOccurrences(of: #"\[[^\]]*\]"#, with: " ", options: .regularExpression)
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
