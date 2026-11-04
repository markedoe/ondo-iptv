import XCTest
import IPTVCore
@testable import Ondo

/// Collections shared with every profile (docs/PROFILES.md, "Shared lists"): sharing and taking back, what each profile
/// sees and may change, a Kids profile, persistence, and sync between two devices. The same cases as the Android
/// `SharedCollectionsTest`.
@MainActor
final class SharedListsAppTests: XCTestCase {
    private var root: URL!

    private static let playlistText = """
    #EXTM3U
    #EXTINF:-1 tvg-id="news.test" group-title="News",Test News
    http://127.0.0.1:9/news.m3u8
    #EXTINF:-1 tvg-id="sport.test" group-title="Sports",Test Sport
    http://127.0.0.1:9/sport.m3u8
    #EXTINF:-1 group-title="XXX Adults",Late Night
    http://127.0.0.1:9/adult.m3u8
    """

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoSharedLists-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    /// A device of its own, in `name`, with the test playlist.
    private func device(_ name: String, playlist: Playlist? = nil) async throws -> AppModel {
        let folder = root.appendingPathComponent(name, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let model = AppModel(storageRoot: folder, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        if model.playlists.isEmpty {
            let file = root.appendingPathComponent("list.m3u")
            try Data(Self.playlistText.utf8).write(to: file)
            try await model.addPlaylist(playlist ?? Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0))
        }
        return model
    }

    private func channel(_ model: AppModel, _ name: String) throws -> Channel {
        try XCTUnwrap(model.content.channels.first { $0.name == name })
    }

    private func main(_ model: AppModel) throws -> Profile {
        try XCTUnwrap(model.profiles.first(where: \.isMain))
    }

    func testSharingMovesTheCollectionWholeAndEveryProfileSeesAndEditsIt() async throws {
        let model = try await device("tv")
        let id = model.createList(named: "Evening")
        model.toggle(.channel(try channel(model, "Test News")), inList: id)
        let before = try XCTUnwrap(model.library.customLists.first)
        let sam = model.addProfile(name: "Sam", colorIndex: 3, symbol: "star.fill", isKids: false, pin: nil)
        XCTAssertTrue(model.canShareList(id))

        model.setListShared(id, true)
        XCTAssertTrue(model.isShared(id))
        XCTAssertTrue(model.library.customLists.isEmpty)
        let shared = try XCTUnwrap(model.household.customLists.first)
        XCTAssertEqual(shared.items, before.items)
        XCTAssertEqual(shared.name, before.name)
        let removedAt = try XCTUnwrap(model.syncState.deleted["list:" + id.uuidString.lowercased()])
        XCTAssertGreaterThan(try XCTUnwrap(shared.placedAt).syncMilliseconds, removedAt)

        // Sam sees it and adds to it; the main profile sees Sam's addition.
        model.switchProfile(to: sam)
        XCTAssertEqual(model.customLists.map(\.name), ["Evening"])
        model.toggle(.channel(try channel(model, "Test Sport")), inList: id)
        model.renameList(id, to: "Friday")
        model.switchProfile(to: try main(model))
        XCTAssertEqual(model.customLists.first?.name, "Friday")
        XCTAssertEqual(model.customLists.first.map { model.items(in: $0).map(\.title) }, ["Test News", "Test Sport"])

        // Kept across launches, apart from every profile's library.
        model.flush()
        let again = try await device("tv")
        XCTAssertEqual(again.household.customLists.map(\.name), ["Friday"])
        XCTAssertTrue(again.library.customLists.isEmpty)
    }

    func testTakingBackMovesItIntoTheProfileAndTheOthersLoseIt() async throws {
        let model = try await device("tv")
        let id = model.createList(named: "Evening")
        model.toggle(.channel(try channel(model, "Test News")), inList: id)
        let sam = model.addProfile(name: "Sam", colorIndex: 3, symbol: "star.fill", isKids: false, pin: nil)
        model.setListShared(id, true)
        model.switchProfile(to: sam)
        model.setListShared(id, false)
        XCTAssertFalse(model.isShared(id))
        XCTAssertEqual(model.library.customLists.map(\.id), [id])
        XCTAssertNotNil(model.syncState.household.deleted["list:" + id.uuidString.lowercased()])
        model.switchProfile(to: try main(model))
        XCTAssertTrue(model.customLists.isEmpty)
    }

    func testAKidsProfileSeesSharedCollectionsWithoutWhatItsRulesHideAndChangesNone() async throws {
        let model = try await device("tv")
        let id = model.createList(named: "Evening")
        model.toggle(.channel(try channel(model, "Test News")), inList: id)
        model.toggle(.channel(try channel(model, "Late Night")), inList: id)
        let kids = model.addProfile(name: "Kids", colorIndex: 4, symbol: "pawprint.fill", isKids: true, pin: nil)
        model.setListShared(id, true)
        model.switchProfile(to: kids)
        let household = model.household
        let list = try XCTUnwrap(model.customLists.first)
        XCTAssertEqual(model.items(in: list).map(\.title), ["Test News"], "adult categories stay hidden")
        XCTAssertFalse(model.canEditList(id))
        XCTAssertFalse(model.canShareList(id))
        model.toggle(.channel(try channel(model, "Test Sport")), inList: id)
        model.renameList(id, to: "Mine")
        model.moveListItems(id, fromOffsets: [0], toOffset: 1)
        model.deleteList(id)
        model.setListShared(id, false)
        XCTAssertEqual(model.household, household)
    }

    func testDeletingASharedCollectionDeletesItForEveryProfile() async throws {
        let model = try await device("tv")
        let id = model.createList(named: "Evening")
        let sam = model.addProfile(name: "Sam", colorIndex: 3, symbol: "star.fill", isKids: false, pin: nil)
        model.setListShared(id, true)
        model.switchProfile(to: sam)
        model.deleteList(id)
        XCTAssertTrue(model.household.customLists.isEmpty)
        XCTAssertNotNil(model.syncState.household.deleted["list:" + id.uuidString.lowercased()])
        model.switchProfile(to: try main(model))
        XCTAssertTrue(model.customLists.isEmpty)
    }

    func testASharedCollectionSyncsBetweenDevicesWithoutADuplicate() async throws {
        let tv = try await device("tv")
        let playlist = try XCTUnwrap(tv.playlists.first)
        let phone = try await device("phone", playlist: playlist)
        let id = tv.createList(named: "Evening")
        tv.toggle(.channel(try channel(tv, "Test News")), inList: id)
        _ = tv.addProfile(name: "Sam", colorIndex: 3, symbol: "star.fill", isKids: false, pin: nil)
        await phone.applySync(tv.syncSnapshot())
        await tv.applySync(phone.syncSnapshot())
        XCTAssertEqual(phone.library.customLists.map(\.id), [id])

        tv.setListShared(id, true)
        await phone.applySync(tv.syncSnapshot())
        await tv.applySync(phone.syncSnapshot())
        for device in [tv, phone] {
            XCTAssertTrue(device.library.customLists.isEmpty)
            XCTAssertEqual(device.household.customLists.map(\.id), [id])
        }
        let snapshot = phone.syncSnapshot()
        XCTAssertEqual(snapshot.household?.lists.map(\.id), [id.uuidString.lowercased()])
        XCTAssertTrue(snapshot.lists.isEmpty)

        // Deleted for every profile on the phone: gone on the TV too.
        phone.deleteList(id)
        await tv.applySync(phone.syncSnapshot())
        XCTAssertTrue(tv.customLists.isEmpty)
    }

    func testDeletingTheProfileThatSharedItLeavesTheSharedCollection() async throws {
        let model = try await device("tv")
        let sam = model.addProfile(name: "Sam", colorIndex: 3, symbol: "star.fill", isKids: false, pin: nil)
        model.switchProfile(to: sam)
        let id = model.createList(named: "Evening")
        model.setListShared(id, true)
        model.switchProfile(to: try main(model))
        model.deleteProfile(sam)
        XCTAssertTrue(model.isShared(id))
        XCTAssertEqual(model.customLists.map(\.id), [id])
    }
}
