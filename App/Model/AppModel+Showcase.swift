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
    /// New releases: movies and series released this year or last year, by release year and not by the
    /// date the provider added them (an old film added yesterday is not new). Movies and series
    /// alternate; each newest first, then the latest additions, then the best rated. Titles without a
    /// known year never appear, and nothing from a hidden or locked category.
    func newReleases(limit: Int = 20, now: Date = .now) -> [ShowcaseItem] {
        let thisYear = Calendar.current.component(.year, from: now)
        let window = (thisYear - 1)...thisYear
        func isNew(_ year: Int?) -> Bool { year.map { window.contains($0) } ?? false }
        let movies = self.movies(in: nil, sort: .playlist).filter { isNew($0.year) }.map(ShowcaseItem.movie).sorted(by: Self.isNewer)
        let series = self.series(in: nil, sort: .playlist).filter { isNew($0.year) }.map(ShowcaseItem.series).sorted(by: Self.isNewer)
        var items: [ShowcaseItem] = []
        for index in 0..<max(movies.count, series.count) where items.count < limit {
            if index < movies.count { items.append(movies[index]) }
            if index < series.count, items.count < limit { items.append(series[index]) }
        }
        return items
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
        for match in trending {
            switch match {
            case .movie(let movie, _): append(.movie(movie))
            case .series(let series, _): append(.series(series))
            }
        }
        for item in newReleases(limit: limit * 2) { append(item) }
        return items
    }

    /// Details already loaded (no network).
    func cachedDetails(for item: ShowcaseItem) -> MediaDetails? {
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

    /// Wide artwork: the provider's backdrop, else the trending one from TMDb.
    func backdropURL(for item: ShowcaseItem) -> URL? {
        if let url = cachedDetails(for: item)?.backdropURL { return url }
        return trending.first { match in
            switch (match, item) {
            case (.movie(let a, _), .movie(let b)): return a.id == b.id
            case (.series(let a, _), .series(let b)): return a.id == b.id
            default: return false
            }
        }?.trending.backdropURL
    }

    /// Plays a movie (resuming it) or the next episode of a series.
    func playShowcase(_ item: ShowcaseItem) async {
        switch item {
        case .movie(let movie):
            play(movie)
        case .series(let series):
            guard let details = try? await details(for: series), let next = nextEpisode(in: series, details: details) else { return }
            play(next.episode, in: series, season: next.season.episodes)
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

/// "More like this": other titles of the same category (none from a locked or hidden category).
extension AppModel {
    func similarMovies(to movie: Movie, limit: Int = 15) -> [Movie] {
        guard !excludedCategoryIDs.contains(movie.categoryID) else { return [] }
        return Array((index.moviesByCategory[movie.categoryID] ?? []).lazy.filter { $0.id != movie.id }.prefix(limit))
    }

    func similarSeries(to series: Series, limit: Int = 15) -> [Series] {
        guard !excludedCategoryIDs.contains(series.categoryID) else { return [] }
        return Array((index.seriesByCategory[series.categoryID] ?? []).lazy.filter { $0.id != series.id }.prefix(limit))
    }
}
