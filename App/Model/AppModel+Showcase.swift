import Foundation
import IPTVCore

/// A movie or a series shown on the home screen.
enum ShowcaseItem: Identifiable, Hashable {
    case movie(Movie)
    case series(Series)

    var id: String {
        switch self {
        case .movie(let movie): return "m-" + movie.id
        case .series(let series): return "s-" + series.id
        }
    }

    var title: String {
        switch self {
        case .movie(let movie): return movie.name
        case .series(let series): return series.name
        }
    }

    var posterURL: URL? {
        switch self {
        case .movie(let movie): return movie.posterURL
        case .series(let series): return series.coverURL
        }
    }

    var year: Int? {
        switch self {
        case .movie(let movie): return movie.year
        case .series(let series): return series.year
        }
    }

    var rating: Double? {
        switch self {
        case .movie(let movie): return movie.rating
        case .series(let series): return series.rating
        }
    }

    var addedAt: Date? {
        switch self {
        case .movie(let movie): return movie.addedAt
        case .series(let series): return series.addedAt
        }
    }

    var contentType: ContentType {
        switch self {
        case .movie: return .movie
        case .series: return .series
        }
    }
}

/// What the home screen features: continue watching first, then trending titles and new releases.
extension AppModel {
    /// New movies: released this year or last year, by release year and not by the date the provider
    /// added them (an old film added yesterday is not new). Newest first, then the latest additions, then
    /// the best rated. Titles without a known year never appear, nothing from a hidden or locked category, and no
    /// movie hidden as watched.
    func newMovies(limit: Int = 20, now: Date = .now) -> [ShowcaseItem] {
        let window = Self.releaseWindow(now)
        // Home asks several times per redraw (carousel and shelves): the whole catalogue is gone through once.
        let all = cachedList(newKey("movies", window)) {
            self.movies(in: nil, sort: .playlist, includingWatched: true).filter { movie in movie.year.map { window.contains($0) } ?? false }
                .map(ShowcaseItem.movie).sorted(by: Self.isNewer)
        }
        // The kept list does not follow the watch history: the movies hidden as watched are left out afterwards.
        let watched = hiddenWatchedMovieIDs()
        guard !watched.isEmpty else { return Array(all.prefix(limit)) }
        return Array(all.lazy.filter { item in
            if case .movie(let movie) = item { return !watched.contains(movie.id) }
            return true
        }.prefix(limit))
    }

    /// New series, by the same rule as `newMovies`.
    func newSeries(limit: Int = 20, now: Date = .now) -> [ShowcaseItem] {
        let window = Self.releaseWindow(now)
        let all = cachedList(newKey("series", window)) {
            self.series(in: nil, sort: .playlist).filter { show in show.year.map { window.contains($0) } ?? false }
                .map(ShowcaseItem.series).sorted(by: Self.isNewer)
        }
        return Array(all.prefix(limit))
    }

    /// What the new titles depend on: the catalogue, the categories left out (hidden, locked, kids) and the years.
    private func newKey(_ kind: String, _ window: ClosedRange<Int>) -> String {
        "new-\(kind)|\(activePlaylist?.id.uuidString ?? "")|\(content.fetchedAt.timeIntervalSince1970)|\(content.movies.count)|"
            + "\(content.series.count)|\(excludedCategoryIDs.hashValue)|\(window.lowerBound)"
    }

    /// New movies and series alternating (the carousel).
    func newReleases(limit: Int = 20, now: Date = .now) -> [ShowcaseItem] {
        let movies = newMovies(limit: limit, now: now)
        let series = newSeries(limit: limit, now: now)
        var items: [ShowcaseItem] = []
        for index in 0..<max(movies.count, series.count) where items.count < limit {
            if index < movies.count { items.append(movies[index]) }
            if index < series.count, items.count < limit { items.append(series[index]) }
        }
        return items
    }

    /// This year and last year.
    private static func releaseWindow(_ now: Date) -> ClosedRange<Int> {
        let thisYear = Calendar.current.component(.year, from: now)
        return (thisYear - 1)...thisYear
    }

    private static func isNewer(_ a: ShowcaseItem, _ b: ShowcaseItem) -> Bool {
        if a.year != b.year { return (a.year ?? 0) > (b.year ?? 0) }
        if a.addedAt != b.addedAt { return (a.addedAt ?? .distantPast) > (b.addedAt ?? .distantPast) }
        if a.rating != b.rating { return (a.rating ?? 0) > (b.rating ?? 0) }
        return a.title.localizedStandardCompare(b.title) == .orderedAscending
    }

    /// Movies and series of the user's favourites (My List), most recently added first.
    func myList() -> [ShowcaseItem] {
        let movies = favorites(.movie).compactMap { entry in index.moviesByID[entry.reference.itemID].map { (entry.addedAt, ShowcaseItem.movie($0)) } }
        let series = favorites(.series).compactMap { entry in index.seriesByID[entry.reference.itemID].map { (entry.addedAt, ShowcaseItem.series($0)) } }
        return (movies + series).sorted { $0.0 > $1.0 }.map(\.1)
    }

    /// The item a history entry belongs to (movie or series).
    func showcaseItem(for entry: HistoryEntry) -> ShowcaseItem? {
        switch entry.reference.type {
        case .movie: return index.moviesByID[entry.reference.itemID].map(ShowcaseItem.movie)
        case .series: return index.seriesByID[entry.reference.itemID].map(ShowcaseItem.series)
        case .live: return nil
        }
    }

    /// Up to `limit` titles for the hero carousel, without duplicates.
    func heroItems(limit: Int = 5) -> [ShowcaseItem] {
        var items: [ShowcaseItem] = []
        func append(_ item: ShowcaseItem) {
            if items.count < limit, !items.contains(item) { items.append(item) }
        }
        for entry in continueWatching().prefix(2) {
            if let item = showcaseItem(for: entry) { append(item) }
        }
        for match in trendingTitles() {
            switch match {
            case .movie(let movie, _): append(.movie(movie))
            case .series(let series, _): append(.series(series))
            }
        }
        for item in newReleases(limit: limit * 2) { append(item) }
        return items
    }

    /// Trending titles as Home shows them: without the movies hidden as watched.
    func trendingTitles() -> [TrendingMatch] {
        let watched = hiddenWatchedMovieIDs()
        guard !watched.isEmpty else { return trending }
        return trending.filter { match in
            if case .movie(let movie, _) = match { return !watched.contains(movie.id) }
            return true
        }
    }

    /// Details already loaded (no network).
    func cachedDetails(for item: ShowcaseItem) -> MediaDetails? {
        _ = detailsRevision  // redraw the caller when details arrive
        switch item {
        case .movie(let movie): return movieDetailsCache[movie.id]
        case .series(let series): return seriesDetailsCache[series.id]?.info
        }
    }

    /// Loads the details of an item (Xtream info call; cached afterwards).
    func loadDetails(for item: ShowcaseItem) async -> MediaDetails? {
        switch item {
        case .movie(let movie): return await details(for: movie)
        case .series(let series): return try? await details(for: series).info
        }
    }

    /// Plays a movie (resuming it) or the next episode of a series.
    func playShowcase(_ item: ShowcaseItem) async {
        switch item {
        case .movie(let movie):
            play(movie)
        case .series(let series):
            guard let details = await episodes(of: series), let next = nextEpisode(in: series, details: details) else { return }
            play(next.episode, in: series, season: next.season.episodes)
        }
    }

    /// The seasons of a series before playing it, with a visible wait and a message when the provider fails
    /// (otherwise the tap would seem to do nothing).
    func episodes(of series: Series) async -> SeriesDetails? {
        isPreparingPlayback = true
        defer { isPreparingPlayback = false }
        do {
            return try await details(for: series)
        } catch {
            bannerMessage = String(localized: "Could not load the episodes: \(error.localizedDescription)")
            return nil
        }
    }

    /// Episode to watch next: the one in progress, else the one after the last finished, else the first.
    func nextEpisode(in series: Series, details: SeriesDetails) -> (episode: Episode, season: Season)? {
        var lastWatched: (episode: Episode, season: Season, entry: HistoryEntry)?
        for season in details.seasons {
            for episode in season.episodes {
                if let entry = progress(episode: episode, in: series),
                   lastWatched.map({ entry.lastWatchedAt > $0.entry.lastWatchedAt }) ?? true {
                    lastWatched = (episode, season, entry)
                }
            }
        }
        guard let last = lastWatched else {
            guard let first = details.seasons.first, let episode = first.episodes.first else { return nil }
            return (episode, first)
        }
        if last.entry.isFinished, let index = last.season.episodes.firstIndex(of: last.episode) {
            if index + 1 < last.season.episodes.count { return (last.season.episodes[index + 1], last.season) }
            if let seasonIndex = details.seasons.firstIndex(of: last.season), seasonIndex + 1 < details.seasons.count,
               let next = details.seasons[seasonIndex + 1].episodes.first {
                return (next, details.seasons[seasonIndex + 1])
            }
        }
        return (last.episode, last.season)
    }
}

/// "More like this": other titles of the same category (none from a locked or hidden category, no movie hidden as watched).
extension AppModel {
    func similarMovies(to movie: Movie, limit: Int = 15) -> [Movie] {
        guard !excludedCategoryIDs.contains(movie.categoryID) else { return [] }
        let watched = hiddenWatchedMovieIDs()
        return Array((index.moviesByCategory[movie.categoryID] ?? []).lazy.filter { $0.id != movie.id && !watched.contains($0.id) }.prefix(limit))
    }

    func similarSeries(to series: Series, limit: Int = 15) -> [Series] {
        guard !excludedCategoryIDs.contains(series.categoryID) else { return [] }
        return Array((index.seriesByCategory[series.categoryID] ?? []).lazy.filter { $0.id != series.id }.prefix(limit))
    }
}
