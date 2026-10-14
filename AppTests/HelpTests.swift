import XCTest
@testable import Ondo

/// Every help page exists in each language of the app (they are long texts: a missing one would show in English).
final class HelpTests: XCTestCase {
    func testEveryHelpPageIsTranslated() throws {
        for language in ["fr", "es", "pt", "zh-Hans", "ar", "hi"] {
            let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"), language)
            let bundle = try XCTUnwrap(Bundle(path: path))
            for topic in HelpTopic.all {
                for key in [topic.title.key, topic.body.key] {
                    // A missing key returns `value`; a translation may equal the English text ("Downloads" in pt).
                    let missing = "⟨missing⟩"
                    XCTAssertNotEqual(bundle.localizedString(forKey: key, value: missing, table: nil), missing, "\(topic.id) in \(language)")
                }
            }
        }
    }

    func testTopicsAreUnique() {
        XCTAssertEqual(Set(HelpTopic.all.map(\.id)).count, HelpTopic.all.count)
    }
}
