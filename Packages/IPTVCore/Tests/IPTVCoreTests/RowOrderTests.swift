import XCTest
@testable import IPTVCore

/// The order of the rows of the Movies and Series tabs (the same cases as the Kotlin `RowOrderTest`).
final class RowOrderTests: XCTestCase {
    private let action = RowOrder.categoryKey(id: "movie-1", name: "Action")
    private let drama = RowOrder.categoryKey(id: "movie-2", name: "  Drama   FR ")
    private let kids = RowOrder.categoryKey(id: "movie-3", name: "Kids")

    private func keys(_ rows: [RowOrder.Row]) -> [String] { rows.map(\.key) }

    func testACategoryKeyHoldsItsIDAndItsNameFolded() {
        XCTAssertEqual(action, "cat:movie-1|action")
        XCTAssertEqual(drama, "cat:movie-2|drama fr")
        // Only A to Z fold; control characters go, and a bar in the ID cannot cut the key.
        XCTAssertEqual(RowOrder.categoryKey(id: "a|\nb", name: "Élan\tÉTÉ"), "cat:ab|Élan ÉtÉ")
    }

    func testWithoutASavedOrderTheRowsKeepTheirDefaultOrder() {
        let current = RowOrder.builtIns(series: false) + [action, drama]
        XCTAssertEqual(keys(RowOrder.arrange(current: current, order: [], hidden: [])), current)
        // Series have their award winners too, under the same key as movies.
        XCTAssertEqual(RowOrder.builtIns(series: true), ["all", "continue", "mylist", "new", "trending", "awards"])
    }

    func testTheSavedOrderAppliesAndNewCategoriesGoToTheEnd() {
        let current = ["all", "continue", action, drama, kids]
        let rows = RowOrder.arrange(current: current, order: [drama, "all", action], hidden: ["continue"])
        XCTAssertEqual(keys(rows), [drama, "all", action, "continue", kids])
        XCTAssertEqual(rows.filter(\.hidden).map(\.key), ["continue"])
    }

    func testARemovedCategoryIsRememberedAndComesBackAtItsPlace() {
        let saved = [drama, kids, "all", action]
        // Kids is gone from the playlist: not shown, but kept after Drama when the viewer moves the others.
        let without = ["all", action, drama]
        XCTAssertEqual(keys(RowOrder.arrange(current: without, order: saved, hidden: [kids])), [drama, "all", action])
        let next = RowOrder.saving(arranged: ["all", drama, action], previous: saved)
        XCTAssertEqual(next, ["all", drama, kids, action])
        // Hidden while gone, and hidden again when it comes back.
        let hidden = RowOrder.savingHidden(current: without, hiddenNow: [], previous: [kids])
        XCTAssertEqual(hidden, [kids])
        let back = RowOrder.arrange(current: without + [kids], order: next, hidden: hidden)
        XCTAssertEqual(keys(back), ["all", drama, kids, action])
        XCTAssertEqual(back.filter(\.hidden).map(\.key), [kids])
    }

    func testACategoryRenumberedOrRenamedIsFoundByItsNameOrItsID() {
        let saved = [drama, action]
        // The provider numbered Drama anew: found by its name.
        let renumbered = RowOrder.categoryKey(id: "movie-20", name: "Drama FR")
        // Action renamed: found by its ID.
        let renamed = RowOrder.categoryKey(id: "movie-1", name: "Action & Adventure")
        let rows = RowOrder.arrange(current: ["all", renamed, renumbered], order: saved, hidden: [action])
        XCTAssertEqual(keys(rows), [renumbered, renamed, "all"])
        XCTAssertEqual(rows.filter(\.hidden).map(\.key), [renamed])
        // Saved again, they take their new keys.
        XCTAssertEqual(RowOrder.saving(arranged: keys(rows), previous: saved), [renumbered, renamed, "all"])
    }

    func testEachRowOnceWhateverTheSavedKeys() {
        let rows = RowOrder.arrange(current: ["all", action], order: [action, action, "all", "all", "cat:movie-1|other"], hidden: [])
        XCTAssertEqual(keys(rows), [action, "all"])
        XCTAssertEqual(RowOrder.saving(arranged: ["all", action], previous: ["", "all", action]), ["all", action])
    }
}
