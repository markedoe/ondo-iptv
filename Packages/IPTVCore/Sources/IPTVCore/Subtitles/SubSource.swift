import Foundation

/// SubSource API (api.subsource.net): find the title, then its subtitles in each preferred language. Files
/// come zipped. Languages are named in English ("french").
public struct SubSourceClient: SubtitleProvider {
    public let service = SubtitleService.subSource
    private let apiKey: String
    private let http: HTTPClient
    static let base = "https://api.subsource.net/api/v1"

    public init(apiKey: String, http: HTTPClient) {
        self.apiKey = apiKey
        self.http = http
    }

    private func get(_ path: String, _ items: [(String, String?)] = []) async throws -> Data {
        var components = URLComponents(string: Self.base + path)!
        let queryItems = SubtitleHTTP.queryItems(items)
        components.queryItems = queryItems.isEmpty ? nil : queryItems
        do {
            return try await http.data(from: components.url!, headers: ["X-API-Key": apiKey, "Accept": "application/json"])
        } catch {
            throw SubtitleHTTP.translate(error, service: service)
        }
    }

    /// Checks the API key with a title search (401 or 403 when the service refuses it).
    public func checkKey() async -> CredentialCheck {
        do {
            _ = try await get("/movies/search", [("searchType", "text"), ("q", "The Matrix"), ("type", "movie")])
            return .accepted
        } catch {
            return .failure(error)
        }
    }

    public func search(_ query: SubtitleQuery) async throws -> [OnlineSubtitle] {
        let titles = try JSONDecoder().decode(Envelope<[Title]>.self, from: try await get("/movies/search", [
            ("searchType", "text"),
            ("q", query.title),
            ("type", query.isEpisode ? "series" : "movie"),
            ("season", query.season.map(String.init)),
        ])).data ?? []
        guard let title = Self.bestMatch(titles, for: query) else { return [] }

        var results: [OnlineSubtitle] = []
        // Two languages at most: one request each, and the service counts them.
        for code in query.languages.prefix(2) {
            guard let name = SubtitleLanguages.englishName(of: code)?.lowercased() else { continue }
            let subtitles = try JSONDecoder().decode(Envelope<[Subtitle]>.self, from: try await get("/subtitles", [
                ("movieId", String(title.movieID)),
                ("language", name),
                ("limit", "30"),
                ("sort", "popular"),
            ])).data ?? []
            results += subtitles.map { subtitle in
                OnlineSubtitle(
                    service: service,
                    reference: String(subtitle.subtitleID),
                    language: SubtitleLanguages.code(for: subtitle.language ?? name) ?? code,
                    release: subtitle.releaseInfo?.joined(separator: " · ") ?? "",
                    downloads: subtitle.downloads,
                    hearingImpaired: subtitle.hearingImpaired ?? false
                )
            }
        }
        // A season's page lists every episode: keep this one's when the releases name it.
        if let code = query.episodeCode {
            let episode = results.filter { $0.release.uppercased().contains(code) }
            if !episode.isEmpty { return episode }
        }
        return results
    }

    public func download(_ subtitle: OnlineSubtitle, for query: SubtitleQuery) async throws -> DownloadedSubtitle {
        let data = try await get("/subtitles/\(subtitle.reference)/download")
        return try SubtitleArchive.pick(from: data, episodeCode: query.episodeCode)
    }

    /// Same season for a series; then the release year when known; else the exact title; else the first answer.
    static func bestMatch(_ titles: [Title], for query: SubtitleQuery) -> Title? {
        var candidates = titles
        if let season = query.season {
            let sameSeason = candidates.filter { $0.season == season }
            if !sameSeason.isEmpty { candidates = sameSeason }
        }
        if let year = query.year, let sameYear = candidates.first(where: { $0.releaseYear == year }) {
            return sameYear
        }
        let wanted = TextNormalizer.normalize(query.title)
        return candidates.first { TextNormalizer.normalize($0.title) == wanted } ?? candidates.first
    }

    private struct Envelope<T: Decodable>: Decodable {
        let data: T?
    }

    struct Title: Decodable, Equatable {
        let movieID: Int
        let title: String
        let releaseYear: Int?
        let season: Int?

        enum CodingKeys: String, CodingKey {
            case title, releaseYear, season
            case movieID = "movieId"
        }
    }

    private struct Subtitle: Decodable {
        let subtitleID: Int
        let language: String?
        let releaseInfo: [String]?
        let downloads: Int?
        let hearingImpaired: Bool?

        enum CodingKeys: String, CodingKey {
            case language, releaseInfo, downloads, hearingImpaired
            case subtitleID = "subtitleId"
        }
    }
}
