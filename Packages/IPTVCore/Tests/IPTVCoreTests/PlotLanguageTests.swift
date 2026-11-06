import XCTest
@testable import IPTVCore

/// The language of a provider's description and the tags at the end of a title's name, for hidden languages
/// (`fixtures/plot-language`, the same files and cases as the Kotlin `PlotLanguageTest`).
final class PlotLanguageTests: XCTestCase {
    private struct Cases: Decodable {
        struct Plot: Decodable {
            var language: String?
            var text: String
        }
        struct Trailing: Decodable {
            var name: String
            var tag: String?
        }
        struct Languages: Decodable {
            var hidden: [String]
            var languages: [String]
        }
        var plots: [Plot]
        var trailing: [Trailing]
        var languages: [Languages]
    }

    private func fixture(_ name: String) throws -> Data {
        let file = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/plot-language/" + name)
        return try Data(contentsOf: file)
    }

    private func cases() throws -> Cases { try JSONDecoder().decode(Cases.self, from: fixture("cases.json")) }

    func testTheTableIsTheSharedOne() throws {
        let shared = String(decoding: try fixture("table.txt"), as: UTF8.self)
        XCTAssertEqual(PlotLanguage.table.trimmingCharacters(in: .whitespacesAndNewlines), shared.trimmingCharacters(in: .whitespacesAndNewlines))
        // Short lines (the linters' limits): a language with many words goes on over several lines.
        XCTAssertTrue(PlotLanguage.table.split(separator: "\n").allSatisfy { $0.utf16.count <= 150 })
    }

    /// Each word of the table is one the scan reads (folded, short enough), and belongs to one language only.
    func testEveryWordOfTheTableIsReadByTheScan() {
        var owners: [UInt64: String] = [:]
        for line in PlotLanguage.table.split(separator: "\n") {
            let parts = line.split(separator: "|").map(String.init)
            guard parts.count > 2, parts[1] == "words" else { continue }
            for word in parts.dropFirst(2) {
                let key = PlotLanguage.key(word)
                XCTAssertNotNil(key, "the scan cannot read \(word)")
                XCTAssertEqual(word, word.lowercased(), "\(word) is lower case")
                if let key {
                    XCTAssertNil(owners[key], "\(word) belongs to two languages")
                    owners[key] = parts[0]
                }
            }
        }
        XCTAssertEqual(PlotLanguage.languages, ["es", "pt", "fr", "en", "it", "de", "nl", "tr", "pl", "ar", "hi", "zh"])
    }

    func testTheLanguageOfEachDescriptionOfTheCases() throws {
        let plots = try cases().plots
        XCTAssertGreaterThan(plots.count, 20)
        for plot in plots {
            XCTAssertEqual(PlotLanguage.detect(plot.text), plot.language, plot.text)
        }
    }

    /// Spanish and Portuguese share many words: each is told by its own ones, in either case.
    func testSpanishAndPortugueseAreToldApart() {
        let spanish = "Cuando su hermano desaparece, Marta deja la ciudad y vuelve al pueblo donde nadie quiere hablar de lo que pasó hace años."
        let portuguese = "Quando o irmão desaparece, Marta deixa a cidade e volta para a vila onde ninguém quer falar sobre o que aconteceu há anos. Mas ela não desiste."
        XCTAssertEqual(PlotLanguage.detect(spanish), "es")
        XCTAssertEqual(PlotLanguage.detect(portuguese), "pt")
        XCTAssertEqual(PlotLanguage.detect(spanish.uppercased()), "es")
    }

    func testAShortOrUnclearDescriptionHasNoLanguage() {
        XCTAssertNil(PlotLanguage.detect(nil))
        XCTAssertNil(PlotLanguage.detect(""))
        XCTAssertNil(PlotLanguage.detect("El amor y su sombra."), "too short")
        // As many words of two languages: neither.
        XCTAssertNil(PlotLanguage.detect("El hombre and the mujer, los amigos with the casa del pueblo, ella and his hijo."), "mixed")
    }

    /// Only the start of a description is read: what follows the first `PlotLanguage.scanned` scalars counts for nothing.
    func testOnlyTheStartOfADescriptionIsRead() {
        let filler = String(repeating: "x", count: PlotLanguage.scanned)
        let spanish = "Un joven abogado descubre que su padre esconde un secreto cuando regresa a su pueblo."
        XCTAssertNil(PlotLanguage.detect(filler + " " + spanish))
        XCTAssertEqual(PlotLanguage.detect(spanish + " " + filler), "es")
    }

    func testTheLanguagesOfHiddenTags() throws {
        for item in try cases().languages {
            XCTAssertEqual(PlotLanguage.hiddenLanguages(Set(item.hidden)), Set(item.languages), "\(item.hidden)")
        }
        XCTAssertTrue(PlotLanguage.isLanguageTag("LAT"))
        XCTAssertFalse(PlotLanguage.isLanguageTag("VOSTFR"), "French subtitles are no French audio")
        XCTAssertFalse(PlotLanguage.isLanguageTag("HBO"))
    }

    /// Same cases as the Kotlin core: a tag at the very end of a title's name, years and quality words after it left out.
    func testTheTagAtTheEndOfATitlesName() throws {
        let trailing = try cases().trailing
        XCTAssertGreaterThan(trailing.count, 20)
        for item in trailing {
            XCTAssertEqual(CategoryTags.trailingTag(of: item.name), item.tag, item.name)
        }
        XCTAssertTrue(CategoryTags.isHidden(name: "La Casa (ES)", hiddenTags: ["ES"]))
        XCTAssertTrue(CategoryTags.isHidden(name: "|ES| La Casa", hiddenTags: ["ES"]), "its start still counts")
        XCTAssertFalse(CategoryTags.isHidden(name: "The Office (UK)", hiddenTags: ["ES"]), "only the hidden tag")
        XCTAssertFalse(CategoryTags.isHidden(name: "La Casa (ES)", hiddenTags: []), "nothing hidden")
    }

    /// Regression guard for a hidden language's titles filed in categories of no language: a series of "Netflix"
    /// described in Spanish, or named "… (ES)", is hidden with Spanish; a category tagged with a language the viewer
    /// kept keeps its titles whatever their description; nothing is hidden while no hidden tag stands for a language.
    func testATitleIsHiddenByItsNameOrByItsDescription() {
        let spanish = "Un joven abogado descubre que su padre esconde un secreto que pondrá en peligro a toda la familia cuando regresa a su pueblo."
        let english = "A young lawyer discovers that his father is hiding a secret that will put the whole family in danger when he returns home."
        let spanishTags: Set<String> = ["ES"]
        // Described in Spanish, in a category of no tag ("Netflix") or of a tag of no language ("HBO | Series").
        XCTAssertTrue(CategoryTags.isHiddenTitle(name: "La Casa", plot: spanish, categoryTag: nil, hiddenTags: spanishTags))
        XCTAssertTrue(CategoryTags.isHiddenTitle(name: "La Casa", plot: spanish, categoryTag: CategoryTags.tag(of: "HBO | Series"), hiddenTags: spanishTags))
        // Another tag of the language hidden ("LAT"): the same language.
        XCTAssertTrue(CategoryTags.isHiddenTitle(name: "La Casa", plot: spanish, categoryTag: nil, hiddenTags: ["LAT"]))
        // Named with it at the end.
        XCTAssertTrue(CategoryTags.isHiddenTitle(name: "La Casa (ES)", plot: english, categoryTag: nil, hiddenTags: spanishTags))
        // A category of a language the viewer kept wins.
        XCTAssertFalse(CategoryTags.isHiddenTitle(name: "La Casa", plot: spanish, categoryTag: CategoryTags.tag(of: "|FR| Séries"), hiddenTags: spanishTags))
        // Nothing hidden, or no tag of a language: nothing new disappears.
        XCTAssertFalse(CategoryTags.isHiddenTitle(name: "La Casa", plot: spanish, categoryTag: nil, hiddenTags: []))
        XCTAssertFalse(CategoryTags.isHiddenTitle(name: "La Casa", plot: spanish, categoryTag: nil, hiddenTags: ["VIP"]))
        XCTAssertFalse(CategoryTags.isHiddenTitle(name: "La Casa", plot: english, categoryTag: nil, hiddenTags: spanishTags))
        XCTAssertFalse(CategoryTags.isHiddenTitle(name: "La Casa", plot: nil, categoryTag: nil, hiddenTags: spanishTags), "no description")
    }

    /// `TitleTags` (built once per catalogue, off the main actor) hides what `CategoryTags.isHiddenTitle` hides, title by
    /// title, so that every list leaves the same ones out.
    func testTheCatalogueIndexHidesWhatTheRuleHides() {
        let spanish = "Un joven abogado descubre que su padre esconde un secreto que pondrá en peligro a toda la familia cuando regresa a su pueblo."
        let english = "A young lawyer discovers that his father is hiding a secret that will put the whole family in danger when he returns home."
        let categories = [
            MediaCategory(id: "netflix", name: "Netflix", type: .series),
            MediaCategory(id: "fr", name: "|FR| Séries", type: .series),
            MediaCategory(id: "hbo", name: "HBO | Series", type: .series),
        ]
        var series: [Series] = []
        let titles: [(name: String, plot: String?)] = [("La Casa", spanish), ("The House", english), ("La Casa (ES)", english), ("|ES| El Puente", nil)]
        for (index, title) in titles.enumerated() {
            for category in categories {
                series.append(Series(id: "\(index)-\(category.id)", name: title.name, categoryID: category.id, plot: title.plot))
            }
        }
        let content = PlaylistContent(seriesCategories: categories, series: series)
        let tags = TitleTags(content: content)
        let hiddenSets: [Set<String>] = [["ES"], ["LAT"], ["FR"], ["VIP"], []]
        for hidden in hiddenSets {
            let expected = Set(series.filter { show in
                let tag: String? = CategoryTags.tag(of: categories.first { $0.id == show.categoryID }?.name ?? "")
                return CategoryTags.isHiddenTitle(name: show.name, plot: show.plot, categoryTag: tag, hiddenTags: hidden)
            }.map(\.id))
            XCTAssertEqual(tags.hidden(by: hidden).seriesIDs, expected, "\(hidden)")
        }
        XCTAssertEqual(tags.hidden(by: ["ES"]).seriesIDs.count, 2 + 3 + 3, "described in Spanish (not under |FR|), named with ES")
    }
}
