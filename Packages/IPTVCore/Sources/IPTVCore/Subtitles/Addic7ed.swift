import Foundation

/// Addic7ed's series subtitles, through the public Gestdown API (no account needed). Episodes only.
public struct Addic7edClient: SubtitleProvider {
    public let service = SubtitleService.addic7ed
    private let http: HTTPClient
    private let userAgent: String
    static let base = "https://api.gestdown.info"

    public init(userAgent: String, http: HTTPClient) {
        self.userAgent = userAgent
        self.http = http
    }

    private func get(_ path: String) async throws -> Data {
        guard let url = URL(string: Self.base + path) else { throw HTTPClientError.invalidURL }
        return try await http.data(from: url, headers: ["User-Agent": userAgent, "Accept": "application/json"])
    }

    private static func pathComponent(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? value
    }

    public func search(_ query: SubtitleQuery) async throws -> [OnlineSubtitle] {
        guard query.isEpisode, let season = query.season, let episodeNumber = query.episode else { return [] }
        let shows: [Show]
        do {
            shows = try JSONDecoder().decode(ShowSearch.self, from: try await get("/shows/search/\(Self.pathComponent(query.title))")).shows
        } catch HTTPClientError.badStatus(404) {
            return []
        }
        let wanted = TextNormalizer.normalize(query.title)
        guard let show = shows.first(where: { TextNormalizer.normalize($0.name) == wanted }) ?? shows.first else { return [] }

        var results: [OnlineSubtitle] = []
        for code in query.languages.prefix(2) {
            guard let language = SubtitleLanguages.englishName(of: code) else { continue }
            let listing: SeasonListing
            do {
                listing = try JSONDecoder().decode(SeasonListing.self, from: try await get("/shows/\(show.id)/\(season)/\(language)"))
            } catch HTTPClientError.badStatus(404) {
                continue
            }
            for episode in listing.episodes where episode.number == episodeNumber {
                for subtitle in episode.subtitles where subtitle.completed != false {
                    results.append(OnlineSubtitle(
                        service: service,
                        reference: subtitle.downloadURI,
                        language: code,
                        release: [show.name, query.episodeCode, subtitle.version].compactMap { $0 }.joined(separator: " · "),
                        downloads: subtitle.downloadCount,
                        hearingImpaired: subtitle.hearingImpaired ?? false
                    ))
                }
            }
        }
        return results
    }

    public func download(_ subtitle: OnlineSubtitle, for query: SubtitleQuery) async throws -> DownloadedSubtitle {
        let data = try await get(subtitle.reference)
        let name = [query.title, query.episodeCode, subtitle.language].compactMap { $0 }.joined(separator: ".") + ".srt"
        return DownloadedSubtitle(fileName: name, data: data)
    }

    private struct ShowSearch: Decodable {
        let shows: [Show]
    }

    private struct Show: Decodable {
        let id: String
        let name: String
    }

    private struct SeasonListing: Decodable {
        let episodes: [Episode]
    }

    private struct Episode: Decodable {
        let number: Int
        let subtitles: [Subtitle]
    }

    private struct Subtitle: Decodable {
        let version: String?
        let completed: Bool?
        let hearingImpaired: Bool?
        let downloadURI: String
        let downloadCount: Int?

        enum CodingKeys: String, CodingKey {
            case version, completed, hearingImpaired, downloadCount
            case downloadURI = "downloadUri"
        }
    }
}
