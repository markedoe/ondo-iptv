import XCTest
@testable import IPTVCore

/// The names IPTVCore gives where the provider gives none read in the viewer's words; ids and the provider's names
/// stay. Same cases as the Android core's `PlaceholderNamesTest`.
final class PlaceholderNamesTests: XCTestCase {
    private let french = PlaceholderNames(
        uncategorized: "Non classé",
        other: "Autre",
        otherNumbered: { "Autre \($0)" },
        channel: { "Chaîne \($0)" },
        movie: { "Film \($0)" },
        series: { "Série \($0)" },
        episode: { "Épisode \($0)" }
    )

    private func parse(_ text: String) throws -> PlaylistContent {
        try PlaylistLoader(http: MockHTTPClient(), localFileURL: { URL(fileURLWithPath: $0) }).parse(Data(text.utf8))
    }

    func testAnM3UEntryWithoutGroupOrEpisodeTitle() throws {
        let content = try parse("""
            #EXTM3U
            #EXTINF:-1,News 24
            http://stream.example.com/live/news.m3u8
            #EXTINF:-1 group-title="Series",Copper Valley S01E02
            http://stream.example.com/series/cv-s01e02.mkv
            #EXTINF:-1 group-title="Series",Copper Valley S01E03 Pilot
            http://stream.example.com/series/cv-s01e03.mkv
            """)
        XCTAssertEqual(content.liveCategories.map(\.name), ["Uncategorized"], "the core's own word, kept on disk")
        XCTAssertEqual(content.series.first?.episodes.first?.title, "Episode 2")

        let shown = french.renaming(content)
        XCTAssertEqual(shown.liveCategories.map(\.name), ["Non classé"])
        XCTAssertEqual(shown.liveCategories.map(\.id), content.liveCategories.map(\.id), "the id stays")
        XCTAssertEqual(shown.channels, content.channels)
        XCTAssertEqual(shown.series.first?.episodes.map(\.title), ["Épisode 2", "Pilot"])
        XCTAssertEqual(shown.series.first?.episodes.map(\.id), content.series.first?.episodes.map(\.id))
    }

    func testXtreamItemsWithoutNamesAndCategoriesThePanelLeavesOut() async throws {
        let http = MockHTTPClient()
        http.routes = [
            "api:": Data(#"{"user_info":{"auth":1,"status":"Active"}}"#.utf8),
            "api:get_live_categories": Data(#"[{"category_id":"1"}]"#.utf8),
            "api:get_live_streams": Data(#"[{"stream_id":12,"category_id":"1"},{"stream_id":13,"category_id":"8"},{"stream_id":14,"category_id":"9","name":"Nine"}]"#.utf8),
            "api:get_vod_categories": Data("[]".utf8),
            "api:get_vod_streams": Data(#"[{"stream_id":7}]"#.utf8),
            "api:get_series_categories": Data("[]".utf8),
            "api:get_series": Data(#"[{"series_id":3}]"#.utf8),
            "api:get_series_info": Data(#"{"info":{"name":"x"},"episodes":{"1":[{"id":"31","episode_num":4},{"id":"32","episode_num":5,"title":"Finale"}]}}"#.utf8),
        ]
        let client = XtreamClient(credentials: XtreamCredentials(server: URL(string: "http://panel.example.com")!, username: "demo", password: "demo"), http: http)
        let content = try await client.fetchContent(format: .ts)
        XCTAssertEqual(content.liveCategories.map(\.name), ["Uncategorized", "Other", "Other 2"])

        let shown = french.renaming(content)
        XCTAssertEqual(shown.liveCategories.map(\.name), ["Non classé", "Autre", "Autre 2"])
        XCTAssertEqual(shown.liveCategories.map(\.id), content.liveCategories.map(\.id))
        XCTAssertEqual(shown.channels.map(\.name), ["Chaîne 12", "Chaîne 13", "Nine"])
        XCTAssertEqual(shown.movies.map(\.name), ["Film 7"])
        XCTAssertEqual(shown.series.map(\.name), ["Série 3"])
        XCTAssertEqual(shown.channels.map(\.id), content.channels.map(\.id))

        let details = try await client.seriesDetails(seriesID: 3)
        XCTAssertEqual(french.renaming(details).seasons.first?.episodes.map(\.title), ["Épisode 4", "Finale"])
    }

    func testTheProvidersOwnNamesStay() throws {
        let channel = Channel(id: "c1", name: "Channel 5", categoryID: "live-1", streamURL: URL(string: "http://stream.example.com/5.m3u8")!)
        XCTAssertEqual(french.renaming(channel).name, "Channel 5", "a channel of an M3U list named so by its provider")
        var otherNumber = channel
        otherNumber.xtreamStreamID = 6
        XCTAssertEqual(french.renaming(otherNumber).name, "Channel 5", "a name with another number than the stream's")
        for name in ["Other 1", "Other 02", "Other x", "Others", "Uncategorized movies"] {
            XCTAssertEqual(french.renaming(MediaCategory(id: "live-1", name: name, type: .live)).name, name)
        }
    }

    func testTheCoresWordsInEnglish() throws {
        let content = try parse("#EXTM3U\n#EXTINF:-1,News 24\nhttp://stream.example.com/n.m3u8\n")
        XCTAssertEqual(PlaceholderNames.english.renaming(content), content)
    }
}
