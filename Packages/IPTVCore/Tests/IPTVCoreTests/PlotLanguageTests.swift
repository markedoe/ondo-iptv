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
            var catalogue: [String]
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

    /// Regression guard: a language hides its descriptions only when none of its tags in the catalogue is kept (a viewer
    /// who keeps PT and hides BR, as "Show only your languages?" can leave it, keeps the descriptions in Portuguese), and
    /// English never (providers describe in English whatever the audio).
    func testTheLanguagesOfHiddenTags() throws {
        for item in try cases().languages {
            let found: Set<String> = PlotLanguage.hiddenLanguages(Set(item.hidden), catalogueTags: Set(item.catalogue))
            XCTAssertEqual(found, Set(item.languages), "\(item.hidden)")
        }
        XCTAssertEqual(PlotLanguage.neverHidden, ["en"])
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
        func hidden(_ name: String, _ plot: String?, _ categoryTag: String?, _ tags: Set<String>, catalogue: Set<String> = ["FR"]) -> Bool {
            let languages: Set<String> = PlotLanguage.hiddenLanguages(tags, catalogueTags: catalogue)
            return CategoryTags.isHiddenTitle(name: name, plot: plot, categoryTag: categoryTag, hiddenTags: tags, hiddenLanguages: languages)
        }
        // Described in Spanish, in a category of no tag ("Netflix") or of a tag of no language ("HBO | Series").
        XCTAssertTrue(hidden("La Casa", spanish, nil, spanishTags))
        XCTAssertTrue(hidden("La Casa", spanish, CategoryTags.tag(of: "HBO | Series"), spanishTags))
        // Another tag of the language hidden ("LAT"): the same language.
        XCTAssertTrue(hidden("La Casa", spanish, nil, ["LAT"]))
        // Named with it at the end.
        XCTAssertTrue(hidden("La Casa (ES)", english, nil, spanishTags))
        // A category of a language the viewer kept wins.
        XCTAssertFalse(hidden("La Casa", spanish, CategoryTags.tag(of: "|FR| Séries"), spanishTags))
        // Nothing hidden, or no tag of a language: nothing new disappears.
        XCTAssertFalse(hidden("La Casa", spanish, nil, []))
        XCTAssertFalse(hidden("La Casa", spanish, nil, ["VIP"]))
        XCTAssertFalse(hidden("La Casa", english, nil, spanishTags))
        XCTAssertFalse(hidden("La Casa", nil, nil, spanishTags), "no description")
        // Another tag of the language kept in the catalogue (LAT hidden, ES kept): its descriptions stay.
        XCTAssertFalse(hidden("La Casa", spanish, nil, ["LAT"], catalogue: ["ES", "LAT"]))
        XCTAssertTrue(hidden("La Casa [LAT]", spanish, nil, ["LAT"], catalogue: ["ES", "LAT"]), "its name still counts")
        // English is never hidden by description.
        XCTAssertFalse(hidden("The House", english, nil, ["EN", "UK", "US"]))
        XCTAssertTrue(hidden("The House (UK)", english, nil, ["UK"]), "its name still counts")
    }

    /// `PlotLanguageIndex` (read once per catalogue, off the main actor) with `TitleTags` hides what
    /// `CategoryTags.isHiddenTitle` hides, title by title, so that every list leaves the same ones out.
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
        let plots = PlotLanguageIndex(content: content)
        XCTAssertNil(plots.series["en"], "English is not kept")
        let hiddenSets: [Set<String>] = [["ES"], ["LAT"], ["EN"], ["FR"], ["VIP"], []]
        for hidden in hiddenSets {
            let languages: Set<String> = PlotLanguage.hiddenLanguages(hidden, catalogueTags: [])
            let expected = Set(series.filter { show in
                let tag: String? = CategoryTags.tag(of: categories.first { $0.id == show.categoryID }?.name ?? "")
                return CategoryTags.isHiddenTitle(name: show.name, plot: show.plot, categoryTag: tag, hiddenTags: hidden, hiddenLanguages: languages)
            }.map(\.id))
            let found: Set<String> = tags.hidden(by: hidden).seriesIDs.union(plots.hidden(languages).seriesIDs)
            XCTAssertEqual(found, expected, "\(hidden)")
        }
        let spanishLanguages: Set<String> = PlotLanguage.hiddenLanguages(["ES"], catalogueTags: [])
        let spanishFound: Set<String> = tags.hidden(by: ["ES"]).seriesIDs.union(plots.hidden(spanishLanguages).seriesIDs)
        XCTAssertEqual(spanishFound.count, 2 + 3 + 3, "described in Spanish (not under |FR|), named with ES")
    }
}
