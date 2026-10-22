import XCTest
@testable import IPTVCore

final class ContentBuilderTests: XCTestCase {
    private func content() throws -> PlaylistContent {
        try PlaylistLoader(http: MockHTTPClient(), localFileURL: { URL(fileURLWithPath: $0) }).parse(try Fixture.data("sample.m3u"))
    }

    func testClassification() throws {
        let content = try content()
        XCTAssertEqual(content.channels.map(\.name), ["TV1 HD", "Nation 2", "Stadia Sports 1", "Aria", "Wave Radio", "Hot channel", "TV1 HD"])
        XCTAssertEqual(content.movies.map(\.name), ["Lighthouse", "Dust Road Fury"])
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
        XCTAssertEqual(series.name, "Copper Valley")
        XCTAssertEqual(series.coverURL?.absoluteString, "http://img.example.com/copper.jpg")
        XCTAssertEqual(series.episodes.map(\.code), ["S01E01", "S01E02", "S02E01"])
        XCTAssertEqual(series.episodes[0].title, "Pilot")
        XCTAssertEqual(series.episodes[2].title, "Seven Bridges")
    }

    func testSeriesTakeTheirYearFromTheTitle() throws {
        let m3u = """
        #EXTM3U
        #EXTINF:-1 group-title="Series | Drama",The Archive (2025) S01E01
        http://example.com/a101.mp4
        #EXTINF:-1 group-title="Series | Drama",The Archive (2025) S01E02
        http://example.com/a102.mp4
        """
        let content = try PlaylistLoader(http: MockHTTPClient(), localFileURL: { URL(fileURLWithPath: $0) }).parse(Data(m3u.utf8))
        let series = try XCTUnwrap(content.series.first)
        XCTAssertEqual(content.series.count, 1)
        XCTAssertEqual(series.name, "The Archive")
        XCTAssertEqual(series.year, 2025)
        XCTAssertEqual(series.episodes.map(\.code), ["S01E01", "S01E02"])
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

    /// Thousands of channels on one address: numbered in order, without retrying every number taken before.
    func testManyChannelsSharingAURL() throws {
        let m3u = "#EXTM3U\n" + String(repeating: "#EXTINF:-1 group-title=\"News\",TV\nhttp://example.com/live.ts\n", count: 20_000)
        let started = Date()
        let channels = try PlaylistLoader(http: MockHTTPClient(), localFileURL: { URL(fileURLWithPath: $0) }).parse(Data(m3u.utf8)).channels
        XCTAssertLessThan(Date().timeIntervalSince(started), 10)
        let base = channels[0].id
        XCTAssertEqual(channels.prefix(3).map(\.id), [base, base + "-2", base + "-3"])
        XCTAssertEqual(channels.last?.id, base + "-20000")
        XCTAssertEqual(Set(channels.map(\.id)).count, 20_000)
    }

    func testCatchupAttributes() throws {
        let channels = try content().channels
        XCTAssertEqual(channels[0].catchup, CatchupInfo(mode: .shift, days: 2), "header defaults apply")
        XCTAssertEqual(channels[1].catchup?.mode, .flussonic)
        XCTAssertEqual(channels[1].catchup?.days, 5)
        XCTAssertEqual(channels[3].catchup?.mode, .default)
        XCTAssertEqual(channels[3].catchup?.source, "http://archive.example.com/aria?start={utc}&end={utcend}")
    }

    func testXtreamStreamIDDetectedInPlainM3U() throws {
        let tv1 = try content().channels[0]
        XCTAssertEqual(tv1.xtreamStreamID, 101)
        XCTAssertEqual(tv1.epgID, "TV1.fr")
    }

    func testTitleParser() {
        XCTAssertEqual(TitleParser.parseEpisode("The Workshop (US) S03E12 Night Shift")?.seriesName, "The Workshop (US)")
        XCTAssertEqual(TitleParser.parseEpisode("Adrift 2x05")?.season, 2)
        XCTAssertEqual(TitleParser.parseEpisode("Adrift 2x05")?.episode, 5)
        XCTAssertEqual(TitleParser.parseEpisode("Dix sur dix Saison 2 Episode 3")?.episode, 3)
        XCTAssertNil(TitleParser.parseEpisode("TV1 HD"))
        XCTAssertEqual(TitleParser.splitYear("Sandstorm [2021]").0, "Sandstorm")
        XCTAssertEqual(TitleParser.splitYear("Sandstorm [2021]").1, 2021)
        XCTAssertEqual(TitleParser.splitYear("Star Runner 2049").1, 2049)
        XCTAssertNil(TitleParser.splitYear("Lattice").1)
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

    func testAJSONListWithAStrayValueKeepsItsChannels() {
        let array = #"[{"name":"One","url":"http://stream.example.com/1.m3u8"},null,"junk",{"name":"Two","url":"http://stream.example.com/2.m3u8"}]"#
        XCTAssertEqual(JSONPlaylistParser.parse(Data(array.utf8)).entries.map(\.title), ["One", "Two"])
        let object = #"{"channels":[7,{"name":"One","url":"http://stream.example.com/1.m3u8"}]}"#
        XCTAssertEqual(JSONPlaylistParser.parse(Data(object.utf8)).entries.map(\.title), ["One"])
    }

    func testFileExtensionComesFromThePathNeverTheHost() throws {
        func ext(_ string: String) throws -> String { ContentClassifier.fileExtension(of: try XCTUnwrap(URL(string: string))) }
        XCTAssertEqual(try ext("http://stream.example.com"), "")
        XCTAssertEqual(try ext("http://stream.example.com?id=1.2"), "")
        XCTAssertEqual(try ext("http://stream.example.tv/"), "")
        XCTAssertEqual(try ext("http://stream.example.com:8080/live/1.TS?token=a.b"), "ts")
        XCTAssertEqual(try ext("http://stream.example.com/films/dust-road.mkv#t=10"), "mkv")
        XCTAssertEqual(try ext("file:///videos/clip.mp4"), "mp4")
        XCTAssertEqual(try ext("http://stream.example.com/stream"), "")
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
