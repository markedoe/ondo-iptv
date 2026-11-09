import XCTest
@testable import Ondo

/// My List is a bookmark, Watched a check mark, never two checks: every My List button, menu item and mark draws
/// `MyListSymbol` (an outlined bookmark, filled once in My List), and the watched badge keeps its check mark. Android has
/// the same test (`MyListBookmarkTest`). A new My List button is added to `surfaces` here.
final class MyListBookmarkTests: XCTestCase {
    func testMyListIsABookmarkFilledOnceInTheList() {
        XCTAssertEqual(MyListSymbol.name(inList: false), "bookmark")
        XCTAssertEqual(MyListSymbol.name(inList: true), "bookmark.fill")
        for inList in [false, true] {
            XCTAssertFalse(MyListSymbol.name(inList: inList).contains("checkmark"))
        }
    }

    /// Each source file with a My List button, menu item or mark, and how many times it draws `MyListSymbol`.
    private static let surfaces: [String: Int] = [
        "App/Features/Common/Components.swift": 1,     // a channel's row: in My List
        "App/Features/Common/DetailScaffold.swift": 1, // a movie's or a series' page
        "App/Features/Common/MediaShelves.swift": 2,   // "Add to My List" / "Remove from My List" menus
        "App/Features/Home/HeroCarousel.swift": 1,     // Home's featured titles
        "App/Player/PlayerView.swift": 1,              // a channel in the player
    ]

    private func appDirectory() throws -> URL {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while !FileManager.default.fileExists(atPath: directory.appendingPathComponent("App/Features").path) {
            let parent = directory.deletingLastPathComponent()
            guard parent.path != directory.path else { throw XCTSkip("App/Features not found") }
            directory = parent
        }
        return directory
    }

    func testEveryMyListSurfaceDrawsTheBookmarkAndTheWatchedBadgeItsCheck() throws {
        let root = try appDirectory()
        let app = root.appendingPathComponent("App")
        let files = try XCTUnwrap(FileManager.default.enumerator(at: app, includingPropertiesForKeys: nil))
            .compactMap { $0 as? URL }.filter { $0.pathExtension == "swift" }
        var found: [String: Int] = [:]
        for file in files {
            let source = try String(contentsOf: file, encoding: .utf8)
            let path = "App/" + file.path.components(separatedBy: "/App/").last!
            let uses = source.components(separatedBy: "MyListSymbol.name(").count - 1
            if uses > 0 { found[path] = uses }
            // A My List state next to a check mark: the old symbol, Watched's.
            for line in source.components(separatedBy: .newlines) where line.contains("My List") || line.contains("inList ?") {
                XCTAssertFalse(line.contains("\"checkmark"), "\(path): My List drawn with a check mark: \(line)")
            }
        }
        XCTAssertEqual(found, Self.surfaces, "a My List button is added to `surfaces`")

        let shelves = try String(contentsOf: root.appendingPathComponent("App/Features/Common/MediaShelves.swift"), encoding: .utf8)
        let badge = try XCTUnwrap(shelves.range(of: "struct WatchedBadge: View")).upperBound
        let body = shelves[badge...].prefix(400)
        XCTAssertTrue(body.contains("Image(systemName: \"checkmark\")"), "the watched badge keeps its check mark")
        XCTAssertFalse(body.contains("bookmark"))
    }
}
