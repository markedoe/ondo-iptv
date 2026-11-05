import Foundation
import IPTVCore

extension TitleExclusions {
    /// Whether a movie or a series is left out of the lists (`AppModel.exclusions`).
    func contains(_ item: ShowcaseItem) -> Bool {
        switch item {
        case .movie(let movie): return contains(movie)
        case .series(let series): return contains(series)
        }
    }
}

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

    /// The title as the hero and the detail pages show it: without the provider's tags ("|FR| Le Transport VFF" ->
    /// "Le Transport"). Display only: `title` stays the provider's for keys, search and history.
    var displayTitle: String { CatalogueMatcher.displayTitle(title) }

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

    var categoryID: String {
        switch self {
        case .movie(let movie): return movie.categoryID
        case .series(let series): return series.categoryID
        }
    }
}

/// What the home screen features: the recent releases (the carousel), new movies and series, trending titles.
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
            + "\(content.series.count)|\(exclusions.hashValue)|\(window.lowerBound)"
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

    /// The day the carousel counts a title's release from (`RecentReleases.releaseDay`): TMDb's, else the provider's full
    /// date from a page already opened.
    func releaseDay(of item: ShowcaseItem) -> Int? {
        RecentReleases.releaseDay(tmdb: tmdbTitle(for: item)?.releaseDate, provider: providerRelease(of: item))
    }

    /// The provider's release date of a title whose details are known: a movie's; a series' latest episode's, else its
    /// own (its first).
    func providerRelease(of item: ShowcaseItem) -> String? {
        switch item {
        case .movie(let movie): return movieDetailsCache[movie.id]?.releaseDate
        case .series(let series):
            guard let details = seriesDetailsCache[series.id] else { return nil }
            let latest = details.seasons.flatMap(\.episodes).compactMap(\.releaseDate)
                .filter { RecentReleases.epochDay($0) != nil }.map { String($0.prefix(10)) }.max()
            return latest ?? details.info.releaseDate
        }
    }

    /// A poster, or a backdrop to show (TMDb's or the provider's).
    private func hasArtwork(_ item: ShowcaseItem) -> Bool {
        item.posterURL != nil || tmdbTitle(for: item)?.backdropURL != nil || cachedDetails(for: item)?.backdropURL != nil
    }

    /// The movies and series whose release TMDb is asked about for the carousel (`RecentReleases.mayBeRecent`: released
    /// this year or last year, or changed by the provider in the last `RecentReleases.widest` days, as a series with a new
    /// season), the latest changed first; nothing from a hidden or locked category, a hidden language or a movie hidden as
    /// watched. The carousel's fill asks those it needs (`fillCarouselReleases`), a bounded number.
    func releasesToAsk(now: Date = .now) -> [ShowcaseItem] {
        let today = RecentReleases.today(now)
        let all = cachedList(newKey("release-ask", today...today)) { () -> [ShowcaseItem] in
            let movies: [ShowcaseItem] = self.movies(in: nil, sort: .playlist, includingWatched: true)
                .filter { RecentReleases.mayBeRecent(year: $0.year, addedAt: $0.addedAt, today: today) }.map(ShowcaseItem.movie)
            let series: [ShowcaseItem] = self.series(in: nil, sort: .playlist)
                .filter { RecentReleases.mayBeRecent(year: $0.year, addedAt: $0.addedAt, today: today) }.map(ShowcaseItem.series)
            let items = movies + series
            // Titles without a date keep the playlist's order, after the others (the offset breaks the tie).
            return self.distinctFilms(items).enumerated().sorted { a, b in
                let x = a.element.addedAt ?? .distantPast, y = b.element.addedAt ?? .distantPast
                return x != y ? x > y : a.offset < b.offset
            }.map(\.element)
        }
        let watched = hiddenWatchedMovieIDs()
        guard !watched.isEmpty else { return all }
        return all.filter { item in
            if case .movie(let movie) = item { return !watched.contains(movie.id) }
            return true
        }
    }

    /// The carousel's candidates read at most (`fillCarouselReleases`).
    static let carouselCandidates = 2_000

    /// Home's carousel goes by release date (`RecentReleases`), which TMDb gives: what the store kept of the titles that
    /// may have been released lately (`releasesToAsk`) is read at once, then, with the viewer's TMDb token, those whose
    /// date it does not know, or whose provider changed them since TMDb was asked (a series' new episode), are fetched in
    /// the background, `RatingFill.limit` at most, at the pace of the other requests; the carousel is picked again twice,
    /// not at each answer. Stops when Home goes (its task is cancelled).
    func fillCarouselReleases(now: Date = .now) async {
        let state = tmdbMetadata
        let items = Array(releasesToAsk(now: now).prefix(Self.carouselCandidates))
        guard !items.isEmpty else { return }
        await readStoredMetadata(items.flatMap { metadataKeys(for: $0) })
        state.carouselRevision += 1
        guard !Task.isCancelled, hasTMDbKey, !isTMDbKeyRefused, !state.isPaused else { return }
        let ask = RatingFill.toAsk(items) { item in
            if state.attempted.contains(item.id) || state.prefetching[item.id] != nil || state.opened[item.id] != nil { return true }
            let kept = tmdbTitle(for: item)
            return !RecentReleases.needsAsking(kept: kept != nil, found: kept?.status == .found, releaseDate: kept?.releaseDate,
                                               fetchedAt: kept?.fetchedAt ?? .distantPast, addedAt: item.addedAt)
        }
        guard !ask.isEmpty else { return }
        let byID = Dictionary(ask.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        await RatingFill.run(
            ask.map(\.id),
            fetch: { [weak self] id in await self?.fetchRelease(byID[id]) ?? false },
            resort: { [weak self] in await self?.carouselAnswered() }
        )
    }

    /// One title of the carousel's fill: asked unless something else did meanwhile; true when TMDb gave its release date.
    private func fetchRelease(_ item: ShowcaseItem?) async -> Bool {
        let state = tmdbMetadata
        guard let item, hasTMDbKey, !isTMDbKeyRefused, !state.isPaused, !state.attempted.contains(item.id),
              state.prefetching[item.id] == nil, state.opened[item.id] == nil else { return false }
        state.queue.remove(item.id)
        state.waiting[item.id] = nil
        await fetchTMDbMetadata(for: item, paced: true)
        return !(tmdbTitle(for: item)?.releaseDate ?? "").isEmpty
    }

    private func carouselAnswered() {
        tmdbMetadata.carouselRevision += 1
    }

    /// Movies and series of the user's favourites (My List), most recently added first.
    func myList() -> [ShowcaseItem] {
        let movies = favorites(.movie).compactMap { entry in index.moviesByID[entry.reference.itemID].map { (entry.addedAt, ShowcaseItem.movie($0)) } }
        let series = favorites(.series).compactMap { entry in index.seriesByID[entry.reference.itemID].map { (entry.addedAt, ShowcaseItem.series($0)) } }
        // A film once, whichever of its versions are kept.
        return distinctFilms((movies + series).sorted { $0.0 > $1.0 }.map(\.1))
    }

    /// The item a history entry belongs to (movie or series).
    func showcaseItem(for entry: HistoryEntry) -> ShowcaseItem? {
        switch entry.reference.type {
        case .movie: return index.moviesByID[entry.reference.itemID].map(ShowcaseItem.movie)
        case .series: return index.seriesByID[entry.reference.itemID].map(ShowcaseItem.series)
        case .live: return nil
        }
    }

    /// Up to `limit` titles for the hero carousel, by release date and not by the day the provider added them
    /// (`RecentReleases.pick`): the titles released in the last 7 days (a movie's release, a series' latest episode or
    /// season), best first by TMDb's weighted rating, the window widening to 14, 30 then 60 days while fewer than `limit`
    /// qualify; with no votes, the newest releases by date; with no recent release, none (Home shows no carousel). Only
    /// titles with artwork, nothing from a hidden or locked category, a hidden language or a movie hidden as watched; each
    /// film once. Picked again when the carousel's fill brought dates (`TMDbMetadata.carouselRevision`).
    func heroItems(limit: Int = 5, now: Date = .now) -> [ShowcaseItem] {
        let today = RecentReleases.today(now)
        let watched = hiddenWatchedMovieIDs()
        let key = newKey("hero", today...today) + "|\(limit)|\(watched.hashValue)|\(tmdbMetadata.carouselRevision)|\(films.isGrouped)"
        return cachedList(key) { () -> [ShowcaseItem] in
            let movies: [ShowcaseItem] = self.movies(in: nil, sort: .playlist, includingWatched: true).filter { !watched.contains($0.id) }.map(ShowcaseItem.movie)
            let series: [ShowcaseItem] = self.series(in: nil, sort: .playlist).map(ShowcaseItem.series)
            let shown = movies + series
            var released: [String: Int] = [:]
            let recent = shown.filter { item in
                guard let day = self.releaseDay(of: item), (0..<RecentReleases.widest).contains(today - day) else { return false }
                released[item.id] = day
                return true
            }
            var byID: [String: ShowcaseItem] = [:]
            var seen = Set<String>()
            let candidates = self.distinctFilms(recent).compactMap { item -> RecentReleases.Candidate? in
                let film = item.title.trimmingCharacters(in: .whitespaces).lowercased() + "|" + (item.year.map(String.init) ?? "")
                guard seen.insert(film).inserted else { return nil }
                byID[item.id] = item
                let votes = self.tmdbTitle(for: item)?.votes
                return RecentReleases.Candidate(id: item.id, title: item.title, released: released[item.id], average: votes?.average,
                                                votes: votes?.count ?? 0, categoryID: item.categoryID, hasArtwork: self.hasArtwork(item))
            }
            return RecentReleases.pick(candidates, today: today, size: limit).compactMap { byID[$0.id] }
        }
    }

    /// Trending titles as Home shows them: without the movies hidden as watched.
    func trendingTitles() -> [TrendingMatch] {
        let watched = hiddenWatchedMovieIDs()
        let films = self.films
        // Again here: a language hidden after the trending titles were read leaves them too.
        let excluded = exclusions
        var seen = Set<Int>()
        return trending.filter { match in
            switch match {
            case .movie(let movie, _): if excluded.contains(movie) { return false }
            case .series(let series, _): if excluded.contains(series) { return false }
            }
            guard case .movie(let movie, _) = match else { return true }
            if watched.contains(movie.id) { return false }
            // Each film once, whatever its versions.
            guard let group = films.group(of: movie) else { return true }
            return seen.insert(group).inserted
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
            // A film's card plays its default version.
            playFilm(movie)
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
            // An episode listed twice (the same address) is not the one after it.
            if let following = last.season.episodes[(index + 1)...].first(where: { $0.streamURL != last.episode.streamURL }) {
                return (following, last.season)
            }
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
        let excluded = exclusions
        guard !excluded.contains(movie) else { return [] }
        let watched = hiddenWatchedMovieIDs()
        // Never another version of the film itself; each film once. Kept for the page's redraws (a picked version, a
        // detail arriving): the category is gone through once per movie, catalogue, exclusions and watched movies.
        let films = self.films
        let key = "similar|\(activePlaylist?.id.uuidString ?? "")|\(content.fetchedAt.timeIntervalSince1970)|\(movie.id)|\(movie.categoryID)|"
            + "\(limit)|\(excluded.hashValue)|\(watched.hashValue)|\(films.isGrouped)"
        return cachedList(key) {
            let own = films.group(of: movie)
            let others = (index.moviesByCategory[movie.categoryID] ?? []).filter {
                $0.id != movie.id && !watched.contains($0.id) && !excluded.contains($0) && (own == nil || films.group(of: $0) != own)
            }
            return Array(films.distinct(others).prefix(limit))
        }
    }

    func similarSeries(to series: Series, limit: Int = 15) -> [Series] {
        let excluded = exclusions
        guard !excluded.contains(series) else { return [] }
        return Array((index.seriesByCategory[series.categoryID] ?? []).lazy.filter { $0.id != series.id && !excluded.contains($0) }.prefix(limit))
    }
}
