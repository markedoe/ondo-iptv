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
        let movies = movieDetailsCache.compactMap { id, details -> Movie? in
            guard People.field(details.cast, names: name) || People.field(details.director, names: name),
                  let movie = index.moviesByID[id], !excluded.contains(movie.categoryID) else { return nil }
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

    /// The actors and directors of `content`'s series (`peopleIndex`), built off the main thread.
    func buildPeopleIndex(from content: PlaylistContent, for playlist: Playlist) {
        peopleTask?.cancel()
        peopleTask = Task {
            let people = await Task.detached(priority: .utility) { PeopleIndex(fields: content.series.flatMap { [$0.cast, $0.director] }) }.value
            guard !Task.isCancelled, activePlaylist?.id == playlist.id else { return }
            peopleIndex = people
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

    /// With the viewer's TMDb token, the people TMDb finds for a search (only the words typed are sent).
    func tmdbPeople(matching query: String) async -> [String] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard hasTMDbKey, trimmed.count >= 3, let credential = secrets.get(Keychain.tmdbKey) else { return [] }
        let client = TMDbClient(credential: credential, http: http, language: Locale.preferredLanguages.first ?? "en-US")
        return (try? await client.people(matching: trimmed))?.prefix(8).map(\.name) ?? []
    }

    /// The pages already opened, then the person's credits on TMDb (when a key is set) that the playlist has.
    func works(of name: String) async -> PersonWorks {
        var works = knownWorks(of: name)
        guard let credential = secrets.get(Keychain.tmdbKey) else { return works }
        let client = TMDbClient(credential: credential, http: http, language: Locale.preferredLanguages.first ?? "en-US")
        guard let person = try? await client.person(named: name),
              let credits = try? await client.credits(ofPerson: person.id) else { return works }
        works.searchedOnline = true
        let movies = content.movies
        let series = content.series
        let matches = await Task.detached { CatalogueMatcher.match(credits, movies: movies, series: series) }.value
        let excluded = excludedCategoryIDs
        for match in matches {
            switch match {
            case .movie(let movie, _) where !excluded.contains(movie.categoryID) && !works.movies.contains(where: { $0.id == movie.id }):
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
            movies = self.movies(in: nil, sort: .release)
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
