import Foundation
import IPTVCore

/// A value on a movie's or a series' page that opens the other titles of the playlist sharing it.
enum MetadataRoute: Hashable {
    /// An award ("palme-dor"): its winners and its selections.
    case award(String)
    /// A festival or ceremony ("cannes", see `AwardGroup`).
    case festival(String)
    case year(Int)
    case genre(String)
}

extension AppModel {
    /// The movies and series of the playlist sharing this value, hidden and locked categories and the movies hidden as
    /// watched left out.
    func titles(for route: MetadataRoute) -> SearchResults {
        var results = allTitles(for: route)
        results.movies = hidingWatched(results.movies)
        return results
    }

    private func allTitles(for route: MetadataRoute) -> SearchResults {
        switch route {
        case .award(let id):
            let excluded = excludedCategoryIDs
            let found = movieAwards.compactMap { movieID, entries -> (Movie, AwardsCatalogue.Entry)? in
                guard let entry = entries.first(where: { $0.awardID == id }), let movie = index.moviesByID[movieID],
                      !excluded.contains(movie.categoryID) else { return nil }
                return (movie, entry)
            }
            // Winners first, then the most recent.
            let sorted = found.sorted { ($0.1.won ? 1 : 0, $0.1.year, $1.0.name) > ($1.1.won ? 1 : 0, $1.1.year, $0.0.name) }
            return SearchResults(movies: sorted.map(\.0))
        case .festival(let group):
            return search("", releasedIn: nil, awardGroup: group, winnersOnly: false)
        case .year(let year):
            return search("", releasedIn: year...year, awardGroup: nil, winnersOnly: false)
        case .genre(let genre):
            let wanted = TextNormalizer.normalize(genre)
            let excluded = excludedCategoryIDs
            func has(_ field: String?) -> Bool { Self.genres(in: field).contains { TextNormalizer.normalize($0) == wanted } }
            // A movie's genre is known once its page was opened; a series' comes with the catalogue.
            let movies = movieDetailsCache.compactMap { id, details -> Movie? in
                guard has(details.genre), let movie = index.moviesByID[id], !excluded.contains(movie.categoryID) else { return nil }
                return movie
            }
            let series = content.series.filter { !excluded.contains($0.categoryID) && (has($0.genre) || has(seriesDetailsCache[$0.id]?.info.genre)) }
            return SearchResults(movies: movies.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending },
                                 series: series.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending })
        }
    }

    /// The award of a movie this route stands for, to show next to it ("Winner, 1994").
    func entry(of movie: Movie, for route: MetadataRoute) -> AwardsCatalogue.Entry? {
        guard case .award(let id) = route else { return nil }
        return movieAwards[movie.id]?.first { $0.awardID == id }
    }

    /// The genres of a genre field ("Drama, Thriller", "Action / Adventure").
    static func genres(in field: String?) -> [String] {
        guard let field else { return [] }
        return field.split(whereSeparator: { ",/|&".contains($0) })
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }
}
