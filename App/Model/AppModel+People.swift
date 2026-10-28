import Foundation
import IPTVCore

/// The movies and series of the playlist a person played in or made (a name tapped in a movie's details).
struct PersonWorks: Equatable {
    var movies: [Movie] = []
    var series: [Series] = []
    /// TMDb's credits were searched (a key is set and it answered); otherwise only the pages already opened are.
    var searchedOnline = false

    var isEmpty: Bool { movies.isEmpty && series.isEmpty }
}

extension AppModel {
    /// Pages already opened whose cast or director names this person, and the series whose list entry does (series
    /// lists name their cast; movie lists do not).
    func knownWorks(of name: String) -> PersonWorks {
        let excluded = excludedCategoryIDs
        let watched = hiddenWatchedMovieIDs()
        let movies = movieDetailsCache.compactMap { id, details -> Movie? in
            guard People.field(details.cast, names: name) || People.field(details.director, names: name),
                  let movie = index.moviesByID[id], !excluded.contains(movie.categoryID), !watched.contains(movie.id) else { return nil }
            return movie
        }
        var series = seriesDetailsCache.compactMap { id, details -> Series? in
            guard People.field(details.info.cast, names: name) || People.field(details.info.director, names: name),
                  let show = index.seriesByID[id], !excluded.contains(show.categoryID) else { return nil }
            return show
        }
        let opened = Set(series.map(\.id))
        series += content.series.filter { show in
            !opened.contains(show.id) && !excluded.contains(show.categoryID)
                && (People.field(show.cast, names: name) || People.field(show.director, names: name))
        }
        return Self.sorted(PersonWorks(movies: movies, series: series))
    }

    /// The actors and directors of `content`'s series (`peopleIndex`), and the catalogue's titles by their key
    /// (`catalogueByMetadataKey`), built off the main thread. The series' cast and directors join the local people index.
    func buildPeopleIndex(from content: PlaylistContent, for playlist: Playlist) {
        peopleTask?.cancel()
        let store = metadataStore
        peopleTask = Task {
            let (people, titles) = await Task.detached(priority: .utility) { () -> (PeopleIndex, [String: [ShowcaseItem]]) in
                let people = PeopleIndex(fields: content.series.flatMap { [$0.cast, $0.director] })
                var titles: [String: [ShowcaseItem]] = [:]
                var links: [PersonLink] = []
                for movie in content.movies {
                    if let key = MetadataKey.title(.movie, name: movie.name, year: movie.year) { titles[key, default: []].append(.movie(movie)) }
                }
                for show in content.series {
                    guard let key = MetadataKey.title(.series, name: show.name, year: show.year) else { continue }
                    titles[key, default: []].append(.series(show))
                    links += PersonLink.links(fields: [show.cast, show.director], titleKeys: [key])
                }
                // A few thousand at a time: a page opened meanwhile reads the store between them.
                for start in stride(from: 0, to: links.count, by: 2_000) {
                    store.index(Array(links[start..<min(start + 2_000, links.count)]))
                }
                return (people, titles)
            }.value
            guard !Task.isCancelled, activePlaylist?.id == playlist.id else { return }
            peopleIndex = people
            catalogueByMetadataKey = titles
        }
    }

    /// Actors and directors whose name matches a search: from the pages already opened and the series lists.
    func people(matching query: String) -> [String] {
        let openedFields = movieDetailsCache.values.flatMap { [$0.cast, $0.director] }
            + seriesDetailsCache.values.flatMap { [$0.info.cast, $0.info.director] }
        var seen = Set<String>()
        return (PeopleIndex(fields: openedFields).names(matching: query) + peopleIndex.names(matching: query))
            .filter { seen.insert(TextNormalizer.normalize($0)).inserted }
            .prefix(20)
            .map { $0 }
    }

    /// Actors and directors of the local people index whose name matches a search, among those with a title in this
    /// playlist (outside hidden and locked categories). Works offline; their photos come with them.
    func indexedPeople(matching query: String) async -> [String] {
        guard PeopleSearch.tokens(query) != nil else { return [] }
        let store = metadataStore
        let catalogue = catalogueByMetadataKey
        let excluded = excludedCategoryIDs
        let found = await Task.detached(priority: .userInitiated) { () -> [PersonRecord] in
            store.people(matching: query, limit: 60).compactMap { entry in
                let shown = store.titleKeys(ofPerson: entry.person.personKey).contains { key in
                    (catalogue[key] ?? []).contains { !excluded.contains($0.categoryID) }
                }
                return shown ? entry.person : nil
            }
        }.value
        for person in found {
            if let photo = person.photoURL { tmdbMetadata.photos[person.personKey] = photo }
        }
        return found.prefix(20).map(\.displayName)
    }

    /// With the viewer's TMDb token, the people TMDb finds for a search (only the words typed are sent); their photos
    /// are kept.
    func tmdbPeople(matching query: String) async -> [String] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard hasTMDbKey, trimmed.count >= 3, let credential = secrets.get(Keychain.tmdbKey) else { return [] }
        let client = TMDbClient(credential: credential, http: http, language: Locale.preferredLanguages.first ?? "en-US")
        do {
            let people = Array(try await client.people(matching: trimmed).prefix(8))
            rememberTMDbPeople(people)
            return people.map(\.name)
        } catch {
            noteTMDbFailure(error, credential: credential)
            return []
        }
    }

    /// The pages already opened and the local people index: at once, without the network.
    func localWorks(of name: String) async -> PersonWorks {
        var works = knownWorks(of: name)
        let store = metadataStore
        let key = MetadataKey.person(name)
        let titleKeys = await Task.detached(priority: .userInitiated) { store.titleKeys(ofPerson: key) }.value
        let excluded = excludedCategoryIDs
        let watched = hiddenWatchedMovieIDs()
        for item in titleKeys.flatMap({ catalogueByMetadataKey[$0] ?? [] }) {
            switch item {
            case .movie(let movie) where !excluded.contains(movie.categoryID) && !watched.contains(movie.id)
                && !works.movies.contains(where: { $0.id == movie.id }):
                works.movies.append(movie)
            case .series(let show) where !excluded.contains(show.categoryID) && !works.series.contains(where: { $0.id == show.id }):
                works.series.append(show)
            default:
                break
            }
        }
        return Self.sorted(works)
    }

    /// The pages already opened and the local people index, then the person's credits on TMDb (when a key is set) that
    /// the playlist has. What TMDb gives joins the index (and the person's photo is kept), for the next time.
    func works(of name: String) async -> PersonWorks {
        var works = await localWorks(of: name)
        guard let credential = secrets.get(Keychain.tmdbKey) else { return works }
        let client = TMDbClient(credential: credential, http: http, language: Locale.preferredLanguages.first ?? "en-US")
        let credits: [TMDbItem]
        do {
            guard let person = try await client.person(named: name) else { return works }
            rememberTMDbPeople([person], as: [name])
            credits = try await client.credits(ofPerson: person.id)
        } catch {
            noteTMDbFailure(error, credential: credential)
            return works
        }
        works.searchedOnline = true
        let movies = content.movies
        let series = content.series
        let matches = await Task.detached { CatalogueMatcher.match(credits, movies: movies, series: series) }.value
        let personKey = MetadataKey.person(name)
        let links = matches.compactMap { match -> PersonLink? in
            let key: String?
            switch match {
            case .movie(let movie, _): key = MetadataKey.title(.movie, name: movie.name, year: movie.year)
            case .series(let show, _): key = MetadataKey.title(.series, name: show.name, year: show.year)
            }
            return key.map { PersonLink(personKey: personKey, displayName: name, titleKey: $0) }
        }
        if !personKey.isEmpty, !links.isEmpty {
            let store = metadataStore
            Task.detached(priority: .utility) { store.index(links) }
        }
        let excluded = excludedCategoryIDs
        let watched = hiddenWatchedMovieIDs()
        for match in matches {
            switch match {
            case .movie(let movie, _) where !excluded.contains(movie.categoryID) && !watched.contains(movie.id)
                && !works.movies.contains(where: { $0.id == movie.id }):
                works.movies.append(movie)
            case .series(let show, _) where !excluded.contains(show.categoryID) && !works.series.contains(where: { $0.id == show.id }):
                works.series.append(show)
            default:
                break
            }
        }
        return Self.sorted(works)
    }

    private static func sorted(_ works: PersonWorks) -> PersonWorks {
        var works = works
        works.movies.sort { releasedFirst(($0.year, $0.addedAt, $0.name), ($1.year, $1.addedAt, $1.name)) }
        works.series.sort { releasedFirst(($0.year, $0.addedAt, $0.name), ($1.year, $1.addedAt, $1.name)) }
        return works
    }

    // MARK: - Search by release year

    /// Release years of the playlist's movies and series, newest first (the search filter's choices).
    func releaseYears() -> [Int] {
        let excluded = excludedCategoryIDs
        var years = Set<Int>()
        for movie in content.movies where !excluded.contains(movie.categoryID) { if let year = movie.year { years.insert(year) } }
        for show in content.series where !excluded.contains(show.categoryID) { if let year = show.year { years.insert(year) } }
        return years.sorted(by: >)
    }

    /// Search limited to movies and series released in `years`; an empty query lists all of them, newest first.
    func search(_ query: String, releasedIn years: ClosedRange<Int>) -> SearchResults {
        search(query, releasedIn: years, awardGroup: nil, winnersOnly: false)
    }

    /// Search limited to release years and/or to the movies of a festival or ceremony (`awardGroup`, see
    /// `AwardsCatalogue`); an empty query lists everything that matches, newest first.
    func search(_ query: String, releasedIn years: ClosedRange<Int>?, awardGroup: String?, winnersOnly: Bool) -> SearchResults {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        let movies: [Movie]
        let series: [Series]
        if trimmed.isEmpty {
            // Search shows the movies hidden as watched; the pages of a year or a festival leave them out (`titles(for:)`).
            movies = self.movies(in: nil, sort: .release, includingWatched: true)
            series = self.series(in: nil, sort: .release)
        } else {
            let found = index.search.search(trimmed, limitPerType: 5000, excludedCategoryIDs: excludedCategoryIDs)
            movies = found.movies
            series = found.series
        }
        func released(_ year: Int?) -> Bool {
            guard let years else { return true }
            return year.map(years.contains) ?? false
        }
        if let awardGroup {
            // Awards are given to films: series are left out.
            return SearchResults(movies: movies.filter { released($0.year) && movie($0, hasAwardIn: awardGroup, winnersOnly: winnersOnly) })
        }
        return SearchResults(movies: movies.filter { released($0.year) }, series: series.filter { released($0.year) })
    }
}
