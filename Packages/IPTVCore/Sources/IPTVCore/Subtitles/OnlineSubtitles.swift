import Foundation

/// What is being watched, to find its subtitles.
public struct SubtitleQuery: Sendable, Equatable {
    public var title: String
    public var year: Int?
    public var season: Int?
    public var episode: Int?
    /// ISO 639-1 codes, preferred first.
    public var languages: [String]

    public init(title: String, year: Int? = nil, season: Int? = nil, episode: Int? = nil, languages: [String]) {
        self.title = title
        self.year = year
        self.season = season
        self.episode = episode
        self.languages = languages
    }

    public var isEpisode: Bool { season != nil && episode != nil }

    /// "S01E02", how releases name an episode.
    public var episodeCode: String? {
        guard let season, let episode else { return nil }
        return String(format: "S%02dE%02d", season, episode)
    }
}

/// The subtitle services Ondo can search, with the user's own key or account.
public enum SubtitleService: String, CaseIterable, Codable, Sendable, Identifiable {
    case openSubtitles
    case subdl
    case subSource
    case addic7ed

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .openSubtitles: return "OpenSubtitles"
        case .subdl: return "SubDL"
        case .subSource: return "SubSource"
        case .addic7ed: return "Addic7ed"
        }
    }
}

/// One subtitle found online.
public struct OnlineSubtitle: Identifiable, Hashable, Sendable {
    public var service: SubtitleService
    /// What the service needs to download it (file id, path or address).
    public var reference: String
    /// ISO 639-1 code when known, else the service's own name for the language.
    public var language: String
    /// Release the subtitle was timed for, or its file name.
    public var release: String
    public var downloads: Int?
    public var hearingImpaired: Bool

    public var id: String { "\(service.rawValue):\(reference)" }

    public init(service: SubtitleService, reference: String, language: String, release: String, downloads: Int? = nil, hearingImpaired: Bool = false) {
        self.service = service
        self.reference = reference
        self.language = language
        self.release = release
        self.downloads = downloads
        self.hearingImpaired = hearingImpaired
    }
}

/// A downloaded subtitle file, ready for `SubtitleParser`.
public struct DownloadedSubtitle: Sendable, Equatable {
    public var fileName: String
    public var data: Data

    public init(fileName: String, data: Data) {
        self.fileName = fileName
        self.data = data
    }
}

public protocol SubtitleProvider: Sendable {
    var service: SubtitleService { get }
    func search(_ query: SubtitleQuery) async throws -> [OnlineSubtitle]
    func download(_ subtitle: OnlineSubtitle, for query: SubtitleQuery) async throws -> DownloadedSubtitle
}

public enum SubtitleSearchError: Error, LocalizedError, Equatable {
    case invalidCredentials(SubtitleService)
    case quotaReached(SubtitleService)
    case noSubtitleInArchive
    case serviceError(SubtitleService, String)

    public var errorDescription: String? {
        switch self {
        case .invalidCredentials(let service):
            return "\(service.displayName) refused the key or the login. Check them in Settings > Subtitles."
        case .quotaReached(let service):
            return "Download limit reached on \(service.displayName) for today. Signing in, or a paid plan, raises it."
        case .noSubtitleInArchive:
            return "The downloaded archive holds no SRT or WebVTT subtitle."
        case .serviceError(let service, let message):
            return "\(service.displayName): \(message)"
        }
    }
}

/// Searches every configured service at once. A service that fails does not hide the others' results: its
/// error is reported next to them.
public struct SubtitleSearch: Sendable {
    public struct Outcome: Sendable {
        public var results: [OnlineSubtitle]
        public var failures: [SubtitleService: String]
    }

    public let providers: [any SubtitleProvider]

    public init(providers: [any SubtitleProvider]) {
        self.providers = providers
    }

    public func search(_ query: SubtitleQuery) async -> Outcome {
        let found = await withTaskGroup(of: (SubtitleService, Result<[OnlineSubtitle], Error>).self) { group in
            for provider in providers {
                group.addTask {
                    do {
                        return (provider.service, .success(try await provider.search(query)))
                    } catch {
                        return (provider.service, .failure(error))
                    }
                }
            }
            var all: [(SubtitleService, Result<[OnlineSubtitle], Error>)] = []
            for await result in group { all.append(result) }
            return all
        }
        var results: [OnlineSubtitle] = []
        var failures: [SubtitleService: String] = [:]
        for (service, result) in found {
            switch result {
            case .success(let subtitles): results += subtitles
            case .failure(let error): failures[service] = error.localizedDescription
            }
        }
        return Outcome(results: Self.ranked(results, languages: query.languages, services: providers.map(\.service)), failures: failures)
    }

    public func download(_ subtitle: OnlineSubtitle, for query: SubtitleQuery) async throws -> DownloadedSubtitle {
        guard let provider = providers.first(where: { $0.service == subtitle.service }) else {
            throw SubtitleSearchError.serviceError(subtitle.service, "not configured")
        }
        return try await provider.download(subtitle, for: query)
    }

    /// Preferred language first, then the services in their order, then the most downloaded.
    static func ranked(_ results: [OnlineSubtitle], languages: [String], services: [SubtitleService]) -> [OnlineSubtitle] {
        func languageRank(_ subtitle: OnlineSubtitle) -> Int { languages.firstIndex(of: subtitle.language) ?? languages.count }
        func serviceRank(_ subtitle: OnlineSubtitle) -> Int { services.firstIndex(of: subtitle.service) ?? services.count }
        return results.sorted {
            (languageRank($0), serviceRank($0), -($0.downloads ?? 0), $0.release) < (languageRank($1), serviceRank($1), -($1.downloads ?? 0), $1.release)
        }
    }
}

/// Language codes and the English names some services use instead.
public enum SubtitleLanguages {
    static let englishNames: [String: String] = [
        "ar": "Arabic", "bg": "Bulgarian", "cs": "Czech", "da": "Danish", "de": "German", "el": "Greek", "en": "English",
        "es": "Spanish", "fa": "Persian", "fi": "Finnish", "fr": "French", "he": "Hebrew", "hi": "Hindi", "hr": "Croatian",
        "hu": "Hungarian", "id": "Indonesian", "it": "Italian", "ja": "Japanese", "ko": "Korean", "nl": "Dutch",
        "no": "Norwegian", "pl": "Polish", "pt": "Portuguese", "ro": "Romanian", "ru": "Russian", "sr": "Serbian",
        "sv": "Swedish", "th": "Thai", "tr": "Turkish", "uk": "Ukrainian", "vi": "Vietnamese", "zh": "Chinese",
    ]

    public static var codes: [String] { englishNames.keys.sorted() }

    public static func englishName(of code: String) -> String? {
        englishNames[code.lowercased()]
    }

    /// "french", "French", "FR", "fr" or "pt-BR" -> "fr" / "pt"; nil when unknown.
    public static func code(for value: String) -> String? {
        let lowered = value.lowercased()
        let base = String(lowered.split(separator: "-").first ?? Substring(lowered))
        if englishNames[base] != nil { return base }
        return englishNames.first { $0.value.lowercased() == lowered }?.key
    }
}

/// The subtitle file to keep from an archive: the episode's when the archive holds a season, else the first
/// SRT, else the first WebVTT.
enum SubtitleArchive {
    static func pick(from data: Data, episodeCode: String?) throws -> DownloadedSubtitle {
        let archive = try ZipArchive(data)
        let candidates = archive.files.filter { ["srt", "vtt"].contains(($0.name as NSString).pathExtension.lowercased()) }
        let ordered = candidates.sorted { lhs, rhs in
            (rank(lhs.name, episodeCode), lhs.name) < (rank(rhs.name, episodeCode), rhs.name)
        }
        guard let entry = ordered.first else { throw SubtitleSearchError.noSubtitleInArchive }
        return DownloadedSubtitle(fileName: (entry.name as NSString).lastPathComponent, data: try archive.contents(of: entry))
    }

    private static func rank(_ name: String, _ episodeCode: String?) -> Int {
        let matchesEpisode = episodeCode.map { name.uppercased().contains($0) } ?? false
        let isSRT = name.lowercased().hasSuffix(".srt")
        return (matchesEpisode ? 0 : 2) + (isSRT ? 0 : 1)
    }
}

enum SubtitleHTTP {
    /// Maps an HTTP failure to what the viewer can act on.
    static func translate(_ error: Error, service: SubtitleService) -> Error {
        switch error {
        case HTTPClientError.badStatus(401), HTTPClientError.badStatus(403):
            return SubtitleSearchError.invalidCredentials(service)
        case HTTPClientError.badStatus(406), HTTPClientError.badStatus(429):
            return SubtitleSearchError.quotaReached(service)
        default:
            return error
        }
    }

    static func queryItems(_ items: [(String, String?)]) -> [URLQueryItem] {
        items.compactMap { name, value in value.map { URLQueryItem(name: name, value: $0) } }
    }
}
