import Foundation

/// Real-Debrid account used as a VOD source: the files of the user's download list become movies / episodes.
public struct RealDebridClient: Sendable {
    public let token: String
    public let http: HTTPClient
    static let base = "https://api.real-debrid.com/rest/1.0"

    public init(token: String, http: HTTPClient) {
        self.token = token.trimmingCharacters(in: .whitespacesAndNewlines)
        self.http = http
    }

    private var headers: [String: String] { ["Authorization": "Bearer \(token)"] }

    struct DownloadDTO: Decodable {
        var id: String
        var filename: String
        var mimeType: String?
        var download: String
        var streamable: Int?
        var generated: String?

        enum CodingKeys: String, CodingKey { case id, filename, mimeType, download, streamable, generated }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = c.lenientString(.id) ?? UUID().uuidString
            filename = c.lenientString(.filename) ?? ""
            mimeType = c.lenientString(.mimeType)
            download = c.lenientString(.download) ?? ""
            streamable = c.lenientInt(.streamable)
            generated = c.lenientString(.generated)
        }

        var isVideo: Bool {
            if let mimeType, mimeType.hasPrefix("video/") { return true }
            return ContentClassifier.videoExtensions.contains((filename as NSString).pathExtension.lowercased())
        }
    }

    public struct User: Decodable, Sendable {
        public var username: String
        public var type: String
        public var expiration: String?
    }

    public func user() async throws -> User {
        let data = try await http.data(from: URL(string: Self.base + "/user")!, headers: headers)
        return try JSONDecoder().decode(User.self, from: data)
    }

    /// All video files of the download list (paginated), as a playlist.
    public func fetchContent(maxPages: Int = 20) async throws -> PlaylistContent {
        var entries: [M3UEntry] = []
        let limit = 100
        for page in 1...maxPages {
            let url = URL(string: Self.base + "/downloads?page=\(page)&limit=\(limit)")!
            let data: Data
            do {
                data = try await http.data(from: url, headers: headers)
            } catch HTTPClientError.badStatus(let code) where code == 204 || (code == 404 && page > 1) {
                break
            }
            let list = (try? JSONDecoder().decode(LossyArray<DownloadDTO>.self, from: data))?.elements ?? []
            for item in list where item.isVideo {
                guard let link = URL.lenient(item.download) else { continue }
                let (title, isEpisode) = ReleaseName.clean(item.filename)
                entries.append(M3UEntry(
                    duration: -1,
                    title: title,
                    attributes: ["group-title": isEpisode ? "Real-Debrid Series" : "Real-Debrid", "tvg-type": isEpisode ? "series" : "movie"],
                    url: link,
                    extGroup: nil,
                    headers: .none
                ))
            }
            if list.count < limit { break }
        }
        return M3UContentBuilder.build(from: M3UPlaylist(headerAttributes: [:], entries: entries))
    }
}

/// Turns scene release names into readable titles: "The.Movie.2021.1080p.WEB-DL.x264.mkv" -> "The Movie (2021)".
public enum ReleaseName {
    private static let stopTokens: Set<String> = [
        "2160p", "1080p", "720p", "480p", "4k", "uhd", "hdr", "hdr10", "dv", "web", "webdl", "web-dl", "webrip", "bluray",
        "brrip", "bdrip", "dvdrip", "hdtv", "x264", "x265", "h264", "h265", "hevc", "aac", "ac3", "dts", "remux", "proper",
        "repack", "multi", "vostfr", "truefrench", "french", "vff", "vfq", "subfrench", "internal", "10bit", "amzn", "nf", "dsnp",
    ]

    /// Returns the cleaned title and whether it looks like an episode (`S01E02` is kept for grouping).
    public static func clean(_ filename: String) -> (String, Bool) {
        let base = (filename as NSString).deletingPathExtension
        let words = base
            .replacingOccurrences(of: ".", with: " ")
            .replacingOccurrences(of: "_", with: " ")
            .split(separator: " ")
            .map(String.init)
        var kept: [String] = []
        var isEpisode = false
        for word in words {
            if word.range(of: #"^[Ss]\d{1,2}[Ee]\d{1,3}$"#, options: .regularExpression) != nil {
                kept.append(word.uppercased())
                isEpisode = true
                break
            }
            let lower = word.lowercased()
            if stopTokens.contains(lower) || lower.hasPrefix("[") { break }
            kept.append(word)
        }
        // The release year is the last year-like token ("Star Runner 2049 (2017)").
        var year: Int?
        if !isEpisode, let index = kept.indices.last(where: { $0 > 0 && Self.year(kept[$0]) != nil }) {
            year = Self.year(kept[index])
            kept = Array(kept[..<index])
        }
        var title = kept.joined(separator: " ").trimmingCharacters(in: CharacterSet(charactersIn: " -"))
        if title.isEmpty { title = base }
        if let year, !isEpisode { title += " (\(year))" }
        return (title, isEpisode)
    }

    private static func year(_ word: String) -> Int? {
        guard let value = Int(word.trimmingCharacters(in: CharacterSet(charactersIn: "()[]"))), (1920...2100).contains(value) else { return nil }
        return value
    }
}
