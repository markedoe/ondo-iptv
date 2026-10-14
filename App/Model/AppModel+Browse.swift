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
        if let category {
            list = index.moviesByCategory[category.id] ?? []
        } else {
            let excluded = excludedCategoryIDs
            list = content.movies.filter { !excluded.contains($0.categoryID) }
        }
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
        if let category {
            list = index.seriesByCategory[category.id] ?? []
        } else {
            let excluded = excludedCategoryIDs
            list = content.series.filter { !excluded.contains($0.categoryID) }
        }
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
        var results = index.search.search(query, limitPerType: 150, excludedCategoryIDs: excludedCategoryIDs)
        let hidden = hiddenChannelIDs
        if !hidden.isEmpty { results.channels.removeAll { hidden.contains($0.id) } }
        return results
    }

    // MARK: - Details

    func details(for movie: Movie) async -> MediaDetails? {
        if let cached = movieDetailsCache[movie.id] { return cached }
        guard let streamID = movie.xtreamStreamID, let credentials = activePlaylist?.source.xtreamCredentials else { return nil }
        let client = XtreamClient(credentials: credentials, http: http, headers: activePlaylist?.headers ?? .none)
        guard let details = try? await client.movieDetails(streamID: streamID) else { return nil }
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
