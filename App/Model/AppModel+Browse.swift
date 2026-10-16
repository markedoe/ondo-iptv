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

    func movies(in category: MediaCategory?, sort: PosterSort) -> [Movie] {
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
        if sortedListCache.count >= 12 { sortedListCache.removeAll() }
        sortedListCache[key] = list
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

    func recentlyAddedMovies(limit: Int = 20) -> [Movie] {
        Array(movies(in: nil, sort: .recent).prefix(limit)).filter { $0.addedAt != nil }
    }

    func recentlyAddedSeries(limit: Int = 20) -> [Series] {
        Array(series(in: nil, sort: .recent).prefix(limit)).filter { $0.addedAt != nil }
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

    func details(for movie: Movie) async -> MediaDetails? {
        if let cached = movieDetailsCache[movie.id] { return cached }
        // The Home prefetch and the page may ask at the same time: one request.
        if let running = movieDetailsInFlight[movie.id] { return await running.value }
        guard let streamID = movie.xtreamStreamID, let credentials = activePlaylist?.source.xtreamCredentials else { return nil }
        let client = XtreamClient(credentials: credentials, http: http, headers: activePlaylist?.headers ?? .none)
        let request = Task { try? await client.movieDetails(streamID: streamID) }
        movieDetailsInFlight[movie.id] = request
        let fetched = await request.value
        movieDetailsInFlight[movie.id] = nil
        guard let details = fetched else { return nil }
        movieDetailsCache[movie.id] = details
        detailsRevision += 1
        return details
    }

    func details(for series: Series) async throws -> SeriesDetails {
        if let cached = seriesDetailsCache[series.id] { return cached }
        let details: SeriesDetails
        if let seriesID = series.xtreamSeriesID, let credentials = activePlaylist?.source.xtreamCredentials {
            let client = XtreamClient(credentials: credentials, http: http, headers: activePlaylist?.headers ?? .none)
            details = try await client.seriesDetails(seriesID: seriesID)
        } else {
            let grouped = Dictionary(grouping: series.episodes, by: \.season)
            let seasons = grouped.keys.sorted().map { number in
                Season(number: number, name: String(localized: "Season \(number)"), episodes: grouped[number] ?? [])
            }
            details = SeriesDetails(
                info: MediaDetails(title: series.name, plot: series.plot, genre: series.genre, rating: series.rating, posterURL: series.coverURL),
                seasons: seasons
            )
        }
        seriesDetailsCache[series.id] = details
        detailsRevision += 1
        return details
    }
}
