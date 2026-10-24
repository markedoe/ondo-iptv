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

        let absurd = [Int.min, Int.max].map { (year: Int) in Movie(id: String(year), name: "Lighthouse", categoryID: "c", streamURL: url, year: year) }
        XCTAssertEqual(CatalogueMatcher.match(items, movies: absurd, series: []).map(\.id), [])
        XCTAssertTrue(CatalogueMatcher.closeYears(2008, 2009))
        XCTAssertFalse(CatalogueMatcher.closeYears(Int.min, 2008))
        XCTAssertFalse(CatalogueMatcher.closeYears(Int.max, Int.min))
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

    /// A key is checked with TMDb before it is saved: accepted, refused, or TMDb out of reach.
    func testAKeyIsCheckedBeforeItIsSaved() async throws {
        let http = MockHTTPClient()
        http.routes["https://api.themoviedb.org/3/authentication?language=en-US&api_key=GOOD"] =
            Data(#"{"success":true,"status_code":1,"status_message":"Success."}"#.utf8)
        let accepted = try await TMDbClient(credential: "GOOD", http: http).isAccepted()
        XCTAssertTrue(accepted)
        // TMDb answers 401 to a key it does not know.
        http.failures["https://api.themoviedb.org/3/authentication?language=en-US&api_key=BAD"] = [HTTPClientError.badStatus(401)]
        let refused = try await TMDbClient(credential: "BAD", http: http).isAccepted()
        XCTAssertFalse(refused)
        http.failures["https://api.themoviedb.org/3/authentication?language=en-US&api_key=OFFLINE"] = [URLError(.notConnectedToInternet)]
        do {
            _ = try await TMDbClient(credential: "OFFLINE", http: http).isAccepted()
            XCTFail("an unreachable TMDb is not a refusal")
        } catch {
            XCTAssertEqual((error as? URLError)?.code, .notConnectedToInternet)
        }
    }

    /// Only TMDb's own answer accepts a key: a page that is not it (a proxy's, a network's sign-in page) checks nothing,
    /// and the key is not saved as accepted.
    func testAnAnswerThatIsNotTMDbsAcceptsNoKey() async throws {
        let http = MockHTTPClient()
        for (key, body) in [("PAGE", "<html><body>Sign in to the network</body></html>"), ("EMPTY", "{}")] {
            http.routes["https://api.themoviedb.org/3/authentication?language=en-US&api_key=\(key)"] = Data(body.utf8)
            do {
                let accepted = try await TMDbClient(credential: key, http: http).isAccepted()
                XCTFail("\(body) is not TMDb's answer, yet the key was \(accepted ? "accepted" : "refused")")
            } catch {}
        }
        // TMDb's own answer refusing a key without a 401 still refuses it.
        http.routes["https://api.themoviedb.org/3/authentication?language=en-US&api_key=NO"] =
            Data(#"{"success":false,"status_code":7,"status_message":"Invalid API key."}"#.utf8)
        let refused = try await TMDbClient(credential: "NO", http: http).isAccepted()
        XCTAssertFalse(refused)
    }

    /// Search finds actors and directors by any word of their name, the ones named most often first.
    func testPeopleAreFoundByName() {
        let index = PeopleIndex(fields: ["Ada Vance, Tom Reyes", "Ada Vance (voice)", "Lena Moss", nil, "Éva Adams, Tomás Ruiz"])
        XCTAssertEqual(index.names(matching: "ada"), ["Ada Vance", "Éva Adams"], "names starting with the query first")
        XCTAssertEqual(index.names(matching: "TOMAS"), ["Tomás Ruiz"], "accents and case ignored")
        XCTAssertEqual(index.names(matching: "vance ad"), ["Ada Vance"], "every word, in any order")
        XCTAssertEqual(index.names(matching: "to"), [], "three letters at least")
        XCTAssertEqual(index.names(matching: "eva"), ["Éva Adams"])
    }

    /// TMDb's people for a search, the most popular first.
    func testPeopleAreSearchedOnTMDb() async throws {
        let http = MockHTTPClient()
        http.routes["https://api.themoviedb.org/3/search/person?query=ada&language=en-US&api_key=KEY"] =
            Data(#"{"results":[{"id":1,"name":"Ada Lowe","popularity":2.5},{"id":2,"name":"Ada Vance","popularity":9.1}]}"#.utf8)
        let people = try await TMDbClient(credential: "KEY", http: http).people(matching: " ada ")
        XCTAssertEqual(people.map(\.name), ["Ada Vance", "Ada Lowe"])
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
