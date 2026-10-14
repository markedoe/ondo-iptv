import XCTest
@testable import IPTVCore

/// Same cases as the Kotlin core (`awards_sample.json` in both test suites).
final class AwardsTests: XCTestCase {
    private func catalogue() throws -> AwardsCatalogue {
        try AwardsCatalogue(data: Fixture.data("awards_sample.json"))
    }

    private func movie(_ id: String, _ name: String, year: Int?) -> Movie {
        Movie(id: id, name: name, categoryID: "m", streamURL: URL(string: "http://example.com/movie/\(id).mp4")!, year: year)
    }

    func testMoviesAreRecognisedByTitleAndYearInAnyLanguage() throws {
        let catalogue = try catalogue()
        let movies = [
            movie("1", "FR - Pulp Fiction [MULTI] (1994)", year: 1994),
            movie("2", "Anatomie d’une chute", year: 2024),
            movie("3", "Parasite", year: 2019),
            movie("4", "Parasite", year: 1982),
            movie("5", "Unknown Film", year: 2020),
        ]
        let found = catalogue.match(movies)
        XCTAssertEqual(found["1"]?.first, AwardsCatalogue.Entry(awardID: "palme-dor", year: 1994, won: true), "wins first")
        XCTAssertEqual(found["1"]?.count, 2)
        XCTAssertEqual(found["2"]?.first?.awardID, "cesar-best-film", "the French title, one year apart")
        XCTAssertEqual(found["3"]?.first, AwardsCatalogue.Entry(awardID: "oscar-best-picture", year: 2020, won: true))
        XCTAssertEqual(found["4"], [AwardsCatalogue.Entry(awardID: "palme-dor", year: 1982, won: false)], "the 1982 film, not the 2019 one")
        XCTAssertNil(found["5"])
    }

    func testATitleWithoutAYearMustBeUnique() throws {
        let catalogue = try catalogue()
        let found = catalogue.match([movie("1", "Parasite", year: nil), movie("2", "The Third Man", year: nil)])
        XCTAssertNil(found["1"], "two awarded films are called Parasite")
        XCTAssertEqual(found["2"]?.first?.awardID, "palme-dor")
    }

    func testATMDbIdWinsOverTheTitle() throws {
        let found = try catalogue().match([movie("1", "Some Other Name", year: nil)], tmdbIDs: ["1": 680])
        XCTAssertEqual(found["1"]?.first?.awardID, "palme-dor")
    }

    func testAwardNamesFallBackToEnglish() throws {
        let catalogue = try catalogue()
        XCTAssertEqual(catalogue.award("palme-dor")?.name(language: "fr"), "Palme d'or")
        XCTAssertEqual(catalogue.award("cesar-best-film")?.name(language: "fr"), "César Award for Best Film")
        XCTAssertEqual(catalogue.groups, ["cannes", "oscars", "cesars"])
    }
}
