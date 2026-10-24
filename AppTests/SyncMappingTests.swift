import XCTest
import IPTVCore
@testable import Ondo

/// The app's data as a sync snapshot and back (`SyncMapping`, docs/SYNC.md): pure functions, no device involved.
final class SyncMappingTests: XCTestCase {
    let m3uID = UUID()
    let xtreamID = UUID()
    let fileID = UUID()
    let t0 = Date(syncMilliseconds: 1_760_000_000_123)

    func ref(_ playlist: UUID, _ type: ContentType, _ item: String, _ episode: String? = nil) -> MediaReference {
        MediaReference(playlistID: playlist, type: type, itemID: item, episodeID: episode)
    }

    func model() -> ([Playlist], UserLibrary, SyncState) {
        let playlists = [
            Playlist(id: m3uID, name: "News", source: .remote(URL(string: "http://example.com/news.m3u")!),
                     epgURLs: [URL(string: "http://example.com/a.xml")!, URL(string: "http://example.com/b.xml")!], createdAt: t0),
            Playlist(id: xtreamID, name: "Home", source: .xtream(XtreamCredentials(server: URL(string: "http://panel.example.com:8080")!,
                                                                                   username: "viewer", password: "example-password")),
                     createdAt: t0.addingTimeInterval(1)),
            Playlist(id: fileID, name: "File", source: .local(fileName: "list.m3u"), createdAt: t0),
        ]
        var library = UserLibrary()
        library.favorites = [
            FavoriteEntry(reference: ref(xtreamID, .live, "c1"), title: "Ondo News", imageURL: URL(string: "http://example.com/c1.png"), addedAt: t0, sortIndex: 0),
            FavoriteEntry(reference: ref(xtreamID, .movie, "m1"), title: "Glass City", imageURL: nil, addedAt: t0.addingTimeInterval(2), sortIndex: 1),
        ]
        library.history = [
            HistoryEntry(reference: ref(xtreamID, .series, "s1", "e2"), title: "Harbor", subtitle: "S01E02", lastWatchedAt: t0.addingTimeInterval(30),
                         position: 100, duration: 2700, streamExtension: "mkv"),
            HistoryEntry(reference: ref(xtreamID, .live, "c1"), title: "Ondo News", lastWatchedAt: t0.addingTimeInterval(20)),
            HistoryEntry(reference: ref(xtreamID, .movie, "m1"), title: "Glass City", lastWatchedAt: t0.addingTimeInterval(10), position: 600, duration: 6000),
        ]
        library.customLists = [CustomList(name: "Weekend", items: [FavoriteEntry(reference: ref(xtreamID, .movie, "m1"), title: "Glass City", imageURL: nil, addedAt: t0)], updatedAt: t0)]
        library.favoriteTombstones[ref(xtreamID, .movie, "AbC").itemKey] = t0
        library.historyTombstones[ref(xtreamID, .movie, "m2").playbackKey] = t0
        library.historyTombstones[ref(xtreamID, .live, "c9").playbackKey] = t0
        var state = SyncState()
        state.deleted["list:old"] = t0.syncMilliseconds
        return (playlists, library, state)
    }

    func testTheSnapshotHoldsWhatSyncCarries() throws {
        let (playlists, library, state) = model()
        let snapshot = SyncMapping.snapshot(playlists: playlists, library: library, state: state, deviceName: "iPhone", now: t0)
        XCTAssertEqual(snapshot.playlists.map(\.id), [m3uID, xtreamID].map { $0.uuidString.lowercased() }, "no file playlist")
        XCTAssertEqual(snapshot.playlists[0].epgUrl, "http://example.com/a.xml")
        XCTAssertEqual(snapshot.playlists[1].password, "example-password")
        XCTAssertEqual(snapshot.playlists[1].url, "http://panel.example.com:8080")
        // The playlist ID in lowercase, the provider's item ID as it is; live channel history is not synced.
        let x = xtreamID.uuidString.lowercased()
        XCTAssertEqual(Set(snapshot.deleted.keys), ["list:old", "favorite:\(x)|movie|AbC", "progress:\(x)|movie|m2|"])
        XCTAssertEqual(snapshot.progress.count, 2)
        XCTAssertEqual(snapshot.favorites.count, 2)
        // Decodes as the same snapshot.
        XCTAssertEqual(SyncSnapshot.decode(try snapshot.encoded()), snapshot)
    }

    func testApplyingToAFreshDeviceGivesTheSameData() {
        let (playlists, library, state) = model()
        let snapshot = SyncMapping.snapshot(playlists: playlists, library: library, state: state, deviceName: nil, now: t0)
        let created = snapshot.playlists.compactMap { SyncMapping.playlist(from: $0, base: nil) }
        XCTAssertEqual(created.map(\.id), [m3uID, xtreamID])
        XCTAssertEqual(created.map(\.source), playlists.prefix(2).map(\.source))
        XCTAssertEqual(created.map(\.name), ["News", "Home"])
        XCTAssertEqual(created.map(\.createdAt), playlists.prefix(2).map(\.createdAt))
        XCTAssertEqual(created[0].epgURLs, [URL(string: "http://example.com/a.xml")!])
        let applied = SyncMapping.library(UserLibrary(), applying: snapshot)
        XCTAssertEqual(applied.favorites.map(\.id), library.favorites.map(\.id))
        XCTAssertEqual(applied.favorites.map(\.imageURL), library.favorites.map(\.imageURL))
        XCTAssertEqual(applied.history.map(\.id), library.history.filter { $0.reference.type != .live }.map(\.id))
        XCTAssertEqual(applied.history.map(\.position), [100, 600])
        XCTAssertEqual(applied.customLists.map(\.id), library.customLists.map(\.id))
        XCTAssertEqual(applied.customLists.first?.items.map(\.id), library.customLists.first?.items.map(\.id))
        XCTAssertEqual(applied.customLists.first?.updatedAt, t0)
        // Again: the same snapshot.
        let again = SyncMapping.snapshot(playlists: created, library: applied, state: state, deviceName: nil, now: t0)
        XCTAssertEqual(again.playlists.map { var p = $0; p.epgUrl = nil; return p }, snapshot.playlists.map { var p = $0; p.epgUrl = nil; return p })
        XCTAssertEqual(again.favorites, snapshot.favorites)
        XCTAssertEqual(again.progress, snapshot.progress)
        XCTAssertEqual(again.lists, snapshot.lists)
    }

    func testApplyingItsOwnSnapshotChangesNothing() {
        let (playlists, library, state) = model()
        let snapshot = SyncMapping.snapshot(playlists: playlists, library: library, state: state, deviceName: nil, now: t0)
        XCTAssertEqual(SyncMapping.library(library, applying: snapshot), library)
        for (playlist, record) in zip(playlists, snapshot.playlists) {
            XCTAssertEqual(SyncMapping.playlist(from: record, base: playlist), playlist)
        }
        XCTAssertNil(SyncMapping.playlist(from: snapshot.playlists[0], base: playlists[2]), "a file playlist is never touched")
        let merged = SyncMerge.merge(snapshot, snapshot)
        XCTAssertEqual(SyncMapping.library(library, applying: merged), library)
    }

    func testAnEditFromAnotherDevice() {
        let (playlists, _, _) = model()
        var record = SyncMapping.syncPlaylist(playlists[0], updatedAt: 5)!
        record.name = "News and sport"
        record.epgUrl = "http://example.com/b.xml"
        let edited = SyncMapping.playlist(from: record, base: playlists[0])!
        XCTAssertEqual(edited.name, "News and sport")
        XCTAssertEqual(edited.epgURLs.map(\.absoluteString), ["http://example.com/b.xml"])
        XCTAssertEqual(edited.source, playlists[0].source)
        record.epgUrl = nil
        XCTAssertEqual(SyncMapping.playlist(from: record, base: playlists[0])!.epgURLs, [])
        var login = SyncMapping.syncPlaylist(playlists[1], updatedAt: 5)!
        login.password = "new"
        login.url = "panel.example.com:8080"
        let relogged = SyncMapping.playlist(from: login, base: playlists[1])!
        XCTAssertEqual(relogged.source.xtreamCredentials?.password, "new")
        XCTAssertEqual(relogged.source.xtreamCredentials?.server.absoluteString, "http://panel.example.com:8080")
        XCTAssertTrue(SyncMapping.syncedFieldsDiffer(playlists[1], relogged))
        var channelEdit = playlists[1]
        channelEdit.channelEdits["c1"] = ChannelEdit(name: "One")
        XCTAssertFalse(SyncMapping.syncedFieldsDiffer(playlists[1], channelEdit))
    }

    /// `fixtures/sync/merged.json` at the top of the repository (shared with the core and Android tests).
    private func mergedExample() throws -> SyncSnapshot {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while !FileManager.default.fileExists(atPath: directory.appendingPathComponent("fixtures/sync").path) {
            let parent = directory.deletingLastPathComponent()
            guard parent.path != directory.path else { throw XCTSkip("fixtures/sync not found") }
            directory = parent
        }
        let data = try Data(contentsOf: directory.appendingPathComponent("fixtures/sync/merged.json"))
        return try XCTUnwrap(SyncSnapshot.decode(data))
    }

    func testTheSharedExampleAppliesToALibrary() throws {
        let merged = try mergedExample()
        var cleared = UserLibrary()
        cleared.historyClearedAt = Date(syncMilliseconds: 3_550)
        let library = SyncMapping.library(cleared, applying: merged)
        XCTAssertEqual(library.favorites.map(\.title), ["Glass City", "Harbor Lights", "From a file playlist"])
        XCTAssertEqual(library.history.map(\.title), ["Harbor Lights"], "not later than Clear Watch History: stays cleared")
        XCTAssertEqual(library.history.first?.reference.episodeID, "e2")
        XCTAssertEqual(merged.playlists.compactMap { SyncMapping.playlist(from: $0, base: nil) }.count, 2)
    }

    func testTimesGoToMillisecondsAndBackExactly() {
        for ms: Int64 in [0, 1, 999, 1_760_000_000_123, 4_102_444_800_999] {
            XCTAssertEqual(Date(syncMilliseconds: ms).syncMilliseconds, ms)
        }
        var state = SyncState()
        state.deleted = ["list:old": 0, "playlist:p": t0.syncMilliseconds]
        state.replaced = ["p": "q", "old": "x"]
        XCTAssertEqual(state.pruned(now: t0).deleted, ["playlist:p": t0.syncMilliseconds])
        XCTAssertEqual(state.pruned(now: t0).replaced, ["p": "q"])
        let decoded = try? JSONDecoder().decode(SyncState.self, from: Data("{}".utf8))
        XCTAssertEqual(decoded?.deviceID.count, 36)
    }

    /// File playlists stay on their device: a `file:` address from another device is taken neither as a playlist nor as
    /// its guide.
    func testAFileAddressFromAnotherDeviceIsRefused() {
        let id = UUID().uuidString.lowercased()
        var file = SyncPlaylist(id: id, name: "F", kind: SyncPlaylist.m3u, url: "file:///list.m3u", addedAt: 1, updatedAt: 1)
        XCTAssertNil(SyncMapping.playlist(from: file, base: nil))
        file.url = "FILE:///list.m3u"
        XCTAssertNil(SyncMapping.playlist(from: file, base: nil))
        let guide = SyncPlaylist(id: id, name: "G", kind: SyncPlaylist.m3u, url: "http://example.com/list.m3u",
                                 epgUrl: "file:///guide.xml", addedAt: 1, updatedAt: 1)
        XCTAssertEqual(SyncMapping.playlist(from: guide, base: nil)?.epgURLs, [])
        XCTAssertEqual(SyncMapping.playlist(from: guide, base: nil)?.source, PlaylistSource.remote(URL(string: "http://example.com/list.m3u")!))
    }

    /// Artwork from another device is a web address: Nuke reads a `file:` or `data:` one inline and whole, so another
    /// device could have this one read its own files. The device's own artwork comes back as it went, whatever it is.
    func testArtworkFromAnotherDeviceIsAWebAddress() {
        let x = xtreamID.uuidString.lowercased()
        func applied(_ image: String) -> [URL?] {
            let received = SyncSnapshot(
                device: "other",
                favorites: [SyncFavorite(playlist: x, kind: "movie", item: "m1", title: "Glass City", image: image, addedAt: 1)],
                progress: [SyncProgress(playlist: x, kind: "movie", item: "m1", title: "Glass City", image: image, position: 60,
                                        duration: 600, watchedAt: 1)],
                lists: [SyncList(id: UUID().uuidString.lowercased(), name: "Weekend",
                                 items: [SyncListItem(playlist: x, kind: "movie", item: "m1", title: "Glass City", image: image)], updatedAt: 1)]
            )
            let library = SyncMapping.library(UserLibrary(), applying: received)
            return [library.favorites.first?.imageURL, library.history.first?.imageURL, library.customLists.first?.items.first?.imageURL]
        }
        for image in ["file:///etc/hosts", "FILE:///etc/hosts", "data:image/png;base64,iVBORw0KGgo=", "ftp://example.com/a.png"] {
            XCTAssertEqual(applied(image), [nil, nil, nil], image)
        }
        for image in ["http://example.com/a.png", "HTTPS://example.com/a.png"] {
            XCTAssertEqual(applied(image), Array(repeating: URL(string: image), count: 3), image)
        }

        var library = UserLibrary()
        let own = URL(string: "file:///logo.png")
        library.favorites = [FavoriteEntry(reference: ref(xtreamID, .movie, "m1"), title: "Glass City", imageURL: own, addedAt: t0, sortIndex: 0)]
        let snapshot = SyncMapping.snapshot(playlists: [], library: library, state: SyncState(), deviceName: nil, now: t0)
        XCTAssertEqual(SyncMapping.library(library, applying: snapshot).favorites.first?.imageURL, own)
    }

    /// Int64.max milliseconds comes back from `Date` as 2^63, past Int64: it saturates instead of trapping.
    func testTimesAtTheEndsOfInt64Saturate() {
        XCTAssertEqual(Date(syncMilliseconds: .max).syncMilliseconds, .max)
        XCTAssertEqual(Date(syncMilliseconds: .min).syncMilliseconds, .min)
        XCTAssertEqual(Date(timeIntervalSince1970: 1e300).syncMilliseconds, .max)
        XCTAssertEqual(Date(timeIntervalSince1970: -1e300).syncMilliseconds, .min)

        let (playlists, library, state) = model()
        let x = xtreamID.uuidString.lowercased()
        let received = SyncSnapshot(
            device: "other",
            favorites: [SyncFavorite(playlist: x, kind: "movie", item: "m9", title: "Far", addedAt: .max)],
            progress: [SyncProgress(playlist: x, kind: "movie", item: "m8", title: "Later", position: 60, duration: 600, watchedAt: .max)],
            lists: [SyncList(id: UUID().uuidString.lowercased(), name: "Far", updatedAt: .max)]
        )
        let applied = SyncMapping.library(library, applying: received)
        let snapshot = SyncMapping.snapshot(playlists: playlists, library: applied, state: state, deviceName: nil, now: t0)
        XCTAssertEqual(snapshot.favorites.first { $0.item == "m9" }?.addedAt, .max)
        XCTAssertEqual(snapshot.progress.first { $0.item == "m8" }?.watchedAt, .max)
        XCTAssertEqual(snapshot.lists.first?.updatedAt, .max)
    }

    /// A position and duration another device sends can be any number: the screens that show them and the player that
    /// resumes at them do not trap.
    @MainActor
    func testAResumePointPastIntRangeDoesNotTrap() throws {
        let x = xtreamID.uuidString.lowercased()
        let received = SyncSnapshot(device: "other", progress: [
            SyncProgress(playlist: x, kind: "movie", item: "m1", title: "Far", position: 1e300, duration: 0, watchedAt: t0.syncMilliseconds),
            SyncProgress(playlist: x, kind: "movie", item: "m2", title: "Long", position: 31, duration: 1e300, watchedAt: t0.syncMilliseconds),
        ])
        let history = SyncMapping.library(UserLibrary(), applying: received).history
        let far = try XCTUnwrap(history.first { $0.reference.itemID == "m1" })
        let long = try XCTUnwrap(history.first { $0.reference.itemID == "m2" })
        XCTAssertTrue(far.isResumable)
        XCTAssertTrue(long.isResumable)
        XCTAssertFalse(long.timeLeftText.isEmpty)
        XCTAssertEqual(Formatting.clock(far.position), Formatting.clock(1e12))

        let controller = PlayerController()
        controller.startLog(for: PlaybackItem(reference: far.reference, title: far.title, streamURL: URL(string: "http://example.com/m1.mp4")!,
                                              isLive: false, startPosition: far.position))
        XCTAssertEqual(controller.log.entries.count, 1)
    }
}
