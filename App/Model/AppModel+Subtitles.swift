import Foundation
import IPTVCore

/// Account fields of the subtitle services, kept in the keychain like the providers' passwords.
enum SubtitleCredential: String, CaseIterable {
    case openSubtitlesKey = "subtitles-opensubtitles-key"
    case openSubtitlesUsername = "subtitles-opensubtitles-username"
    case openSubtitlesPassword = "subtitles-opensubtitles-password"
    case subdlKey = "subtitles-subdl-key"
    case subSourceKey = "subtitles-subsource-key"
}

/// What a subtitle service said about the key or the account typed in Settings.
enum SubtitleKeyCheck: Equatable {
    case checking
    case accepted
    case refused
    case unreachable
}

extension SubtitleCredential {
    /// The fields of a service, its key first.
    static func fields(of service: SubtitleService) -> [SubtitleCredential] {
        switch service {
        case .openSubtitles: return [.openSubtitlesKey, .openSubtitlesUsername, .openSubtitlesPassword]
        case .subdl: return [.subdlKey]
        case .subSource: return [.subSourceKey]
        case .addic7ed: return []
        }
    }
}

/// Online subtitles: the user's own keys, the services they enable, and the query for what is playing.
extension AppModel {
    /// Checks a service's key (and OpenSubtitles' account) with the service and saves them only once it accepts them.
    /// An empty key removes what was saved, without a check; nil then.
    func checkAndSaveSubtitleCredentials(_ service: SubtitleService, values: [SubtitleCredential: String]) async -> SubtitleKeyCheck? {
        let fields = SubtitleCredential.fields(of: service)
        func value(_ field: SubtitleCredential) -> String? {
            let raw = values[field] ?? ""
            let trimmed = field == .openSubtitlesPassword ? raw : raw.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        guard let firstField = fields.first else { return nil }
        guard let key = value(firstField) else {
            for field in fields { setSubtitleCredential(nil, for: field) }
            return nil
        }
        let check: CredentialCheck
        switch service {
        case .openSubtitles:
            check = await OpenSubtitlesClient(
                apiKey: key, username: value(.openSubtitlesUsername), password: value(.openSubtitlesPassword),
                userAgent: Self.subtitleUserAgent, http: http
            ).checkCredentials()
        case .subdl:
            check = await SubDLClient(apiKey: key, http: http).checkKey()
        case .subSource:
            check = await SubSourceClient(apiKey: key, http: http).checkKey()
        case .addic7ed:
            return nil
        }
        switch check {
        case .accepted:
            for field in fields { setSubtitleCredential(value(field), for: field) }
            return .accepted
        case .refused:
            return .refused
        case .unreachable:
            return .unreachable
        }
    }

    func subtitleCredential(_ field: SubtitleCredential) -> String? {
        secrets.get(field.rawValue)
    }

    func setSubtitleCredential(_ value: String?, for field: SubtitleCredential) {
        let trimmed = field == .openSubtitlesPassword ? value : value?.trimmingCharacters(in: .whitespacesAndNewlines)
        let stored = trimmed?.isEmpty == false ? trimmed : nil
        guard stored != subtitleCredential(field) else { return }
        secrets.set(stored, for: field.rawValue)
        subtitleProviders = nil  // new credentials, new clients (and a refused login may be retried)
    }

    func setAddic7edSubtitles(_ enabled: Bool) {
        updateSettings { $0.addic7edSubtitles = enabled }
        subtitleProviders = nil
    }

    /// Services that can be searched: those with a key, and Addic7ed unless switched off.
    var subtitleServices: [SubtitleService] {
        subtitleSearch().providers.map(\.service)
    }

    func subtitleSearch() -> SubtitleSearch {
        if let subtitleProviders { return SubtitleSearch(providers: subtitleProviders) }
        var providers: [any SubtitleProvider] = []
        let userAgent = Self.subtitleUserAgent
        if let key = subtitleCredential(.openSubtitlesKey) {
            providers.append(OpenSubtitlesClient(
                apiKey: key,
                username: subtitleCredential(.openSubtitlesUsername),
                password: subtitleCredential(.openSubtitlesPassword),
                userAgent: userAgent,
                http: http
            ))
        }
        if let key = subtitleCredential(.subdlKey) { providers.append(SubDLClient(apiKey: key, http: http)) }
        if let key = subtitleCredential(.subSourceKey) { providers.append(SubSourceClient(apiKey: key, http: http)) }
        if settings.addic7edSubtitles { providers.append(Addic7edClient(userAgent: userAgent, http: http)) }
        subtitleProviders = providers
        return SubtitleSearch(providers: providers)
    }

    /// How the app introduces itself to subtitle services (OpenSubtitles requires a name and version).
    static var subtitleUserAgent: String {
        "Ondo v\(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0")"
    }

    /// The search for what is playing: the catalogue's title and year for a film, the series name with the
    /// season and episode for an episode. Every field stays editable in the search screen.
    func subtitleQuery(for item: PlaybackItem) -> SubtitleQuery {
        let languages = Self.subtitleLanguages(saved: settings.subtitleSearchLanguages, preferred: settings.preferredSubtitleLanguage,
                                               device: Locale.preferredLanguages)
        switch item.reference.type {
        case .movie:
            let movie = index.moviesByID[item.reference.itemID]
            return SubtitleQuery(title: movie?.name ?? item.title, year: movie?.year, languages: languages)
        case .series:
            let series = index.seriesByID[item.reference.itemID]
            let known = seriesDetailsCache[item.reference.itemID]?.seasons
                .flatMap(\.episodes)
                .first { $0.id == item.reference.episodeID }
            let parsed = item.subtitle.flatMap(Self.episodeNumbers(in:))
            return SubtitleQuery(
                title: series?.name ?? item.title,
                year: series?.year,
                season: known?.season ?? parsed?.season,
                episode: known?.number ?? parsed?.episode,
                languages: languages
            )
        case .live:
            return SubtitleQuery(title: item.title, languages: languages)
        }
    }

    /// "S01E02 · Arrival" -> (1, 2).
    static func episodeNumbers(in text: String) -> (season: Int, episode: Int)? {
        guard let match = text.range(of: #"[Ss](\d{1,3})\s*[Ee](\d{1,4})"#, options: .regularExpression) else { return nil }
        let digits = text[match].split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        guard digits.count == 2 else { return nil }
        return (digits[0], digits[1])
    }

    /// The last languages searched, else the preferred subtitle language, the device's, then English.
    static func subtitleLanguages(saved: [String], preferred: String?, device: [String]) -> [String] {
        if !saved.isEmpty { return saved }
        var languages: [String] = []
        for candidate in [preferred].compactMap { $0 } + device + ["en"] {
            guard let code = SubtitleLanguages.code(for: candidate), !languages.contains(code) else { continue }
            languages.append(code)
        }
        return Array(languages.prefix(2))
    }

    func rememberSubtitleLanguages(_ languages: [String]) {
        updateSettings { $0.subtitleSearchLanguages = languages }
    }
}
