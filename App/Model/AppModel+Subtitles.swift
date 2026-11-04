import Foundation
import IPTVCore

/// Account fields of the subtitle services, kept in the keychain like the providers' passwords; sync carries each one
/// to the user's other devices (`syncService`, docs/SYNC.md "Key").
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

    var service: SubtitleService {
        switch self {
        case .openSubtitlesKey, .openSubtitlesUsername, .openSubtitlesPassword: return .openSubtitles
        case .subdlKey: return .subdl
        case .subSourceKey: return .subSource
        }
    }

    /// Its record's `service` in sync's `keys`.
    var syncService: String {
        switch self {
        case .openSubtitlesKey: return SyncKey.openSubtitles
        case .openSubtitlesUsername: return SyncKey.openSubtitlesUsername
        case .openSubtitlesPassword: return SyncKey.openSubtitlesPassword
        case .subdlKey: return SyncKey.subdl
        case .subSourceKey: return SyncKey.subSource
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
        subtitleChecksInFlight.insert(service)
        defer { subtitleChecksInFlight.remove(service) }
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

    /// Saves a field typed here (nil or empty removes it): the other devices get the change by sync, dated now.
    func setSubtitleCredential(_ value: String?, for field: SubtitleCredential, now: Date = Date()) {
        let trimmed = field == .openSubtitlesPassword ? value : value?.trimmingCharacters(in: .whitespacesAndNewlines)
        let stored = trimmed?.isEmpty == false ? trimmed : nil
        guard stored != subtitleCredential(field), storeSubtitleCredential(stored, for: field) else { return }
        let time = syncStamp(now).syncMilliseconds
        // A removal keeps its time too: a merge made before it cannot bring the key back.
        syncState.subtitleKeysUpdatedAt[field.syncService] = time
        if stored == nil { syncState.deleted["key:" + field.syncService] = time }
        saveSyncState(now: now)
        syncSoon()
    }

    /// Writes a field to the keychain (nil removes it); sync calls it without dating it again. False when the keychain
    /// refused the write.
    @discardableResult
    func storeSubtitleCredential(_ value: String?, for field: SubtitleCredential) -> Bool {
        let stored = secrets.set(value, for: field.rawValue)
        subtitleProviders = nil  // new credentials, new clients (and a refused login may be retried)
        return stored
    }

    func setAddic7edSubtitles(_ enabled: Bool, now: Date = Date()) {
        guard enabled != settings.addic7edOptIn else { return }
        updateSettings { $0.addic7edOptIn = enabled }
        subtitleProviders = nil
        syncState.subtitleKeysUpdatedAt[SyncKey.addic7ed] = syncStamp(now).syncMilliseconds
        saveSyncState(now: now)
        syncSoon()
    }

    /// The subtitle services' fields of the keychain and the Addic7ed switch, as sync sends them: each dated when it was
    /// saved (here or on the device it came from); the switch only once it was turned on or off somewhere.
    func subtitleSyncKeys() -> [SyncKey] {
        var keys: [SyncKey] = SubtitleCredential.allCases.compactMap { field in
            guard let value = subtitleCredential(field), !value.isEmpty else { return nil }
            return SyncKey(service: field.syncService, value: value, updatedAt: syncState.subtitleKeysUpdatedAt[field.syncService] ?? 0)
        }
        if let time = syncState.subtitleKeysUpdatedAt[SyncKey.addic7ed] {
            keys.append(SyncKey(service: SyncKey.addic7ed, value: settings.addic7edOptIn ? SyncKey.on : SyncKey.off, updatedAt: time))
        }
        return keys
    }

    /// The subtitle services' records of a merged snapshot: a field saved when it changed (its service checked it on the
    /// device where it was typed, so it is not checked again), removed when it was removed elsewhere since it was saved
    /// here; Addic7ed turned on or off. A service whose key is being checked here is left as it is: its save comes after.
    func applySyncedSubtitleKeys(_ snapshot: SyncSnapshot) {
        var changed = false
        for field in SubtitleCredential.allCases where !subtitleChecksInFlight.contains(field.service) {
            let service = field.syncService
            let record = snapshot.keys.first { $0.service == service }
            let current = subtitleCredential(field)
            if let record {
                let usable = field == .openSubtitlesPassword ? SyncMapping.isUsablePassword(record.value) : SyncMapping.isUsableKey(record.value)
                // Saved here after the snapshot that was merged: the next session sends it.
                guard usable, record.updatedAt >= (syncState.subtitleKeysUpdatedAt[service] ?? .min) else { continue }
                if record.value == current {
                    syncState.subtitleKeysUpdatedAt[service] = record.updatedAt
                } else if storeSubtitleCredential(record.value, for: field) {
                    // Dated as the merge dated it only once the keychain holds it.
                    syncState.subtitleKeysUpdatedAt[service] = record.updatedAt
                    changed = true
                }
            } else if let removed = snapshot.deleted["key:" + service], removed >= (syncState.subtitleKeysUpdatedAt[service] ?? 0),
                      current != nil {
                // Removed elsewhere: no new deletion here, the other device's time stays.
                if storeSubtitleCredential(nil, for: field) {
                    syncState.subtitleKeysUpdatedAt[service] = removed
                    changed = true
                }
            }
        }
        if let record = snapshot.keys.first(where: { $0.service == SyncKey.addic7ed }), record.value == SyncKey.on || record.value == SyncKey.off,
           record.updatedAt >= (syncState.subtitleKeysUpdatedAt[SyncKey.addic7ed] ?? .min) {
            let on = record.value == SyncKey.on
            if on != settings.addic7edOptIn {
                updateSettings { $0.addic7edOptIn = on }
                subtitleProviders = nil
            }
            syncState.subtitleKeysUpdatedAt[SyncKey.addic7ed] = record.updatedAt
        }
        if changed { subtitleCredentialsRevision += 1 }
    }

    /// Services that can be searched: those with a key, and Addic7ed once turned on (off by default).
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
        if settings.addic7edOptIn { providers.append(Addic7edClient(userAgent: userAgent, http: http)) }
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
        for candidate in [preferred].compactMap({ $0 }) + device + ["en"] {
            guard let code = SubtitleLanguages.code(for: candidate), !languages.contains(code) else { continue }
            languages.append(code)
        }
        return Array(languages.prefix(2))
    }

    func rememberSubtitleLanguages(_ languages: [String]) {
        updateSettings { $0.subtitleSearchLanguages = languages }
    }
}
