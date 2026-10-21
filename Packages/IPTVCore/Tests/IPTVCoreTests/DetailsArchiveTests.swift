import XCTest
@testable import IPTVCore

/// Movie and series pages kept between launches: how long a copy is used, and how many are kept.
final class DetailsArchiveTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private let hour: TimeInterval = 3600

    func testASeriesIsAskedForAgainSoonerThanAMovie() {
        func fresh(_ age: TimeInterval, _ maxAge: TimeInterval) -> Bool {
            DetailsArchive.isFresh(now.addingTimeInterval(-age), maxAge: maxAge, now: now)
        }
        XCTAssertTrue(fresh(11 * hour, DetailsArchive.seriesMaxAge))
        XCTAssertFalse(fresh(13 * hour, DetailsArchive.seriesMaxAge), "new episodes are looked for twice a day")
        XCTAssertTrue(fresh(6 * 24 * hour, DetailsArchive.movieMaxAge))
        XCTAssertFalse(fresh(8 * 24 * hour, DetailsArchive.movieMaxAge), "a movie's page is fetched again after a week")
        XCTAssertFalse(DetailsArchive.isFresh(nil, maxAge: DetailsArchive.movieMaxAge, now: now), "never fetched")
        XCTAssertFalse(fresh(-hour, DetailsArchive.movieMaxAge), "a date in the future is not trusted")
    }

    func testTheMostRecentlyFetchedAreKept() {
        var archive = DetailsArchive()
        for index in 0..<5 {
            archive.movies["m\(index)"] = .init(value: MediaDetails(title: "Movie \(index)"), fetchedAt: now.addingTimeInterval(Double(index) * hour))
            archive.series["s\(index)"] = .init(
                value: SeriesDetails(info: MediaDetails(title: "Series \(index)"), seasons: []),
                fetchedAt: now.addingTimeInterval(Double(index) * hour)
            )
        }
        let trimmed = archive.trimmed(movieLimit: 3, seriesLimit: 2)
        XCTAssertEqual(Set(trimmed.movies.keys), ["m2", "m3", "m4"])
        XCTAssertEqual(Set(trimmed.series.keys), ["s3", "s4"])
        XCTAssertEqual(archive.trimmed(), archive, "under the limits, nothing is dropped")
    }

    func testTheArchiveSurvivesARelaunch() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("DetailsArchive-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let playlistID = UUID()
        let episode = Episode(
            id: "e1", title: "Pilot", season: 1, number: 1,
            streamURL: try XCTUnwrap(URL(string: "http://panel.example.com/series/u/p/e1.mkv")),
            posterURL: URL(string: "http://img.example.com/still.jpg")
        )
        let archive = DetailsArchive(
            movies: ["m1": .init(value: MediaDetails(title: "Lighthouse", backdropURL: URL(string: "http://img.example.com/wide.jpg")), fetchedAt: now)],
            series: ["s1": .init(value: SeriesDetails(info: MediaDetails(title: "Harbor"), seasons: [Season(number: 1, name: "Season 1", episodes: [episode])]), fetchedAt: now)]
        )
        try FileStore(directory: directory).save(archive, name: DetailsArchive.documentName(for: playlistID))

        let loaded = FileStore(directory: directory).load(DetailsArchive.self, name: DetailsArchive.documentName(for: playlistID))
        XCTAssertEqual(loaded, archive)
        XCTAssertEqual(loaded?.series["s1"]?.value.seasons.first?.episodes.first?.posterURL?.lastPathComponent, "still.jpg")
    }
}
