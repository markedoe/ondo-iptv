import XCTest
@testable import IPTVCore

final class LibraryTests: XCTestCase {
    let playlistID = UUID()

    private func ref(_ type: ContentType, _ id: String, episode: String? = nil) -> MediaReference {
        MediaReference(playlistID: playlistID, type: type, itemID: id, episodeID: episode)
    }

    func testFavoritesToggleAndOrder() {
        var library = UserLibrary()
        XCTAssertTrue(library.toggleFavorite(ref(.live, "a"), title: "A", imageURL: nil))
        XCTAssertTrue(library.toggleFavorite(ref(.live, "b"), title: "B", imageURL: nil))
        XCTAssertTrue(library.toggleFavorite(ref(.movie, "m"), title: "M", imageURL: nil))
        XCTAssertTrue(library.isFavorite(ref(.live, "a")))
        XCTAssertEqual(library.favorites(playlistID: playlistID, type: .live).map(\.title), ["A", "B"])

        library.moveFavorites(playlistID: playlistID, type: .live, fromOffsets: IndexSet(integer: 1), toOffset: 0)
        XCTAssertEqual(library.favorites(playlistID: playlistID, type: .live).map(\.title), ["B", "A"])

        XCTAssertFalse(library.toggleFavorite(ref(.live, "a"), title: "A", imageURL: nil))
        XCTAssertFalse(library.isFavorite(ref(.live, "a")))
        XCTAssertEqual(library.favorites(playlistID: playlistID).count, 2)
    }

    func testSeriesFavoriteIsSeriesLevel() {
        var library = UserLibrary()
        library.toggleFavorite(ref(.series, "s", episode: "e1"), title: "S", imageURL: nil)
        XCTAssertTrue(library.isFavorite(ref(.series, "s")))
        XCTAssertTrue(library.isFavorite(ref(.series, "s", episode: "e2")))
    }

    func testHistoryAndContinueWatching() {
        var library = UserLibrary()
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        library.recordPlayback(HistoryEntry(reference: ref(.movie, "m1"), title: "M1", lastWatchedAt: t0))
        library.updateProgress(ref(.movie, "m1"), position: 600, duration: 6000, now: t0)
        library.recordPlayback(HistoryEntry(reference: ref(.series, "s", episode: "e1"), title: "S", lastWatchedAt: t0.addingTimeInterval(10)))
        library.updateProgress(ref(.series, "s", episode: "e1"), position: 1000, duration: 2000, now: t0.addingTimeInterval(10))
        library.recordPlayback(HistoryEntry(reference: ref(.series, "s", episode: "e2"), title: "S", lastWatchedAt: t0.addingTimeInterval(20)))
        library.updateProgress(ref(.series, "s", episode: "e2"), position: 100, duration: 2000, now: t0.addingTimeInterval(20))
        library.recordPlayback(HistoryEntry(reference: ref(.movie, "done"), title: "Done", lastWatchedAt: t0))
        library.updateProgress(ref(.movie, "done"), position: 5900, duration: 6000, now: t0.addingTimeInterval(30))
        library.recordPlayback(HistoryEntry(reference: ref(.live, "c1"), title: "C1", lastWatchedAt: t0.addingTimeInterval(40)))

        let resume = library.continueWatching(playlistID: playlistID)
        XCTAssertEqual(resume.map(\.reference.playbackKey), [ref(.series, "s", episode: "e2").playbackKey, ref(.movie, "m1").playbackKey],
                       "one entry per series (latest episode), finished and live excluded")
        XCTAssertEqual(library.recentChannels(playlistID: playlistID).map(\.title), ["C1"])
        XCTAssertEqual(library.progress(for: ref(.movie, "m1"))?.position, 600)

        // Re-opening keeps the resume position.
        library.recordPlayback(HistoryEntry(reference: ref(.movie, "m1"), title: "M1", lastWatchedAt: t0.addingTimeInterval(50)))
        XCTAssertEqual(library.progress(for: ref(.movie, "m1"))?.position, 600)
        XCTAssertEqual(library.history.first?.title, "M1")
    }

    func testHistoryCap() {
        var library = UserLibrary()
        for i in 0..<(UserLibrary.maxHistory + 20) {
            library.recordPlayback(HistoryEntry(reference: ref(.live, "c\(i)"), title: "C\(i)"))
        }
        XCTAssertEqual(library.history.count, UserLibrary.maxHistory)
        XCTAssertEqual(library.history.first?.title, "C\(UserLibrary.maxHistory + 19)")
    }

    func testCategoriesAndCleanup() throws {
        var library = UserLibrary()
        library.setHidden(true, playlistID: playlistID, categoryID: "live-1")
        XCTAssertTrue(library.isHidden(playlistID: playlistID, categoryID: "live-1"))
        let content = PlaylistContent(liveCategories: [
            MediaCategory(id: "live-xxx", name: "XXX | Adult", type: .live),
            MediaCategory(id: "live-news", name: "News", type: .live),
        ])
        library.lockAdultCategories(playlistID: playlistID, content: content)
        XCTAssertTrue(library.isLocked(playlistID: playlistID, categoryID: "live-xxx"))
        XCTAssertFalse(library.isLocked(playlistID: playlistID, categoryID: "live-news"))

        library.toggleFavorite(ref(.live, "a"), title: "A", imageURL: nil)
        library.recordPlayback(HistoryEntry(reference: ref(.live, "a"), title: "A"))
        let other = MediaReference(playlistID: UUID(), type: .live, itemID: "z")
        library.toggleFavorite(other, title: "Z", imageURL: nil)
        library.removeData(for: playlistID)
        XCTAssertEqual(library.favorites.map(\.title), ["Z"])
        XCTAssertTrue(library.history.isEmpty)
        XCTAssertTrue(library.hiddenCategoryKeys.isEmpty)
        XCTAssertTrue(library.lockedCategoryKeys.isEmpty)
    }

    func testFileStoreRoundTripAndBackup() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = FileStore(directory: directory)
        var library = UserLibrary()
        library.toggleFavorite(ref(.live, "a"), title: "A", imageURL: URL(string: "http://img/a.png"))
        try store.save(library, name: "library")
        XCTAssertEqual(store.load(UserLibrary.self, name: "library"), library)

        // A corrupted main file falls back to the previous version.
        try store.save(library, name: "library")
        try Data("{corrupt".utf8).write(to: store.url(for: "library"))
        XCTAssertEqual(store.load(UserLibrary.self, name: "library"), library)

        store.remove(name: "library")
        XCTAssertNil(store.load(UserLibrary.self, name: "library"))
    }

    func testBackupArchive() throws {
        let playlist = Playlist(name: "Mine", source: .xtream(XtreamCredentials(server: URL(string: "http://p")!, username: "u", password: "")))
        var library = UserLibrary()
        library.toggleFavorite(ref(.live, "a"), title: "A", imageURL: nil)
        let data = try BackupArchive(playlists: [playlist], library: library).encoded()
        let decoded = try BackupArchive.decode(data)
        XCTAssertEqual(decoded.playlists, [playlist])
        XCTAssertEqual(decoded.library, library)
    }

    func testPlaylistRefreshPolicy() {
        var playlist = Playlist(name: "P", source: .remote(URL(string: "http://x")!), autoRefreshHours: 12)
        XCTAssertTrue(playlist.needsRefresh())
        let now = Date()
        playlist.lastRefreshedAt = now.addingTimeInterval(-3600)
        XCTAssertFalse(playlist.needsRefresh(now: now))
        playlist.lastRefreshedAt = now.addingTimeInterval(-13 * 3600)
        XCTAssertTrue(playlist.needsRefresh(now: now))
        playlist.autoRefreshHours = 0
        XCTAssertFalse(playlist.needsRefresh(now: now))
    }
}

final class PlaylistDecodingTests: XCTestCase {
    func testOldPlaylistJSONWithoutNewFieldsStillDecodes() throws {
        let json = """
        {"id":"7C1C2B8E-3E0B-4F65-9A57-3C0E2D7A1F00","name":"Old","source":{"remote":{"_0":"http://x.example.com/list.m3u"}}}
        """
        let playlist = try JSONDecoder().decode(Playlist.self, from: Data(json.utf8))
        XCTAssertEqual(playlist.name, "Old")
        XCTAssertEqual(playlist.autoRefreshHours, 24)
        XCTAssertFalse(playlist.xtreamForceM3U)
        XCTAssertTrue(playlist.useProviderEPG)
        XCTAssertEqual(playlist.xtreamOutput, .m3u8)
    }

    func testSecrets() {
        let xtream = PlaylistSource.xtream(XtreamCredentials(server: URL(string: "http://p")!, username: "u", password: "pw"))
        XCTAssertEqual(xtream.secret, "pw")
        XCTAssertEqual(xtream.withSecret("new").secret, "new")
        XCTAssertEqual(PlaylistSource.realDebrid(token: "t").withSecret("").secret, "")
        XCTAssertNil(PlaylistSource.remote(URL(string: "http://x")!).secret)
    }

    func testRoundTrip() throws {
        let playlist = Playlist(name: "P", source: .xtream(XtreamCredentials(server: URL(string: "http://p")!, username: "u", password: "p")), xtreamForceM3U: true)
        let decoded = try JSONDecoder().decode(Playlist.self, from: JSONEncoder().encode(playlist))
        XCTAssertEqual(decoded, playlist)
    }

    func testForceM3UUsesGetPHP() async throws {
        let credentials = XtreamCredentials(server: URL(string: "http://panel.example.com:8080")!, username: "demo", password: "pw")
        let http = MockHTTPClient()
        http.routes["api:"] = try Fixture.data("xtream_auth.json")
        http.routes[XtreamURLBuilder(credentials: credentials).m3uURL(format: .m3u8).absoluteString] = try Fixture.data("sample.m3u")
        let playlist = Playlist(name: "P", source: .xtream(credentials), xtreamForceM3U: true)
        let content = try await PlaylistLoader(http: http, localFileURL: { URL(fileURLWithPath: $0) }).load(playlist)
        XCTAssertEqual(content.channels.count, 7)
        XCTAssertEqual(content.account?.maxConnections, 2)
    }

    func testADownloadedPlaylistCannotPointTheGuideAtDeviceFiles() async throws {
        let text = "#EXTM3U url-tvg=\"file:///etc/hosts,http://epg.example.com/guide.xml\"\n#EXTINF:-1,News\nhttp://example.com/news.m3u8\n"
        let http = MockHTTPClient()
        http.routes["http://example.com/list.m3u"] = Data(text.utf8)
        let loader = PlaylistLoader(http: http, localFileURL: { URL(fileURLWithPath: $0) })
        let remote = try await loader.load(Playlist(name: "P", source: .remote(URL(string: "http://example.com/list.m3u")!)))
        XCTAssertEqual(remote.providerEPGURLs.map(\.absoluteString), ["http://epg.example.com/guide.xml"])

        // A playlist file on the device may use a guide file next to it.
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("ondo-\(UUID().uuidString).m3u")
        try Data(text.utf8).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let local = try await loader.load(Playlist(name: "P", source: .local(fileName: file.path)))
        XCTAssertEqual(local.providerEPGURLs.count, 2)
    }
}

final class LibraryMergeTests: XCTestCase {
    func testMergeConverges() {
        let playlist = UUID()
        func ref(_ id: String) -> MediaReference { MediaReference(playlistID: playlist, type: .movie, itemID: id) }
        let t0 = Date(timeIntervalSince1970: 1_000)

        var phone = UserLibrary()
        phone.toggleFavorite(ref("a"), title: "A", imageURL: nil, now: t0)
        phone.recordPlayback(HistoryEntry(reference: ref("m"), title: "M", lastWatchedAt: t0, position: 100, duration: 1000))
        phone.setHidden(true, playlistID: playlist, categoryID: "c1")

        var mac = UserLibrary()
        mac.toggleFavorite(ref("b"), title: "B", imageURL: nil, now: t0.addingTimeInterval(5))
        mac.recordPlayback(HistoryEntry(reference: ref("m"), title: "M", lastWatchedAt: t0.addingTimeInterval(60), position: 500, duration: 1000))
        mac.customLists = [CustomList(name: "Kids")]

        let onPhone = phone.merged(with: mac)
        let onMac = mac.merged(with: phone)
        XCTAssertEqual(Set(onPhone.favorites.map(\.title)), ["A", "B"])
        XCTAssertEqual(onPhone.history.count, 1)
        XCTAssertEqual(onPhone.history.first?.position, 500, "most recent progress wins")
        XCTAssertEqual(onPhone.hiddenCategoryKeys.count, 1)
        XCTAssertEqual(onPhone.customLists.map(\.name), ["Kids"])
        XCTAssertEqual(onPhone.history, onMac.history)
        XCTAssertEqual(Set(onPhone.favorites.map(\.id)), Set(onMac.favorites.map(\.id)))
        XCTAssertEqual(onPhone.merged(with: onMac).history, onPhone.history, "idempotent")
    }

    func testDeletionsPropagate() {
        let playlist = UUID()
        func ref(_ id: String) -> MediaReference { MediaReference(playlistID: playlist, type: .live, itemID: id) }
        let t0 = Date(timeIntervalSince1970: 1_000)
        var phone = UserLibrary()
        phone.toggleFavorite(ref("a"), title: "A", imageURL: nil, now: t0)
        phone.recordPlayback(HistoryEntry(reference: ref("h"), title: "H", lastWatchedAt: t0))
        var mac = phone

        // Removed on the phone later.
        phone.toggleFavorite(ref("a"), title: "A", imageURL: nil, now: t0.addingTimeInterval(10))
        phone.clearHistory(now: t0.addingTimeInterval(10))

        let merged = mac.merged(with: phone)
        XCTAssertTrue(merged.favorites.isEmpty, "removal wins over an older copy")
        XCTAssertTrue(merged.history.isEmpty)

        // Re-added afterwards on the Mac: the newer addition wins.
        mac = merged
        mac.toggleFavorite(ref("a"), title: "A", imageURL: nil, now: t0.addingTimeInterval(20))
        XCTAssertEqual(mac.merged(with: phone).favorites.count, 1)
    }

    func testOldLibraryJSONDecodes() throws {
        let json = #"{"favorites":[],"history":[],"hiddenCategoryKeys":[],"lockedCategoryKeys":[]}"#
        let library = try JSONDecoder().decode(UserLibrary.self, from: Data(json.utf8))
        XCTAssertTrue(library.customLists.isEmpty)
        XCTAssertNil(library.historyClearedAt)
    }
}

final class CustomListTests: XCTestCase {
    func testListLifecycleAndSync() {
        let playlist = UUID()
        let movie = MediaReference(playlistID: playlist, type: .movie, itemID: "m")
        let channel = MediaReference(playlistID: playlist, type: .live, itemID: "c")
        let t0 = Date(timeIntervalSince1970: 1_000)

        var phone = UserLibrary()
        let kids = phone.createList(named: "Kids", now: t0)
        XCTAssertTrue(phone.toggle(movie, title: "M", imageURL: nil, inList: kids, now: t0))
        XCTAssertTrue(phone.toggle(channel, title: "C", imageURL: nil, inList: kids, now: t0))
        phone.moveItems(inList: kids, fromOffsets: IndexSet(integer: 1), toOffset: 0, now: t0)
        XCTAssertEqual(phone.customLists[0].items.map(\.title), ["C", "M"])
        XCTAssertTrue(phone.customLists[0].contains(movie))

        var mac = phone
        // Phone removes an item later; Mac renames earlier: the latest edit wins for the whole list.
        mac.renameList(kids, to: "Children", now: t0.addingTimeInterval(5))
        XCTAssertFalse(phone.toggle(movie, title: "M", imageURL: nil, inList: kids, now: t0.addingTimeInterval(10)))
        let merged = mac.merged(with: phone)
        XCTAssertEqual(merged.customLists.first?.items.map(\.title), ["C"])
        XCTAssertEqual(merged.customLists.first?.name, "Kids")

        // Deletion propagates.
        var deleted = merged
        deleted.deleteList(kids)
        XCTAssertTrue(merged.merged(with: deleted).customLists.isEmpty)
        XCTAssertTrue(deleted.merged(with: merged).customLists.isEmpty)
    }
}

final class ProfileTests: XCTestCase {
    func testDefaultProfileKeepsHistoricalLibraryName() throws {
        XCTAssertEqual(Profile(id: Profile.defaultID, name: "Me").libraryDocumentName, "library")
        let kid = Profile(name: "Kid", isKids: true)
        XCTAssertEqual(kid.libraryDocumentName, "library-\(kid.id.uuidString)")
        let decoded = try JSONDecoder().decode(Profile.self, from: Data(#"{"id":"00000000-0000-0000-0000-000000000001"}"#.utf8))
        XCTAssertFalse(decoded.isKids)
        XCTAssertFalse(decoded.isProtected)
    }
}

final class ReminderTests: XCTestCase {
    private let playlistID = UUID()
    private let start = Date.utc(2025, 10, 9, 20, 0)

    private func reminder(_ channel: String, startOffset: TimeInterval = 0) -> ProgramReminder {
        ProgramReminder(
            playlistID: playlistID,
            channelID: channel,
            channelName: channel.capitalized,
            title: "Show on \(channel)",
            start: start.addingTimeInterval(startOffset),
            end: start.addingTimeInterval(startOffset + 3600)
        )
    }

    func testToggleAndOrdering() {
        var library = UserLibrary()
        XCTAssertTrue(library.toggleReminder(reminder("b", startOffset: 600)))
        XCTAssertTrue(library.toggleReminder(reminder("a")))
        XCTAssertEqual(library.reminders.map(\.channelID), ["a", "b"], "soonest first")
        XCTAssertTrue(library.hasReminder(id: reminder("a").id))

        XCTAssertFalse(library.toggleReminder(reminder("a")))
        XCTAssertFalse(library.hasReminder(id: reminder("a").id))
        XCTAssertNotNil(library.reminderTombstones[reminder("a").id])
    }

    func testUpcomingAndPruning() {
        var library = UserLibrary()
        library.toggleReminder(reminder("a"))
        library.toggleReminder(reminder("b", startOffset: 7200))
        XCTAssertEqual(library.upcomingReminders(at: start.addingTimeInterval(3700)).map(\.channelID), ["b"])
        library.pruneReminders(endedBefore: start.addingTimeInterval(3700))
        XCTAssertEqual(library.reminders.map(\.channelID), ["b"])
    }

    func testDeletionPropagatesAndReAddingWins() {
        let t0 = Date.utc(2025, 10, 1, 8)
        var phone = UserLibrary()
        phone.toggleReminder(reminder("a"), now: t0)
        var mac = phone

        mac.removeReminder(id: reminder("a").id, now: t0.addingTimeInterval(60))
        XCTAssertTrue(phone.merged(with: mac).reminders.isEmpty, "removal on the Mac reaches the phone")
        XCTAssertTrue(mac.merged(with: phone).reminders.isEmpty, "and the phone's old copy does not bring it back")

        phone = phone.merged(with: mac)
        phone.toggleReminder(reminder("a"), now: t0.addingTimeInterval(120))
        XCTAssertEqual(mac.merged(with: phone).reminders.count, 1, "set again later: the newer intent wins")
        XCTAssertEqual(phone.merged(with: mac), mac.merged(with: phone).merged(with: phone), "converges")
    }

    func testRemovingAPlaylistDropsItsReminders() {
        var library = UserLibrary()
        library.toggleReminder(reminder("a"))
        library.removeData(for: playlistID)
        XCTAssertTrue(library.reminders.isEmpty)
    }

    func testLibrariesWithoutRemindersDecode() throws {
        let json = #"{"favorites":[],"history":[]}"#
        let library = try JSONDecoder().decode(UserLibrary.self, from: Data(json.utf8))
        XCTAssertTrue(library.reminders.isEmpty)
        XCTAssertTrue(library.reminderTombstones.isEmpty)
    }
}

final class ChannelManagementTests: XCTestCase {
    private let playlistID = UUID()

    func testEditsRenameAndRenumberButKeepGuideMatching() {
        let content = PlaylistContent(channels: [
            Channel(id: "1", name: "FR: TV1 FHD", number: 1, categoryID: "a", streamURL: URL(string: "http://h/1")!),
            Channel(id: "2", name: "Nation 2", categoryID: "a", streamURL: URL(string: "http://h/2")!, epgName: "nation2"),
            Channel(id: "3", name: "Aria", categoryID: "a", streamURL: URL(string: "http://h/3")!),
        ])
        let edited = content.applying([
            "1": ChannelEdit(name: "TV1", number: 101),
            "2": ChannelEdit(number: 2),
            "x": ChannelEdit(name: "Ghost"),
        ])
        XCTAssertEqual(edited.channels.map(\.name), ["TV1", "Nation 2", "Aria"])
        XCTAssertEqual(edited.channels.map(\.number), [101, 2, nil])
        XCTAssertEqual(edited.channels[0].epgName, "FR: TV1 FHD", "the provider name still matches the guide")
        XCTAssertEqual(edited.channels[1].epgName, "nation2")
        XCTAssertEqual(content.applying([:]), content)
    }

    func testHiddenChannelsSyncBothWays() {
        let t0 = Date.utc(2025, 10, 1, 8)
        var phone = UserLibrary()
        phone.setHidden(true, playlistID: playlistID, channelID: "c1", now: t0)
        XCTAssertTrue(phone.isHidden(playlistID: playlistID, channelID: "c1"))
        XCTAssertEqual(phone.hiddenChannelIDs(playlistID: playlistID), ["c1"])

        var mac = UserLibrary().merged(with: phone)
        XCTAssertTrue(mac.isHidden(playlistID: playlistID, channelID: "c1"), "hiding reaches the other device")

        mac.setHidden(false, playlistID: playlistID, channelID: "c1", now: t0.addingTimeInterval(60))
        XCTAssertFalse(phone.merged(with: mac).isHidden(playlistID: playlistID, channelID: "c1"), "unhiding reaches it too")
        XCTAssertFalse(mac.merged(with: phone).isHidden(playlistID: playlistID, channelID: "c1"), "and an old copy does not hide it again")

        phone = phone.merged(with: mac)
        phone.setHidden(true, playlistID: playlistID, channelID: "c1", now: t0.addingTimeInterval(120))
        XCTAssertTrue(mac.merged(with: phone).isHidden(playlistID: playlistID, channelID: "c1"), "hidden again later wins")

        phone.removeData(for: playlistID)
        XCTAssertTrue(phone.hiddenChannels.isEmpty)
    }

    func testPlaylistChannelEditsDecode() throws {
        var playlist = Playlist(name: "P", source: .remote(URL(string: "http://h/p.m3u")!))
        playlist.channelEdits = ["1": ChannelEdit(name: "TV1", number: 101)]
        let decoded = try JSONDecoder().decode(Playlist.self, from: JSONEncoder().encode(playlist))
        XCTAssertEqual(decoded.channelEdits["1"], ChannelEdit(name: "TV1", number: 101))
    }
}
