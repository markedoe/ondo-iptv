import XCTest
import IPTVCore
@testable import Ondo

/// What is every profile's and what is each profile's own (docs/PROFILES.md), on every surface: one list of each, so that
/// a new shared or per-profile item is added here. A shared item changed or removed in a profile that is not the main one
/// is the same in every profile, here and, once synced, on the other device. A per-profile item changed in one profile
/// leaves the others alone, and syncs to the same profile on the other device (or stays on the device, as docs/SYNC.md
/// says of the hidden categories and languages).
@MainActor
final class ProfileScopeTests: XCTestCase {
    private var root: URL!
    private var server: LocalHTTPServer?

    private static let catalogue = """
    #EXTM3U
    #EXTINF:-1 tvg-id="news.test" group-title="News",Test News
    http://127.0.0.1:9/news.m3u8
    #EXTINF:5400 group-title="Movies | Drama",Quiet Water (2021)
    http://127.0.0.1:9/movie.mp4
    """

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoScope-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        server = nil
        try? FileManager.default.removeItem(at: root)
    }

    private func device(_ name: String) async throws -> AppModel {
        let folder = root.appendingPathComponent(name, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let model = AppModel(storageRoot: folder, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        return model
    }

    /// A session both ways, as two devices have.
    private func session(_ client: AppModel, _ server: AppModel) async {
        await server.applySync(client.syncSnapshot())
        await client.applySync(server.syncSnapshot())
    }

    /// `read` in each profile, the open one again at the end.
    private func inEveryProfile<T>(_ model: AppModel, _ read: @MainActor (AppModel) -> T) -> [T] {
        let opened = model.activeProfile
        defer { model.switchProfile(to: opened) }
        return model.profiles.map { profile in
            model.switchProfile(to: profile)
            return read(model)
        }
    }

    private func open(_ model: AppModel, _ id: UUID) throws {
        model.switchProfile(to: try XCTUnwrap(model.profiles.first { $0.id == id }))
    }

    /// Shared by every profile: `set`, then `clear` when it can be removed; `read` nil when absent.
    private struct Shared {
        var name: String
        var syncs = true
        var set: @MainActor (AppModel) async throws -> Void
        var clear: (@MainActor (AppModel) async throws -> Void)?
        var read: @MainActor (AppModel) -> AnyHashable?
    }

    /// Each profile's own: `set` in one profile; `read` nil when absent.
    private struct PerProfile {
        var name: String
        var syncs = true
        var set: @MainActor (AppModel) throws -> Void
        var read: @MainActor (AppModel) -> AnyHashable?
    }

    private var extraAddress: URL!

    /// A value read, nil when absent, as the lists compare them.
    private static func found(_ value: (some Hashable)?) -> AnyHashable? { value.map { AnyHashable($0) } }

    private func subtitleKey(_ service: SubtitleService, _ values: [SubtitleCredential: String]) -> Shared {
        Shared(name: "subtitle key: \(service.rawValue)",
               set: { $0.saveSubtitleCredentials(values.mapValues { Optional($0) }) },
               clear: { model in model.saveSubtitleCredentials(Dictionary(uniqueKeysWithValues: values.keys.map { ($0, String?.none) })) },
               read: { model in
                   let fields = SubtitleCredential.fields(of: service).map { model.subtitleCredential($0) }
                   return fields.first! == nil ? nil : AnyHashable(fields.map { $0 ?? "" })
               })
    }

    private var shared: [Shared] {
        let extra = extraAddress!
        return [
            Shared(name: "playlist added, edited and deleted",
                   set: { model in
                       try await model.addPlaylist(Playlist(name: "Extra", source: .remote(extra), autoRefreshHours: 0))
                       var edited = try XCTUnwrap(model.playlists.first { $0.name == "Extra" })
                       edited.name = "Extra renamed"
                       await model.updatePlaylist(edited)
                   },
                   clear: { model in
                       let edited = try XCTUnwrap(model.playlists.first { $0.name == "Extra renamed" })
                       await model.deletePlaylist(edited)
                   },
                   read: { model in Self.found(model.playlists.first { $0.name == "Extra renamed" }?.id) }),
            Shared(name: "list shared with every profile",
                   set: { model in model.setListShared(model.createList(named: "Family"), true) },
                   clear: { model in model.deleteList(try XCTUnwrap(model.household.customLists.first { $0.name == "Family" }).id) },
                   read: { model in Self.found(model.household.customLists.first { $0.name == "Family" }?.id) }),
            Shared(name: "TMDb token",
                   set: { $0.setTMDbCredential("test-tmdb-token") },
                   clear: { $0.setTMDbCredential(nil) },
                   read: { Self.found($0.secrets.get(Keychain.tmdbKey)) }),
            subtitleKey(.openSubtitles, [.openSubtitlesKey: "test-opensubtitles-key", .openSubtitlesUsername: "test-user",
                                         .openSubtitlesPassword: "test password"]),
            subtitleKey(.subdl, [.subdlKey: "test-subdl-key"]),
            subtitleKey(.subSource, [.subSourceKey: "test-subsource-key"]),
            Shared(name: "Addic7ed switch",
                   set: { $0.setAddic7edSubtitles(true) },
                   clear: { $0.setAddic7edSubtitles(false) },
                   read: { $0.settings.addic7edOptIn ? AnyHashable(true) : nil }),
            // The playback settings are every profile's, and each device's: they do not sync (docs/SYNC.md).
            Shared(name: "playback setting", syncs: false,
                   set: { model in model.updateSettings { $0.preferredSubtitleLanguage = "pt" } },
                   read: { $0.settings.preferredSubtitleLanguage == "pt" ? AnyHashable("pt") : nil }),
        ]
    }

    /// The tabs whose rows the viewer orders: a new one is added here.
    private static let rowTabs: [ContentType] = [.movie, .series]

    private var perProfile: [PerProfile] {
        Self.rowTabs.map { type in
            PerProfile(name: "row order: \(type.rawValue)",
                       set: { $0.setRowOrder(type, arranged: ["continue", "all"], hidden: ["new"]) },
                       read: { model in Self.found(model.savedRowOrder(type).map { rows in rows.order + ["|"] + rows.hidden }) })
        } + [
            // Hidden categories and languages stay on the device (docs/SYNC.md): each profile's there.
            PerProfile(name: "hidden categories", syncs: false,
                       set: { model in model.setHidden(true, category: try XCTUnwrap(model.categories(for: .movie).first)) },
                       read: { $0.library.hiddenCategoryKeys.isEmpty ? nil : AnyHashable($0.library.hiddenCategoryKeys) }),
            PerProfile(name: "hidden languages", syncs: false,
                       set: { $0.setHidden(true, tag: "PT") },
                       read: { $0.hiddenTags.contains("PT") ? AnyHashable("PT") : nil }),
            PerProfile(name: "My List",
                       set: { model in model.toggleFavorite(try XCTUnwrap(model.content.movies.first)) },
                       read: { model in Self.found(model.library.favorites.first { $0.reference.type == .movie }?.reference.itemID) }),
            PerProfile(name: "favourite channels",
                       set: { model in model.toggleFavorite(try XCTUnwrap(model.content.channels.first)) },
                       read: { model in Self.found(model.library.favorites.first { $0.reference.type == .live }?.reference.itemID) }),
            PerProfile(name: "progress",
                       set: { model in
                           let playlist = try XCTUnwrap(model.activePlaylist)
                           let movie = try XCTUnwrap(model.content.movies.first)
                           let item = PlaybackItem(reference: MediaReference(playlistID: playlist.id, type: .movie, itemID: movie.id),
                                                   title: movie.name, streamURL: URL(string: "http://127.0.0.1:9/movie.mp4")!, isLive: false)
                           model.recordStart(item)
                           model.recordProgress(item, position: 1_200, duration: 5_400)
                       },
                       read: { model in Self.found(model.library.history.first { $0.reference.type == .movie }?.reference.itemID) }),
            PerProfile(name: "lists",
                       set: { _ = $0.createList(named: "Weekend") },
                       read: { model in Self.found(model.library.customLists.first { $0.name == "Weekend" }?.id) }),
        ]
    }

    /// Two devices: the first with a playlist and a second profile, open; the second told of both.
    private func devices() async throws -> (tv: AppModel, phone: AppModel, guest: UUID) {
        let catalogue = Self.catalogue
        let server = try LocalHTTPServer { request in
            request.path.hasSuffix(".m3u") ? LocalHTTPServer.Response.text(catalogue, type: "audio/x-mpegurl") : LocalHTTPServer.Response.notFound
        }
        self.server = server
        let base = try await server.start()
        extraAddress = base.appending(path: "extra.m3u")
        let tv = try await device("tv")
        let phone = try await device("phone")
        try await tv.addPlaylist(Playlist(name: "Home", source: .remote(base.appending(path: "list.m3u")), autoRefreshHours: 0))
        let guest = tv.addProfile(name: "Guest", colorIndex: 1, symbol: "star.fill", isKids: false, pin: nil)
        await session(tv, phone)
        tv.switchProfile(to: guest)
        return (tv, phone, guest.id)
    }

    func testSharedItemsAreEveryProfilesHereAndOnTheOtherDevice() async throws {
        let (tv, phone, guest) = try await devices()
        for item in shared {
            try await Task.sleep(for: .milliseconds(5))
            try open(tv, guest)
            try await item.set(tv)
            let value = try XCTUnwrap(item.read(tv), item.name)
            XCTAssertEqual(inEveryProfile(tv, item.read), tv.profiles.map { _ in Optional(value) }, "\(item.name): the same in every profile")
            await session(tv, phone)
            let there = inEveryProfile(phone, item.read)
            XCTAssertEqual(there.count, 2, "\(item.name): the other device has both profiles")
            if item.syncs {
                XCTAssertEqual(there, there.map { _ in Optional(value) }, "\(item.name): synced to every profile")
            } else {
                XCTAssertTrue(there.allSatisfy { $0 == nil }, "\(item.name): stays on the device")
            }
            guard let clear = item.clear else { continue }
            try await Task.sleep(for: .milliseconds(5))
            try open(tv, guest)
            try await clear(tv)
            XCTAssertTrue(inEveryProfile(tv, item.read).allSatisfy { $0 == nil }, "\(item.name): removed in every profile")
            await session(tv, phone)
            XCTAssertTrue(inEveryProfile(tv, item.read).allSatisfy { $0 == nil }, "\(item.name): still removed after the session")
            XCTAssertTrue(inEveryProfile(phone, item.read).allSatisfy { $0 == nil }, "\(item.name): removed on the other device")
        }
    }

    func testPerProfileItemsStayInTheirProfileAndSyncToIt() async throws {
        let (tv, phone, guest) = try await devices()
        for item in perProfile {
            try await Task.sleep(for: .milliseconds(5))
            try open(tv, guest)
            try item.set(tv)
            let value = try XCTUnwrap(item.read(tv), item.name)
            try open(tv, Profile.defaultID)
            XCTAssertNil(item.read(tv), "\(item.name): not in the main profile")
            try open(tv, guest)
            await session(tv, phone)
            try open(phone, guest)
            if item.syncs {
                XCTAssertEqual(item.read(phone), value, "\(item.name): in the same profile there")
            } else {
                XCTAssertNil(item.read(phone), "\(item.name): stays on the device")
            }
            try open(phone, Profile.defaultID)
            XCTAssertNil(item.read(phone), "\(item.name): not in the main profile there")
        }
    }
}
