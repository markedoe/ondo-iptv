import XCTest
@testable import IPTVCore

final class CategoryTagsTests: XCTestCase {
    /// Same cases as the Kotlin core (`category_tags.json` in both test suites).
    func testTagsOfCategoryNames() throws {
        let cases = try JSONSerialization.jsonObject(with: Fixture.data("category_tags.json")) as? [String: Any] ?? [:]
        XCTAssertGreaterThan(cases.count, 20)
        for (name, expected) in cases {
            XCTAssertEqual(CategoryTags.tag(of: name), expected as? String, name)
        }
    }

    func testATagNeedsTwoCategories() {
        let categories = [
            MediaCategory(id: "1", name: "|PT| Filmes", type: .movie),
            MediaCategory(id: "2", name: "|PT| Séries", type: .series),
            MediaCategory(id: "3", name: "FR: Films", type: .movie),
            MediaCategory(id: "4", name: "Drama", type: .movie),
        ]
        let found = CategoryTags.detect(in: categories)
        XCTAssertEqual(Set(found.keys), ["PT"])
        XCTAssertEqual(found["PT"]?.map(\.id), ["1", "2"])
    }

    func testAHiddenTagSyncsBothWays() {
        let t0 = Date(timeIntervalSince1970: 1_000)
        var phone = UserLibrary()
        phone.setHidden(true, tag: "PT", now: t0)
        var mac = phone
        XCTAssertTrue(mac.isHidden(tag: "PT"))

        // Shown again later on the Mac: the phone follows after a merge, and the Mac stays as it is.
        mac.setHidden(false, tag: "PT", now: t0.addingTimeInterval(60))
        phone = phone.merged(with: mac)
        XCTAssertFalse(phone.isHidden(tag: "PT"))
        XCTAssertFalse(mac.merged(with: phone).isHidden(tag: "PT"))

        // Hidden once more on the phone: the newest decision wins.
        phone.setHidden(true, tag: "PT", now: t0.addingTimeInterval(120))
        XCTAssertTrue(mac.merged(with: phone).isHidden(tag: "PT"))
    }

    func testOlderLibrariesHaveNoHiddenTags() throws {
        let library = try JSONDecoder().decode(UserLibrary.self, from: Data(#"{"favorites": []}"#.utf8))
        XCTAssertTrue(library.hiddenCategoryTags.isEmpty)
    }
}
