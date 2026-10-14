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
    /// Pages already opened whose cast or director names this person.
    func knownWorks(of name: String) -> PersonWorks {
        let excluded = excludedCategoryIDs
        let movies = movieDetailsCache.compactMap { id, details -> Movie? in
            guard People.field(details.cast, names: name) || People.field(details.director, names: name),
                  let movie = index.moviesByID[id], !excluded.contains(movie.categoryID) else { return nil }
            return movie
        }
        let series = seriesDetailsCache.compactMap { id, details -> Series? in
            guard People.field(details.info.cast, names: name) || People.field(details.info.director, names: name),
                  let show = index.seriesByID[id], !excluded.contains(show.categoryID) else { return nil }
            return show
        }
        return Self.sorted(PersonWorks(movies: movies, series: series))
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
        return SearchResults(
            movies: movies.filter { $0.year.map(years.contains) ?? false },
            series: series.filter { $0.year.map(years.contains) ?? false }
        )
    }
}
