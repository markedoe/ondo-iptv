import XCTest
@testable import IPTVCore

/// An episode's own title on every screen, and TMDb's stills and names for the episodes a provider leaves bare. Same
/// cases as Android's `EpisodeTitlesTest`.
final class EpisodeTitlesTests: XCTestCase {
    private let jackRyan = "|EN| Tom Clancy's Jack Ryan"

    func testAProvidersNameWithOnlyTheSeriesAndCodeIsNoTitle() {
        // Before: the provider's whole name ("1. |EN| Tom Clancy's Jack …" on the page).
        XCTAssertEqual(EpisodeTitles.clean("|EN| Tom Clancy's Jack Ryan S03E02", seriesName: jackRyan, number: 2), "Episode 2")
        XCTAssertEqual(EpisodeTitles.clean("|EN| Tom Clancy's Jack Ryan S03E01", seriesName: nil, number: 1), "Episode 1")
        XCTAssertEqual(EpisodeTitles.clean("|EN| Tom Clancy's Jack Ryan", seriesName: jackRyan, number: 4), "Episode 4", "the series' name alone")
        XCTAssertEqual(EpisodeTitles.clean("", seriesName: jackRyan, number: 5), "Episode 5")
        XCTAssertEqual(EpisodeTitles.clean("Episode 03", seriesName: jackRyan, number: 3), "Episode 3")
        XCTAssertEqual(EpisodeTitles.clean("E03", seriesName: jackRyan, number: 3), "Episode 3")
    }

    func testAnEpisodesOwnTitleKeepsWithoutTheSeriesNameOrTags() {
        XCTAssertEqual(EpisodeTitles.clean("Copper Valley - S01E01 - Pilot", seriesName: "Copper Valley", number: 1), "Pilot")
        XCTAssertEqual(EpisodeTitles.clean("|EN| Tom Clancy's Jack Ryan S01E01 The Big Fish", seriesName: jackRyan, number: 1), "The Big Fish")
        XCTAssertEqual(EpisodeTitles.clean("Tom Clancy's Jack Ryan - The Big Fish", seriesName: jackRyan, number: 1), "The Big Fish")
        XCTAssertEqual(EpisodeTitles.clean("[EN] The Big Fish", seriesName: jackRyan, number: 1), "The Big Fish")
        XCTAssertEqual(EpisodeTitles.clean("Cat's in the Bag...", seriesName: "Copper Valley", number: 2), "Cat's in the Bag...")
        // A number that is not the episode's own is its title.
        XCTAssertEqual(EpisodeTitles.clean("42", seriesName: jackRyan, number: 7), "42")
        XCTAssertEqual(EpisodeTitles.clean("07", seriesName: jackRyan, number: 7), "Episode 7")
        // A title that only starts like the series' name is the episode's own.
        XCTAssertEqual(EpisodeTitles.clean("Copper Valleys", seriesName: "Copper Valley", number: 1), "Copper Valleys")
    }

    /// "Episode N" is IPTVCore's name: the app shows it in its language, "Épisode 2" in French.
    func testAnUntitledEpisodeReadsInTheAppsLanguage() {
        let french = PlaceholderNames(
            uncategorized: "Non classé", other: "Autre", otherNumbered: { "Autre \($0)" }, channel: { "Chaîne \($0)" },
            movie: { "Film \($0)" }, series: { "Série \($0)" }, episode: { "Épisode \($0)" }
        )
        let title = EpisodeTitles.clean("|EN| Tom Clancy's Jack Ryan S03E02", seriesName: jackRyan, number: 2)
        let episode = Episode(id: "e", title: title, season: 3, number: 2, streamURL: URL(string: "http://example.com/e.mp4")!)
        XCTAssertEqual(french.renaming(episode).title, "Épisode 2")
        XCTAssertEqual(EpisodeTitles.cleanSubtitle("S03E02 · \(jackRyan) S03E02", seriesName: jackRyan, names: french), "S03E02 · Épisode 2")
        XCTAssertEqual(EpisodeTitles.cleanSubtitle("S03E02 · Falcon", seriesName: jackRyan), "S03E02 · Falcon")
        XCTAssertEqual(EpisodeTitles.cleanSubtitle("VF 4K", seriesName: jackRyan), "VF 4K", "not an episode's line")
    }

    /// The regression on Xtream panels: the details' episodes come without the series' name and code.
    func testXtreamEpisodesNamedOnlyByTheirCodeReadAsTheirNumber() async throws {
        let http = MockHTTPClient()
        http.routes["api:get_series_info"] = Data((
            #"{"info":{"name":"|EN| Tom Clancy's Jack Ryan"},"episodes":{"3":["#
                + #"{"id":"1","episode_num":1,"title":"|EN| Tom Clancy's Jack Ryan S03E01","container_extension":"mp4"},"#
                + #"{"id":"2","episode_num":2,"title":"|EN| Tom Clancy's Jack Ryan S03E02 - Falcon","container_extension":"mp4"}]}}"#
        ).utf8)
        let credentials = XtreamCredentials(server: URL(string: "http://panel.example.com")!, username: "viewer", password: "example")
        let details = try await XtreamClient(credentials: credentials, http: http).seriesDetails(seriesID: 7)
        XCTAssertEqual(details.seasons.first?.episodes.map(\.title), ["Episode 1", "Falcon"])
    }

    func testTMDbFillsTheStillsAndNamesTheProviderLeavesOut() {
        func episode(_ number: Int, _ poster: String?, title: String? = nil) -> Episode {
            Episode(
                id: "e\(number)", title: title ?? "\(jackRyan) S03E0\(number)", season: 3, number: number,
                streamURL: URL(string: "http://example.com/\(number).mp4")!, posterURL: poster.flatMap(URL.init(string:))
            )
        }
        let details = SeriesDetails(info: MediaDetails(title: jackRyan), seasons: [
            Season(number: 2, name: "Season 2", episodes: [
                Episode(id: "s2e1", title: "Pilot", season: 2, number: 1, streamURL: URL(string: "http://example.com/s2e1.mp4")!),
            ]),
            Season(number: 3, name: "Season 3", episodes: [
                episode(1, "http://example.com/provider-1.jpg"),
                episode(2, nil),
                episode(3, nil),
                episode(4, "http://example.com/provider-4.jpg", title: "Own Title"),
                episode(5, nil),
            ]),
        ])
        let tmdb = [3: [
            TMDbEpisode(number: 1, name: "Falcon", stillPath: "/s1.jpg"),
            TMDbEpisode(number: 2, name: "Paralysis", stillPath: "/s2.jpg"),
            TMDbEpisode(number: 3, name: "Episode 3", stillPath: "/s3.jpg", overview: "TMDb's plot"),
            TMDbEpisode(number: 4, stillPath: "/s4.jpg"),
            // Episode 5: TMDb has no still either.
            TMDbEpisode(number: 5),
        ]]
        let merged = EpisodeTitles.merge(details, seriesName: jackRyan, tmdb: tmdb)
        let season3 = merged.seasons[1].episodes
        XCTAssertEqual(
            season3.map { $0.posterURL?.absoluteString },
            [
                "http://example.com/provider-1.jpg", "https://image.tmdb.org/t/p/w300/s2.jpg", "https://image.tmdb.org/t/p/w300/s3.jpg",
                "http://example.com/provider-4.jpg", nil,
            ],
            "the provider's picture first, TMDb's still where it gave none, the placeholder when neither has one"
        )
        XCTAssertEqual(season3.map(\.title), ["Falcon", "Paralysis", "Episode 3", "Own Title", "Episode 5"])
        XCTAssertEqual(season3[2].plot, "TMDb's plot")
        // Matched by number in the same season, never by position or another season's.
        XCTAssertEqual(merged.seasons[0].episodes.map(\.title), ["Pilot"])
    }

    func testASeasonsEpisodesComeInOneRequest() async throws {
        let http = MockHTTPClient()
        http.routes["https://api.themoviedb.org/3/tv/73375/season/3?"] = Data((
            #"{"id":1,"season_number":3,"episodes":[{"episode_number":1,"name":"Falcon","still_path":"/s1.jpg","overview":"x"},"#
                + #"{"episode_number":2,"name":"Paralysie","still_path":null},{"name":"no number"}]}"#
        ).utf8)
        let client = TMDbClient(credential: "KEY", http: http, language: "fr-FR")
        let episodes = try await client.seasonEpisodes(seriesID: 73375, season: 3)
        XCTAssertEqual(episodes, [TMDbEpisode(number: 1, name: "Falcon", stillPath: "/s1.jpg", overview: "x"), TMDbEpisode(number: 2, name: "Paralysie")])
        XCTAssertEqual(http.requested.count, 1, "one request for the whole season")
        let missing = try await client.seasonEpisodes(seriesID: 73375, season: 9)
        XCTAssertTrue(missing.isEmpty, "a season TMDb does not have: none")
    }

    /// A series' cast on TMDb is every season's (`aggregate_credits`), not only its latest season's (`credits`), and
    /// its makers are its creators (`created_by`), with their photos.
    func testASeriesCreditsAreEverySeasonsAndItsCreators() async throws {
        let http = MockHTTPClient()
        http.routes["https://api.themoviedb.org/3/tv/73375?"] = Data((
            #"{"id":73375,"name":"Tom Clancy's Jack Ryan","created_by":[{"id":1,"name":"Carlton Cuse","profile_path":"/cc.jpg"},"#
                + #"{"id":2,"name":"Graham Roland","profile_path":"/gr.jpg"}],"#
                + #""credits":{"cast":[{"id":3,"name":"John Krasinski","profile_path":"/jk.jpg","order":0}],"crew":[]},"#
                + #""aggregate_credits":{"cast":[{"id":4,"name":"Nina Hoss","profile_path":"/nh.jpg","order":7},"#
                + #"{"id":3,"name":"John Krasinski","profile_path":"/jk.jpg","order":0}],"#
                + #""crew":[{"id":5,"name":"Episode Director","jobs":[{"job":"Director"}]}]}}"#
        ).utf8)
        http.routes["https://api.themoviedb.org/3/tv/73375/videos?"] = Data(#"{"results":[]}"#.utf8)
        let found = try await TMDbClient(credential: "KEY", http: http).titleInfo(of: .series, tmdbID: "73375", title: "Tom Clancy's Jack Ryan", year: nil)
        let info = try XCTUnwrap(found)
        XCTAssertEqual(info.directors.map(\.name), ["Carlton Cuse", "Graham Roland"])
        XCTAssertEqual(info.directors.map { $0.profileURL?.absoluteString }, ["https://image.tmdb.org/t/p/w185/cc.jpg", "https://image.tmdb.org/t/p/w185/gr.jpg"])
        XCTAssertEqual(info.cast.map(\.name), ["John Krasinski", "Nina Hoss"], "every season's, in their billing order")
        let asked = try XCTUnwrap(http.requested.first { $0.path == "/3/tv/73375" })
        let appended = URLComponents(url: asked, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "append_to_response" }?.value
        XCTAssertEqual(appended, "aggregate_credits")
    }
}
