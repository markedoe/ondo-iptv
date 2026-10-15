import XCTest
@testable import IPTVCore

final class CategoryStylesTests: XCTestCase {
    /// Same cases as the Kotlin core (`category_styles.json` in both test suites).
    func testStylesAndWordsOfCategoryNames() throws {
        let fixture = try JSONSerialization.jsonObject(with: Fixture.data("category_styles.json")) as? [String: Any] ?? [:]
        let styles = fixture["styles"] as? [String: [String]] ?? [:]
        XCTAssertGreaterThan(styles.count, 15)
        for (name, expected) in styles {
            XCTAssertEqual(CategoryStyles.order.filter { CategoryStyles.matches(name, rule: $0) }, expected, name)
        }
        let words = fixture["words"] as? [[String: Any]] ?? []
        XCTAssertGreaterThan(words.count, 5)
        for item in words {
            let name = try XCTUnwrap(item["name"] as? String)
            let word = try XCTUnwrap(item["word"] as? String)
            let rule = try XCTUnwrap(CategoryStyles.rule(forWord: word))
            XCTAssertEqual(CategoryStyles.matches(name, rule: rule), item["matches"] as? Bool, "\(name) / \(word)")
        }
        XCTAssertNil(CategoryStyles.rule(forWord: " - "))
    }

    func testStylesFoundInAPlaylist() {
        let categories = [
            MediaCategory(id: "1", name: "|FR| Manga", type: .series),
            MediaCategory(id: "2", name: "Anime VOSTFR", type: .movie),
            MediaCategory(id: "3", name: "Documentaires", type: .movie),
            MediaCategory(id: "4", name: "Drama", type: .movie),
        ]
        let found = CategoryStyles.detect(in: categories)
        XCTAssertEqual(Set(found.keys), ["manga", "documentary"])
        XCTAssertEqual(found["manga"]?.map(\.id), ["1", "2"])
        XCTAssertEqual(CategoryStyles.rule(hiding: "|FR| Manga", among: ["sport", "manga"]), "manga")
        XCTAssertNil(CategoryStyles.rule(hiding: "Drama", among: ["sport", "manga"]))
    }

    func testAHiddenWordSyncsBothWays() {
        let t0 = Date(timeIntervalSince1970: 1_000)
        var phone = UserLibrary()
        phone.setHidden(true, word: "manga", now: t0)
        var mac = phone
        mac.setHidden(false, word: "manga", now: t0.addingTimeInterval(60))
        phone = phone.merged(with: mac)
        XCTAssertFalse(phone.isHidden(word: "manga"))
        phone.setHidden(true, word: "manga", now: t0.addingTimeInterval(120))
        XCTAssertTrue(mac.merged(with: phone).isHidden(word: "manga"))
        let decoded = try? JSONDecoder().decode(UserLibrary.self, from: JSONEncoder().encode(phone))
        XCTAssertEqual(decoded?.isHidden(word: "manga"), true)
    }
}
