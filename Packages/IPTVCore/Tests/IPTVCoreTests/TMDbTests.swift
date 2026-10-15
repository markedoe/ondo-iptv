import XCTest
@testable import IPTVCore

final class TMDbTests: XCTestCase {
    func testTrendingDecodingAndMatching() async throws {
        let http = MockHTTPClient()
        http.routes["https://api.themoviedb.org/3/trending/all/week?language=fr-FR&api_key=KEY"] = try Fixture.data("tmdb_trending.json")
        let items = try await TMDbClient(credential: " KEY ", http: http, language: "fr-FR").trending()
        XCTAssertEqual(items.count, 4, "people are filtered out")
        XCTAssertEqual(items[1].title, "Copper Valley")
        XCTAssertEqual(items[1].year, 2008)
        XCTAssertEqual(items[0].posterURL?.absoluteString, "https://image.tmdb.org/t/p/w342/poster-sandstorm.jpg")

        let url = URL(string: "http://x/1.mp4")!
        let movies = [
            Movie(id: "1", name: "FR - Sandstorm: Part Two [MULTI] 4K", categoryID: "c", streamURL: url, year: 2024),
            Movie(id: "2", name: "Lighthouse", categoryID: "c", streamURL: url, year: 1990),
            Movie(id: "3", name: "Paper Trail (1999)", categoryID: "c", streamURL: url),
        ]
        let series = [Series(id: "s", name: "Copper Valley (US)", categoryID: "c")]
        let matches = CatalogueMatcher.match(items, movies: movies, series: series)
        XCTAssertEqual(matches.map(\.id), ["m-1", "s-s", "m-3"], "Lighthouse rejected: year too far apart")
    }

    func testBearerTokenUsesHeader() async throws {
        let http = MockHTTPClient()
        let token = String(repeating: "a", count: 120)
        http.routes["https://api.themoviedb.org/3/trending/all/week?language=en-US"] = Data(#"{"results":[]}"#.utf8)
        let items = try await TMDbClient(credential: token, http: http).trending()
        XCTAssertTrue(items.isEmpty)
        let request = try XCTUnwrap(http.sent.first)
        XCTAssertEqual(request.headers["Authorization"], "Bearer \(token)")
        XCTAssertEqual(request.url.query, "language=en-US", "a token never goes into the address")
    }

    /// A page whose provider gave no backdrop that loads: TMDb's, by id first, else by title and year.
    func testABackdropIsFoundByIDOrByTitle() async throws {
        let http = MockHTTPClient()
        http.routes["https://api.themoviedb.org/3/movie/910001?language=en-US&api_key=KEY"] =
            Data(#"{"id":910001,"title":"Lighthouse","backdrop_path":"/lighthouse-wide.jpg"}"#.utf8)
        http.routes["https://api.themoviedb.org/3/search/movie?query=sandstorm&language=en-US&api_key=KEY"] =
            Data((#"{"results":[{"id":1,"title":"Sandstorm Rising","backdrop_path":"/rising.jpg","release_date":"2021-01-01"},"#
                  + #"{"id":2,"title":"Sandstorm","backdrop_path":"/old.jpg","release_date":"1988-05-01"},"#
                  + #"{"id":3,"title":"Sandstorm","backdrop_path":"/sandstorm.jpg","release_date":"2021-03-01"}]}"#).utf8)
        http.routes["https://api.themoviedb.org/3/search/tv?query=copper%20valley&language=en-US&api_key=KEY"] =
            Data(#"{"results":[{"id":7,"name":"Copper Valley","backdrop_path":null}]}"#.utf8)
        let client = TMDbClient(credential: "KEY", http: http)

        let byID = try await client.backdropURL(of: .movie, tmdbID: "910001", title: "Lighthouse", year: 2010)
        XCTAssertEqual(byID?.absoluteString, "https://image.tmdb.org/t/p/w1280/lighthouse-wide.jpg")
        let byTitle = try await client.backdropURL(of: .movie, tmdbID: nil, title: "FR - Sandstorm (2020) [4K]", year: 2020)
        XCTAssertEqual(byTitle?.absoluteString, "https://image.tmdb.org/t/p/w1280/sandstorm.jpg", "same title, a year apart at most")
        let none = try await client.backdropURL(of: .series, tmdbID: nil, title: "Copper Valley", year: nil)
        XCTAssertNil(none, "TMDb has no backdrop either")
    }

    /// Tapping a name in a movie's details: the person's credits, then what of it the catalogue has.
    func testAPersonsCreditsAreFoundInTheCatalogue() async throws {
        let http = MockHTTPClient()
        http.routes["https://api.themoviedb.org/3/search/person?query=Ada%20Vance&language=en-US&api_key=KEY"] = try Fixture.data("tmdb_person_search.json")
        http.routes["https://api.themoviedb.org/3/person/902/combined_credits?language=en-US&api_key=KEY"] = try Fixture.data("tmdb_person_credits.json")
        let client = TMDbClient(credential: "KEY", http: http)

        let found = try await client.person(named: " Ada Vance ")
        let person = try XCTUnwrap(found)
        XCTAssertEqual(person.id, 902, "the most popular person of that name")
        XCTAssertEqual(person.profileURL?.absoluteString, "https://image.tmdb.org/t/p/w185/ada-vance.jpg")

        let credits = try await client.credits(ofPerson: person.id)
        XCTAssertEqual(credits.map(\.title), ["Sandstorm: Part Two", "Lighthouse", "Copper Valley", "Paper Trail", "Unreleased Story"],
                       "cast and crew, each once, newest first, undated last")

        let url = URL(string: "http://x/1.mp4")!
        let movies = [
            Movie(id: "1", name: "FR - Sandstorm: Part Two [MULTI] 4K", categoryID: "c", streamURL: url, year: 2024),
            Movie(id: "2", name: "Paper Trail (1999)", categoryID: "c", streamURL: url),
            Movie(id: "3", name: "Another Film", categoryID: "c", streamURL: url, year: 2015),
        ]
        let series = [Series(id: "s", name: "Copper Valley (US)", categoryID: "c")]
        let matches = CatalogueMatcher.match(credits, movies: movies, series: series)
        XCTAssertEqual(matches.map(\.id), ["m-1", "s-s", "m-2"])
    }

    func testNobodyIsSearchedForAnEmptyName() async throws {
        let http = MockHTTPClient()
        let person = try await TMDbClient(credential: "KEY", http: http).person(named: "  ")
        XCTAssertNil(person)
        XCTAssertTrue(http.sent.isEmpty)
    }
}

final class PeopleTests: XCTestCase {
    func testNamesAreSplitCleanedAndKeptOnce() {
        XCTAssertEqual(People.names(in: "Ada Vance, Tom Reyes (voice), Lena Moss / Ada Vance; Jo Park..."),
                       ["Ada Vance", "Tom Reyes", "Lena Moss", "Jo Park"])
        XCTAssertEqual(People.names(in: "N/A"), [])
        XCTAssertEqual(People.names(in: nil), [])
    }

    func testAFieldNamesAPersonWhateverTheAccents() {
        XCTAssertTrue(People.field("Lena Moss, Élodie Marçeau", names: "elodie marceau"))
        XCTAssertFalse(People.field("Lena Mossberg", names: "Lena Moss"))
    }
}

final class RealDebridTests: XCTestCase {
    func testReleaseNames() {
        XCTAssertEqual(ReleaseName.clean("The.Movie.2021.1080p.WEB-DL.x264.mkv").0, "The Movie (2021)")
        XCTAssertEqual(ReleaseName.clean("Show.Name.S02E05.720p.HDTV.mkv").0, "Show Name S02E05")
        XCTAssertTrue(ReleaseName.clean("Show.Name.S02E05.720p.HDTV.mkv").1)
        XCTAssertEqual(ReleaseName.clean("Star Runner 2049 (2017) [MULTI].mp4").0, "Star Runner 2049 (2017)")
        XCTAssertEqual(ReleaseName.clean("holiday_video.mp4").0, "holiday video")
    }

    func testDownloadsBecomeMoviesAndSeries() async throws {
        let http = MockHTTPClient()
        http.routes["https://api.real-debrid.com/rest/1.0/downloads?page=1&limit=100"] = Data("""
        [{"id":"A","filename":"Sandstorm.Part.Two.2024.2160p.WEB.mkv","mimeType":"video/x-matroska","download":"https://dl.example.com/d/A/Sandstorm.mkv","streamable":1},
         {"id":"B","filename":"Show.S01E01.1080p.mkv","mimeType":"video/x-matroska","download":"https://dl.example.com/d/B/s1e1.mkv"},
         {"id":"C","filename":"Show.S01E02.1080p.mkv","download":"https://dl.example.com/d/C/s1e2.mkv"},
         {"id":"D","filename":"archive.zip","mimeType":"application/zip","download":"https://dl.example.com/d/D/a.zip"}]
        """.utf8)
        let content = try await RealDebridClient(token: "T", http: http).fetchContent()
        XCTAssertEqual(content.movies.map(\.name), ["Sandstorm Part Two"])
        XCTAssertEqual(content.movies.first?.year, 2024)
        XCTAssertEqual(content.series.first?.name, "Show")
        XCTAssertEqual(content.series.first?.episodes.map(\.code), ["S01E01", "S01E02"])
        XCTAssertTrue(content.channels.isEmpty)
    }
}
