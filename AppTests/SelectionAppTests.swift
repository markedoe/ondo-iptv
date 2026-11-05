import XCTest
import IPTVCore
@testable import Ondo

/// Selections (docs/SYNC.md, "Selections"): a row of the titles of the tags chosen, in the tabs chosen, a new pick every
/// day; saved at the top of its tabs, deleted with its row, each profile's own, synced. The same rules as Android's
/// `SelectionAppTest` and `SelectionRepositoryTest`.
@MainActor
final class SelectionAppTests: XCTestCase {
    private var root: URL!

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoSelections-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    /// A device with a playlist: a Palme d'Or of 1994, a film of 2025, an anime of 2001 (its category says so), a series.
    private func device(_ name: String = "first") async throws -> AppModel {
        let folder = root.appendingPathComponent(name, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent("list.m3u")
        try Data("""
        #EXTM3U
        #EXTINF:5400 group-title="Movies | Drama",Pulp Fiction (1994)
        http://127.0.0.1:9/movie/pulp.mp4
        #EXTINF:5400 group-title="Movies | Drama",Northern Lights (2025)
        http://127.0.0.1:9/movie/northern.mp4
        #EXTINF:5400 group-title="Movies | Anime",Spirited Away (2001)
        http://127.0.0.1:9/movie/spirited.mp4
        """.utf8).write(to: file)
        let model = AppModel(storageRoot: folder, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0))
        await model.awardsTask?.value
        return model
    }

    private func names(_ model: AppModel, _ tags: [String], all: Bool = false, tab: String = SyncRows.movies) -> Set<String> {
        let selection = SyncSelection(id: "s", tags: tags, match: all ? SyncSelection.all : SyncSelection.any, tabs: [tab], updatedAt: 1)
        return Set(model.selectionTitles(selection, tab: tab).map(\.title))
    }

    func testEachGroupOfTagsPicksItsTitles() async throws {
        let model = try await device()
        XCTAssertEqual(names(model, [Selections.award("cannes")]), ["Pulp Fiction"])
        XCTAssertEqual(names(model, [Selections.decade(2025)]), ["Northern Lights"])
        XCTAssertEqual(names(model, [Selections.style("manga")]), ["Spirited Away"])
        // A movie's genre is known once its page was opened.
        XCTAssertEqual(names(model, [Selections.genre("Crime")!]), [])
        let pulp = try XCTUnwrap(model.content.movies.first { $0.name == "Pulp Fiction" })
        model.movieDetailsCache[pulp.id] = MediaDetails(title: "Pulp Fiction", genre: "Crime, Drama")
        XCTAssertEqual(names(model, [Selections.genre("Crime")!]), ["Pulp Fiction"])
        // The same page opened again with other genres: the tags follow, though no page was added.
        model.movieDetailsCache[pulp.id] = MediaDetails(title: "Pulp Fiction", genre: "Thriller")
        XCTAssertEqual(names(model, [Selections.genre("Thriller")!]), ["Pulp Fiction"])
        XCTAssertEqual(names(model, [Selections.genre("Crime")!]), [])
        model.movieDetailsCache[pulp.id] = MediaDetails(title: "Pulp Fiction", genre: "Crime, Drama")
        // Any tag, or all of them.
        XCTAssertEqual(names(model, [Selections.award("cannes"), Selections.decade(2025)]), ["Pulp Fiction", "Northern Lights"])
        XCTAssertEqual(names(model, [Selections.award("cannes"), Selections.decade(2025)], all: true), [])
        XCTAssertEqual(names(model, [Selections.award("cannes"), Selections.decade(1990)], all: true), ["Pulp Fiction"])
        // The choices offered: the decades and the styles of the playlist, its festivals.
        let choices = model.selectionChoices()
        XCTAssertEqual(choices.decades, [2020, 2000, 1990])
        XCTAssertTrue(choices.awardGroups.contains("cannes"))
        XCTAssertEqual(choices.styles, ["manga"])
        XCTAssertEqual(choices.genres.map(\.name), ["Crime", "Drama"])
    }

    /// A tag of the selection the playlist no longer offers still shows on its page, so that it can be taken out.
    func testATagNoLongerOfferedStillShowsOnTheSelectionsPage() async throws {
        let model = try await device()
        let groups = model.selectionTagGroups(keeping: [Selections.decade(1950), Selections.decade(1990), "mood:calm"])
        let decades = try XCTUnwrap(groups.first { $0.kind == Selections.decadePrefix })
        XCTAssertEqual(decades.tags, [Selections.decade(2020), Selections.decade(2000), Selections.decade(1990), Selections.decade(1950)])
        XCTAssertFalse(groups.flatMap(\.tags).contains("mood:calm"))
    }

    func testTheRowIsANewPickEachDayAndLeavesOutAHiddenCategory() async throws {
        let model = try await device()
        let selection = SyncSelection(id: "s", tags: [Selections.decade(1990), Selections.decade(2000), Selections.decade(2020)],
                                      tabs: [SyncRows.movies], updatedAt: 1)
        let day = Date(timeIntervalSince1970: 1_760_000_000)
        let today = model.selectionTitles(selection, tab: SyncRows.movies, now: day).map(\.title)
        XCTAssertEqual(Set(today), ["Pulp Fiction", "Northern Lights", "Spirited Away"])
        XCTAssertEqual(model.selectionTitles(selection, tab: SyncRows.movies, now: day.addingTimeInterval(60)).map(\.title), today)
        let week = (1...7).map { model.selectionTitles(selection, tab: SyncRows.movies, now: day.addingTimeInterval(Double($0) * 86_400)).map(\.title) }
        XCTAssertTrue(week.contains { $0 != today })
        XCTAssertEqual(AppModel.selectionDay(day, calendar: Calendar(identifier: .gregorian).with(timeZone: "UTC")), "2025-10-09")
        // Whatever calendar the device shows: the Gregorian date, as on Android.
        XCTAssertEqual(AppModel.selectionDay(day, calendar: Calendar(identifier: .buddhist).with(timeZone: "UTC")), "2025-10-09")
        XCTAssertEqual(AppModel.selectionDay(day, calendar: Calendar(identifier: .japanese).with(timeZone: "UTC")), "2025-10-09")
        // A hidden category leaves its titles out, as every row.
        let anime = try XCTUnwrap(model.categories(for: .movie).first { $0.name.contains("Anime") })
        model.setHidden(true, category: anime)
        XCTAssertFalse(model.selectionTitles(selection, tab: SyncRows.movies).map(\.title).contains("Spirited Away"))
    }

    func testASelectionGoesToTheTopOfItsTabsAndItsDeletionSyncs() async throws {
        let model = try await device()
        model.setRowOrder(.movie, arranged: [RowOrder.continueWatching, RowOrder.all], hidden: [])
        let id = try XCTUnwrap(model.saveSelection(id: nil, name: " Nineties ", tags: [Selections.decade(1990)], all: false, tabs: [SyncRows.movies]))
        XCTAssertEqual(model.selection(id)?.name, "Nineties")
        XCTAssertEqual(model.savedRowOrder(.movie)?.order, [Selections.rowKey(id), RowOrder.continueWatching, RowOrder.all])
        XCTAssertEqual(model.arrangedRows(.movie).first?.key, Selections.rowKey(id))
        XCTAssertEqual(model.rowTitles(PseudoCategory.selectionPrefix + id, type: .movie).map(\.title), ["Pulp Fiction"])
        XCTAssertFalse(model.homeRowKeysShown().contains(Selections.rowKey(id)))
        // Shown on Home too: its row goes to the top of Home, and Home's See All lists its titles.
        model.saveSelection(id: id, name: "Nineties", tags: [Selections.decade(1990)], all: false, tabs: [SyncRows.home, SyncRows.movies])
        XCTAssertEqual(model.savedRowOrder(tab: SyncRows.home)?.order, [Selections.rowKey(id)])
        XCTAssertTrue(model.homeRowKeysShown().contains(Selections.rowKey(id)))
        XCTAssertEqual(model.titles(for: .selection(id)).movies.map(\.name), ["Pulp Fiction"])
        // It syncs, then its deletion does.
        let phone = try await device("phone")
        _ = await phone.applySync(model.syncSnapshot())
        XCTAssertEqual(phone.selection(id)?.tags, [Selections.decade(1990)])
        model.deleteSelection(id)
        XCTAssertNil(model.selection(id))
        XCTAssertFalse(model.savedRowOrder(.movie)?.order.contains(Selections.rowKey(id)) ?? false)
        XCTAssertNotNil(model.syncSnapshot().deleted[SyncSelection.keyPrefix + id])
        _ = await phone.applySync(model.syncSnapshot())
        XCTAssertNil(phone.selection(id))
    }

    func testEachProfileHasItsOwnAndAKidsProfileCannotMakeOne() async throws {
        let model = try await device()
        let id = try XCTUnwrap(model.saveSelection(id: nil, name: "Anime", tags: [Selections.style("manga")], all: false, tabs: [SyncRows.movies]))
        let sam = model.addProfile(name: "Sam", colorIndex: 1, symbol: "initial", isKids: false, pin: nil)
        let kid = model.addProfile(name: "Kid", colorIndex: 2, symbol: "initial", isKids: true, pin: nil)
        model.switchProfile(to: sam)
        XCTAssertNil(model.selection(id))
        model.switchProfile(to: kid)
        XCTAssertNil(model.saveSelection(id: nil, name: "Cartoons", tags: [Selections.style("kids")], all: false, tabs: [SyncRows.home]))
        XCTAssertTrue(model.profileSelections.isEmpty)
        model.switchProfile(to: try XCTUnwrap(model.profiles.first(where: \.isMain)))
        XCTAssertEqual(model.syncSnapshot().selections.map(\.id), [id])
    }
}

private extension Calendar {
    func with(timeZone identifier: String) -> Calendar {
        var calendar = self
        calendar.timeZone = TimeZone(identifier: identifier)!
        return calendar
    }
}
