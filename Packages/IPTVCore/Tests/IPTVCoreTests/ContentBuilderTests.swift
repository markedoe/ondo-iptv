import XCTest
@testable import IPTVCore

final class ContentBuilderTests: XCTestCase {
    private func content() throws -> PlaylistContent {
        try PlaylistLoader(http: MockHTTPClient(), localFileURL: { URL(fileURLWithPath: $0) }).parse(try Fixture.data("sample.m3u"))
    }

    func testClassification() throws {
        let content = try content()
        XCTAssertEqual(content.channels.map(\.name), ["TF1 HD", "France 2", "beIN Sports 1", "Arte", "FIP Radio", "Hot channel", "TF1 HD"])
        XCTAssertEqual(content.movies.map(\.name), ["Inception", "Mad Max Fury Road"])
        XCTAssertEqual(content.movies.first?.year, 2010)
        XCTAssertEqual(content.series.count, 1)
        XCTAssertEqual(content.providerEPGURLs.count, 2)
    }

    func testCategoriesKeepOrderAndAreShared() throws {
        let content = try content()
        XCTAssertEqual(content.liveCategories.map(\.name), ["France, TNT", "Sports", "Radio", "XXX Adults"])
        let tnt = content.liveCategories[0]
        XCTAssertEqual(content.channels.filter { $0.categoryID == tnt.id }.count, 4)
        XCTAssertTrue(content.liveCategories[3].looksAdult)
        XCTAssertFalse(tnt.looksAdult)
    }

    func testSeriesGrouping() throws {
        let series = try XCTUnwrap(try content().series.first)
        XCTAssertEqual(series.name, "Breaking Bad")
        XCTAssertEqual(series.coverURL?.absoluteString, "http://img.example.com/bb.jpg")
        XCTAssertEqual(series.episodes.map(\.code), ["S01E01", "S01E02", "S02E01"])
        XCTAssertEqual(series.episodes[0].title, "Pilot")
        XCTAssertEqual(series.episodes[2].title, "Seven Thirty-Seven")
    }

    func testStableAndUniqueIDs() throws {
        let first = try content()
        let second = try content()
        XCTAssertEqual(first.channels.map(\.id), second.channels.map(\.id), "ids must survive a reload (favourites)")
        XCTAssertEqual(Set(first.channels.map(\.id)).count, first.channels.count, "duplicate URLs still get unique ids")
    }

    func testEpisodesSharingAURLGetUniqueIDs() throws {
        let m3u = """
        #EXTM3U
        #EXTINF:-1 group-title="Series | Drama",Show S01E01
        http://example.com/same.mp4
        #EXTINF:-1 group-title="Series | Drama",Show S01E02
        http://example.com/same.mp4
        """
        let content = try PlaylistLoader(http: MockHTTPClient(), localFileURL: { URL(fileURLWithPath: $0) }).parse(Data(m3u.utf8))
        let episodes = try XCTUnwrap(content.series.first).episodes
        XCTAssertEqual(episodes.map(\.code), ["S01E01", "S01E02"])
        XCTAssertEqual(Set(episodes.map(\.id)).count, 2, "progress is kept per episode")
    }

    func testCatchupAttributes() throws {
        let channels = try content().channels
        XCTAssertEqual(channels[0].catchup, CatchupInfo(mode: .shift, days: 2), "header defaults apply")
        XCTAssertEqual(channels[1].catchup?.mode, .flussonic)
        XCTAssertEqual(channels[1].catchup?.days, 5)
        XCTAssertEqual(channels[3].catchup?.mode, .default)
        XCTAssertEqual(channels[3].catchup?.source, "http://archive.example.com/arte?start={utc}&end={utcend}")
    }

    func testXtreamStreamIDDetectedInPlainM3U() throws {
        let tf1 = try content().channels[0]
        XCTAssertEqual(tf1.xtreamStreamID, 101)
        XCTAssertEqual(tf1.epgID, "TF1.fr")
    }

    func testTitleParser() {
        XCTAssertEqual(TitleParser.parseEpisode("The Office (US) S03E12 Traveling Salesmen")?.seriesName, "The Office (US)")
        XCTAssertEqual(TitleParser.parseEpisode("Lost 2x05")?.season, 2)
        XCTAssertEqual(TitleParser.parseEpisode("Lost 2x05")?.episode, 5)
        XCTAssertEqual(TitleParser.parseEpisode("Dix pour cent Saison 2 Episode 3")?.episode, 3)
        XCTAssertNil(TitleParser.parseEpisode("TF1 HD"))
        XCTAssertEqual(TitleParser.splitYear("Dune [2021]").0, "Dune")
        XCTAssertEqual(TitleParser.splitYear("Dune [2021]").1, 2021)
        XCTAssertEqual(TitleParser.splitYear("Blade Runner 2049").1, 2049)
        XCTAssertNil(TitleParser.splitYear("Matrix").1)
    }

    func testXSPFAndJSON() throws {
        let loader = PlaylistLoader(http: MockHTTPClient(), localFileURL: { URL(fileURLWithPath: $0) })
        let xspf = try loader.parse(try Fixture.data("sample.xspf"))
        XCTAssertEqual(xspf.channels.map(\.name), ["Channel A", "Channel B"])
        XCTAssertEqual(xspf.liveCategories.first?.name, "News")

        let json = try loader.parse(try Fixture.data("sample.json"))
        XCTAssertEqual(json.channels.map(\.name), ["JSON One"])
        XCTAssertEqual(json.channels.first?.epgID, "one.fr")
        XCTAssertEqual(json.movies.map(\.name), ["JSON Movie"])
    }

    func testParseErrors() throws {
        let loader = PlaylistLoader(http: MockHTTPClient(), localFileURL: { URL(fileURLWithPath: $0) })
        XCTAssertThrowsError(try loader.parse(Data("<html>Forbidden</html>".utf8))) { error in
            XCTAssertEqual(error as? PlaylistLoaderError, .unsupportedFormat)
        }
        XCTAssertThrowsError(try loader.parse(Data("#EXTM3U\n".utf8))) { error in
            XCTAssertEqual(error as? PlaylistLoaderError, .emptyPlaylist)
        }
    }
}
