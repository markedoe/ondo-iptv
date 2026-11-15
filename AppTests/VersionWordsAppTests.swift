import XCTest
import IPTVCore
@testable import Ondo

/// A film version's chip in each of the app's seven languages: the abbreviations spelled out in the viewer's words, and
/// two versions never read the same (a word of one tag never another's). Android's `VersionWordsAppTest` does the same.
final class VersionWordsAppTests: XCTestCase {
    private let languages = ["en", "fr", "es", "pt", "zh-Hans", "ar", "hi"]

    private func words(_ language: String) throws -> VersionWords {
        if language == "en" { return VersionWords() }
        let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"), language)
        return VersionWords.app(bundle: try XCTUnwrap(Bundle(path: path)))
    }

    private func labels(_ names: [String], _ language: String) throws -> [String] {
        let movies = names.enumerated().map { index, name in
            Movie(id: "v\(index)", name: name, categoryID: "c", streamURL: URL(string: "http://stream.example.com/\(index)")!)
        }
        return FilmVersions.labels(movies, categoryName: { _ in nil }, words: try words(language))
    }

    func testEveryTagReadsDifferentlyInEveryLanguage() throws {
        for language in languages {
            let words = try words(language)
            let all = VersionTag.allCases.map(words.word) + [words.standard, words.numbered(1), words.numbered(2)]
            XCTAssertEqual(Set(all).count, all.count, "\(language): \(all)")
            XCTAssertFalse(all.contains { $0.isEmpty }, language)
            if language != "en" { XCTAssertNotEqual(words.frenchSubtitles, "Original, French subtitles", "\(language) is translated") }
        }
    }

    func testTheReportedFilmReadsInEachLanguage() throws {
        let expected = [
            "en": ["Standard", "4K Dolby Vision", "Original, French subtitles"],
            "fr": ["Standard", "4K Dolby Vision", "VO, sous-titres français"],
            "es": ["Estándar", "4K Dolby Vision", "VO, subtítulos en francés"],
            "pt": ["Padrão", "4K Dolby Vision", "Original, legendas em francês"],
            "zh-Hans": ["标准", "4K Dolby Vision", "原声，法语字幕"],
            "ar": ["قياسية", "4K Dolby Vision", "الأصلية، ترجمة فرنسية"],
            "hi": ["स्टैंडर्ड", "4K Dolby Vision", "मूल, फ़्रेंच सबटाइटल"],
        ]
        for (language, result) in expected {
            XCTAssertEqual(try labels(["Copper Valley (2026)", "Copper Valley 4K DV (2026)", "Copper Valley VOSTFR (2026)"], language), result, language)
        }
        XCTAssertEqual(try labels(["Copper Valley", "Copper Valley"], "fr"), ["Version 1", "Version 2"])
        XCTAssertEqual(try labels(["Copper Valley", "Copper Valley"], "es"), ["Versión 1", "Versión 2"])
    }

    /// Whatever a film's versions are, two never read the same, in any language.
    func testTwoVersionsNeverReadTheSameInAnyLanguage() throws {
        let pool = [
            "Copper Valley", "Copper Valley 4K", "Copper Valley 4K", "Copper Valley 4K DV", "Copper Valley 1080p", "Copper Valley SD",
            "Copper Valley VOSTFR", "Copper Valley VOST", "Copper Valley VO", "FR - Copper Valley", "EN - Copper Valley",
            "Copper Valley MULTI VFF", "Copper Valley VFQ", "Copper Valley VFI", "Copper Valley Standard", "Copper Valley 3D",
        ]
        for language in languages {
            for a in pool {
                for b in pool {
                    for c in pool {
                        let result = try labels([a, b, c], language)
                        XCTAssertEqual(Set(result).count, result.count, "\(language): \(a), \(b), \(c) -> \(result)")
                    }
                }
            }
        }
    }
}
