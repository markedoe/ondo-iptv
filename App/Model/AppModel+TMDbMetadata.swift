import Foundation
import IPTVCore
import Observation

/// What TMDb said about the titles and people on screen (with the viewer's token): read from the store kept between
/// launches (`AppModel.metadataStore`) at once, fetched again only when missing or old, a few titles ahead in the
/// background (Home's carousel, the titles in progress, the rows as they appear).
@MainActor
@Observable
final class TMDbMetadata {
    /// Background requests at the same time, and the pace of all of them (TMDb's limits are far above).
    static let maxPrefetching = 2
    static let requestInterval: Duration = .milliseconds(250)

    /// Titles by key (`MetadataKey`): read from the store, or fetched this session.
    var titles: [String: TitleMetadata] = [:]
    /// People's photos by key (`MetadataKey.person`).
    var photos: [String: URL] = [:]

    /// Keys already looked for in the store (found there or not).
    @ObservationIgnored var read: Set<String> = []
    /// Titles (`ShowcaseItem.id`) asked for this session, answered or not: not asked again before the next launch.
    @ObservationIgnored var attempted: Set<String> = []
    @ObservationIgnored var queue = MetadataQueue()
    @ObservationIgnored var waiting: [String: ShowcaseItem] = [:]
    @ObservationIgnored var prefetching: [String: Task<Void, Never>] = [:]
    /// Pages the viewer opened, fetched at once.
    @ObservationIgnored var opened: [String: Task<Void, Never>] = [:]
    /// Titles that appeared, checked against the store together a moment later.
    @ObservationIgnored var toCheck: [ShowcaseItem] = []
    @ObservationIgnored var checking: Set<String> = []
    @ObservationIgnored var checkTask: Task<Void, Never>?
    /// The app is in the background: nothing is fetched.
    @ObservationIgnored var isPaused = false
    @ObservationIgnored let pacer = RequestPacer(interval: TMDbMetadata.requestInterval)
    /// Each title's keys, by its id and the TMDb id its details gave (computing a key reads the title's words).
    @ObservationIgnored var keysCache: [String: [String]] = [:]
}

extension AppModel {
    // MARK: - The store

    /// The SQLite file, or a store in memory when it cannot be opened (TMDb is then asked again at each launch).
    nonisolated static func openMetadataStore(in folder: URL) -> any MetadataStoring {
        (try? SQLiteMetadataStore(directory: folder)) ?? InMemoryMetadataStore()
    }

    /// The image addresses that led nowhere in the last days: their titles show TMDb's picture or the blurred poster
    /// at once.
    func loadDeadImages() async {
        let store = metadataStore
        let since = Date().addingTimeInterval(-MetadataFreshness.deadImage)
        let dead = await Task.detached(priority: .userInitiated) { store.deadImages(since: since) }.value
        backdrops.dead.formUnion(dead)
    }

    /// The keys a title is read and written under (`MetadataKey.all`): its TMDb id's once its details give one, its
    /// title's always.
    func metadataKeys(for item: ShowcaseItem) -> [String] {
        let tmdbID = cachedDetails(for: item)?.tmdbID
        let cacheKey = item.id + "|" + (tmdbID ?? "")
        if let keys = tmdbMetadata.keysCache[cacheKey] { return keys }
        let keys = MetadataKey.all(item.contentType, tmdbID: tmdbID, name: item.title, year: item.year)
        tmdbMetadata.keysCache[cacheKey] = keys
        return keys
    }

    /// What TMDb said about a title, as far as this session knows (old answers included).
    func tmdbTitle(for item: ShowcaseItem) -> TitleMetadata? {
        metadataKeys(for: item).lazy.compactMap { self.tmdbMetadata.titles[$0] }.first
    }

    /// The provider's trailer, else TMDb's.
    func trailerYouTubeID(for item: ShowcaseItem, provider: String?) -> String? {
        if let provider = provider?.trimmingCharacters(in: .whitespaces), !provider.isEmpty { return provider }
        return tmdbTitle(for: item)?.trailerKey
    }

    /// "★ 7.1 (12,345)" with TMDb's rating and how many gave it, when TMDb gave both; else the provider's "★ 7.1".
    func ratingFact(for item: ShowcaseItem, provider rating: Double?) -> String? {
        if let votes = tmdbTitle(for: item)?.votes {
            return "★ " + Formatting.rating(votes.average) + " (" + votes.count.formatted() + ")"
        }
        guard let rating, rating > 0 else { return nil }
        return "★ " + Formatting.rating(rating)
    }

    /// Clear Cache: TMDb's answers, the people index and the dead image addresses leave the store. The titles on screen
    /// keep what they show; the store is filled again as titles are fetched and the catalogue is read.
    func forgetTMDbMetadata() async {
        let store = metadataStore
        await Task.detached(priority: .utility) { store.removeAll() }.value
        tmdbMetadata.read = []
        tmdbMetadata.attempted = []
    }

    /// Reads what the store has for these keys, once a session each.
    func readStoredMetadata(_ keys: [String]) async {
        let missing = Array(Set(keys).subtracting(tmdbMetadata.read))
        guard !missing.isEmpty else { return }
        tmdbMetadata.read.formUnion(missing)
        let store = metadataStore
        let found = await Task.detached(priority: .userInitiated) { store.titles(missing) }.value
        for (key, title) in found where tmdbMetadata.titles[key] == nil {
            tmdbMetadata.titles[key] = title
        }
    }

    /// What the store has for a title, at once (a page that opens shows it before anything is fetched).
    func showStoredMetadata(for item: ShowcaseItem) async {
        await readStoredMetadata(metadataKeys(for: item))
    }

    private func isFresh(_ keys: [String]) -> Bool {
        keys.lazy.compactMap { self.tmdbMetadata.titles[$0] }.first.map { MetadataFreshness.isFresh($0) } ?? false
    }

    private var tmdbLanguage: String {
        Locale.preferredLanguages.first ?? "en-US"
    }

    // MARK: - A page the viewer opens

    /// With the viewer's TMDb token, what TMDb knows of a title the viewer has on screen (its page, the carousel's
    /// current title): from the store at once, then fetched ahead of the background prefetch when missing or old.
    /// Only the title's TMDb id, else its title, is sent; its year is only compared on the device.
    func loadTMDbMetadata(for item: ShowcaseItem) async {
        let keys = metadataKeys(for: item)
        guard !keys.isEmpty else { return }
        await readStoredMetadata(keys)
        let state = tmdbMetadata
        guard hasTMDbKey, !state.isPaused, !isFresh(metadataKeys(for: item)) else { return }
        if let running = state.opened[item.id] ?? state.prefetching[item.id] {
            await running.value
            return
        }
        guard !state.attempted.contains(item.id) else { return }
        // It jumps the queue.
        state.queue.remove(item.id)
        state.waiting[item.id] = nil
        let task = Task { await fetchTMDbMetadata(for: item, paced: false) }
        state.opened[item.id] = task
        await task.value
        state.opened[item.id] = nil
    }

    // MARK: - Background prefetch

    /// Titles about to be seen (Home's carousel, the titles in progress, the first rows, then each card as its row
    /// appears): those the store has no recent answer for are fetched in the background, two at a time, a few
    /// requests a second, only with the viewer's TMDb token.
    func prefetchMetadata(_ items: [ShowcaseItem]) {
        guard hasTMDbKey, !isTMDbKeyRefused else { return }
        let state = tmdbMetadata
        let new = items.filter { item in
            !state.attempted.contains(item.id) && !state.checking.contains(item.id) && state.waiting[item.id] == nil
                && state.prefetching[item.id] == nil && state.opened[item.id] == nil
        }
        guard !new.isEmpty else { return }
        state.toCheck += new
        state.checking.formUnion(new.map(\.id))
        guard state.checkTask == nil else { return }
        state.checkTask = Task {
            // The cards that appear together are read from the store together.
            try? await Task.sleep(for: .milliseconds(300))
            await checkPrefetchBatch()
        }
    }

    private func checkPrefetchBatch() async {
        let state = tmdbMetadata
        let batch = state.toCheck
        state.toCheck = []
        state.checkTask = nil
        let keyed = batch.map { ($0, metadataKeys(for: $0)) }
        await readStoredMetadata(keyed.flatMap { $0.1 })
        for (item, keys) in keyed {
            state.checking.remove(item.id)
            guard !keys.isEmpty, !isFresh(keys), !state.attempted.contains(item.id), state.opened[item.id] == nil,
                  state.prefetching[item.id] == nil else { continue }
            if state.queue.add(item.id) { state.waiting[item.id] = item }
        }
        // Those the queue dropped (scrolled long ago).
        if state.waiting.count > state.queue.count {
            state.waiting = state.waiting.filter { state.queue.contains($0.key) }
        }
        pumpMetadataQueue()
    }

    /// Starts the next waiting titles, up to `TMDbMetadata.maxPrefetching` at a time.
    func pumpMetadataQueue() {
        let state = tmdbMetadata
        while !state.isPaused, hasTMDbKey, !isTMDbKeyRefused, state.prefetching.count < TMDbMetadata.maxPrefetching,
              let id = state.queue.next() {
            guard let item = state.waiting.removeValue(forKey: id), !state.attempted.contains(id), state.opened[id] == nil else { continue }
            state.prefetching[id] = Task {
                await fetchTMDbMetadata(for: item, paced: true)
                tmdbMetadata.prefetching[id] = nil
                pumpMetadataQueue()
            }
        }
    }

    /// The app goes to the background: the requests under way stop (their titles wait for the return).
    func pauseMetadataPrefetch() {
        tmdbMetadata.isPaused = true
        for task in tmdbMetadata.prefetching.values { task.cancel() }
        for task in tmdbMetadata.opened.values { task.cancel() }
    }

    func resumeMetadataPrefetch() {
        guard tmdbMetadata.isPaused else { return }
        tmdbMetadata.isPaused = false
        pumpMetadataQueue()
    }

    /// Another token (or none): titles are asked again with it; without one, nothing waits.
    func tmdbCredentialChanged() {
        let state = tmdbMetadata
        state.attempted = []
        if !hasTMDbKey {
            state.queue.removeAll()
            state.waiting = [:]
            for task in state.prefetching.values { task.cancel() }
        }
    }

    /// Asks TMDb about a title and keeps the answer, a "not found" included, under each of its keys. A request TMDb did
    /// not answer keeps nothing: the title is asked again at the next launch.
    private func fetchTMDbMetadata(for item: ShowcaseItem, paced: Bool) async {
        guard let credential = secrets.get(Keychain.tmdbKey) else { return }
        let details = cachedDetails(for: item)
        let title = details.map(\.title).flatMap { $0.isEmpty ? nil : $0 } ?? item.title
        let year = item.year ?? details?.releaseDate.flatMap { Int($0.prefix(4)) }
        let client = TMDbClient(credential: credential, http: http, language: tmdbLanguage, pacer: paced ? tmdbMetadata.pacer : nil)
        let info: TMDbTitleInfo?
        do {
            info = try await client.titleInfo(of: item.contentType, tmdbID: details?.tmdbID, title: title, year: year)
        } catch {
            if Task.isCancelled {
                // The app went to the background: first in line when it comes back.
                if tmdbMetadata.queue.add(item.id, urgent: true) { tmdbMetadata.waiting[item.id] = item }
            } else {
                // TMDb refused or failed: not asked again this session. Out of reach: the next page or card asks again.
                if !(error is URLError) { tmdbMetadata.attempted.insert(item.id) }
                noteTMDbFailure(error, credential: credential)
            }
            return
        }
        tmdbMetadata.attempted.insert(item.id)
        var keys = metadataKeys(for: item)
        // Also under the id TMDb gave: found at once when the provider's details name it later.
        if let info, !keys.contains(MetadataKey.tmdb(item.contentType, id: info.tmdbID)) {
            keys.append(MetadataKey.tmdb(item.contentType, id: info.tmdbID))
        }
        let now = Date()
        let rows = keys.map { TitleMetadata(key: $0, kind: MetadataKey.kind(item.contentType), info: info, fetchedAt: now) }
        for row in rows { tmdbMetadata.titles[row.key] = row }
        tmdbMetadata.read.formUnion(keys)
        let credits = info.map(CreditRecord.credits(of:)) ?? []
        let store = metadataStore
        let savedKeys = keys
        await Task.detached(priority: .utility) {
            store.save(rows)
            if info != nil { store.saveCredits(credits, for: savedKeys) }
        }.value
    }

    // MARK: - People

    /// A person's photo, when the store or TMDb gave one.
    func personPhoto(named name: String) -> URL? {
        tmdbMetadata.photos[MetadataKey.person(name)]
    }

    /// Reads the photos the store has for these people.
    func loadPersonPhotos(_ names: [String]) async {
        let keys = names.map(MetadataKey.person).filter { !$0.isEmpty && tmdbMetadata.photos[$0] == nil }
        guard !keys.isEmpty else { return }
        let store = metadataStore
        let people = await Task.detached(priority: .userInitiated) { store.people(keys) }.value
        for (key, person) in people {
            if let photo = person.photoURL { tmdbMetadata.photos[key] = photo }
        }
    }

    /// People TMDb told about (a search, a person's page): their photo and TMDb id are kept.
    func rememberTMDbPeople(_ people: [TMDbPerson], as names: [String]? = nil) {
        let now = Date()
        let records = people.enumerated().compactMap { index, person -> PersonRecord? in
            let name = names.flatMap { index < $0.count ? $0[index] : nil } ?? person.name
            let key = MetadataKey.person(name)
            guard !key.isEmpty else { return nil }
            if let photo = person.profileURL { tmdbMetadata.photos[key] = photo }
            return PersonRecord(personKey: key, displayName: name, tmdbID: person.id, photoURL: person.profileURL, fetchedAt: now)
        }
        guard !records.isEmpty else { return }
        let store = metadataStore
        Task.detached(priority: .utility) { store.savePeople(records) }
    }

    /// Adds the people of the provider's cast and director fields to the local index, under the title's keys.
    func indexProviderPeople(_ entries: [(item: ShowcaseItem, details: MediaDetails)]) {
        let named = entries.filter { $0.details.cast?.isEmpty == false || $0.details.director?.isEmpty == false }
        guard !named.isEmpty else { return }
        let store = metadataStore
        Task.detached(priority: .utility) {
            let links = named.flatMap { entry in
                PersonLink.links(fields: [entry.details.cast, entry.details.director],
                                 titleKeys: MetadataKey.all(entry.item.contentType, tmdbID: entry.details.tmdbID, name: entry.item.title, year: entry.item.year))
            }
            for start in stride(from: 0, to: links.count, by: 2_000) {
                store.index(Array(links[start..<min(start + 2_000, links.count)]))
            }
        }
    }
}
