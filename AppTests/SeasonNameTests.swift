import XCTest
import IPTVCore
@testable import Ondo

/// An Xtream season the provider does not name is called "Season 2" by IPTVCore, in English: its button reads in
/// the app's language, like the seasons of an M3U playlist. A name of the provider's own stays as it is.
final class SeasonNameTests: XCTestCase {
    func testASeasonNamedByItsNumberReadsInEachLanguage() throws {
        let expected = ["fr": "Saison 2", "es": "Temporada 2", "pt": "Temporada 2", "zh-Hans": "第 2 季", "ar": "الموسم 2", "hi": "सीज़न 2"]
        for (language, name) in expected {
            let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"), language)
            let bundle = try XCTUnwrap(Bundle(path: path))
            XCTAssertEqual(Season(number: 2, name: "Season 2", episodes: []).displayName(bundle: bundle), name, language)
            XCTAssertEqual(Season(number: 0, name: "Specials", episodes: []).displayName(bundle: bundle), "Specials", language)
        }
    }
}
