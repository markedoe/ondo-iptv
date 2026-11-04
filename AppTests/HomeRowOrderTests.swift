import XCTest
import IPTVCore
@testable import Ondo

/// Home's rows in the viewer's order (Settings → Row Order, docs/SYNC.md "Rows"): the order saved applies, a row hidden
/// never shows, each profile has its own, and the order syncs as the tab `home`. The carousel stays at the top.
@MainActor
final class HomeRowOrderTests: XCTestCase {
    private var root: URL!

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoHomeRows-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func device(_ name: String) async -> AppModel {
        let folder = root.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let model = AppModel(storageRoot: folder, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        return model
    }

    /// Every Home of the Apple app: iPhone, iPad and Mac show one `HomeView`, whose rows are `homeRowKeysShown()`. A
    /// new Home goes here with what it shows. (Android's phone and TV Home: `HomeRowOrderTest`.)
    private let surfaces: [(name: String, rows: @MainActor (AppModel) -> [String])] = [
        ("iPhone", { $0.homeRowKeysShown() }),
        ("iPad", { $0.homeRowKeysShown() }),
        ("Mac", { $0.homeRowKeysShown() }),
    ]

    func testAHiddenRowStaysHiddenOnEveryHomeAndTheOthersFollowTheOrder() async {
        let model = await device("first")
        // No movie or series: the channels right after My List, the collections before them on a VOD playlist.
        XCTAssertEqual(model.homeRowKeysShown(), RowOrder.homeBuiltIns(hasVOD: false, collections: true))
        XCTAssertEqual(AppModel.homeRowKeys(hasVOD: true),
                       ["continue-movies", "continue-series", "mylist", "new-movies", "new-series", "trending", "collections", "channels"])
        model.setRowOrder(tab: SyncRows.home, arranged: ["trending", "channels", "mylist", "collections"], hidden: ["mylist"])
        for surface in surfaces {
            let rows = surface.rows(model)
            XCTAssertFalse(rows.contains(RowOrder.myList), surface.name)
            // The order saved, then the rows it does not name in their default order.
            XCTAssertEqual(rows, ["trending", "channels", "collections", "continue-movies", "continue-series", "new-movies", "new-series"],
                           surface.name)
        }
        // Settings lists every row, the hidden one switched off; the Movies tab keeps its own order.
        XCTAssertEqual(model.arrangedHomeRows().filter(\.hidden).map(\.key), ["mylist"])
        XCTAssertNil(model.savedRowOrder(.movie))
        // Default order keeps what is hidden.
        model.resetRowOrder(tab: SyncRows.home)
        XCTAssertEqual(model.savedRowOrder(tab: SyncRows.home)?.order, [])
        XCTAssertEqual(model.savedRowOrder(tab: SyncRows.home)?.hidden, ["mylist"])
        XCTAssertFalse(model.homeRowKeysShown().contains(RowOrder.myList))
    }

    /// An order saved on Android (no collections row there) keeps Apple's collections at their default place.
    func testAnOrderFromADeviceWithoutCollectionsKeepsThemInTheirPlace() async {
        let model = await device("first")
        let fromAndroid = SyncSnapshot(device: "tv", rows: [SyncRows(tab: SyncRows.home, order: ["trending", "mylist"], hidden: ["new-series"], updatedAt: 900)])
        let received = await model.applySync(fromAndroid)
        XCTAssertTrue(received)
        XCTAssertEqual(model.homeRowKeysShown(), ["trending", "mylist", "continue-movies", "continue-series", "channels", "new-movies", "collections"])
    }

    /// Each profile has its own Home order, synced in its own data; a Kids profile cannot change it.
    func testEachProfileHasItsOwnHomeOrderAndAKidsProfileCannotChangeIt() async throws {
        let model = await device("first")
        model.setRowOrder(tab: SyncRows.home, arranged: ["mylist", "trending"], hidden: ["channels"])
        let main = model.savedRowOrder(tab: SyncRows.home)
        let sam = model.addProfile(name: "Sam", colorIndex: 1, symbol: "initial", isKids: false, pin: nil)
        let kid = model.addProfile(name: "Kid", colorIndex: 2, symbol: "initial", isKids: true, pin: nil)
        model.switchProfile(to: sam)
        XCTAssertNil(model.savedRowOrder(tab: SyncRows.home))
        XCTAssertTrue(model.homeRowKeysShown().contains("channels"))
        model.setRowOrder(tab: SyncRows.home, arranged: ["trending", "mylist"], hidden: [])
        model.switchProfile(to: kid)
        model.setRowOrder(tab: SyncRows.home, arranged: ["mylist"], hidden: ["mylist"])
        XCTAssertNil(model.savedRowOrder(tab: SyncRows.home))
        model.switchProfile(to: try XCTUnwrap(model.profiles.first(where: \.isMain)))
        XCTAssertEqual(model.savedRowOrder(tab: SyncRows.home), main)
        let snapshot = model.syncSnapshot()
        XCTAssertEqual(snapshot.rows.map(\.order), [["mylist", "trending"]])
        let samData = snapshot.profileData.first { $0.profile == sam.id.uuidString.lowercased() }
        XCTAssertEqual(samData?.rows.first { $0.tab == SyncRows.home }.map { Array($0.order.prefix(2)) }, ["trending", "mylist"])
    }

    /// The app keeps and passes on a tab it does not know, as the cores do: Home's order from a newer device reaches the
    /// next one through a device that has no use for it.
    func testTheAppPassesOnATabItDoesNotKnow() async {
        let model = await device("first")
        let later = SyncRows(tab: "later", order: ["some-row"], hidden: [], updatedAt: 800)
        let home = SyncRows(tab: SyncRows.home, order: ["channels"], hidden: ["trending"], updatedAt: 900)
        let received = await model.applySync(SyncSnapshot(device: "newer", rows: [home, later]))
        XCTAssertTrue(received)
        XCTAssertEqual(model.syncSnapshot().rows, [home, later])
    }
}
