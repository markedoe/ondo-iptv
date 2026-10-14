import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import IPTVCore

/// Online subtitle search: each service against its documented answers (fictional titles and keys).
final class SubtitleSearchTests: XCTestCase {
    private let movie = SubtitleQuery(title: "Quiet Water", year: 2021, languages: ["fr", "en"])
    private let episode = SubtitleQuery(title: "Harbor Lights", season: 1, episode: 2, languages: ["fr", "en"])
    private let userAgent = "Ondo v1.0"

    private func json(_ text: String) -> Data { Data(text.utf8) }

    // MARK: Archives and encodings

    func testTheEpisodeIsPickedFromASeasonArchive() throws {
        let archive = try ZipArchive(try Fixture.data("subtitles.zip"))
        XCTAssertEqual(archive.files.map(\.name), ["readme.txt", "Harbor.Lights.S01E01.srt", "Harbor.Lights.S01E02.srt"], "folders and macOS metadata left out")
        let picked = try SubtitleArchive.pick(from: try Fixture.data("subtitles.zip"), episodeCode: "S01E02")
        XCTAssertEqual(picked.fileName, "Harbor.Lights.S01E02.srt")
        XCTAssertEqual(SubtitleParser.parse(picked.data).first?.text, "Deuxième épisode.")
        XCTAssertEqual(try SubtitleArchive.pick(from: try Fixture.data("subtitles.zip"), episodeCode: nil).fileName, "Harbor.Lights.S01E01.srt")
    }

    func testStoredEntriesAndWebVTTAreRead() throws {
        let picked = try SubtitleArchive.pick(from: try Fixture.data("stored.zip"), episodeCode: nil)
        XCTAssertEqual(picked.fileName, "Quiet.Water.2021.vtt")
        XCTAssertEqual(SubtitleParser.parse(picked.data).map(\.text), ["Quiet."])
    }

    func testArchivesAreCappedAndCheckedBeforeBeingTrusted() throws {
        let archive = try ZipArchive(try Fixture.data("large.zip"))
        XCTAssertThrowsError(try archive.contents(of: archive.files[0], limit: 100_000)) { error in
            XCTAssertEqual(error as? ZipError, .entryTooLarge(100_000))
        }
        XCTAssertThrowsError(try ZipArchive(Data("1\n00:00:01,000 --> 00:00:02,000\nPlain text.\n".utf8))) { error in
            XCTAssertEqual(error as? ZipError, .notAnArchive)
        }
        var damaged = try Fixture.data("subtitles.zip")
        damaged.removeSubrange(40..<200)
        XCTAssertThrowsError(try SubtitleArchive.pick(from: damaged, episodeCode: nil))
    }

    func testWindowsEncodedSubtitlesKeepTheirApostrophesAndLigatures() {
        // "L’œuvre" in Windows-1252: 0x92 is the curly apostrophe, 0x9C the "œ".
        let data = Data([0x31, 0x0A, 0x30, 0x30, 0x3A, 0x30, 0x30, 0x3A, 0x30, 0x31, 0x2C, 0x30, 0x30, 0x30, 0x20, 0x2D, 0x2D, 0x3E, 0x20,
                         0x30, 0x30, 0x3A, 0x30, 0x30, 0x3A, 0x30, 0x32, 0x2C, 0x30, 0x30, 0x30, 0x0A, 0x4C, 0x92, 0x9C, 0x75, 0x76, 0x72, 0x65, 0x0A])
        XCTAssertEqual(SubtitleParser.parse(data).first?.text, "L’œuvre")
    }

    // MARK: OpenSubtitles

    private let openSubtitlesResults = """
    {"total_count":2,"data":[
     {"id":"1","type":"subtitle","attributes":{"language":"fr","download_count":120,"hearing_impaired":false,
       "release":"Quiet.Water.2021.1080p.WEB","files":[{"file_id":5001,"file_name":"quiet.water.fr.srt"}]}},
     {"id":"2","type":"subtitle","attributes":{"language":"en","download_count":300,"hearing_impaired":true,"release":"","files":[{"file_id":5002,"file_name":"quiet.water.en.srt"}]}},
     {"id":"3","type":"subtitle","attributes":{"language":"en","files":[]}}
    ]}
    """

    func testOpenSubtitlesSearchesWithTheKeyAndSortedLowercaseParameters() async throws {
        let http = MockHTTPClient()
        http.routes["https://api.opensubtitles.com/api/v1/subtitles?"] = json(openSubtitlesResults)
        let client = OpenSubtitlesClient(apiKey: "key-example-0001", userAgent: userAgent, http: http)
        let results = try await client.search(SubtitleQuery(title: "Quiet Water", year: 2021, languages: ["fr", "en"]))

        XCTAssertEqual(results.map(\.reference), ["5001", "5002"], "an entry without a file is skipped")
        XCTAssertEqual(results.map(\.language), ["fr", "en"])
        XCTAssertEqual(results[1].release, "quiet.water.en.srt", "no release name: the file name")
        XCTAssertTrue(results[1].hearingImpaired)
        let request = try XCTUnwrap(http.sent.first)
        XCTAssertEqual(request.headers["Api-Key"], "key-example-0001")
        XCTAssertEqual(request.headers["User-Agent"], userAgent)
        XCTAssertNil(request.headers["Authorization"], "searching never signs in")
        XCTAssertEqual(request.url.query, "languages=en,fr&query=quiet%20water&type=movie&year=2021")
    }

    func testOpenSubtitlesSignsInOnceForDownloadsAndUsesTheReturnedServer() async throws {
        let http = MockHTTPClient()
        http.routes["POST https://api.opensubtitles.com/api/v1/login"] = json(#"{"token":"jwt-example","base_url":"vip-api.opensubtitles.com","status":200,"user":{"allowed_downloads":100}}"#)
        http.routes["POST https://vip-api.opensubtitles.com/api/v1/download"] = json(#"{"link":"https://dl.example.com/file/5001.srt","file_name":"quiet.water.fr.srt","remaining":99}"#)
        http.routes["https://dl.example.com/file/5001.srt"] = json("1\n00:00:01,000 --> 00:00:02,000\nCalme.\n")
        let client = OpenSubtitlesClient(apiKey: "key-example-0001", username: "viewer", password: "pass-example", userAgent: userAgent, http: http)
        let subtitle = OnlineSubtitle(service: .openSubtitles, reference: "5001", language: "fr", release: "r")

        let first = try await client.download(subtitle, for: movie)
        _ = try await client.download(subtitle, for: movie)

        XCTAssertEqual(first.fileName, "quiet.water.fr.srt")
        XCTAssertEqual(SubtitleParser.parse(first.data).first?.text, "Calme.")
        XCTAssertEqual(http.sent.filter { $0.url.path.hasSuffix("/login") }.count, 1, "the session is kept")
        let download = try XCTUnwrap(http.sent.first { $0.url.path.hasSuffix("/download") })
        XCTAssertEqual(download.headers["Authorization"], "Bearer jwt-example")
        XCTAssertEqual(download.headers["Api-Key"], "key-example-0001")
        XCTAssertEqual(try JSONSerialization.jsonObject(with: download.body ?? Data()) as? [String: Int], ["file_id": 5001])
    }

    func testOpenSubtitlesStopsSigningInOnceTheAccountIsRefused() async throws {
        let http = MockHTTPClient()
        http.failures["POST https://api.opensubtitles.com/api/v1/login"] = [HTTPClientError.badStatus(401)]
        http.routes["POST https://api.opensubtitles.com/api/v1/download"] = json(#"{"link":"https://dl.example.com/file/5001.srt"}"#)
        http.routes["https://dl.example.com/file/5001.srt"] = json("1\n00:00:01,000 --> 00:00:02,000\nCalme.\n")
        let client = OpenSubtitlesClient(apiKey: "key-example-0001", username: "viewer", password: "wrong", userAgent: userAgent, http: http)
        let subtitle = OnlineSubtitle(service: .openSubtitles, reference: "5001", language: "fr", release: "r")

        do {
            _ = try await client.download(subtitle, for: movie)
            XCTFail("a refused account is reported")
        } catch {
            XCTAssertEqual(error as? SubtitleSearchError, .invalidCredentials(.openSubtitles))
        }
        _ = try await client.download(subtitle, for: movie)  // without the account, within the anonymous download limit
        XCTAssertEqual(http.sent.filter { $0.url.path.hasSuffix("/login") }.count, 1, "the service asks not to retry a refused login")
    }

    func testOpenSubtitlesExplainsARefusedKeyAndAnExhaustedQuota() async throws {
        let http = MockHTTPClient()
        http.failures["https://api.opensubtitles.com/api/v1/subtitles?languages=en,fr&query=quiet%20water&type=movie&year=2021"] = [HTTPClientError.badStatus(403)]
        http.failures["POST https://api.opensubtitles.com/api/v1/download"] = [HTTPClientError.badStatus(406)]
        let client = OpenSubtitlesClient(apiKey: "bad", userAgent: userAgent, http: http)
        do {
            _ = try await client.search(movie)
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? SubtitleSearchError, .invalidCredentials(.openSubtitles))
        }
        do {
            _ = try await client.download(OnlineSubtitle(service: .openSubtitles, reference: "1", language: "fr", release: ""), for: movie)
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? SubtitleSearchError, .quotaReached(.openSubtitles))
        }
    }

    /// An expired session is opened again once; what goes wrong after that is explained like a first failure.
    func testOpenSubtitlesExplainsAFailureAfterSigningInAgain() async throws {
        let http = MockHTTPClient()
        http.routes["POST https://api.opensubtitles.com/api/v1/login"] = json(#"{"token":"jwt-example","base_url":"vip-api.opensubtitles.com"}"#)
        http.failures["POST https://vip-api.opensubtitles.com/api/v1/download"] = [HTTPClientError.badStatus(401), HTTPClientError.badStatus(406)]
        let client = OpenSubtitlesClient(apiKey: "key-example-0001", username: "viewer", password: "pass-example", userAgent: userAgent, http: http)
        do {
            _ = try await client.download(OnlineSubtitle(service: .openSubtitles, reference: "5001", language: "fr", release: "r"), for: movie)
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? SubtitleSearchError, .quotaReached(.openSubtitles), "\(error)")
        }
        XCTAssertEqual(http.sent.filter { $0.url.path.hasSuffix("/login") }.count, 2, "signed in again once, after the 401")
    }

    // MARK: SubDL

    func testSubDLFindsZippedSubtitlesAndKeepsTheEpisode() async throws {
        let http = MockHTTPClient()
        http.routes["https://api.subdl.com/api/v1/subtitles?"] = json("""
        {"status":true,"results":[{"name":"Harbor Lights","type":"tv"}],"subtitles":[
         {"release_name":"Harbor.Lights.S01E02.720p","name":"SUBDL::harbor.zip","lang":"french","language":"FR","url":"/subtitle/100-200.zip","hi":false,"season":1,"episode":2},
         {"release_name":"Harbor.Lights.S01.Complete","lang":"english","url":"/subtitle/100-201.zip","hi":true}
        ]}
        """)
        http.routes["https://dl.subdl.com/subtitle/100-200.zip"] = try Fixture.data("subtitles.zip")
        let client = SubDLClient(apiKey: "key-example-0002", http: http)

        let results = try await client.search(episode)
        XCTAssertEqual(results.map(\.language), ["fr", "en"])
        XCTAssertEqual(results.map(\.release), ["Harbor.Lights.S01E02.720p", "Harbor.Lights.S01.Complete"])
        let query = try XCTUnwrap(URLComponents(url: try XCTUnwrap(http.sent.first?.url), resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertTrue(query.contains(URLQueryItem(name: "api_key", value: "key-example-0002")))
        XCTAssertTrue(query.contains(URLQueryItem(name: "languages", value: "FR,EN")))
        XCTAssertTrue(query.contains(URLQueryItem(name: "type", value: "tv")))

        let file = try await client.download(results[0], for: episode)
        XCTAssertEqual(file.fileName, "Harbor.Lights.S01E02.srt")
    }

    func testSubDLRefusedKeyAndNoResult() async throws {
        let http = MockHTTPClient()
        http.routes["https://api.subdl.com/api/v1/subtitles?"] = json(#"{"status":false,"error":"Invalid API key"}"#)
        do {
            _ = try await SubDLClient(apiKey: "bad", http: http).search(movie)
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? SubtitleSearchError, .invalidCredentials(.subdl))
        }
        http.routes["https://api.subdl.com/api/v1/subtitles?"] = json(#"{"status":false,"error":"can't find movie or tv"}"#)
        let none = try await SubDLClient(apiKey: "key-example-0002", http: http).search(movie)
        XCTAssertTrue(none.isEmpty)
    }

    // MARK: SubSource

    func testSubSourceFindsTheTitleThenItsSubtitlesPerLanguage() async throws {
        let http = MockHTTPClient()
        http.routes["https://api.subsource.net/api/v1/movies/search?"] = json("""
        {"success":true,"data":[
         {"movieId":11,"title":"Quiet Water","type":"movie","releaseYear":1998,"season":null},
         {"movieId":12,"title":"Quiet Water","type":"movie","releaseYear":2021,"season":null}
        ]}
        """)
        http.routes["https://api.subsource.net/api/v1/subtitles?"] = json("""
        {"success":true,"data":[{"subtitleId":901,"movieId":12,"language":"french","releaseInfo":["Quiet.Water.2021.1080p"],"hearingImpaired":false,"downloads":42}],"pagination":{"page":1}}
        """)
        http.routes["https://api.subsource.net/api/v1/subtitles/901/download"] = try Fixture.data("stored.zip")
        let client = SubSourceClient(apiKey: "key-example-0003", http: http)

        let results = try await client.search(movie)
        XCTAssertEqual(results.first?.reference, "901")
        XCTAssertEqual(results.first?.language, "fr")
        XCTAssertEqual(results.first?.downloads, 42)
        let subtitleRequests = http.sent.filter { $0.url.path.hasSuffix("/subtitles") }
        XCTAssertEqual(subtitleRequests.count, 2, "one request per preferred language")
        XCTAssertTrue(subtitleRequests[0].url.query?.contains("movieId=12") == true, "the year picks the right title")
        XCTAssertTrue(subtitleRequests[0].url.query?.contains("language=french") == true)
        XCTAssertEqual(http.sent.first?.headers["X-API-Key"], "key-example-0003")

        let file = try await client.download(results[0], for: movie)
        XCTAssertEqual(file.fileName, "Quiet.Water.2021.vtt")
    }

    func testSubSourceKeepsTheEpisodeOfASeasonPage() async throws {
        let http = MockHTTPClient()
        http.routes["https://api.subsource.net/api/v1/movies/search?"] = json(#"{"success":true,"data":[{"movieId":20,"title":"Harbor Lights","type":"tvseries","releaseYear":2024,"season":1}]}"#)
        http.routes["https://api.subsource.net/api/v1/subtitles?"] = json("""
        {"success":true,"data":[
         {"subtitleId":1,"language":"french","releaseInfo":["Harbor.Lights.S01E01.WEB"]},
         {"subtitleId":2,"language":"french","releaseInfo":["Harbor.Lights.S01E02.WEB"]}
        ]}
        """)
        let results = try await SubSourceClient(apiKey: "key-example-0003", http: http).search(episode)
        XCTAssertEqual(Set(results.map(\.reference)), ["2"])
    }

    // MARK: Addic7ed

    func testAddic7edOnlySearchesEpisodes() async throws {
        let http = MockHTTPClient()
        let results = try await Addic7edClient(userAgent: userAgent, http: http).search(movie)
        XCTAssertTrue(results.isEmpty)
        XCTAssertTrue(http.sent.isEmpty, "no request for a film")
    }

    func testAddic7edListsTheEpisodesSubtitlesAndDownloadsThem() async throws {
        let http = MockHTTPClient()
        http.routes["https://api.gestdown.info/shows/search/Harbor%20Lights"] = json("""
        {"shows":[{"id":"show-1","name":"Harbor Lights Stories","nbSeasons":1},{"id":"show-2","name":"Harbor Lights","nbSeasons":2}]}
        """)
        http.routes["https://api.gestdown.info/shows/show-2/1/French"] = json("""
        {"episodes":[
         {"season":1,"number":1,"title":"Arrival","subtitles":[{"subtitleId":"a","version":"WEB","completed":true,
           "hearingImpaired":false,"downloadUri":"/subtitles/download/a","language":"French","downloadCount":10}]},
         {"season":1,"number":2,"title":"Tide","subtitles":[
           {"subtitleId":"b","version":"WEB","completed":true,"hearingImpaired":false,"downloadUri":"/subtitles/download/b","language":"French","downloadCount":30},
           {"subtitleId":"c","version":"HDTV","completed":false,"hearingImpaired":false,"downloadUri":"/subtitles/download/c","language":"French","downloadCount":1}
         ]}
        ],"seasonPacks":[]}
        """)
        http.routes["https://api.gestdown.info/subtitles/download/b"] = json("1\n00:00:01,000 --> 00:00:02,000\nLa marée.\n")
        let client = Addic7edClient(userAgent: userAgent, http: http)

        let results = try await client.search(episode)
        XCTAssertEqual(results.map(\.reference), ["/subtitles/download/b"], "the exact show, this episode, finished subtitles only")
        XCTAssertEqual(results.first?.downloads, 30)
        let file = try await client.download(results[0], for: episode)
        XCTAssertEqual(SubtitleParser.parse(file.data).first?.text, "La marée.")
        XCTAssertEqual(file.fileName, "Harbor Lights.S01E02.fr.srt")
    }

    // MARK: Together

    private struct Failing: SubtitleProvider {
        let service: SubtitleService
        func search(_ query: SubtitleQuery) async throws -> [OnlineSubtitle] { throw SubtitleSearchError.invalidCredentials(service) }
        func download(_ subtitle: OnlineSubtitle, for query: SubtitleQuery) async throws -> DownloadedSubtitle { throw SubtitleSearchError.noSubtitleInArchive }
    }

    private struct Fixed: SubtitleProvider {
        let service: SubtitleService
        let results: [OnlineSubtitle]
        func search(_ query: SubtitleQuery) async throws -> [OnlineSubtitle] { results }
        func download(_ subtitle: OnlineSubtitle, for query: SubtitleQuery) async throws -> DownloadedSubtitle {
            DownloadedSubtitle(fileName: subtitle.reference, data: Data())
        }
    }

    func testAFailingServiceDoesNotHideTheOthersAndResultsFollowTheLanguages() async throws {
        let search = SubtitleSearch(providers: [
            Fixed(service: .openSubtitles, results: [
                OnlineSubtitle(service: .openSubtitles, reference: "en-1", language: "en", release: "a", downloads: 900),
                OnlineSubtitle(service: .openSubtitles, reference: "fr-1", language: "fr", release: "b", downloads: 5),
            ]),
            Failing(service: .subdl),
            Fixed(service: .subSource, results: [OnlineSubtitle(service: .subSource, reference: "fr-2", language: "fr", release: "c", downloads: 50)]),
        ])
        let outcome = await search.search(movie)
        XCTAssertEqual(outcome.results.map(\.reference), ["fr-1", "fr-2", "en-1"], "French first (the preferred language), then by service")
        XCTAssertEqual(outcome.failures.keys.sorted { $0.rawValue < $1.rawValue }, [.subdl])
        let downloaded = try await search.download(outcome.results[1], for: movie)
        XCTAssertEqual(downloaded.fileName, "fr-2")
    }

    func testLanguageNamesAndCodes() {
        XCTAssertEqual(SubtitleLanguages.code(for: "french"), "fr")
        XCTAssertEqual(SubtitleLanguages.code(for: "FR"), "fr")
        XCTAssertEqual(SubtitleLanguages.code(for: "pt-BR"), "pt")
        XCTAssertNil(SubtitleLanguages.code(for: "klingon"))
        XCTAssertEqual(SubtitleLanguages.englishName(of: "fr"), "French")
        XCTAssertEqual(episode.episodeCode, "S01E02")
        XCTAssertNil(movie.episodeCode)
    }
}
