import XCTest
@testable import IPTVCore

/// The end credits among a file's chapters. The Kotlin core's `CreditsChapterTest` has the same cases.
final class CreditsChapterTests: XCTestCase {
    func testCreditsNamesInSeveralLanguages() {
        let names = [
            "Credits", "End Credits", "CLOSING CREDITS", "End titles", "Générique", "Generique de fin", "Générique de fin",
            "Abspann", "Créditos", "Créditos finales", "Créditos finais", "Titoli di coda", "Aftiteling", "Napisy końcowe",
            "Титры", "片尾", "片尾曲", "エンディング", "엔딩 크레딧", "엔딩크레딧", "Ending", "Outro", "Chapter 12 - End Credits",
            "Mid-Credits Scene", "  credits  ",
        ]
        for name in names { XCTAssertTrue(CreditsChapter.isCredits(name), name) }
        let others = ["Chapter 12", "The Bending Bridge", "Discredits", "Final battle", "Epilogue", "Generic", ""]
        for name in others { XCTAssertFalse(CreditsChapter.isCredits(name), name) }
    }

    func testOnlyTheLastQuarterCounts() {
        let opening = MediaChapter(title: "Opening Credits", start: 0)
        XCTAssertNil(CreditsChapter.start(in: [opening], duration: 6_000), "opening credits")
        XCTAssertNil(CreditsChapter.start(in: [MediaChapter(title: "Credits", start: 4_499)], duration: 6_000), "just before the last quarter")
        XCTAssertEqual(CreditsChapter.start(in: [MediaChapter(title: "Credits", start: 4_500)], duration: 6_000), 4_500, "at three quarters")
        XCTAssertNil(CreditsChapter.start(in: [MediaChapter(title: "Credits", start: 6_000)], duration: 6_000), "at the very end")
        XCTAssertNil(CreditsChapter.start(in: [MediaChapter(title: "Credits", start: 5_000)], duration: 0), "no duration")
        let chapters = [
            opening, MediaChapter(title: "Chapter 2", start: 600), MediaChapter(title: "Post-credits scene", start: 5_700),
            MediaChapter(title: "End Credits", start: 5_400), MediaChapter(title: nil, start: 5_900),
        ]
        XCTAssertEqual(CreditsChapter.start(in: chapters, duration: 6_000), 5_400, "the earliest credits chapter")
    }

    func testNoCreditsChapter() {
        XCTAssertNil(CreditsChapter.start(in: [], duration: 6_000))
        let chapters = [MediaChapter(title: "Chapter 1", start: 0), MediaChapter(title: "Chapter 9", start: 5_400), MediaChapter(title: nil, start: 5_700)]
        XCTAssertNil(CreditsChapter.start(in: chapters, duration: 6_000))
    }
}
