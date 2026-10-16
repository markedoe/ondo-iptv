import Foundation

/// SubDL API: search with the user's key; files are zipped on its download host.
public struct SubDLClient: SubtitleProvider {
    public let service = SubtitleService.subdl
    private let apiKey: String
    private let http: HTTPClient

    public init(apiKey: String, http: HTTPClient) {
        self.apiKey = apiKey
        self.http = http
    }

    public func search(_ query: SubtitleQuery) async throws -> [OnlineSubtitle] {
        var components = URLComponents(string: "https://api.subdl.com/api/v1/subtitles")!
        components.queryItems = SubtitleHTTP.queryItems([
            ("api_key", apiKey),
            ("film_name", query.title),
            ("type", query.isEpisode ? "tv" : "movie"),
            ("year", query.isEpisode ? nil : query.year.map(String.init)),
            ("season_number", query.season.map(String.init)),
            ("episode_number", query.episode.map(String.init)),
            ("languages", query.languages.map { $0.uppercased() }.joined(separator: ",")),
            ("subs_per_page", "30"),
        ])
        let data: Data
        do {
            data = try await http.data(from: components.url!, headers: [:])
        } catch {
            throw SubtitleHTTP.translate(error, service: service)
        }
        let response = try JSONDecoder().decode(Response.self, from: data)
        if response.status == false {
            let message = response.error ?? response.message ?? "no result"
            if message.lowercased().contains("api") || message.lowercased().contains("key") {
                throw SubtitleSearchError.invalidCredentials(service)
            }
            return []  // "can't find movie or tv" is an empty result, not a failure
        }
        return (response.subtitles ?? []).compactMap { subtitle in
            guard let path = subtitle.url, !path.isEmpty else { return nil }
            let language = subtitle.language ?? subtitle.lang ?? ""
            return OnlineSubtitle(
                service: service,
                reference: path,
                language: SubtitleLanguages.code(for: language) ?? language.lowercased(),
                release: subtitle.releaseName ?? subtitle.name ?? path,
                hearingImpaired: subtitle.hearingImpaired ?? false
            )
        }
    }

    public func download(_ subtitle: OnlineSubtitle, for query: SubtitleQuery) async throws -> DownloadedSubtitle {
        let address = subtitle.reference.hasPrefix("http") ? subtitle.reference : "https://dl.subdl.com" + subtitle.reference
        guard let url = URL(string: address) else { throw SubtitleSearchError.serviceError(service, "invalid download link") }
        let data: Data
        do {
            data = try await http.data(from: url, headers: [:])
        } catch {
            throw SubtitleHTTP.translate(error, service: service)
        }
        return try SubtitleArchive.pick(from: data, episodeCode: query.episodeCode)
    }

    /// Checks the API key with a search: the service answers a refused key with an error that names it.
    public func checkKey() async -> CredentialCheck {
        do {
            _ = try await search(SubtitleQuery(title: "The Matrix", year: 1999, languages: ["en"]))
            return .accepted
        } catch {
            return .failure(error)
        }
    }

    private struct Response: Decodable {
        let status: Bool?
        let error: String?
        let message: String?
        let subtitles: [Subtitle]?
    }

    private struct Subtitle: Decodable {
        let releaseName: String?
        let name: String?
        let lang: String?
        let language: String?
        let url: String?
        let hearingImpaired: Bool?

        enum CodingKeys: String, CodingKey {
            case name, lang, language, url
            case releaseName = "release_name"
            case hearingImpaired = "hi"
        }
    }
}
