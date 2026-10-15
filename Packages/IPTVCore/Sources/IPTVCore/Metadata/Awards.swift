import Foundation

/// Film awards and festival selections (Palme d'Or, Oscars, Golden Bear…): the data bundled with the apps
/// (`awards.json`, made from Wikidata by `tools/awards/generate_awards.py`), and which movies of a playlist they
/// concern. Nothing is sent anywhere: the file is read on the device.
public struct AwardsCatalogue: Decodable, Sendable {
    public struct Award: Decodable, Sendable, Hashable, Identifiable {
        public let id: String
        /// The festival or ceremony ("cannes", "oscars"…).
        public let group: String
        /// Name by app language ("en", "fr", "es", "pt", "zh-Hans", "ar", "hi").
        public let names: [String: String]

        public func name(language: String) -> String {
            names[language] ?? names["en"] ?? id
        }
    }

    /// One award for one film: won, or competed for (festival selection, nomination).
    public struct Entry: Decodable, Sendable, Hashable {
        public let awardID: String
        public let year: Int
        public let won: Bool

        public init(awardID: String, year: Int, won: Bool) {
            self.awardID = awardID
            self.year = year
            self.won = won
        }

        /// Stored as `["palme-dor", 1994, 1]`.
        public init(from decoder: Decoder) throws {
            var container = try decoder.unkeyedContainer()
            awardID = try container.decode(String.self)
            year = try container.decode(Int.self)
            won = try container.decode(Int.self) == 1
        }
    }

    public struct Film: Decodable, Sendable {
        /// Its titles in several languages and its original title.
        public let titles: [String]
        public let year: Int?
        public let tmdb: Int?
        public let awards: [Entry]
    }

    public let awards: [Award]
    public let films: [Film]
    /// The day the file was made ("2026-10-14"): a newer download replaces the copy bundled with the app.
    public let generated: String?

    public static let empty = AwardsCatalogue(awards: [], films: [])

    public init(awards: [Award], films: [Film], generated: String? = nil) {
        self.awards = awards
        self.films = films
        self.generated = generated
    }

    public init(data: Data) throws {
        self = try JSONDecoder().decode(AwardsCatalogue.self, from: data)
    }

    private enum CodingKeys: String, CodingKey { case awards, films, generated }

    /// Whether this catalogue was made after another one (a missing date counts as older).
    public func isNewer(than other: AwardsCatalogue) -> Bool {
        (generated ?? "") > (other.generated ?? "")
    }

    public func award(_ id: String) -> Award? {
        awards.first { $0.id == id }
    }

    /// The festivals and ceremonies, in the order of the file.
    public var groups: [String] {
        var seen = Set<String>()
        return awards.map(\.group).filter { seen.insert($0).inserted }
    }

    /// Awards of the playlist's movies, by movie id, wins first then the most recent. A movie is recognised by its
    /// TMDb id when known (`tmdbIDs`), otherwise by its title and year (one year apart at most, as release dates
    /// differ between countries); a title without a year only when a single awarded film has that title.
    public func match(_ movies: [Movie], tmdbIDs: [String: Int] = [:]) -> [String: [Entry]] {
        guard !films.isEmpty else { return [:] }
        var byTitle: [String: [Int]] = [:]
        var byTMDb: [Int: Int] = [:]
        for (index, film) in films.enumerated() {
            for key in Set(film.titles.map(CatalogueMatcher.key)) where !key.isEmpty {
                byTitle[key, default: []].append(index)
            }
            if let tmdb = film.tmdb { byTMDb[tmdb] = index }
        }
        var result: [String: [Entry]] = [:]
        for movie in movies {
            let found: Int?
            if let tmdb = tmdbIDs[movie.id], let index = byTMDb[tmdb] {
                found = index
            } else {
                let candidates = byTitle[CatalogueMatcher.key(movie.name)] ?? []
                if let year = movie.year {
                    found = candidates.first { films[$0].year.map { abs($0 - year) <= 1 } ?? false }
                } else {
                    found = candidates.count == 1 ? candidates.first : nil
                }
            }
            if let found {
                result[movie.id] = films[found].awards.sorted { ($0.won ? 1 : 0, $0.year) > ($1.won ? 1 : 0, $1.year) }
            }
        }
        return result
    }
}
