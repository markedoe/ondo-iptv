import Foundation
import IPTVCore

/// Browsing: filtered lists of the active catalogue and item details.
extension AppModel {
    // MARK: - Lists

    func channels(in category: MediaCategory?) -> [Channel] {
        let hidden = hiddenChannelIDs
        // A channel whose own name carries a hidden language leaves every list, its category's too.
        let excluded = exclusions
        guard let category else {
            return content.channels.filter { !excluded.contains($0) && !hidden.contains($0.id) }
        }
        let list = index.channelsByCategory[category.id] ?? []
        let byName = excluded.byName.channelIDs
        return hidden.isEmpty && byName.isEmpty ? list : list.filter { !hidden.contains($0.id) && !byName.contains($0.id) }
    }

    /// The movies of a category, or of all of them; the ones hidden as watched are left out unless `includingWatched`
    /// (search, and the lists kept in a cache, which leave them out once read from it).
    /// Each film once (`films`): in a category, the first of its versions there stands for it, so a film is in every
    /// category one of its versions is in.
    func movies(in category: MediaCategory?, sort: PosterSort, includingWatched: Bool = false) -> [Movie] {
        let list = distinctMovies(in: category, sort: sort)
        return includingWatched ? list : hidingWatched(list)
    }

    /// `sortedMovies` with each film once, kept like the sorted lists (once per catalogue, category and order).
    private func distinctMovies(in category: MediaCategory?, sort: PosterSort) -> [Movie] {
        let list = sortedMovies(in: category, sort: sort)
        let films = self.films
        guard films.isGrouped else { return list }
        let excluded = category == nil ? exclusions.hashValue : hiddenByName.hashValue
        let key = "films|\(activePlaylist?.id.uuidString ?? "")|\(content.fetchedAt.timeIntervalSince1970)|\(list.count)|\(category?.id ?? "*")|\(excluded)|\(sort.rawValue)"
        return cachedList(key) { films.distinct(list) }
    }

    private func sortedMovies(in category: MediaCategory?, sort: PosterSort) -> [Movie] {
        let list: [Movie]
        let key: String
        if let category {
            // A title whose own name carries a hidden language leaves its category's list too.
            let byName = hiddenByName.movieIDs
            let all = index.moviesByCategory[category.id] ?? []
            list = byName.isEmpty ? all : all.filter { !byName.contains($0.id) }
            key = "movies|\(activePlaylist?.id.uuidString ?? "")|\(content.fetchedAt.timeIntervalSince1970)|\(list.count)|\(category.id)|\(byName.hashValue)|\(sort.rawValue)"
        } else {
            let excluded = exclusions
            list = content.movies.filter { !excluded.contains($0) }
            key = "movies|\(activePlaylist?.id.uuidString ?? "")|\(content.fetchedAt.timeIntervalSince1970)|\(list.count)|*|\(excluded.hashValue)|\(sort.rawValue)"
        }
        guard sort != .playlist else { return list }
        // Sorting a large catalogue by name takes a moment: once per catalogue, category and order, not at every redraw.
        if let cached = sortedListCache[key] as? [Movie] { return cached }
        let sorted = Self.sorted(list, by: sort)
        cacheSortedList(sorted, key: key)
        return sorted
    }

    static func sorted(_ list: [Movie], by sort: PosterSort) -> [Movie] {
        switch sort {
        case .playlist: return list
        case .name: return list.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        case .recent: return list.sorted { ($0.addedAt ?? .distantPast) > ($1.addedAt ?? .distantPast) }
        case .release: return list.sorted { Self.releasedFirst(($0.year, $0.addedAt, $0.name), ($1.year, $1.addedAt, $1.name)) }
        case .rating: return list.sorted { ($0.rating ?? 0) > ($1.rating ?? 0) }
        }
    }

    func series(in category: MediaCategory?, sort: PosterSort) -> [Series] {
        let list: [Series]
        let key: String
        if let category {
            let byName = hiddenByName.seriesIDs
            let all = index.seriesByCategory[category.id] ?? []
            list = byName.isEmpty ? all : all.filter { !byName.contains($0.id) }
            key = "series|\(activePlaylist?.id.uuidString ?? "")|\(content.fetchedAt.timeIntervalSince1970)|\(list.count)|\(category.id)|\(byName.hashValue)|\(sort.rawValue)"
        } else {
            let excluded = exclusions
            list = content.series.filter { !excluded.contains($0) }
            key = "series|\(activePlaylist?.id.uuidString ?? "")|\(content.fetchedAt.timeIntervalSince1970)|\(list.count)|*|\(excluded.hashValue)|\(sort.rawValue)"
        }
        guard sort != .playlist else { return list }
        if let cached = sortedListCache[key] as? [Series] { return cached }
        let sorted = Self.sorted(list, by: sort)
        cacheSortedList(sorted, key: key)
        return sorted
    }

    /// Keeps the last few sorted lists (the grids on screen and the shelves of Home).
    private func cacheSortedList(_ list: Any, key: String) {
        if sortedListCache.count >= 16 { sortedListCache.removeAll() }
        sortedListCache[key] = list
    }

    /// The list kept under `key`, built once (`key` names everything the list depends on).
    func cachedList<T>(_ key: String, build: () -> [T]) -> [T] {
        if let cached = sortedListCache[key] as? [T] { return cached }
        let list = build()
        cacheSortedList(list, key: key)
        return list
    }

    static func sorted(_ list: [Series], by sort: PosterSort) -> [Series] {
        switch sort {
        case .playlist: return list
        case .name: return list.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        case .recent: return list.sorted { ($0.addedAt ?? .distantPast) > ($1.addedAt ?? .distantPast) }
        case .release: return list.sorted { Self.releasedFirst(($0.year, $0.addedAt, $0.name), ($1.year, $1.addedAt, $1.name)) }
        case .rating: return list.sorted { ($0.rating ?? 0) > ($1.rating ?? 0) }
        }
    }

    typealias ReleaseKey = (year: Int?, added: Date?, name: String)

    /// Newest release first (the year the provider or the title gives); titles without a year last; within a
    /// year, the latest addition first, then by name.
    static func releasedFirst(_ lhs: ReleaseKey, _ rhs: ReleaseKey) -> Bool {
        if lhs.year != rhs.year { return (lhs.year ?? Int.min) > (rhs.year ?? Int.min) }
        if lhs.added != rhs.added { return (lhs.added ?? .distantPast) > (rhs.added ?? .distantPast) }
        return lhs.name.localizedStandardCompare(rhs.name) == .orderedAscending
    }

    /// The hidden languages: a title whose own name carries one is left out of Search whatever its category.
    var hiddenTags: Set<String> { Set(library.hiddenCategoryTags.keys) }

    func search(_ query: String) -> SearchResults {
        Self.search(query, in: index.search, excluded: excludedCategoryIDs, hidden: hiddenChannelIDs, hiddenTags: hiddenTags, films: films)
    }

    /// `search` on a background task: the main thread (and the keyboard) stays free while a large playlist is searched.
    func searchInBackground(_ query: String) async -> SearchResults {
        let engine = index.search, excluded = excludedCategoryIDs, hidden = hiddenChannelIDs, tags = hiddenTags, films = self.films
        return await Task.detached(priority: .userInitiated) {
            Self.search(query, in: engine, excluded: excluded, hidden: hidden, hiddenTags: tags, films: films)
        }.value
    }

    private nonisolated static func search(
        _ query: String, in engine: SearchEngine, excluded: Set<String>, hidden: Set<String>, hiddenTags: Set<String>, films: FilmVersions
    ) -> SearchResults {
        var results = engine.search(query, limitPerType: 150, excludedCategoryIDs: excluded, hiddenTags: hiddenTags)
        if !hidden.isEmpty { results.channels.removeAll { hidden.contains($0.id) } }
        // Each film once, whatever its versions.
        results.movies = films.distinct(results.movies)
        return results
    }

    // MARK: - Details

    /// The details kept from an earlier visit or fetched this session, at once: a page shows them while
    /// `details(for:)` makes sure they are recent enough.
    func storedDetails(for movie: Movie) -> MediaDetails? {
        movieDetailsCache[movie.id]
    }

    func storedDetails(for series: Series) -> SeriesDetails? {
        seriesDetailsCache[series.id]
    }

    /// The kept details while they are recent (`DetailsArchive.movieMaxAge`), else the provider's; the kept ones when
    /// the provider cannot be reached.
    func details(for movie: Movie) async -> MediaDetails? {
        await storedDetailsLoaded()
        let kept = movieDetailsCache[movie.id]
        if let kept, DetailsArchive.isFresh(movieDetailsFetchedAt[movie.id], maxAge: DetailsArchive.movieMaxAge) { return kept }
        // The Home prefetch and the page may ask at the same time: one request.
        if let running = movieDetailsInFlight[movie.id] { return await running.value ?? kept }
        guard let streamID = movie.xtreamStreamID, let playlist = activePlaylist,
              let credentials = playlist.source.xtreamCredentials else { return kept }
        let client = XtreamClient(credentials: credentials, http: http, headers: playlist.headers)
        let request = Task { try? await client.movieDetails(streamID: streamID) }
        movieDetailsInFlight[movie.id] = request
        let fetched = await request.value
        movieDetailsInFlight[movie.id] = nil
        // Another playlist opened meanwhile: these details are not its own.
        guard let details = fetched, activePlaylist?.id == playlist.id else { return fetched ?? kept }
        movieDetailsCache[movie.id] = details
        movieDetailsFetchedAt[movie.id] = .now
        detailsRevision += 1
        scheduleStoredDetailsSave()
        indexProviderPeople([(.movie(movie), details)])
        return details
    }

    /// The kept episodes while they are recent (`DetailsArchive.seriesMaxAge`: a series gains episodes), else the
    /// provider's; the kept ones when the provider cannot be reached. Each episode shows its own title, TMDb's still
    /// where the provider has none, and one the provider does not name reads in the app's language ("Épisode 4")
    /// (`shown(_:of:)`); the kept ones stay as they came, as they are saved.
    func details(for series: Series) async throws -> SeriesDetails {
        let details = try await loadedDetails(for: series)
        return shown(details, of: series)
    }

    private func loadedDetails(for series: Series) async throws -> SeriesDetails {
        await storedDetailsLoaded()
        let kept = seriesDetailsCache[series.id]
        guard let seriesID = series.xtreamSeriesID, let playlist = activePlaylist,
              let credentials = playlist.source.xtreamCredentials else {
            if let kept { return kept }
            // Without a provider's server, the episodes come with the playlist: nothing to fetch or to keep on disk.
            let grouped = Dictionary(grouping: series.episodes, by: \.season)
            let seasons = grouped.keys.sorted().map { number in
                Season(number: number, name: String(localized: "Season \(number)"), episodes: grouped[number] ?? [])
            }
            let details = SeriesDetails(
                info: MediaDetails(title: series.name, plot: series.plot, genre: series.genre, rating: series.rating, posterURL: series.coverURL),
                seasons: seasons
            )
            seriesDetailsCache[series.id] = details
            detailsRevision += 1
            return details
        }
        if let kept, DetailsArchive.isFresh(seriesDetailsFetchedAt[series.id], maxAge: DetailsArchive.seriesMaxAge) { return kept }
        let client = XtreamClient(credentials: credentials, http: http, headers: playlist.headers)
        let details: SeriesDetails
        do {
            details = try await client.seriesDetails(seriesID: seriesID)
        } catch {
            if let kept { return kept }
            throw error
        }
        guard activePlaylist?.id == playlist.id else { return details }
        seriesDetailsCache[series.id] = details
        seriesDetailsFetchedAt[series.id] = .now
        detailsRevision += 1
        scheduleStoredDetailsSave()
        indexProviderPeople([(.series(series), details.info)])
        return details
    }
}

extension PlaceholderNames {
    /// The app's words for the names IPTVCore gives in English where the provider gives none.
    static func app(bundle: Bundle = .main) -> PlaceholderNames {
        PlaceholderNames(
            uncategorized: String(localized: "Uncategorized", bundle: bundle),
            other: String(localized: "Other", bundle: bundle),
            otherNumbered: { String(localized: "Other \($0)", bundle: bundle) },
            channel: { String(localized: "Channel \($0)", bundle: bundle) },
            movie: { String(localized: "Movie \($0)", bundle: bundle) },
            series: { String(localized: "Series \($0)", bundle: bundle) },
            episode: { String(localized: "Episode \($0)", bundle: bundle) }
        )
    }
}
