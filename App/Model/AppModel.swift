import SwiftUI
import IPTVCore
import Observation

enum LoadPhase: Equatable {
    case idle
    case loading(String)
    case loaded
    case failed(message: String, attribution: ErrorAttribution)

    var isLoading: Bool {
        if case .loading = self { return true }
        return false
    }
}

extension ErrorAttribution {
    var label: String {
        switch self {
        case .provider: return String(localized: "Problem on the provider's side")
        case .network: return String(localized: "Network problem")
        case .app: return String(localized: "Problem in the app")
        }
    }

    var systemImage: String {
        switch self {
        case .provider: return "server.rack"
        case .network: return "wifi.exclamationmark"
        case .app: return "exclamationmark.triangle"
        }
    }
}

/// Lookup tables built off the main thread whenever a catalogue is loaded.
struct ContentIndex: Sendable {
    var channelsByCategory: [String: [Channel]] = [:]
    var moviesByCategory: [String: [Movie]] = [:]
    var seriesByCategory: [String: [Series]] = [:]
    var channelsByID: [String: Channel] = [:]
    var moviesByID: [String: Movie] = [:]
    var seriesByID: [String: Series] = [:]
    /// Versions of the same channel ("TV1 HD", "FR: TV1 FHD"…) for automatic failover.
    var channelsByMatchKey: [String: [Channel]] = [:]
    var search = SearchEngine(content: .empty)

    init() {}

    init(content: PlaylistContent) {
        channelsByCategory = Dictionary(grouping: content.channels, by: \.categoryID)
        moviesByCategory = Dictionary(grouping: content.movies, by: \.categoryID)
        seriesByCategory = Dictionary(grouping: content.series, by: \.categoryID)
        channelsByID = Dictionary(content.channels.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        moviesByID = Dictionary(content.movies.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        seriesByID = Dictionary(content.series.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        channelsByMatchKey = Dictionary(grouping: content.channels, by: { TextNormalizer.channelMatchKey($0.name) })
            .filter { $0.value.count > 1 && !$0.key.isEmpty }
        search = SearchEngine(content: content)
    }
}

/// Global app state: playlists, the active catalogue, guide, library and playback sessions.
@MainActor
@Observable
final class AppModel {
    @ObservationIgnored private let userStore: FileStore
    @ObservationIgnored private let cacheStore: FileStore
    @ObservationIgnored let secrets: SecretStore
    @ObservationIgnored let reminderScheduler: ReminderScheduling
    /// The latest reminder sync; the next one waits for it (see `syncReminders`).
    @ObservationIgnored var reminderSync: Task<Void, Never>?
    @ObservationIgnored let http: URLSessionHTTPClient
    @ObservationIgnored let loader: PlaylistLoader

    private(set) var isReady = false
    private(set) var playlists: [Playlist] = []
    private(set) var settings = AppSettings()
    private(set) var library = UserLibrary()
    private(set) var profiles: [Profile] = []
    /// Category locks are device-wide (parental control), not per profile.
    private(set) var lockedCategoryKeys: Set<String> = []
    /// "Who's watching?" screen at launch when several profiles exist.
    var needsProfileSelection = false

    private(set) var content = PlaylistContent.empty
    /// The catalogue as loaded, before the user's channel edits (kept to undo them).
    @ObservationIgnored private(set) var rawContent = PlaylistContent.empty
    private(set) var index = ContentIndex()
    /// Language and country tags of the catalogue's categories ("|PT| Filmes").
    private(set) var categoryTags = CategoryTagIndex()
    /// Awards and festival selections of the catalogue's movies, by movie id (bundled data, matched on the device).
    private(set) var movieAwards: [String: [AwardsCatalogue.Entry]] = [:]
    private(set) var epg = EPGIndex.empty
    private(set) var phase: LoadPhase = .idle
    private(set) var epgPhase: LoadPhase = .idle
    private(set) var isRefreshing = false
    /// Non-blocking error shown as a banner when a refresh fails but cached data is available.
    var bannerMessage: String?
    var isParentalUnlocked = false

    /// Drives the full-screen player on iOS and the player windows on macOS.
    var playbackRequest: PlaybackSession?
    /// Channels watched side by side (iPad / Mac).
    let multiview = Multiview()
    /// Shows the multiview screen (full-screen cover on iPad, a window on Mac).
    var isMultiviewPresented = false
    /// Session kept alive while playing in Picture in Picture with the player UI closed.
    var pictureInPictureSession: PlaybackSession?
    /// Playback of a locked category reached without going through its lock (a reminder, a download, the last
    /// channel at launch): it waits for the parental PIN.
    var pendingLockedPlayback: PendingPlayback?
    /// "Play last channel at launch" waits for the profile to be chosen ("Who's watching?").
    @ObservationIgnored var autoplayPending = false
    @ObservationIgnored var sessions: [UUID: PlaybackSession] = [:]

    @ObservationIgnored private var librarySaveTask: Task<Void, Never>?
    @ObservationIgnored private var epgTask: Task<Void, Never>?
    @ObservationIgnored var movieDetailsCache: [String: MediaDetails] = [:]
    @ObservationIgnored var seriesDetailsCache: [String: SeriesDetails] = [:]
    /// Subtitle services built from the saved keys; rebuilt when a key changes (OpenSubtitles keeps its
    /// signed-in session in its client, so it lives as long as the credentials).
    @ObservationIgnored var subtitleProviders: [any SubtitleProvider]?
    /// Episodes are being fetched before a series starts ("Resume" on a series): shown, so the tap visibly
    /// did something while the provider answers.
    var isPreparingPlayback = false
    /// Bumped when details arrive. The caches are not observed (one change per entry would redraw every
    /// list); screens that show cached details (hero, continue watching) read this instead, so a backdrop
    /// or a plot that arrives after them appears.
    var detailsRevision = 0
    @ObservationIgnored private var shortEPGRequested = Set<String>()
    /// Playlist channels per guide channel, rebuilt when the catalogue or the guide changes.
    @ObservationIgnored var channelsByGuideIDCache: (key: String, map: [String: [Channel]])?

    /// Cached so parental checks in long lists do not hit the keychain.
    private(set) var hasParentalPIN = false
    /// Trending titles (TMDb) that exist in the active catalogue.
    private(set) var trending: [TrendingMatch] = []
    private(set) var hasTMDbKey = false
    @ObservationIgnored private var trendingLoadedFor: (playlist: UUID, date: Date)?

    /// - Parameters:
    ///   - storageRoot: directory for user data and caches (tests use a temporary one).
    ///   - secrets: where passwords, tokens and PIN hashes are stored.
    ///   - reminderScheduler: delivers programme reminders (system notifications by default).
    init(storageRoot: URL? = nil, secrets: SecretStore = KeychainSecretStore(), reminderScheduler: ReminderScheduling? = nil) {
        userStore = FileStore(directory: storageRoot?.appendingPathComponent("Support", isDirectory: true) ?? AppPaths.support)
        cacheStore = FileStore(directory: storageRoot?.appendingPathComponent("Caches", isDirectory: true) ?? AppPaths.caches)
        self.secrets = secrets
        // UI tests must never hit the system permission prompt.
        let isUITest = ProcessInfo.processInfo.arguments.contains { $0.hasPrefix("-uitest") }
        self.reminderScheduler = reminderScheduler ?? (isUITest ? InMemoryReminderScheduler() : UserNotificationReminderScheduler())
        let client = URLSessionHTTPClient()
        http = client
        loader = PlaylistLoader(http: client, localFileURL: { AppPaths.importedPlaylist($0) })
    }

    // MARK: - Startup

    func bootstrap() async {
        guard !isReady else { return }
        #if DEBUG  // only in test builds: an argument must not be able to wipe a user's data
        if ProcessInfo.processInfo.arguments.contains("-uitest-reset") {
            // UI tests start from a clean install.
            for directory in [userStore.directory, cacheStore.directory] {
                let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
                for file in files { try? FileManager.default.removeItem(at: file) }
            }
            try? FileManager.default.createDirectory(at: AppPaths.importedPlaylists, withIntermediateDirectories: true)
        }
        #endif
        settings = userStore.load(AppSettings.self, name: "settings") ?? AppSettings()
        profiles = userStore.load([Profile].self, name: "profiles") ?? []
        if profiles.isEmpty {
            profiles = [Profile(id: Profile.defaultID, name: String(localized: "Me"), colorIndex: 0)]
        }
        library = userStore.load(UserLibrary.self, name: activeProfile.libraryDocumentName) ?? UserLibrary()
        if let locks = userStore.load(Set<String>.self, name: "parental-locks") {
            lockedCategoryKeys = locks
        } else {
            // Migration: locks used to live in the (single) library.
            lockedCategoryKeys = library.lockedCategoryKeys
        }
        needsProfileSelection = profiles.count > 1
        playlists = (userStore.load([Playlist].self, name: "playlists") ?? []).map(withStoredPassword)
        hasParentalPIN = secrets.get(Keychain.parentalPINKey) != nil
        hasTMDbKey = secrets.get(Keychain.tmdbKey) != nil
        isReady = true
        #if DEBUG
        if UITestFixture.isEnabled, playlists.isEmpty, let fixture = try? UITestFixture.makePlaylist() {
            try? await addPlaylist(fixture)
            await UITestFixture.decorate(self)
            return
        }
        #endif
        updateLibrary { $0.pruneReminders(endedBefore: Date()) }
        scheduleReminderSync()
        if let playlist = activePlaylist {
            await activate(playlist)
            if settings.autoplayLastChannel {
                // With several profiles, the channel is the chosen profile's, once it is chosen.
                if needsProfileSelection { autoplayPending = true } else { autoplayLastChannel() }
            }
        }
    }

    var activePlaylist: Playlist? {
        playlists.first { $0.id == settings.activePlaylistID } ?? playlists.first
    }

    var isXtream: Bool { activePlaylist?.source.xtreamCredentials != nil }

    // MARK: - Persistence

    private func withStoredPassword(_ playlist: Playlist) -> Playlist {
        guard let secret = playlist.source.secret, secret.isEmpty,
              let stored = secrets.get(Keychain.passwordKey(for: playlist.id)) else { return playlist }
        var copy = playlist
        copy.source = playlist.source.withSecret(stored)
        return copy
    }

    private func savePlaylists() {
        // Passwords live in the keychain, not in the JSON file (unless the keychain is unavailable,
        // in which case dropping them would lose the login).
        let sanitized = playlists.map { playlist -> Playlist in
            guard let secret = playlist.source.secret,
                  secrets.set(secret, for: Keychain.passwordKey(for: playlist.id))
            else { return playlist }
            var copy = playlist
            copy.source = playlist.source.withSecret("")
            return copy
        }
        try? userStore.save(sanitized, name: "playlists")
    }

    func updateSettings(_ change: (inout AppSettings) -> Void) {
        var updated = settings
        change(&updated)
        guard updated != settings else { return }
        settings = updated
        try? userStore.save(settings, name: "settings")
    }

    func updateLibrary(_ change: (inout UserLibrary) -> Void) {
        change(&library)
        librarySaveTask?.cancel()
        let store = userStore
        let name = activeProfile.libraryDocumentName
        librarySaveTask = Task { [library] in
            try? await Task.sleep(for: .milliseconds(600))
            guard !Task.isCancelled else { return }
            try? store.save(library, name: name)
        }
    }

    /// Flushes pending writes (app going to background, profile switch).
    func flush() {
        librarySaveTask?.cancel()
        try? userStore.save(library, name: activeProfile.libraryDocumentName)
        try? userStore.save(settings, name: "settings")
    }

    // MARK: - Profiles

    var activeProfile: Profile {
        profiles.first { $0.id == settings.activeProfileID } ?? profiles.first ?? Profile(id: Profile.defaultID, name: "")
    }

    private func saveProfiles() {
        try? userStore.save(profiles, name: "profiles")
    }

    func switchProfile(to profile: Profile) {
        guard profiles.contains(where: { $0.id == profile.id }) else { return }
        flush()
        updateSettings { $0.activeProfileID = profile.id }
        library = userStore.load(UserLibrary.self, name: profile.libraryDocumentName) ?? UserLibrary()
        isParentalUnlocked = false
        needsProfileSelection = false
        scheduleReminderSync()
        if autoplayPending {
            autoplayPending = false
            autoplayLastChannel()
        }
    }

    func verifyPIN(_ pin: String, for profile: Profile, now: Date = Date()) -> Bool {
        guard let hash = profile.pinHash else { return true }
        return checkPIN(pin, against: hash, now: now)
    }

    @discardableResult
    func addProfile(name: String, colorIndex: Int, symbol: String, isKids: Bool, pin: String?) -> Profile {
        let profile = Profile(name: name, colorIndex: colorIndex, symbol: symbol, isKids: isKids, pinHash: pin.map(Keychain.hash(pin:)))
        profiles.append(profile)
        saveProfiles()
        return profile
    }

    func updateProfile(_ profile: Profile) {
        guard let position = profiles.firstIndex(where: { $0.id == profile.id }) else { return }
        profiles[position] = profile
        saveProfiles()
    }

    func deleteProfile(_ profile: Profile) {
        guard profiles.count > 1 else { return }
        let wasActive = settings.activeProfileID == profile.id || activeProfile.id == profile.id
        profiles.removeAll { $0.id == profile.id }
        saveProfiles()
        // A pending save of the deleted profile's library would write its file again.
        if wasActive { librarySaveTask?.cancel() }
        userStore.remove(name: profile.libraryDocumentName)
        if wasActive, let first = profiles.first {
            library = userStore.load(UserLibrary.self, name: first.libraryDocumentName) ?? UserLibrary()
            updateSettings { $0.activeProfileID = first.id }
        }
    }

    // MARK: - Trending (TMDb)

    func setTMDbCredential(_ value: String?) {
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines)
        secrets.set(trimmed?.isEmpty == false ? trimmed : nil, for: Keychain.tmdbKey)
        hasTMDbKey = trimmed?.isEmpty == false
        trendingLoadedFor = nil
        if !hasTMDbKey { trending = [] }
    }

    func loadTrending(force: Bool = false) async {
        guard let playlist = activePlaylist, let credential = secrets.get(Keychain.tmdbKey),
              !content.movies.isEmpty || !content.series.isEmpty else {
            trending = []
            return
        }
        if !force, let loaded = trendingLoadedFor, loaded.playlist == playlist.id, Date().timeIntervalSince(loaded.date) < 6 * 3600 {
            return
        }
        let language = Locale.preferredLanguages.first ?? "en-US"
        guard let items = try? await TMDbClient(credential: credential, http: http, language: language).trending() else { return }
        let movies = content.movies
        let series = content.series
        let matches = await Task.detached { CatalogueMatcher.match(items, movies: movies, series: series) }.value
        let excluded = excludedCategoryIDs
        trending = matches.filter { match in
            switch match {
            case .movie(let movie, _): return !excluded.contains(movie.categoryID)
            case .series(let series, _): return !excluded.contains(series.categoryID)
            }
        }
        trendingLoadedFor = (playlist.id, Date())
    }

    // MARK: - Playlists

    /// Downloads the playlist once to validate it, then saves and activates it.
    func addPlaylist(_ playlist: Playlist) async throws {
        let loader = self.loader
        let fresh = try await Task.detached { try await loader.load(playlist) }.value
        var stored = playlist
        stored.lastRefreshedAt = Date()
        playlists.append(stored)
        savePlaylists()
        await saveContentCache(fresh, for: stored.id)
        if settings.lockAdultCategoriesAutomatically {
            lockAdultCategories(playlistID: stored.id, content: fresh)
        }
        updateSettings { $0.activePlaylistID = stored.id; $0.hasCompletedOnboarding = true }
        await apply(fresh, for: stored)
        phase = .loaded
        loadEPG(for: stored, force: true)
    }

    func updatePlaylist(_ playlist: Playlist) async {
        guard let position = playlists.firstIndex(where: { $0.id == playlist.id }) else { return }
        let previous = playlists[position]
        playlists[position] = playlist
        savePlaylists()
        guard playlist.id == activePlaylist?.id else { return }
        if previous.channelEdits != playlist.channelEdits {
            await apply(rawContent, for: playlist)
        }
        if previous.epgChannelOverrides != playlist.epgChannelOverrides {
            epg.overrides = playlist.epgChannelOverrides
            // A guide channel nothing matched before has no programmes loaded yet.
            let added = Set(playlist.epgChannelOverrides.values).subtracting(previous.epgChannelOverrides.values)
            if added.contains(where: { !$0.isEmpty && (epg.data.programs[$0] ?? []).isEmpty }) {
                loadEPG(for: playlist, force: true)
            }
        }
        if previous.source != playlist.source || previous.headers != playlist.headers || previous.xtreamOutput != playlist.xtreamOutput
            || previous.xtreamForceM3U != playlist.xtreamForceM3U {
            await refreshContent(of: playlist, silent: false)
            loadEPG(for: playlist, force: true)
        } else if previous.epgURLs != playlist.epgURLs || previous.useProviderEPG != playlist.useProviderEPG
                    || previous.epgTimeShiftHours != playlist.epgTimeShiftHours {
            loadEPG(for: playlist, force: true)
        }
    }

    func deletePlaylist(_ playlist: Playlist) async {
        playlists.removeAll { $0.id == playlist.id }
        savePlaylists()
        secrets.set(nil, for: Keychain.passwordKey(for: playlist.id))
        cacheStore.remove(name: "content-\(playlist.id.uuidString)")
        cacheStore.remove(name: "epg-\(playlist.id.uuidString)")
        if case .local(let fileName) = playlist.source {
            try? FileManager.default.removeItem(at: AppPaths.importedPlaylist(fileName))
        }
        updateLibrary { $0.removeData(for: playlist.id) }
        if settings.activePlaylistID == playlist.id || activePlaylist == nil {
            updateSettings { $0.activePlaylistID = playlists.first?.id }
            if let next = activePlaylist {
                await activate(next)
            } else {
                content = .empty
                rawContent = .empty
                index = ContentIndex()
                categoryTags = CategoryTagIndex()
                movieAwards = [:]
                epg = .empty
                phase = .idle
                epgPhase = .idle
            }
        }
    }

    func movePlaylists(fromOffsets source: IndexSet, toOffset destination: Int) {
        playlists.move(fromOffsets: source, toOffset: destination)
        savePlaylists()
    }

    /// Copies a user selected file into the container and returns its stored name.
    func importPlaylistFile(at url: URL) throws -> String {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        let name = UUID().uuidString + "-" + url.lastPathComponent
        let destination = AppPaths.importedPlaylist(name)
        try FileManager.default.copyItem(at: url, to: destination)
        return name
    }

    // MARK: - Catalogue

    func activate(_ playlist: Playlist, forceRefresh: Bool = false) async {
        let switching = content.isEmpty || activePlaylist?.id != playlist.id || index.channelsByID.isEmpty
        updateSettings { $0.activePlaylistID = playlist.id }
        movieDetailsCache = [:]
        seriesDetailsCache = [:]
        shortEPGRequested = []
        isParentalUnlocked = false

        var hasCache = false
        if switching || forceRefresh {
            phase = .loading(String(localized: "Opening playlist…"))
            epgTask?.cancel()  // the previous playlist's guide must not land on this one
            epg = .empty
            epgPhase = .idle
            let store = cacheStore
            let name = "content-\(playlist.id.uuidString)"
            let cached = await Task.detached { store.load(PlaylistContent.self, name: name) }.value
            // Another playlist was opened meanwhile: that activation owns the screen now.
            guard activePlaylist?.id == playlist.id else { return }
            if let cached, !cached.isEmpty {
                await apply(cached, for: playlist)
                phase = .loaded
                hasCache = true
            } else {
                content = .empty
                rawContent = .empty
                index = ContentIndex()
                categoryTags = CategoryTagIndex()
                movieAwards = [:]
            }
        } else {
            hasCache = !content.isEmpty
        }

        if forceRefresh || !hasCache || playlist.needsRefresh() {
            await refreshContent(of: playlist, silent: hasCache)
        }
        guard activePlaylist?.id == playlist.id else { return }
        loadEPG(for: playlist, force: false)
    }

    func refreshActivePlaylist() async {
        guard let playlist = activePlaylist else { return }
        await refreshContent(of: playlist, silent: !content.isEmpty)
        loadEPG(for: playlist, force: true)
    }

    private func refreshContent(of playlist: Playlist, silent: Bool) async {
        isRefreshing = true
        defer { isRefreshing = false }
        if !silent { phase = .loading(String(localized: "Downloading playlist…")) }
        do {
            let loader = self.loader
            let fresh = try await Task.detached { try await loader.load(playlist) }.value
            await saveContentCache(fresh, for: playlist.id)
            if let position = playlists.firstIndex(where: { $0.id == playlist.id }) {
                playlists[position].lastRefreshedAt = Date()
                savePlaylists()
            }
            if settings.lockAdultCategoriesAutomatically {
                lockAdultCategories(playlistID: playlist.id, content: fresh)
            }
            guard activePlaylist?.id == playlist.id else { return }
            await apply(fresh, for: playlist)
            phase = .loaded
        } catch {
            let attribution = ErrorAttribution.classify(error)
            let message = error.localizedDescription
            if content.isEmpty {
                phase = .failed(message: message, attribution: attribution)
            } else {
                bannerMessage = String(localized: "Could not refresh the playlist: \(message)")
                phase = .loaded
            }
        }
    }

    private func apply(_ newContent: PlaylistContent, for playlist: Playlist) async {
        let edits = playlist.channelEdits
        let (edited, built, tags, awards) = await Task.detached {
            let edited = newContent.applying(edits)
            let awards = AwardsCatalogue.bundled.match(edited.movies)
            return (edited, ContentIndex(content: edited), CategoryTagIndex(content: edited), awards)
        }.value
        // While the catalogue was being built, another playlist may have been opened (its catalogue must
        // not be replaced by this one), or this one edited (build again with the current edits).
        guard let current = activePlaylist, current.id == playlist.id else { return }
        guard current.channelEdits == edits else {
            await apply(newContent, for: current)
            return
        }
        rawContent = newContent
        content = edited
        index = built
        categoryTags = tags
        movieAwards = awards
    }

    private func saveContentCache(_ content: PlaylistContent, for id: UUID) async {
        let store = cacheStore
        let name = "content-\(id.uuidString)"
        await Task.detached(priority: .utility) { try? store.save(content, name: name) }.value
    }

    // MARK: - Guide

    func loadEPG(for playlist: Playlist? = nil, force: Bool) {
        guard let playlist = playlist ?? activePlaylist else { return }
        epgTask?.cancel()
        let store = cacheStore
        let loader = self.loader
        let name = "epg-\(playlist.id.uuidString)"
        let content = self.content
        let daysAhead = settings.epgDaysAhead
        epgTask = Task {
            epgPhase = .loading(String(localized: "Loading TV guide…"))
            let cached = await Task.detached { store.load(EPGData.self, name: name) }.value
            let overrides = playlist.epgChannelOverrides
            if let cached, !Task.isCancelled {
                let index = await Task.detached { EPGIndex(data: cached, overrides: overrides) }.value
                guard !Task.isCancelled else { return }
                epg = index
            }
            let isStale = cached.map { Date().timeIntervalSince($0.fetchedAt) > 6 * 3600 } ?? true
            guard force || isStale else {
                epgPhase = .loaded
                return
            }
            let daysBack = min(max(content.channels.compactMap { $0.catchup?.days }.max() ?? 1, 1), 14)
            let window = EPGFilter.standard(daysBack: daysBack, daysAhead: daysAhead)
            let loading = Task.detached { await loader.loadEPG(for: playlist, content: content, window: window) }
            // Opening another playlist cancels this load: the downloads stop instead of finishing unseen.
            let result = await withTaskCancellationHandler { await loading.value } onCancel: { loading.cancel() }
            guard !Task.isCancelled, activePlaylist?.id == playlist.id else { return }
            if !result.data.programs.isEmpty {
                let data = result.data
                epg = await Task.detached { EPGIndex(data: data, overrides: overrides) }.value
                Task.detached(priority: .utility) { try? store.save(data, name: name) }
                epgPhase = .loaded
            } else if let failure = result.failures.values.first, cached == nil {
                epgPhase = .failed(message: failure.localizedDescription, attribution: ErrorAttribution.classify(failure))
            } else {
                epgPhase = .loaded
            }
        }
    }

    /// Xtream accounts: fetch now/next on demand for channels the XMLTV guide does not cover.
    func ensureGuide(for channel: Channel) async {
        guard epg.programs(for: channel).isEmpty,
              let streamID = channel.xtreamStreamID,
              let credentials = activePlaylist?.source.xtreamCredentials,
              shortEPGRequested.insert(channel.id).inserted else { return }
        let client = XtreamClient(credentials: credentials, http: http, headers: activePlaylist?.headers ?? .none)
        let guideID = channel.epgID ?? channel.id
        guard let programs = try? await client.shortEPG(streamID: streamID, epgChannelID: guideID, limit: 6), !programs.isEmpty else { return }
        epg.insert(programs, for: channel)
    }

    /// Full archive listing of a channel (catch-up screen).
    func archivePrograms(for channel: Channel) async -> [EPGProgram] {
        let fromGuide = epg.programs(for: channel)
        if !fromGuide.isEmpty { return fromGuide }
        guard let streamID = channel.xtreamStreamID, let credentials = activePlaylist?.source.xtreamCredentials else { return [] }
        let client = XtreamClient(credentials: credentials, http: http, headers: activePlaylist?.headers ?? .none)
        let programs = (try? await client.fullEPG(streamID: streamID, epgChannelID: channel.epgID ?? channel.id)) ?? []
        if !programs.isEmpty { epg.insert(programs, for: channel) }
        return programs
    }

    func logoURL(for channel: Channel) -> URL? {
        channel.logoURL ?? epg.iconURL(for: channel)
    }

    // MARK: - Categories & parental control

    var isParentalControlActive: Bool {
        if activeProfile.isKids { return !isParentalUnlocked }
        return settings.parentalControlEnabled && hasParentalPIN && !isParentalUnlocked
    }

    /// Whether the settings that could weaken parental control (the control itself, profiles, backups) must
    /// be unlocked first. A kids profile on a device where no parent can unlock (no PIN, no other profile)
    /// stays editable, or nobody could ever leave it.
    var settingsNeedParentalUnlock: Bool {
        guard isParentalControlActive else { return false }
        return hasParentalPIN || profiles.contains { !$0.isKids }
    }

    private func saveLocks() {
        try? userStore.save(lockedCategoryKeys, name: "parental-locks")
    }

    private func lockAdultCategories(playlistID: UUID, content: PlaylistContent) {
        let before = lockedCategoryKeys
        for category in content.liveCategories + content.movieCategories + content.seriesCategories where category.looksAdult {
            lockedCategoryKeys.insert(UserLibrary.categoryKey(playlistID: playlistID, categoryID: category.id))
        }
        if lockedCategoryKeys != before { saveLocks() }
    }

    /// Whether the category is configured as locked (regardless of the current unlock state).
    func isLockConfigured(_ category: MediaCategory) -> Bool {
        guard let playlist = activePlaylist else { return false }
        return lockedCategoryKeys.contains(UserLibrary.categoryKey(playlistID: playlist.id, categoryID: category.id))
    }

    func isLocked(_ category: MediaCategory) -> Bool {
        isLocked(categoryID: category.id)
    }

    func isLocked(categoryID: String) -> Bool {
        guard isParentalControlActive, let playlist = activePlaylist else { return false }
        return lockedCategoryKeys.contains(UserLibrary.categoryKey(playlistID: playlist.id, categoryID: categoryID))
    }

    /// Hidden by the viewer, on its own or with its tag ("PT").
    func isHidden(_ category: MediaCategory) -> Bool {
        isHiddenOnItsOwn(category) || hiddenTag(of: category) != nil
    }

    func isHiddenOnItsOwn(_ category: MediaCategory) -> Bool {
        guard let playlist = activePlaylist else { return false }
        return library.isHidden(playlistID: playlist.id, categoryID: category.id)
    }

    /// Categories shown in browse screens (hidden ones removed, empty ones skipped).
    func categories(for type: ContentType) -> [MediaCategory] {
        content.categories(for: type).filter { category in
            !isHidden(category) && count(in: category) > 0 && !isHiddenForKids(category)
        }
    }

    /// Kids profiles do not even see locked or adult-looking categories.
    func isHiddenForKids(_ category: MediaCategory) -> Bool {
        activeProfile.isKids && !isParentalUnlocked && (isLockConfigured(category) || category.looksAdult)
    }

    func count(in category: MediaCategory) -> Int {
        switch category.type {
        case .live: return index.channelsByCategory[category.id]?.count ?? 0
        case .movie: return index.moviesByCategory[category.id]?.count ?? 0
        case .series: return index.seriesByCategory[category.id]?.count ?? 0
        }
    }

    /// Category ids excluded from aggregated lists (search, home, "all channels").
    var excludedCategoryIDs: Set<String> {
        guard activePlaylist != nil else { return [] }
        var excluded = Set<String>()
        for category in content.liveCategories + content.movieCategories + content.seriesCategories {
            if isHidden(category) || isLocked(category) || isHiddenForKids(category) {
                excluded.insert(category.id)
            }
        }
        return excluded
    }

    func setHidden(_ hidden: Bool, category: MediaCategory) {
        guard let playlist = activePlaylist else { return }
        updateLibrary { $0.setHidden(hidden, playlistID: playlist.id, categoryID: category.id) }
    }

    func setLocked(_ locked: Bool, category: MediaCategory) {
        guard let playlist = activePlaylist else { return }
        let key = UserLibrary.categoryKey(playlistID: playlist.id, categoryID: category.id)
        if locked { lockedCategoryKeys.insert(key) } else { lockedCategoryKeys.remove(key) }
        saveLocks()
    }

    func setParentalPIN(_ pin: String?) {
        secrets.set(pin.map(Keychain.hash(pin:)), for: Keychain.parentalPINKey)
        hasParentalPIN = pin != nil
        updateSettings { $0.parentalControlEnabled = pin != nil }
        isParentalUnlocked = false
    }

    /// Unlocks parental control for this session. Without a PIN nothing can unlock it.
    func verifyParentalPIN(_ pin: String, now: Date = Date()) -> Bool {
        guard let stored = secrets.get(Keychain.parentalPINKey) else { return false }
        let ok = checkPIN(pin, against: stored, now: now)
        if ok { isParentalUnlocked = true }
        return ok
    }

    /// When the next PIN may be tried, if wrong PINs made it wait.
    func pinRetryDate(now: Date = Date()) -> Date? {
        guard let raw = secrets.get(Keychain.pinRetryKey), let seconds = TimeInterval(raw) else { return nil }
        let date = Date(timeIntervalSince1970: seconds)
        return date > now ? date : nil
    }

    /// Wait imposed after `failures` wrong PINs in a row: none for the first four, then 30 s, doubling up to
    /// an hour, so a 4-digit PIN cannot be found by trying them all.
    static func pinDelay(afterFailures failures: Int) -> TimeInterval? {
        guard failures >= 5 else { return nil }
        return min(30 * pow(2, Double(min(failures - 5, 12))), 3600)
    }

    /// Compares a PIN with its hash, refusing while a wait is running. The count of wrong PINs is kept with
    /// the secrets, so relaunching the app does not reset it.
    private func checkPIN(_ pin: String, against hash: String, now: Date) -> Bool {
        guard pinRetryDate(now: now) == nil else { return false }
        if Keychain.hash(pin: pin) == hash {
            secrets.set(nil, for: Keychain.pinFailuresKey)
            secrets.set(nil, for: Keychain.pinRetryKey)
            return true
        }
        let failures = (secrets.get(Keychain.pinFailuresKey).flatMap(Int.init) ?? 0) + 1
        secrets.set(String(failures), for: Keychain.pinFailuresKey)
        if let delay = Self.pinDelay(afterFailures: failures) {
            secrets.set(String(now.addingTimeInterval(delay).timeIntervalSince1970), for: Keychain.pinRetryKey)
        }
        return false
    }

    func lockParentalControl() {
        isParentalUnlocked = false
    }

    // MARK: - Data management

    func cacheSize() -> Int64 {
        cacheStore.diskUsage()
    }

    func clearCaches() async {
        for playlist in playlists {
            cacheStore.remove(name: "epg-\(playlist.id.uuidString)")
            if playlist.id != activePlaylist?.id { cacheStore.remove(name: "content-\(playlist.id.uuidString)") }
        }
        Artwork.clearCaches()
        loadEPG(force: true)
    }

    func exportBackup() throws -> Data {
        // Category locks are kept for the whole device, outside the profile's library: they travel with it.
        var archived = library
        archived.lockedCategoryKeys.formUnion(lockedCategoryKeys)
        return try BackupArchive(playlists: playlists, library: archived).encoded()
    }

    func importBackup(_ data: Data) async throws {
        let archive = try BackupArchive.decode(data)
        for playlist in archive.playlists where !playlists.contains(where: { $0.id == playlist.id }) {
            playlists.append(playlist)
        }
        savePlaylists()
        // The same merge as between devices: favourites, lists, history, reminders and hidden channels,
        // with deletions respected.
        updateLibrary { $0 = $0.merged(with: archive.library) }
        // A restore adds the backup's locks, it never removes one.
        let locks = lockedCategoryKeys.union(archive.library.lockedCategoryKeys)
        if locks != lockedCategoryKeys {
            lockedCategoryKeys = locks
            saveLocks()
        }
        scheduleReminderSync()
        updateSettings { $0.hasCompletedOnboarding = true }
        if let playlist = activePlaylist {
            await activate(playlist)
        }
    }
}
