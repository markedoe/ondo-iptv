import Foundation

/// OpenSubtitles.com REST API (v1). The key identifies the app; an optional account raises the daily
/// download limit (5 per address and day without one). Signing in is rate limited by the service, so the
/// session is kept for the app's lifetime and opened only for a download, never for a search.
public actor OpenSubtitlesClient: SubtitleProvider {
    public nonisolated let service = SubtitleService.openSubtitles
    static let host = "api.opensubtitles.com"

    private let apiKey: String
    private let username: String?
    private let password: String?
    private let userAgent: String
    private let http: HTTPClient
    private var session: (token: String, host: String)?
    /// After a refused login, no new attempt until the credentials change (the service asks for it).
    private var loginRefused = false

    public init(apiKey: String, username: String? = nil, password: String? = nil, userAgent: String, http: HTTPClient) {
        self.apiKey = apiKey
        self.username = username?.isEmpty == false ? username : nil
        self.password = password?.isEmpty == false ? password : nil
        self.userAgent = userAgent
        self.http = http
    }

    private func headers(json: Bool = false) -> [String: String] {
        var headers = ["Api-Key": apiKey, "User-Agent": userAgent, "Accept": "application/json"]
        if json { headers["Content-Type"] = "application/json" }
        if let session { headers["Authorization"] = "Bearer \(session.token)" }
        return headers
    }

    private func url(_ path: String, query: [URLQueryItem] = []) -> URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = session?.host ?? Self.host
        components.path = "/api/v1" + path
        // Sorted, lower case, no defaults: the service redirects other forms.
        components.queryItems = query.isEmpty ? nil : query.sorted { $0.name < $1.name }
        return components.url!
    }

    public func search(_ query: SubtitleQuery) async throws -> [OnlineSubtitle] {
        var items: [(String, String?)] = [
            // The service wants the codes sorted too; results are ranked by preference afterwards anyway.
            ("languages", query.languages.map { $0.lowercased() }.sorted().joined(separator: ",")),
            ("query", query.title.lowercased()),
        ]
        if query.isEpisode {
            items += [("type", "episode"), ("season_number", query.season.map(String.init)), ("episode_number", query.episode.map(String.init))]
        } else {
            items += [("type", "movie"), ("year", query.year.map(String.init))]
        }
        let data: Data
        do {
            data = try await http.send(HTTPRequest(url: url("/subtitles", query: SubtitleHTTP.queryItems(items)), headers: headers()))
        } catch {
            throw SubtitleHTTP.translate(error, service: service)
        }
        let response = try JSONDecoder().decodeCheckingKeys(SearchResponse.self, from: data)
        return response.data.compactMap { item in
            guard let file = item.attributes.files?.first else { return nil }
            let attributes = item.attributes
            return OnlineSubtitle(
                service: service,
                reference: String(file.fileID),
                language: SubtitleLanguages.code(for: attributes.language ?? "") ?? attributes.language ?? "",
                release: attributes.release?.isEmpty == false ? attributes.release! : (file.fileName ?? ""),
                downloads: attributes.downloadCount,
                hearingImpaired: attributes.hearingImpaired ?? false
            )
        }
    }

    public func download(_ subtitle: OnlineSubtitle, for query: SubtitleQuery) async throws -> DownloadedSubtitle {
        if session == nil, username != nil, password != nil, !loginRefused { try await logIn() }
        let link: DownloadResponse
        do {
            link = try await requestLink(fileID: subtitle.reference)
        } catch HTTPClientError.badStatus(401) where session != nil {
            // Expired session: sign in once more.
            session = nil
            try await logIn()
            do {
                link = try await requestLink(fileID: subtitle.reference)
            } catch {
                throw SubtitleHTTP.translate(error, service: service)
            }
        } catch {
            throw SubtitleHTTP.translate(error, service: service)
        }
        guard let address = URL(string: link.link) else { throw SubtitleSearchError.serviceError(service, "invalid download link") }
        let data = try await http.send(HTTPRequest(url: address, headers: ["User-Agent": userAgent]))
        return DownloadedSubtitle(fileName: link.fileName ?? "subtitle.srt", data: data)
    }

    private func requestLink(fileID: String) async throws -> DownloadResponse {
        guard let id = Int(fileID) else { throw SubtitleSearchError.serviceError(service, "unknown file") }
        let body = try JSONEncoder().encode(["file_id": id])
        let data = try await http.send(HTTPRequest(url: url("/download"), method: "POST", headers: headers(json: true), body: body))
        return try JSONDecoder().decodeCheckingKeys(DownloadResponse.self, from: data)
    }

    private func logIn() async throws {
        guard let username, let password else { return }
        let body = try JSONEncoder().encode(["username": username, "password": password])
        let headers = ["Api-Key": apiKey, "User-Agent": userAgent, "Accept": "application/json", "Content-Type": "application/json"]
        let data: Data
        do {
            data = try await http.send(HTTPRequest(url: URL(string: "https://\(Self.host)/api/v1/login")!, method: "POST", headers: headers, body: body))
        } catch HTTPClientError.badStatus(401) {
            loginRefused = true
            throw SubtitleSearchError.invalidCredentials(service)
        } catch {
            throw SubtitleHTTP.translate(error, service: service)
        }
        let login = try JSONDecoder().decodeCheckingKeys(LoginResponse.self, from: data)
        session = (login.token, login.baseURL ?? Self.host)
    }

    /// Checks the API key with the service (its list of formats needs one) and, when an account is given, signs in
    /// with it: what Settings saves only once accepted.
    public func checkCredentials() async -> CredentialCheck {
        do {
            _ = try await http.send(HTTPRequest(url: URL(string: "https://\(Self.host)/api/v1/infos/formats")!, headers: headers()))
            if username != nil, password != nil { try await logIn() }
            return .accepted
        } catch {
            return .failure(error)
        }
    }

    // MARK: Wire format

    private struct SearchResponse: Decodable {
        let data: [Item]

        struct Item: Decodable {
            let attributes: Attributes
        }

        struct Attributes: Decodable {
            let language: String?
            let downloadCount: Int?
            let hearingImpaired: Bool?
            let release: String?
            let files: [File]?

            enum CodingKeys: String, CodingKey {
                case language, release, files
                case downloadCount = "download_count"
                case hearingImpaired = "hearing_impaired"
            }
        }

        struct File: Decodable {
            let fileID: Int
            let fileName: String?

            enum CodingKeys: String, CodingKey {
                case fileID = "file_id"
                case fileName = "file_name"
            }
        }
    }

    private struct DownloadResponse: Decodable {
        let link: String
        let fileName: String?

        enum CodingKeys: String, CodingKey {
            case link
            case fileName = "file_name"
        }
    }

    private struct LoginResponse: Decodable {
        let token: String
        let baseURL: String?

        enum CodingKeys: String, CodingKey {
            case token
            case baseURL = "base_url"
        }
    }
}
