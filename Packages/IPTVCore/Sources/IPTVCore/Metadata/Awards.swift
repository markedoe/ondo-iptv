import Foundation

/// Film awards and festival selections (Palme d'Or, Oscars, Golden Bear…) and TV series awards (Emmys, Golden Globes,
/// BAFTA TV…): the data bundled with the apps (`awards.json`, made from Wikidata by `tools/awards/generate_awards.py`),
/// and which movies and series of a playlist they concern. Nothing is sent anywhere: the file is read on the device.
///
/// Series sit in keys of their own (`seriesAwards`, `series`): the apps released before them read only `awards` and
/// `films`, which a newer file keeps as they were. A film is only matched with films, a series with series.
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

    /// A film, or a series (`series`, the year it was first aired).
    public struct Film: Decodable, Sendable {
        /// Its titles in several languages and its original title.
        public let titles: [String]
        public let year: Int?
        public let tmdb: Int?
        public let awards: [Entry]
    }

    /// Film awards and festival selections.
    public let awards: [Award]
    public let films: [Film]
    /// TV series awards; their ids are not those of any film award.
    public let seriesAwards: [Award]
    public let series: [Film]
    /// The day the file was made ("2026-10-14"): a newer download replaces the copy bundled with the app.
    public let generated: String?

    public static let empty = AwardsCatalogue(awards: [], films: [])

    public init(awards: [Award], films: [Film], seriesAwards: [Award] = [], series: [Film] = [], generated: String? = nil) {
        self.awards = awards
        self.films = films
        self.seriesAwards = seriesAwards
        self.series = series
        self.generated = generated
    }

    public init(data: Data) throws {
        self = try JSONDecoder().decodeCheckingKeys(AwardsCatalogue.self, from: data)
    }

    private enum CodingKeys: String, CodingKey { case awards, films, seriesAwards, series, generated }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        awards = try container.decode([Award].self, forKey: .awards)
        films = try container.decode([Film].self, forKey: .films)
        // Files made before series had awards have neither key.
        seriesAwards = try container.decodeIfPresent([Award].self, forKey: .seriesAwards) ?? []
        series = try container.decodeIfPresent([Film].self, forKey: .series) ?? []
        generated = try container.decodeIfPresent(String.self, forKey: .generated)
    }

    /// Whether this catalogue was made after another one (a missing date counts as older).
    public func isNewer(than other: AwardsCatalogue) -> Bool {
        (generated ?? "") > (other.generated ?? "")
    }

    /// A film or a series award.
    public func award(_ id: String) -> Award? {
        awards.first { $0.id == id } ?? seriesAwards.first { $0.id == id }
    }

    /// The festivals and ceremonies, in the order of the file (those of films, then those of series only).
    public var groups: [String] {
        var seen = Set<String>()
        return (awards + seriesAwards).map(\.group).filter { seen.insert($0).inserted }
    }

    /// Awards of the playlist's movies, by movie id, wins first then the most recent. A movie is recognised by its
    /// TMDb id when known (`tmdbIDs`), otherwise by its title and year (one year apart at most, as release dates
    /// differ between countries); a title without a year only when a single awarded film has that title.
    public func match(_ movies: [Movie], tmdbIDs: [String: Int] = [:]) -> [String: [Entry]] {
        Self.match(movies.map { (id: $0.id, name: $0.name, year: $0.year) }, in: films, tmdbIDs: tmdbIDs)
    }

    /// Awards of the playlist's series, by series id: the same rules as movies, among the awarded series only (a film
    /// of the same title is never one of them), by the year the series was first aired and its TMDb TV id.
    public func matchSeries(_ shows: [Series], tmdbIDs: [String: Int] = [:]) -> [String: [Entry]] {
        Self.match(shows.map { (id: $0.id, name: $0.name, year: $0.year) }, in: series, tmdbIDs: tmdbIDs)
    }

    private static func match(_ titles: [(id: String, name: String, year: Int?)], in list: [Film], tmdbIDs: [String: Int]) -> [String: [Entry]] {
        guard !list.isEmpty else { return [:] }
        var byTitle: [String: [Int]] = [:]
        var byTMDb: [Int: Int] = [:]
        for (index, film) in list.enumerated() {
            for key in Set(film.titles.map(CatalogueMatcher.key)) where !key.isEmpty {
                byTitle[key, default: []].append(index)
            }
            if let tmdb = film.tmdb { byTMDb[tmdb] = index }
        }
        var result: [String: [Entry]] = [:]
        for title in titles {
            let found: Int?
            if let tmdb = tmdbIDs[title.id], let index = byTMDb[tmdb] {
                found = index
            } else {
                let candidates = byTitle[CatalogueMatcher.key(title.name)] ?? []
                if let year = title.year {
                    found = candidates.first { list[$0].year.map { CatalogueMatcher.closeYears($0, year) } ?? false }
                } else {
                    found = candidates.count == 1 ? candidates.first : nil
                }
            }
            if let found {
                result[title.id] = list[found].awards.sorted { ($0.won ? 1 : 0, $0.year) > ($1.won ? 1 : 0, $1.year) }
            }
        }
        return result
    }
}
