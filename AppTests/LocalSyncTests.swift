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

    /// The order of the rows of a tab travels as one record, dated by the sync clock: the later one wins on both
    /// devices, and a snapshot from an older version (no rows) leaves it as it is.
    func testTheRowOrderSyncsAndTheLaterOneWins() async throws {
        let first = try await device("first")
        let second = try await device("second")
        first.setRowOrder(.movie, arranged: ["mylist", "all", "continue"], hidden: ["trending"])
        let sent = try XCTUnwrap(first.syncSnapshot().rows.first)
        XCTAssertEqual(sent.tab, "movie")
        XCTAssertEqual(Array(sent.order.prefix(3)), ["mylist", "all", "continue"])
        XCTAssertEqual(sent.hidden, ["trending"])
        // The second device reorders after it saw the first one's: its order is the later, everywhere.
        let received = await second.applySync(first.syncSnapshot())
        XCTAssertTrue(received)
        XCTAssertEqual(second.savedRowOrder(.movie), sent)
        second.setRowOrder(.movie, arranged: ["continue", "all"], hidden: [])
        let later = try XCTUnwrap(second.savedRowOrder(.movie))
        XCTAssertGreaterThan(later.updatedAt, sent.updatedAt)
        let answered = await first.applySync(second.syncSnapshot())
        XCTAssertTrue(answered)
        XCTAssertEqual(first.savedRowOrder(.movie), later)
        // An older version sends no rows: nothing changes.
        let fromOld = await first.applySync(SyncSnapshot(device: "old"))
        XCTAssertTrue(fromOld)
        XCTAssertEqual(first.savedRowOrder(.movie), later)
        // Default order keeps what is hidden.
        first.setRowOrder(.series, arranged: ["all", "mylist"], hidden: ["mylist"])
        first.resetRowOrder(.series)
        XCTAssertEqual(first.savedRowOrder(.series)?.order, [])
        XCTAssertEqual(first.savedRowOrder(.series)?.hidden, ["mylist"])
    }

    /// The order is the device's: a kids profile cannot change it, and the settings do not offer it there.
    func testAKidsProfileCannotChangeTheRowOrder() async throws {
        let model = try await device("first")
        model.setRowOrder(.movie, arranged: ["mylist", "all"], hidden: [])
        let saved = model.savedRowOrder(.movie)
        let kid = model.addProfile(name: "Kid", colorIndex: 2, symbol: "face.smiling", isKids: true, pin: nil)
        model.switchProfile(to: kid)
        XCTAssertFalse(model.canChangeRowOrder)
        // Each profile has its own order (docs/PROFILES.md): the kids profile has none, and cannot set one.
        XCTAssertNil(model.savedRowOrder(.movie))
        model.setRowOrder(.movie, arranged: ["all", "mylist"], hidden: ["all"])
        model.resetRowOrder(.movie)
        XCTAssertNil(model.savedRowOrder(.movie))
        model.switchProfile(to: try XCTUnwrap(model.profiles.first(where: \.isMain)))
        XCTAssertEqual(model.savedRowOrder(.movie), saved)
    }

    /// The records are kept sorted by tab in UTF-16 code units, as the merge sorts them (Swift's `<` puts U+FF5E before
    /// U+1F600, UTF-16 after it): a merge that changes nothing gives them back equal.
    func testRowOrdersAreSortedByTabAsTheMergeSortsThem() async throws {
        let model = try await device("first")
        let wide = SyncRows(tab: "\u{FF5E}tab", order: ["all"], updatedAt: 1)
        let emoji = SyncRows(tab: "\u{1F600}tab", order: ["all"], updatedAt: 1)
        model.syncState.rowOrders = [wide, emoji]
        model.setRowOrder(.movie, arranged: ["all"], hidden: [])
        XCTAssertEqual(model.syncState.rowOrders.map(\.tab), ["movie", "\u{1F600}tab", "\u{FF5E}tab"])
        XCTAssertEqual(SyncMerge.merge(model.syncSnapshot(), SyncSnapshot(device: "old")).rows, model.syncState.rowOrders)
    }

    /// The tab's rows follow the order saved; a category the playlist no longer has keeps its place in it.
    func testTheTabFollowsTheRowOrder() async throws {
        let model = try await deviceWithLibrary()
        let drama = try XCTUnwrap(model.categories(for: .movie).first)
        let key = RowOrder.categoryKey(id: drama.id, name: drama.name)
        model.setRowOrder(.movie, arranged: [key, "all", "continue", "mylist", "new", "trending", "awards"], hidden: ["mylist"])
        let rows = model.arrangedRows(.movie)
        XCTAssertEqual(rows.first?.key, key)
        XCTAssertEqual(rows.filter(\.hidden).map(\.key), ["mylist"])
        // All movies: the latest additions, and its count is the whole grid's.
        XCTAssertEqual(model.allCount(.movie), model.movies(in: nil, sort: .playlist).count)
        XCTAssertEqual(model.allTitles(.movie, limit: 20).count, min(20, model.allCount(.movie)))
    }

    /// An Android device keeps actors and directors in My List and in a list: this device passes them on, and its own
    /// edit of the list keeps them.
    func testThePeopleOfAndroidDevicesArePassedOnAndSurviveAnEditOfTheList() async throws {
        let model = try await device("first")
        let list = UUID()
        let ada = SyncPerson(name: "Ada Example", image: "https://example.com/ada.jpg", addedAt: Date().syncMilliseconds - 1_000)
        let tom = SyncPerson(name: "Tom Example", addedAt: Date().syncMilliseconds - 1_000)
        let tv = SyncSnapshot(device: "tv", lists: [SyncList(id: list.uuidString.lowercased(), name: "Directors", people: [tom], updatedAt: Date().syncMilliseconds - 1_000)],
                              people: [ada])
        await model.applySync(tv)
        XCTAssertEqual(model.syncSnapshot().people, [ada])
        XCTAssertEqual(model.syncSnapshot().lists.map(\.people), [[tom]])

        model.renameList(list, to: "Favourite directors")
        let sent = model.syncSnapshot()
        XCTAssertEqual(sent.lists.map(\.name), ["Favourite directors"])
        XCTAssertEqual(sent.lists.map(\.people), [[tom]], "the edit keeps the list's people")
        // Back on the TV, the renamed list wins and still holds its person.
        XCTAssertEqual(SyncMerge.merge(tv, sent).lists.map(\.people), [[tom]])

        // Removed on the TV: removed here too.
        await model.applySync(SyncSnapshot(device: "tv", deleted: [ada.key: Date().syncMilliseconds]))
        XCTAssertTrue(model.syncSnapshot().people.isEmpty)
    }

    /// A session counts only when its answer was applied: on another profile nothing is, and the caller is told so
    /// (it then keeps sending everything until one is).
    /// Every profile syncs (docs/SYNC.md, "Profiles"): an answer is applied whichever profile is open, and the main
    /// profile's records stay the main profile's.
    func testAnAnswerIsAppliedWhicheverProfileIsOpen() async throws {
        let model = try await device("first")
        let person = SyncPerson(name: "Ada Example", addedAt: Date().syncMilliseconds - 1_000)
        let kid = model.addProfile(name: "Kid", colorIndex: 2, symbol: "face.smiling", isKids: true, pin: nil)
        model.switchProfile(to: kid)
        let applied = await model.applySync(SyncSnapshot(device: "tv", people: [person]))
        XCTAssertTrue(applied)
        XCTAssertEqual(model.syncSnapshot().people, [person])
        XCTAssertEqual(model.syncSnapshot().profileData.first { $0.profile == kid.id.uuidString.lowercased() }?.people ?? [], [])
    }

    /// Times go out as they were stored, even ahead of the clock (sync never moves a time); a deletion learned late is
    /// kept from then.
    func testOwnTimesAreSentAsStoredAndLateDeletionsAreKept() async throws {
        let model = try await device("first")
        let now = Date()
        let year: Int64 = 365 * 86_400_000
        let learnedLate = "favorite:" + UUID().uuidString.lowercased() + "|movie|m1"
        let applied = await model.applySync(SyncSnapshot(device: "tv", people: [SyncPerson(name: "Ada", addedAt: now.syncMilliseconds - 1_000)],
                                                         deleted: [learnedLate: now.syncMilliseconds - 250 * 86_400_000]), now: now)
        XCTAssertTrue(applied)
        XCTAssertNotNil(model.syncSnapshot(now: now.addingTimeInterval(86_400)).deleted[learnedLate], "kept from when it was learned")
        model.syncState.people = [SyncPerson(name: "Bea", addedAt: now.syncMilliseconds + year)]
        XCTAssertEqual(model.syncSnapshot(now: now).people.map(\.addedAt), [now.syncMilliseconds + year])
    }

    /// A change made here after one received from a device whose clock runs ahead is dated after it; what is dated more
    /// than a day ahead is left out, and the clock does not follow it (docs/SYNC.md, "Clocks").
    func testAChangeAfterOneFromAClockAheadIsDatedAfterIt() async throws {
        let model = try await device("first")
        let ahead = Date().syncMilliseconds + 2 * 3_600_000
        let applied = await model.applySync(SyncSnapshot(device: "tv", people: [SyncPerson(name: "Ada", addedAt: ahead)]))
        XCTAssertTrue(applied)
        model.setTMDbCredential("example-tmdb-token-a")
        XCTAssertGreaterThan(try XCTUnwrap(model.syncState.tmdbKeyUpdatedAt), ahead)

        let far = Date().syncMilliseconds + 2 * 86_400_000
        await model.applySync(SyncSnapshot(device: "tv", people: [SyncPerson(name: "Bea", addedAt: far)]))
        XCTAssertEqual(model.syncSnapshot().people.map(\.name), ["Ada"])
        XCTAssertLessThan(model.syncClock.last, far)
        // Started again with its clock back at 1970: the stored times still come first.
        let relaunched = AppModel(storageRoot: root.appendingPathComponent("first", isDirectory: true), secrets: model.secrets,
                                  reminderScheduler: InMemoryReminderScheduler())
        await relaunched.bootstrap()
        XCTAssertGreaterThan(relaunched.syncStamp(Date(timeIntervalSince1970: 0)).syncMilliseconds, ahead)
    }

    /// The playlist in use deleted on another device while something plays here: the catalogue stays under the player
    /// and follows once playback ends.
    func testAPlaylistDeletedElsewhereDuringPlaybackLeavesTheCatalogueUntilItEnds() async throws {
        let model = try await deviceWithLibrary()
        let playlist = try XCTUnwrap(model.activePlaylist)
        let item = PlaybackItem(reference: MediaReference(playlistID: playlist.id, type: .live, itemID: "news"), title: "News",
                                streamURL: URL(string: "http://127.0.0.1:9/news.m3u8")!, isLive: true)
        let session = PlaybackSession(queue: [item], index: 0)
        model.sessions[session.id] = session
        let deleted = ["playlist:" + playlist.id.uuidString.lowercased(): model.syncStamp().syncMilliseconds + 1]
        let applied = await model.applySync(SyncSnapshot(device: "tv", deleted: deleted))
        XCTAssertTrue(applied)
        XCTAssertTrue(model.playlists.isEmpty)
        XCTAssertFalse(model.content.channels.isEmpty, "nothing is emptied under the player")
        XCTAssertEqual(model.contentAfterPlayback, .activate)

        model.sessions[session.id] = nil
        await model.followSyncedContent(.activate)
        XCTAssertTrue(model.content.channels.isEmpty)
    }

    /// A playlist edited here while sync waits on another one (its new address downloads) keeps the edit: the older
    /// copy sync merged before it is not applied over it.
    func testAPlaylistEditedWhileSyncUpdatesAnotherKeepsTheEdit() async throws {
        let gate = DispatchSemaphore(value: 0)
        defer { gate.signal() }
        let text = Self.catalogue
        let server = try LocalHTTPServer { request in
            if request.path.hasSuffix("moved.m3u") { gate.wait() }
            return LocalHTTPServer.Response.text(text, type: "audio/x-mpegurl")
        }
        let base = try await server.start()
        self.server = server
        let model = try await device("first")
        try await model.addPlaylist(Playlist(name: "Living room", source: .remote(base.appending(path: "list.m3u")), autoRefreshHours: 0))
        let living = try XCTUnwrap(model.activePlaylist)
        try await model.addPlaylist(Playlist(name: "Kitchen", source: .remote(base.appending(path: "kitchen.m3u")), autoRefreshHours: 0))
        let kitchen = try XCTUnwrap(model.activePlaylist)
        await model.activate(living)
        // Another device moved the living room playlist to another address: sync downloads it, and waits.
        let moved = SyncPlaylist(id: living.id.uuidString.lowercased(), name: "Living room", kind: SyncPlaylist.m3u,
                                 url: base.appending(path: "moved.m3u").absoluteString, addedAt: living.createdAt.syncMilliseconds,
                                 updatedAt: model.syncStamp().syncMilliseconds + 60_000)
        let sync = Task { await model.applySync(SyncSnapshot(device: "tv", playlists: [moved])) }
        let deadline = Date().addingTimeInterval(10)
        while !server.requests.contains(where: { $0.path.hasSuffix("moved.m3u") }) {
            guard Date() < deadline else { return XCTFail("sync never downloaded the moved playlist") }
            try await Task.sleep(for: .milliseconds(20))
        }
        var renamed = try XCTUnwrap(model.playlists.first { $0.id == kitchen.id })
        renamed.name = "Kitchen TV"
        await model.updatePlaylist(renamed)
        gate.signal()
        await sync.value
        XCTAssertEqual(model.playlists.first { $0.id == kitchen.id }?.name, "Kitchen TV")
        XCTAssertEqual(model.syncSnapshot().playlists.first { $0.id == kitchen.id.uuidString.lowercased() }?.name, "Kitchen TV")
    }

    func testTheTMDbKeyTravelsAndSoDoesItsRemoval() async throws {
        let first = try await device("first")
        let second = try await device("second")
        first.setTMDbCredential(" example-tmdb-token-a ")
        let snapshot = first.syncSnapshot()
        XCTAssertEqual(snapshot.keys.map(\.service), [SyncKey.tmdb])
        XCTAssertEqual(snapshot.keys.map(\.value), ["example-tmdb-token-a"], "as saved, without the spaces")

        await second.applySync(snapshot)
        XCTAssertEqual(second.secrets.get(Keychain.tmdbKey), "example-tmdb-token-a")
        XCTAssertTrue(second.hasTMDbKey)
        XCTAssertEqual(second.syncSnapshot().keys, snapshot.keys, "sent on with the time it was saved")
        // A device that does not sync keys answers without them: that removes nothing.
        await second.applySync(SyncSnapshot(device: "old"))
        XCTAssertEqual(second.secrets.get(Keychain.tmdbKey), "example-tmdb-token-a")

        // Removed afterwards on the first device: removed here too.
        try await Task.sleep(for: .milliseconds(5))
        first.setTMDbCredential(nil)
        XCTAssertNotNil(first.syncSnapshot().deleted["key:tmdb"])
        await second.applySync(first.syncSnapshot())
        XCTAssertNil(second.secrets.get(Keychain.tmdbKey))
        XCTAssertFalse(second.hasTMDbKey)
        XCTAssertTrue(second.syncSnapshot().keys.isEmpty)

        // The key of a service this app does not use is passed on, never used.
        let other = SyncKey(service: "future-service", value: "example-other-key", updatedAt: 300)
        await second.applySync(SyncSnapshot(device: "b", keys: [other]))
        XCTAssertEqual(second.syncSnapshot().keys, [other])
        XCTAssertNil(second.secrets.get(Keychain.tmdbKey))
    }

    /// A TMDb key saved before sync carried it has no date: the app dates it when it starts, once, so it wins over a
    /// removal made earlier on another device (a TV) instead of losing to it and being removed here too.
    func testAnUndatedTMDbKeyIsDatedOnceAndWinsOverAnEarlierRemoval() async throws {
        let folder = root.appendingPathComponent("iphone", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let secrets = InMemorySecretStore()
        // In the keychain, with no sync.json yet: as an iPhone that had the key before sync carried it.
        _ = secrets.set("example-tmdb-token-a", for: Keychain.tmdbKey)
        let removedOnTheTV = Date().addingTimeInterval(-3_600).syncMilliseconds
        let iphone = AppModel(storageRoot: folder, secrets: secrets, reminderScheduler: InMemoryReminderScheduler())
        await iphone.bootstrap()
        let key = try XCTUnwrap(iphone.syncSnapshot().keys.first { $0.service == SyncKey.tmdb })
        XCTAssertGreaterThan(key.updatedAt, removedOnTheTV)

        // The TV removed a token of its own an hour ago: the iPhone's key goes to it, and stays on the iPhone.
        let tv = SyncSnapshot(device: "tv", deleted: ["key:" + SyncKey.tmdb: removedOnTheTV])
        XCTAssertEqual(SyncMerge.merge(iphone.syncSnapshot(), tv).keys.map(\.value), ["example-tmdb-token-a"])
        await iphone.applySync(tv)
        XCTAssertEqual(iphone.secrets.get(Keychain.tmdbKey), "example-tmdb-token-a")

        // Dated once: the next launch keeps that date.
        let again = AppModel(storageRoot: folder, secrets: secrets, reminderScheduler: InMemoryReminderScheduler())
        await again.bootstrap()
        XCTAssertEqual(again.syncSnapshot().keys.first { $0.service == SyncKey.tmdb }?.updatedAt, key.updatedAt)
    }

    /// `fixtures/sync/<name>` at the top of the repository (shared with the core and Android tests).
    private func sharedExample(_ name: String) throws -> SyncSnapshot {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while !FileManager.default.fileExists(atPath: directory.appendingPathComponent("fixtures/sync").path) {
            let parent = directory.deletingLastPathComponent()
            guard parent.path != directory.path else { throw XCTSkip("fixtures/sync not found") }
            directory = parent
        }
        let data = try Data(contentsOf: directory.appendingPathComponent("fixtures/sync").appendingPathComponent(name))
        return try XCTUnwrap(SyncSnapshot.decode(data), name)
    }

    /// The subtitle services' keys travel like the TMDb key (docs/SYNC.md, "Key"): saved in the other device's keychain
    /// without a check, each field on its own, the Addic7ed switch too; a field cleared on one device is cleared on the
    /// other; none of them is kept among the keys only passed on.
    func testSubtitleKeysTravelAndSoDoesTheirRemoval() async throws {
        let first = try await device("first")
        let second = try await device("second")
        first.setSubtitleCredential("test-opensubtitles-key", for: .openSubtitlesKey)
        first.setSubtitleCredential("test-user", for: .openSubtitlesUsername)
        first.setSubtitleCredential("test password", for: .openSubtitlesPassword)
        first.setSubtitleCredential("test-subdl-key", for: .subdlKey)
        first.setAddic7edSubtitles(true)
        let snapshot = first.syncSnapshot()
        XCTAssertEqual(Set(snapshot.keys.map(\.service)), [SyncKey.openSubtitles, SyncKey.openSubtitlesUsername, SyncKey.openSubtitlesPassword,
                                                          SyncKey.subdl, SyncKey.addic7ed])

        let revision = second.subtitleCredentialsRevision
        await second.applySync(snapshot)
        XCTAssertEqual(second.subtitleCredential(.openSubtitlesKey), "test-opensubtitles-key")
        XCTAssertEqual(second.subtitleCredential(.openSubtitlesUsername), "test-user")
        XCTAssertEqual(second.subtitleCredential(.openSubtitlesPassword), "test password", "a space inside a password is kept")
        XCTAssertEqual(second.subtitleCredential(.subdlKey), "test-subdl-key")
        XCTAssertTrue(second.settings.addic7edOptIn)
        XCTAssertEqual(second.subtitleServices, [.openSubtitles, .subdl, .addic7ed])
        XCTAssertNotEqual(second.subtitleCredentialsRevision, revision, "Settings shows them")
        XCTAssertTrue(second.syncState.otherKeys.isEmpty, "kept in the keychain only")
        XCTAssertEqual(Set(second.syncSnapshot().keys), Set(snapshot.keys), "sent on with the times they were saved")
        let state = try String(contentsOf: root.appendingPathComponent("second/Support/sync.json"), encoding: .utf8)
        XCTAssertFalse(state.contains("test-"), "never in sync.json")

        // Cleared afterwards on the first device (the account, Addic7ed turned off): the same on the second.
        try await Task.sleep(for: .milliseconds(5))
        first.setSubtitleCredential(nil, for: .openSubtitlesUsername)
        first.setSubtitleCredential(nil, for: .openSubtitlesPassword)
        first.setAddic7edSubtitles(false)
        await second.applySync(first.syncSnapshot())
        XCTAssertNil(second.subtitleCredential(.openSubtitlesUsername))
        XCTAssertNil(second.subtitleCredential(.openSubtitlesPassword))
        XCTAssertEqual(second.subtitleCredential(.openSubtitlesKey), "test-opensubtitles-key")
        XCTAssertFalse(second.settings.addic7edOptIn)
        XCTAssertEqual(second.subtitleServices, [.openSubtitles, .subdl])
        // A device that does not sync these keys answers without them: that removes nothing.
        await second.applySync(SyncSnapshot(device: "old"))
        XCTAssertEqual(second.subtitleCredential(.subdlKey), "test-subdl-key")
    }

    /// Two devices changed the subtitle keys apart (the shared example `keys-a.json` and `keys-b.json`): each field
    /// keeps the later save, a removal after a save clears it, the key of an unknown service is only passed on.
    func testConcurrentSubtitleKeyEditsMergeFieldByField() async throws {
        let model = try await device("tv")
        await model.applySync(try sharedExample("keys-a.json"))
        XCTAssertEqual(model.subtitleCredential(.openSubtitlesUsername), "test-user")
        await model.applySync(try sharedExample("keys-b.json"))
        XCTAssertEqual(model.subtitleCredential(.openSubtitlesKey), "test-opensubtitles-key-b")
        XCTAssertNil(model.subtitleCredential(.openSubtitlesUsername), "removed after it was saved")
        XCTAssertEqual(model.subtitleCredential(.openSubtitlesPassword), "test-password-b")
        XCTAssertEqual(model.subtitleCredential(.subdlKey), "test-subdl-key-a", "saved later than the other")
        XCTAssertEqual(model.subtitleCredential(.subSourceKey), "test-subsource-key", "saved after its removal")
        XCTAssertFalse(model.settings.addic7edOptIn)
        XCTAssertEqual(model.secrets.get(Keychain.tmdbKey), "test-tmdb-token")
        XCTAssertEqual(model.syncState.otherKeys.map(\.service), ["future-service"])
        let expected = try sharedExample("keys-merged.json")
        XCTAssertEqual(model.syncSnapshot().keys.sorted { $0.service < $1.service }, expected.keys)
    }

    /// A key being checked in Settings is not replaced by sync meanwhile; what sync brought applies afterwards.
    func testSyncLeavesASubtitleKeyBeingCheckedAlone() async throws {
        let model = try await device("phone")
        model.subtitleChecksInFlight.insert(.subdl)
        let received = SyncSnapshot(device: "tv", keys: [SyncKey(service: SyncKey.subdl, value: "test-subdl-key", updatedAt: 1_000)])
        await model.applySync(received)
        XCTAssertNil(model.subtitleCredential(.subdlKey))
        model.subtitleChecksInFlight.remove(.subdl)
        await model.applySync(received)
        XCTAssertEqual(model.subtitleCredential(.subdlKey), "test-subdl-key")
    }

    /// A subtitle key saved before sync carried them is dated once at launch, as the TMDb key, so it travels and wins
    /// over an earlier removal on another device.
    func testAnUndatedSubtitleKeyIsDatedOnce() async throws {
        let folder = root.appendingPathComponent("mac", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let secrets = InMemorySecretStore()
        _ = secrets.set("test-subsource-key", for: SubtitleCredential.subSourceKey.rawValue)
        let removedElsewhere = Date().addingTimeInterval(-3_600).syncMilliseconds
        let mac = AppModel(storageRoot: folder, secrets: secrets, reminderScheduler: InMemoryReminderScheduler())
        await mac.bootstrap()
        let key = try XCTUnwrap(mac.syncSnapshot().keys.first { $0.service == SyncKey.subSource })
        XCTAssertGreaterThan(key.updatedAt, removedElsewhere)
        await mac.applySync(SyncSnapshot(device: "tv", deleted: ["key:" + SyncKey.subSource: removedElsewhere]))
        XCTAssertEqual(mac.subtitleCredential(.subSourceKey), "test-subsource-key")
    }

    /// A key and its account are saved as one: changing only the password dates the key and the username with it, so a
    /// concurrent edit on another device never pairs one device's username with the other's password.
    func testAnOpenSubtitlesAccountTravelsAsOne() async throws {
        let model = try await device("phone")
        model.saveSubtitleCredentials([.openSubtitlesKey: "test-key", .openSubtitlesUsername: "test-user", .openSubtitlesPassword: "test-password"])
        try await Task.sleep(for: .milliseconds(5))
        // Settings sends the fields not edited as saved, the password as typed.
        model.saveSubtitleCredentials([.openSubtitlesKey: "test-key", .openSubtitlesUsername: "test-user", .openSubtitlesPassword: "test-password-2"])
        let sent = Dictionary(uniqueKeysWithValues: model.syncSnapshot().keys.map { ($0.service, $0) })
        XCTAssertEqual(sent[SyncKey.openSubtitlesPassword]?.value, "test-password-2")
        let times = Set([SyncKey.openSubtitles, SyncKey.openSubtitlesUsername, SyncKey.openSubtitlesPassword].compactMap { sent[$0]?.updatedAt })
        XCTAssertEqual(times.count, 1, "dated together")
        // Another device saved its own account a little earlier: none of its fields win alone.
        let earlier = try XCTUnwrap(times.first) - 1
        await model.applySync(SyncSnapshot(device: "tv", keys: [
            SyncKey(service: SyncKey.openSubtitlesUsername, value: "other-user", updatedAt: earlier),
            SyncKey(service: SyncKey.openSubtitlesPassword, value: "other-password", updatedAt: earlier),
        ]))
        XCTAssertEqual(model.subtitleCredential(.openSubtitlesUsername), "test-user")
        XCTAssertEqual(model.subtitleCredential(.openSubtitlesPassword), "test-password-2")
    }

    /// An earlier version kept the subtitle keys it passed on in sync.json (`otherKeys`): at the first launch of this one
    /// they move to the keychain, and leave sync.json.
    func testSubtitleKeysAnEarlierVersionKeptMoveToTheKeychain() async throws {
        let first = try await device("mac")
        let relayed = SyncKey(service: SyncKey.subdl, value: "test-subdl-key", updatedAt: 1_000)
        let other = SyncKey(service: "future-service", value: "test-other-key", updatedAt: 300)
        first.syncState.otherKeys = [relayed, other]
        first.saveSyncState()
        let again = AppModel(storageRoot: root.appendingPathComponent("mac", isDirectory: true), secrets: first.secrets,
                             reminderScheduler: InMemoryReminderScheduler())
        await again.bootstrap()
        XCTAssertEqual(again.subtitleCredential(.subdlKey), "test-subdl-key")
        XCTAssertEqual(again.syncState.otherKeys, [other])
        XCTAssertEqual(again.syncSnapshot().keys.first { $0.service == SyncKey.subdl }, relayed)
        let state = try String(contentsOf: root.appendingPathComponent("mac/Support/sync.json"), encoding: .utf8)
        XCTAssertFalse(state.contains("test-subdl-key"), "gone from sync.json")
    }

    /// Settings saves what the user changed as typed, and the other fields as saved now: a key sync brought while the
    /// user typed a username is not replaced by the one the screen showed before.
    func testSettingsSavesOnlyTheFieldsTheUserChanged() {
        let saved: [SubtitleCredential: String] = [.openSubtitlesKey: "synced-key"]
        let fields = SubtitleServicesView.fieldsToSave(
            SubtitleCredential.fields(of: .openSubtitles),
            typed: [.openSubtitlesKey: "shown-key", .openSubtitlesUsername: "new-user", .openSubtitlesPassword: "new-password"],
            shown: [.openSubtitlesKey: "shown-key", .openSubtitlesUsername: "", .openSubtitlesPassword: ""],
            saved: { saved[$0] }
        )
        XCTAssertEqual(fields, [.openSubtitlesKey: "synced-key", .openSubtitlesUsername: "new-user", .openSubtitlesPassword: "new-password"])
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

    /// Every profile syncs, whichever is open (docs/SYNC.md, "Profiles"): a profile and its data reach the other device,
    /// never another profile there, and its PIN stays where it was set.
    func testEveryProfileSyncsWithItsOwnData() async throws {
        let first = try await deviceWithLibrary()
        let second = try await device("second")
        let movie = try XCTUnwrap(first.content.movies.first)
        let guest = first.addProfile(name: "Guest", colorIndex: 1, symbol: "star.fill", isKids: true, pin: "1234")
        first.switchProfile(to: guest)
        first.setSyncOnLocalNetwork(true)
        XCTAssertNotNil(first.syncSnapshotForSending(), "another profile than the main one syncs too")
        first.setSyncOnLocalNetwork(false)
        let snapshot = first.syncSnapshot()
        XCTAssertTrue(snapshot.profiles.contains { $0.id == guest.id.uuidString.lowercased() && $0.kids })
        XCTAssertFalse(String(decoding: try snapshot.encoded(), as: UTF8.self).lowercased().contains("pinhash"))

        await second.applySync(snapshot)
        let arrived = try XCTUnwrap(second.profiles.first { $0.id == guest.id })
        XCTAssertEqual(arrived.name, "Guest")
        XCTAssertTrue(arrived.isKids)
        XCTAssertNil(arrived.pinHash, "the PIN stays on the device where it was set")
        // The main profile's data is the main profile's there; the guest's (none) stays empty.
        XCTAssertTrue(second.library.favorites.contains { $0.reference.itemID == movie.id })
        second.switchProfile(to: arrived)
        XCTAssertTrue(second.library.favorites.isEmpty)
        XCTAssertTrue(second.library.history.isEmpty)
    }

    /// A profile deleted on one device goes on the other with its data; the other profiles stay as they are.
    func testAProfileDeletedOnOneDeviceGoesOnTheOther() async throws {
        let first = try await deviceWithLibrary()
        let second = try await device("second")
        let guest = first.addProfile(name: "Guest", colorIndex: 1, symbol: "star.fill", isKids: false, pin: nil)
        let other = first.addProfile(name: "Other", colorIndex: 2, symbol: Profile.initialSymbol, isKids: false, pin: nil)
        await second.applySync(first.syncSnapshot())
        XCTAssertEqual(Set(second.profiles.map(\.name)), ["Me", "Guest", "Other"])
        let before = second.syncSnapshot()

        first.deleteProfile(guest)
        await second.applySync(first.syncSnapshot())
        XCTAssertEqual(Set(second.profiles.map(\.name)), ["Me", "Other"])
        XCTAssertTrue(second.profiles.contains { $0.id == other.id })
        // A snapshot from before the deletion brings nothing back.
        await first.applySync(before)
        XCTAssertFalse(first.profiles.contains { $0.id == guest.id })
    }

    // MARK: Hidden categories (docs/SYNC.md, "Hidden categories")

    private func hides(_ model: AppModel) -> (categories: Set<String>, tags: Set<String>, words: Set<String>) {
        (model.library.hiddenCategoryKeys, Set(model.library.hiddenCategoryTags.keys), Set(model.library.hiddenCategoryWords.keys))
    }

    /// A category, a language and a style hidden in a profile on one device are hidden in that profile on the other, and
    /// only there; shown again there, they are shown again on the first; hidden again later, hidden again.
    func testHiddenCategoriesReachTheSameProfileAndShowingAgainFollows() async throws {
        let first = try await deviceWithLibrary()
        let second = try await device("second")
        let playlist = try XCTUnwrap(first.activePlaylist)
        let category = try XCTUnwrap(first.content.movieCategories.first)
        let key = UserLibrary.categoryKey(playlistID: playlist.id, categoryID: category.id)
        let sam = first.addProfile(name: "Sam", colorIndex: 1, symbol: Profile.initialSymbol, isKids: false, pin: nil)
        await second.applySync(first.syncSnapshot())
        first.switchProfile(to: sam)
        first.setHidden(true, category: category)
        first.setHidden(true, tag: "PT")
        first.setHidden(true, word: "manga")
        await second.applySync(first.syncSnapshot())
        // The second device's main profile (open) hides nothing; its Sam hides them all.
        XCTAssertTrue(hides(second).categories.isEmpty && hides(second).tags.isEmpty && hides(second).words.isEmpty)
        second.switchProfile(to: try XCTUnwrap(second.profiles.first { $0.id == sam.id }))
        XCTAssertEqual(hides(second).categories, [key])
        XCTAssertEqual(hides(second).tags, ["PT"])
        XCTAssertEqual(hides(second).words, ["manga"])
        // Shown again there: shown again on the first device; the style stays hidden.
        let shownAt = second.syncStamp()
        second.updateLibrary { library in
            library.setHidden(false, playlistID: playlist.id, categoryID: category.id, now: shownAt)
            library.setHidden(false, tag: "PT", now: shownAt)
        }
        await first.applySync(second.syncSnapshot())
        XCTAssertTrue(hides(first).categories.isEmpty)
        XCTAssertTrue(hides(first).tags.isEmpty)
        XCTAssertEqual(hides(first).words, ["manga"])
        // Hidden again on the first device after that: hidden again on the second.
        first.setHidden(true, tag: "PT")
        await second.applySync(first.syncSnapshot())
        XCTAssertEqual(hides(second).tags, ["PT"])
        // The main profile never hid anything, on either device.
        for model in [first, second] {
            model.switchProfile(to: try XCTUnwrap(model.profiles.first(where: \.isMain)))
            XCTAssertTrue(hides(model).categories.isEmpty && hides(model).tags.isEmpty && hides(model).words.isEmpty)
        }
    }

    /// Hidden sets kept before they synced are dated once, at the first launch after the update, after everything the
    /// device holds (an earlier showing again included): at the first session two devices' sets add up, nothing is shown
    /// again, though one device showed a language again after the other hid it.
    func testHiddenSetsKeptBeforeTheySyncedAddUpAtTheFirstSession() async throws {
        let playlistID = UUID()
        let year2020 = Date(timeIntervalSince1970: 1_580_000_000), year2021 = Date(timeIntervalSince1970: 1_610_000_000)
        var tvLibrary = UserLibrary(hiddenCategoryKeys: [UserLibrary.categoryKey(playlistID: playlistID, categoryID: "movie-1")])
        tvLibrary.hiddenCategoryTags["PT"] = year2020
        tvLibrary.shownCategoryTags["ES"] = year2021
        var phoneLibrary = UserLibrary(hiddenCategoryKeys: [UserLibrary.categoryKey(playlistID: playlistID, categoryID: "movie-2")])
        phoneLibrary.hiddenCategoryTags["ES"] = year2020
        phoneLibrary.hiddenCategoryWords["manga"] = year2020
        func written(_ name: String, _ library: UserLibrary) async throws -> AppModel {
            let folder = root.appendingPathComponent(name, isDirectory: true)
            try FileStore(directory: folder.appendingPathComponent("Support", isDirectory: true)).save(library, name: "library")
            return try await device(name)
        }
        let tv = try await written("tv", tvLibrary)
        let phone = try await written("phone", phoneLibrary)
        // Dated at launch, after everything held, and kept so.
        for model in [tv, phone] {
            XCTAssertTrue(model.library.categoryHiddenAt.values.allSatisfy { $0 > year2021 })
            XCTAssertTrue(model.library.hiddenCategoryTags.values.allSatisfy { $0 > year2021 })
            XCTAssertTrue(model.syncState.hiddenDated)
        }
        await tv.applySync(phone.syncSnapshot())
        await phone.applySync(tv.syncSnapshot())
        for model in [tv, phone] {
            XCTAssertEqual(hides(model).categories, [UserLibrary.categoryKey(playlistID: playlistID, categoryID: "movie-1"),
                                                     UserLibrary.categoryKey(playlistID: playlistID, categoryID: "movie-2")])
            XCTAssertEqual(hides(model).tags, ["PT", "ES"])
            XCTAssertEqual(hides(model).words, ["manga"])
        }
        // Once: a relaunch dates nothing again.
        let dates = tv.library.hiddenCategoryTags.mapValues(\.syncMilliseconds)
        tv.flush()
        let again = AppModel(storageRoot: root.appendingPathComponent("tv", isDirectory: true), secrets: InMemorySecretStore(),
                             reminderScheduler: InMemoryReminderScheduler())
        await again.bootstrap()
        XCTAssertEqual(again.library.hiddenCategoryTags.mapValues(\.syncMilliseconds), dates)
    }

    /// An adult-looking category shown again by sync stays out of a Kids profile, whatever sync says (docs/PROFILES.md).
    func testACategoryShownAgainBySyncStaysOutOfAKidsProfileWhenItLooksAdult() async throws {
        let first = try await deviceWithLibrary()
        let second = try await device("second")
        let playlist = try XCTUnwrap(first.activePlaylist)
        let adult = MediaCategory(id: "movie-99", name: "Adult XXX", type: .movie)
        let kids = first.addProfile(name: "Kids", colorIndex: 4, symbol: Profile.initialSymbol, isKids: true, pin: nil)
        first.switchProfile(to: kids)
        first.isParentalUnlocked = true
        first.setHidden(true, category: adult)
        await second.applySync(first.syncSnapshot())
        second.switchProfile(to: try XCTUnwrap(second.profiles.first { $0.id == kids.id }))
        let shownAt = second.syncStamp()
        second.updateLibrary { $0.setHidden(false, playlistID: playlist.id, categoryID: adult.id, now: shownAt) }
        await first.applySync(second.syncSnapshot())
        XCTAssertFalse(first.library.isHidden(playlistID: playlist.id, categoryID: adult.id), "shown again, as sync says")
        XCTAssertTrue(first.isHiddenForKids(adult), "still out: it looks adult")
    }

    /// A parent hides "Horror" for the Kids profile on a TV with a PIN (the Kids profile alone cannot); on an iPad without
    /// a PIN, the child shows it again. After sync the TV still hides it, and nothing changes from one session to the next;
    /// the iPad keeps the child's choice. A hide made on the iPad still reaches the TV.
    func testAKidsProfileKeepsWhatAParentHidOnADeviceWithAPIN() async throws {
        let tv = try await deviceWithLibrary()
        let ipad = try await device("second")
        let playlist = try XCTUnwrap(tv.activePlaylist)
        let horror = MediaCategory(id: "movie-horror", name: "Horror", type: .movie)
        let kids = tv.addProfile(name: "Kids", colorIndex: 4, symbol: Profile.initialSymbol, isKids: true, pin: nil)
        tv.setParentalPIN("1357")
        tv.switchProfile(to: kids)
        func hidesHorror(_ model: AppModel) -> Bool { model.library.isHidden(playlistID: playlist.id, categoryID: horror.id) }
        // Locked: the Kids profile changes nothing until a parent unlocks it, whatever screen asks.
        tv.setHidden(true, category: horror)
        tv.setHidden(true, tag: "PT")
        tv.setHidden(true, tags: ["ES"])
        tv.setHidden(true, word: "manga")
        XCTAssertTrue(hides(tv).categories.isEmpty && hides(tv).tags.isEmpty && hides(tv).words.isEmpty)
        XCTAssertTrue(tv.verifyParentalPIN("1357"))
        tv.setHidden(true, category: horror)
        XCTAssertTrue(hidesHorror(tv))
        await ipad.applySync(tv.syncSnapshot())
        ipad.switchProfile(to: try XCTUnwrap(ipad.profiles.first { $0.id == kids.id }))
        XCTAssertTrue(hidesHorror(ipad))
        // No PIN on the iPad: its Kids settings open, and the child shows it again.
        ipad.isParentalUnlocked = true
        ipad.setHidden(false, category: horror)
        XCTAssertFalse(hidesHorror(ipad))
        await tv.applySync(ipad.syncSnapshot())
        XCTAssertTrue(hidesHorror(tv), "the TV keeps the parent's choice")
        let kept = tv.library
        await tv.applySync(ipad.syncSnapshot())
        XCTAssertEqual(tv.library, kept, "nothing changes from one session to the next")
        await ipad.applySync(tv.syncSnapshot())
        XCTAssertFalse(hidesHorror(ipad), "the iPad keeps the child's")
        // A hide still applies on the TV.
        ipad.setHidden(true, word: "anime")
        await tv.applySync(ipad.syncSnapshot())
        XCTAssertTrue(tv.library.isHidden(word: "anime"))
    }

    // MARK: Review fixes (profiles)

    /// S5: a film keeps saving into the profile it started in after a switch, and nowhere once that profile is deleted.
    func testAPlaybackSavesIntoTheProfileItStartedIn() async throws {
        let model = try await deviceWithLibrary()
        let playlist = try XCTUnwrap(model.activePlaylist)
        let movie = try XCTUnwrap(model.content.movies.first)
        let item = PlaybackItem(reference: MediaReference(playlistID: playlist.id, type: .movie, itemID: movie.id), title: movie.name,
                                streamURL: URL(string: "http://127.0.0.1:9/movie.mp4")!, isLive: false)
        let main = try XCTUnwrap(model.profiles.first(where: \.isMain))
        XCTAssertEqual(model.library.progress(for: item.reference)?.position, 1_200)
        let guest = model.addProfile(name: "Guest", colorIndex: 1, symbol: Profile.initialSymbol, isKids: false, pin: nil)
        model.switchProfile(to: guest)
        model.recordStart(item)
        model.recordProgress(item, position: 2_000, duration: 5_400)
        // Another profile opens while the film plays (picture in picture, another window).
        model.switchProfile(to: main)
        model.recordProgress(item, position: 3_000, duration: 5_400)
        XCTAssertEqual(model.library.progress(for: item.reference)?.position, 1_200)
        XCTAssertEqual(model.otherLibraries[guest.id]?.progress(for: item.reference)?.position, 3_000)
        // The guest deleted while it plays: its saves go nowhere, never into the main profile.
        model.deleteProfile(guest)
        model.recordProgress(item, position: 4_000, duration: 5_400)
        XCTAssertEqual(model.library.progress(for: item.reference)?.position, 1_200)
    }

    /// S4: a session reads the other profiles' libraries from memory, not from disk.
    func testASessionReadsTheOtherProfilesFromMemory() async throws {
        let model = try await deviceWithLibrary()
        let movie = try XCTUnwrap(model.content.movies.first)
        let main = try XCTUnwrap(model.profiles.first(where: \.isMain))
        let guest = model.addProfile(name: "Guest", colorIndex: 1, symbol: Profile.initialSymbol, isKids: false, pin: nil)
        model.switchProfile(to: guest)
        model.toggleFavorite(movie)
        model.switchProfile(to: main)
        // Its document gone from disk, the session still has the guest's data: it never reads it there.
        try? FileManager.default.removeItem(at: root.appendingPathComponent("first/Support/\(guest.libraryDocumentName).json"))
        let bucket = model.syncSnapshot().profileData.first { $0.profile == guest.id.uuidString.lowercased() }
        XCTAssertEqual(bucket?.favorites.map(\.item), [movie.id])
    }

    /// S3: Kids lifted on another device stays on where a parent set a PIN; made Kids again, it applies.
    func testKidsLiftedElsewhereStaysOnWhereAParentSetAPIN() async throws {
        let model = try await device("first")
        let kid = model.addProfile(name: "Kid", colorIndex: 2, symbol: "face.smiling", isKids: true, pin: nil)
        model.setParentalPIN("1357")
        let id = kid.id.uuidString.lowercased()
        let later = Date().syncMilliseconds + 1_000
        await model.applySync(SyncSnapshot(device: "ipad", profiles: [SyncProfile(id: id, name: "Kid", kids: false, updatedAt: later)]))
        XCTAssertEqual(model.profiles.first { $0.id == kid.id }?.isKids, true)
        XCTAssertEqual(model.profiles.first { $0.id == kid.id }?.updatedAt, later)
        // Without a parent PIN, the change applies.
        let other = try await device("second")
        let kid2 = other.addProfile(name: "Kid", colorIndex: 2, symbol: "face.smiling", isKids: true, pin: nil)
        await other.applySync(SyncSnapshot(device: "ipad", profiles: [SyncProfile(id: kid2.id.uuidString.lowercased(), name: "Kid", kids: false,
                                                                                  updatedAt: Date().syncMilliseconds + 1_000)]))
        XCTAssertEqual(other.profiles.first { $0.id == kid2.id }?.isKids, false)
    }

    /// S1: "Sam" made apart on two devices becomes one profile, the smaller ID, with the data of both.
    func testProfilesOfTheSameNameMadeApartBecomeOne() async throws {
        let model = try await deviceWithLibrary()
        let movie = try XCTUnwrap(model.content.movies.first)
        let playlist = try XCTUnwrap(model.activePlaylist)
        let mine = model.addProfile(name: "Sam", colorIndex: 1, symbol: Profile.initialSymbol, isKids: false, pin: "2468")
        model.switchProfile(to: mine)
        model.toggleFavorite(movie)
        let theirs = UUID()
        let theirsID = theirs.uuidString.lowercased()
        let favorite = SyncFavorite(playlist: playlist.id.uuidString.lowercased(), kind: "movie", item: "elsewhere", title: "Elsewhere",
                                    addedAt: Date().syncMilliseconds - 1_000)
        await model.applySync(SyncSnapshot(device: "ipad", profiles: [SyncProfile(id: theirsID, name: " SAM", updatedAt: Date().syncMilliseconds - 500)],
                                           profileData: [SyncProfileData(profile: theirsID, favorites: [favorite])]))
        let kept = min(mine.id.uuidString.lowercased(), theirsID)
        let sams = model.profiles.filter { SyncProfiles.normalizedName($0.name) == "sam" }
        XCTAssertEqual(sams.map { $0.id.uuidString.lowercased() }, [kept])
        XCTAssertNotNil(sams.first?.pinHash, "the PIN stays on this device")
        let bucket = model.syncSnapshot().profileData.first { $0.profile == kept }
        XCTAssertEqual(Set(bucket?.favorites.map(\.item) ?? []), [movie.id, "elsewhere"])
        XCTAssertNotNil(model.syncSnapshot().deleted[SyncProfile.keyPrefix + (kept == theirsID ? mine.id.uuidString.lowercased() : theirsID)])
    }

    /// S6: a deletion in a profile learned from another device is dated when it was learned, and still travels.
    func testADeletionInAProfileLearnedLateIsKeptFromWhenItWasLearned() async throws {
        let model = try await device("first")
        let guest = model.addProfile(name: "Guest", colorIndex: 1, symbol: Profile.initialSymbol, isKids: false, pin: nil)
        let id = guest.id.uuidString.lowercased()
        let key = "favorite:" + UUID().uuidString.lowercased() + "|movie|m1"
        let old = Date().syncMilliseconds - 300 * 86_400_000
        await model.applySync(SyncSnapshot(device: "tv", profiles: [SyncProfile(id: id, name: "Guest", updatedAt: guest.updatedAt)],
                                           profileData: [SyncProfileData(profile: id, deleted: [key: old])]))
        XCTAssertNotNil(model.syncState.profileStates[id]?.learned[key])
        XCTAssertNotNil(model.syncSnapshot().profileData.first { $0.profile == id }?.deleted[key])
    }

    /// A progress save for a profile not open, made while a merge runs in the background, is kept: the merge is made
    /// again from it (review S4, confirmation).
    func testAProgressSaveForAProfileNotOpenDuringAMergeIsKept() async throws {
        let model = try await deviceWithLibrary()
        let playlist = try XCTUnwrap(model.activePlaylist)
        let movie = try XCTUnwrap(model.content.movies.first)
        let item = PlaybackItem(reference: MediaReference(playlistID: playlist.id, type: .movie, itemID: movie.id), title: movie.name,
                                streamURL: URL(string: "http://127.0.0.1:9/movie.mp4")!, isLive: false)
        let main = try XCTUnwrap(model.profiles.first(where: \.isMain))
        let guest = model.addProfile(name: "Guest", colorIndex: 1, symbol: Profile.initialSymbol, isKids: false, pin: nil)
        model.switchProfile(to: guest)
        model.recordStart(item)
        model.recordProgress(item, position: 2_000, duration: 5_400)
        model.switchProfile(to: main)
        model.afterSyncMerge = { model.recordProgress(item, position: 3_000, duration: 5_400) }
        await model.applySync(SyncSnapshot(device: "tv"))
        XCTAssertEqual(model.otherLibraries[guest.id]?.progress(for: item.reference)?.position, 3_000)
    }

    /// A profile renamed by the one-time match keeps its data when it is not in memory: read from its document, never an
    /// empty library saved over it (review S1, confirmation).
    func testAProfileRenamedByTheMatchKeepsItsDataNotInMemory() async throws {
        let model = try await deviceWithLibrary()
        let movie = try XCTUnwrap(model.content.movies.first)
        let main = try XCTUnwrap(model.profiles.first(where: \.isMain))
        let mine = model.addProfile(name: "Sam", colorIndex: 1, symbol: Profile.initialSymbol, isKids: false, pin: nil)
        model.switchProfile(to: mine)
        model.toggleFavorite(movie)
        model.switchProfile(to: main)
        model.otherLibraries[mine.id] = nil
        let theirs = "00000000-0000-4000-8000-000000000002"
        await model.applySync(SyncSnapshot(device: "ipad", profiles: [SyncProfile(id: theirs, name: "Sam", updatedAt: Date().syncMilliseconds - 500)]))
        XCTAssertEqual(model.profiles.filter { $0.name == "Sam" }.map { $0.id.uuidString.lowercased() }, [theirs])
        let bucket = model.syncSnapshot().profileData.first { $0.profile == theirs }
        XCTAssertEqual(bucket?.favorites.map(\.item), [movie.id])
    }

    // MARK: The viewer's choices across sync and a relaunch

    /// A catalogue with language tags: "show only your languages?" is offered for it.
    private static let taggedCatalogue: String = {
        let groups = ["|PT| Filmes", "|PT| Ação", "|FR| Films", "|FR| Action", "|EN| Movies", "|EN| Drama", "|DE| Filme", "|DE| Action"]
        var lines = ["#EXTM3U"]
        for (index, group) in groups.enumerated() {
            lines.append(#"#EXTINF:5400 group-title="\#(group)",Title \#(index) (2020)"#)
            lines.append("http://127.0.0.1:9/movie/\(index).mp4")
        }
        return lines.joined(separator: "\n")
    }()

    /// An iPhone whose viewer answered the suggestion with "Choose…" (one language hidden) and changed a setting.
    private func iPhoneWithChoices() async throws -> (model: AppModel, playlist: Playlist, address: String) {
        let catalogue = Self.taggedCatalogue
        let server = try LocalHTTPServer { request in
            request.path == "/list.m3u" ? LocalHTTPServer.Response.text(catalogue, type: "audio/x-mpegurl") : LocalHTTPServer.Response.notFound
        }
        self.server = server
        let address = try await server.start().appending(path: "list.m3u")
        let model = try await device("iphone")
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(address), autoRefreshHours: 0))
        let playlist = try XCTUnwrap(model.activePlaylist)
        XCTAssertNotNil(model.tagSuggestion(viewerCodes: ["FR", "EN"]))
        model.setHidden(true, tag: "DE")
        model.dismissTagSuggestion()
        model.updateSettings { $0.hideWatchedMovies = true }
        model.flush()
        return (model, playlist, address.absoluteString)
    }

    private func relaunchedIPhone() async -> AppModel {
        let model = AppModel(storageRoot: root.appendingPathComponent("iphone", isDirectory: true), secrets: InMemorySecretStore(),
                             reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        return model
    }

    private func assertChoicesKept(_ model: AppModel, playlist id: UUID, _ moment: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(model.playlists.map(\.id), [id], moment, file: file, line: line)
        XCTAssertEqual(model.activePlaylist?.id, id, moment, file: file, line: line)
        XCTAssertTrue(model.isHidden(tag: "DE"), moment, file: file, line: line)
        XCTAssertFalse(model.isHidden(tag: "FR"), moment, file: file, line: line)
        XCTAssertTrue(model.settings.hideWatchedMovies, moment, file: file, line: line)
        XCTAssertTrue(model.settings.dismissedTagSuggestions.contains(id), moment, file: file, line: line)
        XCTAssertFalse(model.categoryTags.isEmpty, "the catalogue is open (\(moment))", file: file, line: line)
        XCTAssertNil(model.tagSuggestion(viewerCodes: ["FR", "EN"]), "answered (\(moment))", file: file, line: line)
    }

    /// The TV had the same account, added earlier under another ID: the iPhone's playlist becomes the TV's, and the
    /// viewer's choices go with it, through the next sessions and a relaunch.
    func testChoicesSurviveTheTVsOlderCopyOfThePlaylistAndARelaunch() async throws {
        let (iphone, playlist, address) = try await iPhoneWithChoices()
        let tvID = UUID()
        let older = playlist.createdAt.addingTimeInterval(-86_400).syncMilliseconds
        let tv = SyncSnapshot(device: "tv", deviceName: "TV", playlists: [
            SyncPlaylist(id: tvID.uuidString.lowercased(), name: "Living room", kind: SyncPlaylist.m3u, url: address,
                         addedAt: older, updatedAt: older),
        ])
        await iphone.applySync(tv)
        assertChoicesKept(iphone, playlist: tvID, "after the first session")
        XCTAssertNotNil(iphone.syncState.lastSyncedAt, "the report can say when sync last ran")

        // The next session: the TV answers with what both now hold.
        await iphone.applySync(SyncMerge.merge(tv, iphone.syncSnapshot()))
        assertChoicesKept(iphone, playlist: tvID, "after the next session")
        let answer = SyncMerge.merge(tv, iphone.syncSnapshot())
        iphone.flush()

        let relaunched = await relaunchedIPhone()
        assertChoicesKept(relaunched, playlist: tvID, "after a relaunch")
        await relaunched.applySync(answer)
        assertChoicesKept(relaunched, playlist: tvID, "after the session at launch")
    }

    /// The TV added the same account after the iPhone: the iPhone's playlist stays as it is.
    func testChoicesSurviveTheTVsNewerCopyOfThePlaylistAndARelaunch() async throws {
        let (iphone, playlist, address) = try await iPhoneWithChoices()
        let newer = playlist.createdAt.addingTimeInterval(3_600).syncMilliseconds
        let tv = SyncSnapshot(device: "tv", deviceName: "TV", playlists: [
            SyncPlaylist(id: UUID().uuidString.lowercased(), name: "Living room", kind: SyncPlaylist.m3u, url: address,
                         addedAt: newer, updatedAt: newer),
        ])
        await iphone.applySync(tv)
        assertChoicesKept(iphone, playlist: playlist.id, "after the first session")
        let answer = SyncMerge.merge(tv, iphone.syncSnapshot())
        iphone.flush()

        let relaunched = await relaunchedIPhone()
        assertChoicesKept(relaunched, playlist: playlist.id, "after a relaunch")
        await relaunched.applySync(answer)
        assertChoicesKept(relaunched, playlist: playlist.id, "after the session at launch")
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

    /// A device with tens of thousands of playlists (one snapshot can bring that many) applies the next session in one
    /// pass: sync runs on the main thread, every minute, and a search per playlist took minutes.
    func testManyPlaylistsAreAppliedAgainInOnePass() async throws {
        let folder = root.appendingPathComponent("many", isDirectory: true)
        let playlists: [Playlist] = (0..<30_000).map { (i: Int) -> Playlist in
            Playlist(name: "P\(i)", source: .remote(URL(string: "http://127.0.0.1:9/\(i).m3u")!), autoRefreshHours: 0)
        }
        try FileStore(directory: folder.appendingPathComponent("Support", isDirectory: true)).save(playlists, name: "playlists")
        let model = AppModel(storageRoot: folder, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        XCTAssertEqual(model.playlists.count, playlists.count)

        let snapshot = model.syncSnapshot()
        let start = Date()
        await model.applySync(snapshot)
        let elapsed = Date().timeIntervalSince(start)
        print("\(playlists.count) playlists applied again in \(elapsed)s")
        XCTAssertEqual(model.playlists.map(\.id), playlists.map(\.id))
        XCTAssertLessThan(elapsed, 10)
    }

    func testOnlyLocalAddressesAreReached() throws {
        XCTAssertTrue(LocalSync.isLocal(.hostPort(host: .ipv4(IPv4Address("169.254.3.4")!), port: 9))) // leak-guard: allow
        XCTAssertTrue(LocalSync.isLocal(.hostPort(host: .ipv6(IPv6Address("fe80::1")!), port: 9)))
        XCTAssertTrue(LocalSync.isLocal(.hostPort(host: .ipv6(IPv6Address("fc00::1")!), port: 9)))
        // This device's loopback: any process on it could advertise the service there, and no other device is there.
        for loopback in ["127.0.0.1", "127.1.2.3"] {
            let address = try XCTUnwrap(IPv4Address(loopback), loopback)
            XCTAssertFalse(LocalSync.isLocal(.hostPort(host: .ipv4(address), port: 9)), loopback)
        }
        for loopback in ["::1", "::ffff:127.0.0.1"] {
            let address = try XCTUnwrap(IPv6Address(loopback), loopback)
            XCTAssertFalse(LocalSync.isLocal(.hostPort(host: .ipv6(address), port: 9)), loopback)
        }
        XCTAssertTrue(LocalSync.isLoopback([0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 127, 0, 0, 1]))
        XCTAssertFalse(LocalSync.isLoopback([0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 169, 254, 3, 4]))
        XCTAssertFalse(LocalSync.isLoopback([127, 0, 0]))
        XCTAssertFalse(LocalSync.isLocal(.hostPort(host: .ipv4(IPv4Address("8.8.8.8")!), port: 9)))
        XCTAssertFalse(LocalSync.isLocal(.hostPort(host: .name("example.com", nil), port: 9)), "a name is not an address")
    }
}
