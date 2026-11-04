import XCTest
@testable import IPTVCore

/// TV series awards. Same cases as the Kotlin core (`awards_series_sample.json` in both test suites).
final class SeriesAwardsTests: XCTestCase {
    private func catalogue() throws -> AwardsCatalogue {
        try AwardsCatalogue(data: Fixture.data("awards_series_sample.json"))
    }

    private func movie(_ id: String, _ name: String, year: Int?) -> Movie {
        Movie(id: id, name: name, categoryID: "m", streamURL: URL(string: "http://example.com/movie/\(id).mp4")!, year: year)
    }

    private func show(_ id: String, _ name: String, year: Int?) -> Series {
        Series(id: id, name: name, categoryID: "s", year: year)
    }

    func testSeriesAreRecognisedByTitleAndFirstAirYear() throws {
        let found = try catalogue().matchSeries([
            show("1", "EN - Breaking Bad (2008)", year: 2008),
            show("2", "Breaking Bad", year: 2009),
            show("3", "Breaking Bad", year: 2011),
            show("4", "The Office", year: 2005),
            show("5", "The Office", year: nil),
            show("6", "Unknown Show", year: 2020),
        ])
        XCTAssertEqual(found["1"]?.first, AwardsCatalogue.Entry(awardID: "emmy-drama-series", year: 2014, won: true), "wins first, the most recent")
        XCTAssertEqual(found["1"]?.count, 3)
        XCTAssertNotNil(found["2"], "one year apart")
        XCTAssertNil(found["3"], "three years apart")
        XCTAssertEqual(found["4"], [AwardsCatalogue.Entry(awardID: "golden-globe-tv-drama", year: 2006, won: false)], "the 2005 series")
        XCTAssertNil(found["5"], "two awarded series are called The Office")
        XCTAssertNil(found["6"])
    }

    func testAFilmAndASeriesOfTheSameTitleNeverCrossMatch() throws {
        let catalogue = try catalogue()
        let movies = catalogue.match([movie("m1", "Fargo", year: 1996), movie("m2", "Fargo", year: 2014), movie("m3", "Breaking Bad", year: 2008)])
        XCTAssertEqual(movies["m1"], [AwardsCatalogue.Entry(awardID: "palme-dor", year: 1996, won: false)], "the film's award only")
        XCTAssertNil(movies["m2"], "a movie is never matched with a series")
        XCTAssertNil(movies["m3"], "a movie is never matched with a series")
        let shows = catalogue.matchSeries([show("s1", "Fargo", year: 2014), show("s2", "Fargo", year: 1996), show("s3", "The Third Man", year: nil)])
        XCTAssertEqual(shows["s1"]?.map(\.awardID), ["emmy-limited-series", "golden-globe-tv-drama"], "the series' awards only")
        XCTAssertNil(shows["s2"], "a series is never matched with a film")
        XCTAssertNil(shows["s3"], "a series is never matched with a film")
        // By TMDb id: a series' TV id is looked up among series only.
        XCTAssertNil(catalogue.match([movie("m4", "Other", year: nil)], tmdbIDs: ["m4": 60622])["m4"])
        XCTAssertEqual(catalogue.matchSeries([show("s4", "Other", year: nil)], tmdbIDs: ["s4": 60622])["s4"]?.first?.awardID, "emmy-limited-series")
        XCTAssertNil(catalogue.matchSeries([show("s5", "Other", year: nil)], tmdbIDs: ["s5": 275])["s5"])
    }

    func testSeriesAwardsAreNamedAndGrouped() throws {
        let catalogue = try catalogue()
        XCTAssertEqual(catalogue.award("emmy-drama-series")?.name(language: "fr"), "Primetime Emmy Award de la meilleure série télévisée dramatique")
        XCTAssertEqual(catalogue.award("golden-globe-tv-drama")?.name(language: "fr"), "Golden Globe Award for Best Television Series – Drama")
        XCTAssertEqual(catalogue.award("palme-dor")?.group, "cannes")
        XCTAssertEqual(catalogue.groups, ["cannes", "golden-globes", "emmys"], "films' groups, then those of series only")
    }

    func testAFileWithoutSeriesStillReads() throws {
        let catalogue = try AwardsCatalogue(data: Fixture.data("awards_sample.json"))
        XCTAssertTrue(catalogue.series.isEmpty)
        XCTAssertTrue(catalogue.seriesAwards.isEmpty)
        XCTAssertTrue(catalogue.matchSeries([show("1", "Parasite", year: 2019)]).isEmpty)
    }

    // MARK: - The apps already released read the new file

    /// The decoder of the apps released before series had awards, as it was (only `awards`, `films`, `generated`).
    private struct ReleasedCatalogue: Decodable {
        struct Film: Decodable {
            let titles: [String]
            let year: Int?
            let tmdb: Int?
            let awards: [AwardsCatalogue.Entry]
        }
        let awards: [AwardsCatalogue.Award]
        let films: [Film]
        let generated: String?
    }

    /// The bundled file, the one the "Awards data" workflow publishes for every app, released or not.
    private func bundledFile() throws -> Data {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while !FileManager.default.fileExists(atPath: directory.appendingPathComponent("App/Resources/awards.json").path) {
            let parent = directory.deletingLastPathComponent()
            guard parent.path != directory.path else { throw XCTSkip("App/Resources/awards.json not found") }
            directory = parent
        }
        return try Data(contentsOf: directory.appendingPathComponent("App/Resources/awards.json"))
    }

    func testTheReleasedDecoderReadsTheSameFilmsAndNoSeries() throws {
        let data = try bundledFile()
        let released = try JSONDecoder().decodeCheckingKeys(ReleasedCatalogue.self, from: data)
        let current = try AwardsCatalogue(data: data)
        XCTAssertGreaterThanOrEqual(released.films.count, 1_000, "the released apps keep a download of 1,000 films or more")
        XCTAssertFalse(current.series.isEmpty, "the file has series")
        XCTAssertEqual(released.awards, current.awards, "the film awards, as before")
        func describe(_ titles: [String], _ year: Int?, _ tmdb: Int?, _ awards: [AwardsCatalogue.Entry]) -> String {
            "\(titles)|\(year ?? -1)|\(tmdb ?? -1)|\(awards.map { "\($0.awardID),\($0.year),\($0.won)" })"
        }
        XCTAssertEqual(released.films.map { describe($0.titles, $0.year, $0.tmdb, $0.awards) },
                       current.films.map { describe($0.titles, $0.year, $0.tmdb, $0.awards) }, "the same films")
        // No series award reaches a released app: none is among its awards, none among its films' entries.
        let seriesAwardIDs = Set(current.seriesAwards.map(\.id))
        XCTAssertTrue(Set(released.awards.map(\.id)).isDisjoint(with: seriesAwardIDs))
        XCTAssertFalse(released.films.contains { $0.awards.contains { seriesAwardIDs.contains($0.awardID) } })
        // And the series are not in its films: a series listed as a film would be shown as one.
        let seriesOnly = try XCTUnwrap(current.series.first { $0.titles.contains("Breaking Bad") })
        XCTAssertFalse(released.films.contains { $0.titles.contains("Breaking Bad") && $0.year == seriesOnly.year })
    }
}
