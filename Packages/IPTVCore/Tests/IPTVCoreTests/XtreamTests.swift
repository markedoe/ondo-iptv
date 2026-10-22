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
        let tv1 = content.channels[0]
        XCTAssertEqual(tv1.id, "x101")
        XCTAssertEqual(tv1.epgID, "TV1.fr")
        XCTAssertEqual(tv1.catchup, CatchupInfo(mode: .xtream, days: 7))
        XCTAssertEqual(tv1.streamURL.absoluteString, "http://panel.example.com:8080/live/demo/se%2Fcr+et/101.m3u8")
        XCTAssertEqual(content.channels[1].xtreamStreamID, 102)
        XCTAssertNil(content.channels[1].logoURL)
        XCTAssertNil(content.channels[1].catchup)

        XCTAssertEqual(content.liveCategories.map(\.name), ["FR | Généralistes", "FR | Sport", "XXX | Adult", "Other"])
        XCTAssertEqual(content.channels[3].categoryID, "live-55")

        XCTAssertEqual(content.movies.map(\.name), ["Lighthouse", "Sandstorm: Part Two"])
        XCTAssertEqual(content.movies[0].year, 2010)
        XCTAssertEqual(content.movies[0].rating, 8.8)
        XCTAssertEqual(content.movies[1].rating ?? 0, 8.2, accuracy: 0.001, "rating_5based is converted to /10")
        XCTAssertEqual(content.movies[0].streamURL.absoluteString, "http://panel.example.com:8080/movie/demo/se%2Fcr+et/5001.mkv")

        XCTAssertEqual(content.series.first?.xtreamSeriesID, 9000)
        XCTAssertEqual(content.series.first?.year, 2008)
        XCTAssertEqual(content.series.first?.cast, "Alex Marsh", "the series list names the cast")
        XCTAssertEqual(content.series.first?.director, "Casey Brook")
        XCTAssertEqual(content.series[1].name, "The Archive", "the year in the title is not part of the name")
        XCTAssertEqual(content.series[1].year, 2025, "no year from the provider: the one in the title")
        XCTAssertEqual(content.providerEPGURLs.first?.path, "/xmltv.php")
        XCTAssertNotNil(content.account)
    }

    func testASectionThePanelDoesNotOfferIsEmpty() async throws {
        let http = try mock()
        http.routes["api:get_vod_streams"] = nil  // 404
        http.routes["api:get_series"] = Data("{\"error\":\"disabled\"}".utf8)
        let content = try await XtreamClient(credentials: credentials, http: http).fetchContent(format: .m3u8)
        XCTAssertEqual(content.channels.count, 4)
        XCTAssertTrue(content.movies.isEmpty)
        XCTAssertTrue(content.series.isEmpty)
    }

    func testASectionThatFailsOnceIsAskedAgain() async throws {
        let http = try mock()
        http.failures["api:get_live_streams"] = [HTTPClientError.badStatus(884)]
        let content = try await XtreamClient(credentials: credentials, http: http).fetchContent(format: .m3u8)
        XCTAssertEqual(content.channels.count, 4)
        XCTAssertEqual(http.requested.filter { $0.query?.contains("get_live_streams") == true }.count, 2)
    }

    /// A refresh that lost its channels to a timeout must fail, or the app saves an empty catalogue over the
    /// good one.
    func testASectionThatKeepsFailingFailsTheRefresh() async throws {
        let http = try mock()
        http.failures["api:get_live_streams"] = [URLError(.timedOut), URLError(.timedOut)]
        do {
            _ = try await XtreamClient(credentials: credentials, http: http).fetchContent(format: .m3u8)
            XCTFail("expected the timeout")
        } catch {
            XCTAssertEqual((error as? URLError)?.code, .timedOut)
        }
    }

    func testAnAccountThatListsNothingIsAnError() async throws {
        let http = MockHTTPClient()
        http.routes["api:"] = try Fixture.data("xtream_auth.json")
        let playlist = Playlist(name: "Empty", source: .xtream(credentials))
        do {
            _ = try await PlaylistLoader(http: http, localFileURL: { URL(fileURLWithPath: $0) }).load(playlist)
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? PlaylistLoaderError, .emptyPlaylist)
        }
    }

    func testSeriesDetailsWithDictionaryEpisodes() async throws {
        let details = try await XtreamClient(credentials: credentials, http: try mock()).seriesDetails(seriesID: 9000)
        XCTAssertEqual(details.info.title, "Copper Valley")
        XCTAssertEqual(details.info.backdropURL?.absoluteString, "http://img.example.com/copper_back.jpg")
        XCTAssertEqual(details.seasons.map(\.number), [1, 2])
        XCTAssertEqual(details.seasons[0].name, "Saison 1")
        XCTAssertEqual(details.seasons[0].coverURL?.absoluteString, "http://img.example.com/s1_big.jpg")
        XCTAssertEqual(details.seasons[0].episodes.map(\.title), ["Pilot", "Down the Drain..."])
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
        XCTAssertEqual(details.title, "Lighthouse")
        XCTAssertEqual(details.director, "Jordan Vale")
        XCTAssertEqual(details.durationSeconds, 8880)
        XCTAssertEqual(details.trailerYouTubeID, "trailer0001")
        XCTAssertEqual(details.tmdbID, "910001")
    }

    func testABackdropWithoutItsFileIsSkipped() {
        let empty = ["https://image.tmdb.org/t/p/w1280", "https://image.tmdb.org/t/p/w1280null", " "]
        XCTAssertEqual(XtreamClient.backdropURL(in: empty + ["http://img.example.com/back.jpg"])?.absoluteString,
                       "http://img.example.com/back.jpg")
        XCTAssertEqual(XtreamClient.backdropURL(in: ["https://image.tmdb.org/t/p/w1280/abc.jpg"])?.absoluteString,
                       "https://image.tmdb.org/t/p/w1280/abc.jpg")
        // Other servers may serve images without an extension.
        XCTAssertEqual(XtreamClient.backdropURL(in: ["http://img.example.com/art?id=4"])?.absoluteString, "http://img.example.com/art?id=4")
        XCTAssertNil(XtreamClient.backdropURL(in: empty))
    }

    func testShortEPGDecodesBase64() async throws {
        let programs = try await XtreamClient(credentials: credentials, http: try mock()).shortEPG(streamID: 101, epgChannelID: "TV1.fr")
        XCTAssertEqual(programs.map(\.title), ["Le JT", "Film"])
        XCTAssertEqual(programs[0].summary, "L'actualité")
        XCTAssertNil(programs[1].summary)
        XCTAssertEqual(programs[0].start, Date(timeIntervalSince1970: 1_760_032_800))
    }

    /// A few panels send the guide as plain text: short titles such as "Film" or "Show" are valid base64 as well
    /// and must stay as sent instead of turning into garbage.
    func testShortEPGInPlainTextStaysReadable() async throws {
        let http = MockHTTPClient()
        http.routes["api:get_short_epg"] = Data(#"""
        {"epg_listings":[{"title":"Film","description":"","start_timestamp":1760032800,"stop_timestamp":1760035500},
        {"title":"Show","description":"Le magazine du soir","start_timestamp":1760035500,"stop_timestamp":1760039100}]}
        """#.utf8)
        let programs = try await XtreamClient(credentials: credentials, http: http).shortEPG(streamID: 101, epgChannelID: "TV1.fr")
        XCTAssertEqual(programs.map(\.title), ["Film", "Show"])
        XCTAssertNil(programs[0].summary)
        XCTAssertEqual(programs[1].summary, "Le magazine du soir")
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

    /// Reserved characters in the login survive both URL forms, there and back: a live URL parsed again (for
    /// the HLS fallback and catch-up of a plain M3U) must give back the very same password.
    func testCredentialsWithReservedCharactersRoundTrip() throws {
        let password = "p/a%20s+s&w=o#r?d"  // leak-guard: allow (fictional, made of reserved characters)
        let tricky = XtreamCredentials(server: URL(string: "http://panel.example.com:8080")!, username: "user name", password: password)
        let builder = XtreamURLBuilder(credentials: tricky)
        let live = builder.liveURL(streamID: 42, format: .ts)
        let parsed = try XCTUnwrap(XtreamURLBuilder.parseLiveURL(live), live.absoluteString)
        XCTAssertEqual(parsed.username, tricky.username)
        XCTAssertEqual(parsed.password, tricky.password)
        XCTAssertEqual(parsed.streamID, 42)
        XCTAssertEqual(XtreamURLBuilder.alternateFormatURL(for: live), builder.liveURL(streamID: 42, format: .m3u8))

        let query = try XCTUnwrap(URLComponents(url: builder.apiURL(action: "get_live_streams"), resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertEqual(query.map(\.name), ["username", "password", "action"], "`&` and `=` in the password do not split it")
        XCTAssertEqual(query[0].value, tricky.username)
        XCTAssertEqual(query[1].value, tricky.password)
    }

    /// Numbers a panel writes out of range ("nan", 1e300…) are dropped like any other bad value, never trapped on.
    func testOutOfRangeNumbersAreIgnored() throws {
        let json = #"""
        [{"stream_id":"nan","name":"No id"},
         {"stream_id":101,"name":1e300,"num":"inf","tv_archive":"NaN","tv_archive_duration":1e300,"added":"1e30"},
         {"stream_id":102,"name":"Nation 2","num":-1e19,"tv_archive_duration":"7.9"}]
        """#
        let list = try JSONDecoder().decode(LossyArray<XtreamLiveStreamDTO>.self, from: Data(json.utf8)).elements
        XCTAssertEqual(list.map(\.streamID), [101, 102], "an id that is not a number skips the entry")
        XCTAssertFalse(list[0].name.isEmpty)
        XCTAssertNil(list[0].number)
        XCTAssertFalse(list[0].tvArchive)
        XCTAssertNil(list[0].tvArchiveDuration)
        XCTAssertNil(list[0].added)
        XCTAssertNil(list[1].number)
        XCTAssertEqual(list[1].tvArchiveDuration, 7)
    }

    func testYearsThatAreNotYearsAreIgnored() throws {
        let movies = #"""
        [{"stream_id":1,"year":"-9223372036854775808"},{"stream_id":2,"year":0},{"stream_id":3,"year":"2021"},{"stream_id":4,"year":1e18}]
        """#
        let list = try JSONDecoder().decode(LossyArray<XtreamVODStreamDTO>.self, from: Data(movies.utf8)).elements
        XCTAssertEqual(list.map(\.year), [nil, nil, 2021, nil])
        let series = #"""
        [{"series_id":1,"year":"0","releaseDate":"2019-05-04"},{"series_id":2,"release_date":"-999-01-01"},{"series_id":3,"year":2008}]
        """#
        let shows = try JSONDecoder().decode(LossyArray<XtreamSeriesDTO>.self, from: Data(series.utf8)).elements
        XCTAssertEqual(shows.map(\.year), [2019, nil, 2008])
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
