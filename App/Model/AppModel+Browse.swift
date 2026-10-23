import Foundation
import IPTVCore

/// Browsing: filtered lists of the active catalogue and item details.
extension AppModel {
    // MARK: - Lists

    func channels(in category: MediaCategory?) -> [Channel] {
        let hidden = hiddenChannelIDs
        guard let category else {
            let excluded = excludedCategoryIDs
            return content.channels.filter { !excluded.contains($0.categoryID) && !hidden.contains($0.id) }
        }
        let list = index.channelsByCategory[category.id] ?? []
        return hidden.isEmpty ? list : list.filter { !hidden.contains($0.id) }
    }

    /// The movies of a category, or of all of them; the ones hidden as watched are left out unless `includingWatched`
    /// (search, and the lists kept in a cache, which leave them out once read from it).
    func movies(in category: MediaCategory?, sort: PosterSort, includingWatched: Bool = false) -> [Movie] {
        let list = sortedMovies(in: category, sort: sort)
        return includingWatched ? list : hidingWatched(list)
    }

    private func sortedMovies(in category: MediaCategory?, sort: PosterSort) -> [Movie] {
        let list: [Movie]
        let key: String
        if let category {
            list = index.moviesByCategory[category.id] ?? []
            key = "movies|\(activePlaylist?.id.uuidString ?? "")|\(content.fetchedAt.timeIntervalSince1970)|\(list.count)|\(category.id)|\(sort.rawValue)"
        } else {
            let excluded = excludedCategoryIDs
            list = content.movies.filter { !excluded.contains($0.categoryID) }
            key = "movies|\(activePlaylist?.id.uuidString ?? "")|\(content.fetchedAt.timeIntervalSince1970)|\(list.count)|*|\(excluded.hashValue)|\(sort.rawValue)"
        }
        guard sort != .playlist else { return list }
        // Sorting a large catalogue by name takes a moment: once per catalogue, category and order, not at every redraw.
        if let cached = sortedListCache[key] as? [Movie] { return cached }
        let sorted = Self.sorted(list, by: sort)
        cacheSortedList(sorted, key: key)
        return sorted
    }

    private static func sorted(_ list: [Movie], by sort: PosterSort) -> [Movie] {
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
            list = index.seriesByCategory[category.id] ?? []
            key = "series|\(activePlaylist?.id.uuidString ?? "")|\(content.fetchedAt.timeIntervalSince1970)|\(list.count)|\(category.id)|\(sort.rawValue)"
        } else {
            let excluded = excludedCategoryIDs
            list = content.series.filter { !excluded.contains($0.categoryID) }
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

    private static func sorted(_ list: [Series], by sort: PosterSort) -> [Series] {
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

    func search(_ query: String) -> SearchResults {
        Self.search(query, in: index.search, excluded: excludedCategoryIDs, hidden: hiddenChannelIDs)
    }

    /// `search` on a background task: the main thread (and the keyboard) stays free while a large playlist is searched.
    func searchInBackground(_ query: String) async -> SearchResults {
        let engine = index.search, excluded = excludedCategoryIDs, hidden = hiddenChannelIDs
        return await Task.detached(priority: .userInitiated) {
            Self.search(query, in: engine, excluded: excluded, hidden: hidden)
        }.value
    }

    private nonisolated static func search(_ query: String, in engine: SearchEngine, excluded: Set<String>, hidden: Set<String>) -> SearchResults {
        var results = engine.search(query, limitPerType: 150, excludedCategoryIDs: excluded)
        if !hidden.isEmpty { results.channels.removeAll { hidden.contains($0.id) } }
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
        return details
    }

    /// The kept episodes while they are recent (`DetailsArchive.seriesMaxAge`: a series gains episodes), else the
    /// provider's; the kept ones when the provider cannot be reached.
    func details(for series: Series) async throws -> SeriesDetails {
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
        return details
    }
}
