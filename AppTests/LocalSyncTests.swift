import XCTest
import IPTVCore
import Network
@testable import Ondo

/// Sync on the local network, the app's side (docs/SYNC.md): what a device sends, and how the merged snapshot it gets
/// back is kept. Two devices are two models with their own storage; the snapshot goes from one to the other directly.
@MainActor
final class LocalSyncTests: XCTestCase {
    private var root: URL!
    /// Serves the catalogue on 127.0.0.1 as a provider does, for the whole test: the other device downloads the playlist
    /// from it too. A playlist address from another device is never a file (`SyncMapping`).
    private var server: LocalHTTPServer?

    private static let catalogue = """
    #EXTM3U
    #EXTINF:-1 tvg-id="news.test" group-title="News",Test News
    http://127.0.0.1:9/news.m3u8
    #EXTINF:5400 group-title="Movies | Drama",Quiet Water (2021)
    http://127.0.0.1:9/movie.mp4
    """

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoSync-\(UUID().uuidString)")
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

    /// A device with an M3U playlist, a favourite channel and movie, a movie half watched and a collection.
    private func deviceWithLibrary() async throws -> AppModel {
        let catalogue = Self.catalogue
        let server = try LocalHTTPServer { request in
            request.path == "/list.m3u" ? LocalHTTPServer.Response.text(catalogue, type: "audio/x-mpegurl") : LocalHTTPServer.Response.notFound
        }
        self.server = server
        let address = try await server.start().appending(path: "list.m3u")
        let model = try await device("first")
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(address), autoRefreshHours: 0))
        let playlist = try XCTUnwrap(model.activePlaylist)
        let news = try XCTUnwrap(model.content.channels.first { $0.name == "Test News" })
        let movie = try XCTUnwrap(model.content.movies.first)
        model.toggleFavorite(news)
        model.toggleFavorite(movie)
        let item = PlaybackItem(reference: MediaReference(playlistID: playlist.id, type: .movie, itemID: movie.id), title: movie.name,
                                streamURL: URL(string: "http://127.0.0.1:9/movie.mp4")!, isLive: false)
        model.recordStart(item)
        model.recordProgress(item, position: 1_200, duration: 5_400)
        let list = model.createList(named: "Weekend")
        model.toggle(.movie(movie), inList: list)
        return model
    }

    func testASnapshotKeptByAnotherDeviceGivesItTheSamePlaylistsAndLibrary() async throws {
        let first = try await deviceWithLibrary()
        let snapshot = first.syncSnapshot()
        let playlist = try XCTUnwrap(first.activePlaylist)
        XCTAssertEqual(snapshot.playlists.map(\.id), [playlist.id.uuidString.lowercased()], "IDs are written in lowercase")
        XCTAssertEqual(snapshot.device, first.syncState.deviceID)

        let second = try await device("second")
        await second.applySync(snapshot)

        XCTAssertEqual(second.playlists.map(\.id), [playlist.id])
        XCTAssertEqual(second.playlists.map(\.name), ["Test"])
        guard case .remote(let address) = try XCTUnwrap(second.playlists.first).source, case .remote(let original) = playlist.source else {
            return XCTFail("an M3U playlist stays an M3U playlist")
        }
        XCTAssertEqual(address.absoluteString, original.absoluteString)
        XCTAssertEqual(second.library.favorites.map(\.id), first.library.favorites.map(\.id))
        XCTAssertEqual(second.library.favorites.map(\.title), first.library.favorites.map(\.title))
        let watched = first.library.history.filter { $0.reference.type != .live }
        XCTAssertEqual(second.library.history.map(\.id), watched.map(\.id))
        XCTAssertEqual(second.library.history.first?.position, 1_200)
        XCTAssertEqual(second.library.customLists.map(\.id), first.library.customLists.map(\.id))
        XCTAssertEqual(second.library.customLists.first?.items.map(\.id), first.library.customLists.first?.items.map(\.id))
        // The device had no playlist: the one it got is opened.
        XCTAssertEqual(second.activePlaylist?.id, playlist.id)

        // Its own snapshot now says the same, and keeping it again changes nothing.
        let back = second.syncSnapshot()
        XCTAssertEqual(back.playlists, snapshot.playlists)
        XCTAssertEqual(back.favorites, snapshot.favorites)
        XCTAssertEqual(back.progress, snapshot.progress)
        XCTAssertEqual(back.lists, snapshot.lists)
        let library = second.library
        await second.applySync(back)
        XCTAssertEqual(second.library, library)
    }

    func testFilePlaylistsStayOnTheirDevice() async throws {
        let first = try await deviceWithLibrary()
        let file = Playlist(name: "From a file", source: .local(fileName: "list.m3u"))
        try await first.importBackup(try BackupArchive(playlists: [file], library: UserLibrary()).encoded())
        XCTAssertEqual(first.playlists.count, 2)
        XCTAssertFalse(first.syncSnapshot().playlists.contains { $0.id == file.id.uuidString.lowercased() })
        // Nor deleted by a snapshot that does not have it.
        await first.applySync(SyncSnapshot(device: "other", deleted: ["playlist:" + file.id.uuidString.lowercased(): Date().syncMilliseconds]))
        XCTAssertTrue(first.playlists.contains { $0.id == file.id })
    }

    func testAPlaylistDeletedOnAnotherDeviceIsDeletedHere() async throws {
        let first = try await deviceWithLibrary()
        let second = try await device("second")
        await second.applySync(first.syncSnapshot())
        let playlist = try XCTUnwrap(first.activePlaylist)
        XCTAssertEqual(second.playlists.map(\.id), [playlist.id])

        await first.deletePlaylist(playlist)
        let snapshot = first.syncSnapshot()
        XCTAssertNotNil(snapshot.deleted["playlist:" + playlist.id.uuidString.lowercased()])
        await second.applySync(snapshot)

        XCTAssertTrue(second.playlists.isEmpty)
        XCTAssertTrue(second.library.favorites.isEmpty, "its favourites go with it")
        XCTAssertTrue(second.library.history.isEmpty)
    }

    func testDeletionsMadeHereAreSent() async throws {
        let first = try await deviceWithLibrary()
        let playlist = try XCTUnwrap(first.activePlaylist)
        let id = playlist.id.uuidString.lowercased()
        let movie = try XCTUnwrap(first.content.movies.first)
        first.toggleFavorite(movie)
        let list = try XCTUnwrap(first.library.customLists.first)
        first.deleteList(list.id)
        first.clearHistory()
        let deleted = first.syncSnapshot().deleted
        // The playlist ID in lowercase, the provider's item ID as it is.
        XCTAssertNotNil(deleted["favorite:\(id)|movie|\(movie.id)"])
        XCTAssertNotNil(deleted["list:" + list.id.uuidString.lowercased()])
        XCTAssertNotNil(deleted["progress:\(id)|movie|\(movie.id)|"], "Clear Watch History reaches the other devices")
    }

    func testOnlyAnEditOfWhatSyncCarriesDatesThePlaylist() async throws {
        let first = try await deviceWithLibrary()
        var playlist = try XCTUnwrap(first.activePlaylist)
        let key = playlist.id.uuidString.lowercased()
        let added = try XCTUnwrap(first.syncState.playlistUpdatedAt[key])
        playlist.channelEdits["news"] = ChannelEdit(name: "News")
        await first.updatePlaylist(playlist)
        XCTAssertEqual(first.syncState.playlistUpdatedAt[key], added, "channel edits stay on the device")
        try await Task.sleep(for: .milliseconds(5))
        playlist.name = "Living room"
        await first.updatePlaylist(playlist)
        XCTAssertGreaterThan(try XCTUnwrap(first.syncState.playlistUpdatedAt[key]), added)
    }

    func testOnlyTheMainProfileSyncs() async throws {
        let first = try await deviceWithLibrary()
        let second = try await device("second")
        let guest = second.addProfile(name: "Guest", colorIndex: 1, symbol: "star.fill", isKids: true, pin: nil)
        second.switchProfile(to: guest)
        second.setSyncOnLocalNetwork(true)
        XCTAssertNil(second.syncSnapshotForSending())
        await second.applySync(first.syncSnapshot())
        XCTAssertTrue(second.playlists.isEmpty)
        XCTAssertTrue(second.library.favorites.isEmpty)
        XCTAssertFalse(second.localSync.isRunning, "the client does nothing while another profile is open")
        second.setSyncOnLocalNetwork(false)
    }

    func testSyncIsOffUntilTurnedOn() async throws {
        let model = try await device("first")
        XCTAssertFalse(model.settings.syncOnLocalNetwork)
        XCTAssertFalse(model.localSync.isRunning)
        model.setSyncOnLocalNetwork(true)
        XCTAssertTrue(model.localSync.isRunning)
        model.setInForeground(false)
        XCTAssertFalse(model.localSync.isRunning, "nothing runs in the background")
        model.setInForeground(true)
        model.setSyncOnLocalNetwork(false)
        XCTAssertFalse(model.localSync.isRunning)
    }

    func testOnlyLocalAddressesAreReached() {
        XCTAssertTrue(LocalSync.isLocal(.hostPort(host: .ipv4(IPv4Address("127.0.0.1")!), port: 9)))
        XCTAssertTrue(LocalSync.isLocal(.hostPort(host: .ipv6(IPv6Address("fe80::1")!), port: 9)))
        XCTAssertFalse(LocalSync.isLocal(.hostPort(host: .ipv4(IPv4Address("8.8.8.8")!), port: 9)))
        XCTAssertFalse(LocalSync.isLocal(.hostPort(host: .name("example.com", nil), port: 9)), "a name is not an address")
    }
}
