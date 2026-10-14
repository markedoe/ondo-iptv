import XCTest
@testable import IPTVCore

final class XtreamTests: XCTestCase {
    private let credentials = XtreamCredentials(server: URL(string: "http://panel.example.com:8080")!, username: "demo", password: "se/cr+et")

    private func mock() throws -> MockHTTPClient {
        let http = MockHTTPClient()
        http.routes = [
            "api:": try Fixture.data("xtream_auth.json"),
            "api:get_live_categories": try Fixture.data("xtream_live_categories.json"),
            "api:get_live_streams": try Fixture.data("xtream_live_streams.json"),
            "api:get_vod_categories": try Fixture.data("xtream_vod_categories.json"),
            "api:get_vod_streams": try Fixture.data("xtream_vod_streams.json"),
            "api:get_series_categories": try Fixture.data("xtream_series_categories.json"),
            "api:get_series": try Fixture.data("xtream_series.json"),
            "api:get_series_info": try Fixture.data("xtream_series_info.json"),
            "api:get_vod_info": try Fixture.data("xtream_vod_info.json"),
            "api:get_short_epg": try Fixture.data("xtream_short_epg.json"),
        ]
        return http
    }

    func testAuthentication() async throws {
        let account = try await XtreamClient(credentials: credentials, http: try mock()).authenticate()
        XCTAssertEqual(account.status, "Active")
        XCTAssertTrue(account.isActive)
        XCTAssertEqual(account.maxConnections, 2)
        XCTAssertEqual(account.expiresAt, Date(timeIntervalSince1970: 1_924_992_000))
        XCTAssertEqual(account.allowedOutputFormats, ["m3u8", "ts", "rtmp"])
        XCTAssertEqual(account.serverTimeZone, "Europe/Paris")
    }

    func testAuthenticationFailure() async throws {
        let http = MockHTTPClient()
        http.routes["api:"] = try Fixture.data("xtream_auth_failed.json")
        do {
            _ = try await XtreamClient(credentials: credentials, http: http).authenticate()
            XCTFail("expected failure")
        } catch {
            XCTAssertEqual(error as? XtreamError, .authenticationFailed(nil))
        }
        http.routes["api:"] = Data("<html>nginx</html>".utf8)
        do {
            _ = try await XtreamClient(credentials: credentials, http: http).authenticate()
            XCTFail("expected failure")
        } catch {
            XCTAssertEqual(error as? XtreamError, .invalidResponse)
        }
    }

    func testFetchContentWithMessyTypes() async throws {
        let content = try await XtreamClient(credentials: credentials, http: try mock()).fetchContent(format: .m3u8)
        XCTAssertEqual(content.channels.count, 4, "entries without stream_id and non-objects are skipped")
        let tf1 = content.channels[0]
        XCTAssertEqual(tf1.id, "x101")
        XCTAssertEqual(tf1.epgID, "TF1.fr")
        XCTAssertEqual(tf1.catchup, CatchupInfo(mode: .xtream, days: 7))
        XCTAssertEqual(tf1.streamURL.absoluteString, "http://panel.example.com:8080/live/demo/se%2Fcr+et/101.m3u8")
        XCTAssertEqual(content.channels[1].xtreamStreamID, 102)
        XCTAssertNil(content.channels[1].logoURL)
        XCTAssertNil(content.channels[1].catchup)

        XCTAssertEqual(content.liveCategories.map(\.name), ["FR | Généralistes", "FR | Sport", "XXX | Adult", "Other"])
        XCTAssertEqual(content.channels[3].categoryID, "live-55")

        XCTAssertEqual(content.movies.map(\.name), ["Inception", "Dune: Part Two"])
        XCTAssertEqual(content.movies[0].year, 2010)
        XCTAssertEqual(content.movies[0].rating, 8.8)
        XCTAssertEqual(content.movies[1].rating ?? 0, 8.2, accuracy: 0.001, "rating_5based is converted to /10")
        XCTAssertEqual(content.movies[0].streamURL.absoluteString, "http://panel.example.com:8080/movie/demo/se%2Fcr+et/5001.mkv")

        XCTAssertEqual(content.series.first?.xtreamSeriesID, 9000)
        XCTAssertEqual(content.series.first?.year, 2008)
        XCTAssertEqual(content.series[1].name, "The Archive", "the year in the title is not part of the name")
        XCTAssertEqual(content.series[1].year, 2025, "no year from the provider: the one in the title")
        XCTAssertEqual(content.providerEPGURLs.first?.path, "/xmltv.php")
        XCTAssertNotNil(content.account)
    }

    func testSeriesDetailsWithDictionaryEpisodes() async throws {
        let details = try await XtreamClient(credentials: credentials, http: try mock()).seriesDetails(seriesID: 9000)
        XCTAssertEqual(details.info.title, "Breaking Bad")
        XCTAssertEqual(details.info.backdropURL?.absoluteString, "http://img.example.com/bb_back.jpg")
        XCTAssertEqual(details.seasons.map(\.number), [1, 2])
        XCTAssertEqual(details.seasons[0].name, "Saison 1")
        XCTAssertEqual(details.seasons[0].coverURL?.absoluteString, "http://img.example.com/s1_big.jpg")
        XCTAssertEqual(details.seasons[0].episodes.map(\.title), ["Pilot", "Cat's in the Bag..."])
        XCTAssertEqual(details.seasons[0].episodes[0].durationSeconds, 3480)
        XCTAssertEqual(details.seasons[1].episodes[0].durationSeconds, 2820)
        XCTAssertEqual(details.seasons[1].episodes[0].streamURL.absoluteString, "http://panel.example.com:8080/series/demo/se%2Fcr+et/9010.mp4")
    }

    func testSeriesDetailsWithArrayEpisodes() async throws {
        let http = MockHTTPClient()
        http.routes["api:get_series_info"] = try Fixture.data("xtream_series_info_array.json")
        let details = try await XtreamClient(credentials: credentials, http: http).seriesDetails(seriesID: 1)
        XCTAssertEqual(details.seasons.map(\.number), [1, 2])
        XCTAssertEqual(details.seasons[0].episodes.count, 2)
        XCTAssertEqual(details.seasons[1].name, "Season 2")
    }

    func testMovieDetails() async throws {
        let details = try await XtreamClient(credentials: credentials, http: try mock()).movieDetails(streamID: 5001)
        XCTAssertEqual(details.title, "Inception")
        XCTAssertEqual(details.director, "Christopher Nolan")
        XCTAssertEqual(details.durationSeconds, 8880)
        XCTAssertEqual(details.trailerYouTubeID, "YoHD9XEInc0")
        XCTAssertEqual(details.tmdbID, "27205")
    }

    func testShortEPGDecodesBase64() async throws {
        let programs = try await XtreamClient(credentials: credentials, http: try mock()).shortEPG(streamID: 101, epgChannelID: "TF1.fr")
        XCTAssertEqual(programs.map(\.title), ["Le JT", "Film"])
        XCTAssertEqual(programs[0].summary, "L'actualité")
        XCTAssertNil(programs[1].summary)
        XCTAssertEqual(programs[0].start, Date(timeIntervalSince1970: 1_760_032_800))
    }

    func testURLBuilder() {
        let builder = XtreamURLBuilder(credentials: credentials)
        let api = builder.apiURL(action: "get_series_info", parameters: ["series_id": "3"])
        XCTAssertTrue(api.absoluteString.contains("password=se/cr%2Bet"), api.absoluteString)
        XCTAssertTrue(api.absoluteString.hasSuffix("action=get_series_info&series_id=3"))

        let start = Date.utc(2025, 10, 9, 18, 30)
        let paris = TimeZone(identifier: "Europe/Paris")!
        XCTAssertEqual(
            builder.timeshiftURL(streamID: 101, start: start, durationMinutes: 45, serverTimeZone: paris, format: .ts).absoluteString,
            "http://panel.example.com:8080/timeshift/demo/se%2Fcr+et/45/2025-10-09:20-30/101.ts"
        )
        XCTAssertEqual(builder.m3uURL(format: .ts).query, "username=demo&password=se/cr%2Bet&type=m3u_plus&output=ts")
        XCTAssertTrue(builder.xmltvURL.absoluteString.hasSuffix("password=se/cr%2Bet"), builder.xmltvURL.absoluteString)
    }

    func testCredentialsNormalisation() {
        XCTAssertEqual(XtreamCredentials.normalizeServer("panel.example.com:8080/")?.absoluteString, "http://panel.example.com:8080")
        XCTAssertEqual(XtreamCredentials.normalizeServer(" https://panel.example.com/player_api.php?x=1 ")?.absoluteString, "https://panel.example.com")
        XCTAssertNil(XtreamCredentials.normalizeServer("   "))

        let fromLink = XtreamCredentials.fromM3ULink(URL(string: "http://p.example.com:80/get.php?username=u1&password=p1&type=m3u_plus&output=ts")!)
        XCTAssertEqual(fromLink, XtreamCredentials(server: URL(string: "http://p.example.com:80")!, username: "u1", password: "p1"))
        XCTAssertNil(XtreamCredentials.fromM3ULink(URL(string: "http://p.example.com/list.m3u")!))
    }

    func testLiveURLRecognition() {
        let parsed = XtreamURLBuilder.parseLiveURL(URL(string: "http://h.example.com:8080/live/u/p/42.ts")!)
        XCTAssertEqual(parsed?.streamID, 42)
        XCTAssertEqual(parsed?.username, "u")
        XCTAssertEqual(XtreamURLBuilder.parseLiveURL(URL(string: "http://h.example.com:8080/u/p/42")!)?.streamID, 42)
        XCTAssertNil(XtreamURLBuilder.parseLiveURL(URL(string: "http://h.example.com/movie/u/p/42.mkv")!))
        XCTAssertNil(XtreamURLBuilder.parseLiveURL(URL(string: "http://h.example.com/hls/stream.m3u8")!))
        XCTAssertEqual(
            XtreamURLBuilder.alternateFormatURL(for: URL(string: "http://h.example.com:8080/live/u/p/42.ts")!)?.absoluteString,
            "http://h.example.com:8080/live/u/p/42.m3u8"
        )
    }
}
