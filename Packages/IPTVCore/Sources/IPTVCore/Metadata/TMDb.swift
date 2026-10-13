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

    public func trending(window: String = "week") async throws -> [TMDbItem] {
        var components = URLComponents(string: "https://api.themoviedb.org/3/trending/all/\(window)")!
        var items = [URLQueryItem(name: "language", value: language)]
        if !usesBearerToken { items.append(URLQueryItem(name: "api_key", value: credential)) }
        components.queryItems = items
        let headers = usesBearerToken ? ["Authorization": "Bearer \(credential)", "Accept": "application/json"] : ["Accept": "application/json"]
        let data = try await http.data(from: components.url!, headers: headers)
        struct Page: Decodable { var results: [TMDbItem] }
        return try JSONDecoder().decode(Page.self, from: data).results.filter { $0.mediaType == .movie || $0.mediaType == .tv }
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

/// Finds which trending titles exist in the user's catalogue ("FR - Dune (2021) 4K" ↔ "Dune").
public enum CatalogueMatcher {
    static func key(_ title: String) -> String {
        var name = title
        // Remove bracketed tags: [MULTI], (VOSTFR)…
        name = name.replacingOccurrences(of: #"\[[^\]]*\]"#, with: " ", options: .regularExpression)
        name = TitleParser.splitYear(name).0
        name = name.replacingOccurrences(of: #"\([^)]*\)"#, with: " ", options: .regularExpression)
        return TextNormalizer.channelMatchKey(name)
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
                    return abs(a - b) <= 1
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
