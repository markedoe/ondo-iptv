import XCTest
import IPTVCore
@testable import Ondo

/// The Movies tab's All row sorts the whole catalogue by date added, kept with the other sorted lists. The cache used to
/// be emptied whole once it held 16 lists, and each category row on screen adds one: a tab of many categories sorted the
/// whole catalogue again on a later redraw. The cache now lets go of the list used longest ago only. Counted, not timed.
@MainActor
final class SortedListCacheTests: XCTestCase {
    private var root: URL!

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoSortedLists-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    func testTheAllRowIsSortedOnceAcrossRedrawsOfManyRows() async throws {
        // 40 categories of three films each.
        var text = "#EXTM3U\n"
        for index in 0..<120 {
            text += "#EXTINF:5400 group-title=\"Movies | Genre \(index % 40)\",Film \(index) (\(2000 + index % 20))\n"
            text += "http://127.0.0.1:9/movie/\(index).mp4\n"
        }
        let file = root.appendingPathComponent("list.m3u")
        try Data(text.utf8).write(to: file)
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0))
        let categories = model.categories(for: .movie)
        XCTAssertEqual(categories.count, 40)

        // What the Movies tab asks for at each redraw: the All row and its count, then the rows on screen.
        func redraw(rows: ArraySlice<MediaCategory>) {
            XCTAssertEqual(model.allTitles(.movie, limit: 20).count, 20)
            XCTAssertEqual(model.allCount(.movie), 120)
            for category in rows { XCTAssertFalse(VODBrowser.items(in: category, type: .movie, model: model).isEmpty) }
        }
        let sorts = model.listSorts
        redraw(rows: categories.prefix(6))
        XCTAssertEqual(model.listSorts, sorts + 1, "the whole catalogue sorted once")
        // Scrolling down every row, a few at a time, each step a redraw.
        for start in stride(from: 0, to: categories.count, by: 5) {
            redraw(rows: categories[start..<min(start + 6, categories.count)])
        }
        XCTAssertEqual(model.listSorts, sorts + 1, "not sorted again while the rows scroll by")
    }
}
