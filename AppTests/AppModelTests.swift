import XCTest
import IPTVCore
@testable import Ondo

/// Tests of the app layer (state, persistence, profiles, parental control) with an isolated
/// storage directory and an in-memory secret store.
@MainActor
final class AppModelTests: XCTestCase {
    private var root: URL!
    private var secrets: InMemorySecretStore!
    private var notifications: InMemoryReminderScheduler!

    private static let playlistText = """
    #EXTM3U
    #EXTINF:-1 tvg-id="news.test" group-title="News",Test News
    http://127.0.0.1:9/news.m3u8
    #EXTINF:-1 tvg-id="sport.test" group-title="Sports",Test Sport
    http://127.0.0.1:9/sport.m3u8
    #EXTINF:-1 group-title="XXX Adults",Late Night
    http://127.0.0.1:9/adult.m3u8
    #EXTINF:5400 group-title="Movies | Drama",Quiet Water (2021)
    http://127.0.0.1:9/movie.mp4
    """

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        secrets = InMemorySecretStore()
        notifications = InMemoryReminderScheduler()
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func makeModel() -> AppModel {
        AppModel(storageRoot: root, secrets: secrets, reminderScheduler: notifications)
    }

    private func playlistFile() throws -> URL {
        let url = root.appendingPathComponent("list.m3u")
        try Data(Self.playlistText.utf8).write(to: url)
        return url
    }

    private func modelWithPlaylist() async throws -> AppModel {
        let model = makeModel()
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(try playlistFile()), autoRefreshHours: 0))
        return model
    }

    func testAddPlaylistLoadsAndPersistsCatalogue() async throws {
        let model = try await modelWithPlaylist()
        XCTAssertEqual(model.content.channels.count, 3)
        XCTAssertEqual(model.content.movies.map(\.name), ["Quiet Water"])
        XCTAssertEqual(model.activePlaylist?.name, "Test")
        XCTAssertEqual(model.phase, .loaded)

        // A fresh launch restores the playlist and its cached catalogue.
        let relaunched = makeModel()
        await relaunched.bootstrap()
        XCTAssertEqual(relaunched.playlists.map(\.name), ["Test"])
        XCTAssertEqual(relaunched.content.channels.count, 3)
    }

    func testSecretsLeaveThePlaylistFileWhenTheStoreWorks() async throws {
        let model = makeModel()
        await model.bootstrap()
        let credentials = XtreamCredentials(server: URL(string: "http://127.0.0.1:9")!, username: "user", password: "s3cret")
        let backup = BackupArchive(playlists: [Playlist(name: "X", source: .xtream(credentials))], library: UserLibrary())
        try? await model.importBackup(backup.encoded())

        let saved = try String(contentsOf: root.appendingPathComponent("Support/playlists.json"), encoding: .utf8)
        XCTAssertFalse(saved.contains("s3cret"), "password must not be written to disk")
        XCTAssertEqual(secrets.values.values.first, "s3cret")

        // Relaunch: the password comes back from the secret store.
        let relaunched = makeModel()
        await relaunched.bootstrap()
        XCTAssertEqual(relaunched.playlists.first?.source.xtreamCredentials?.password, "s3cret")
    }

    func testSecretsStayInThePlaylistFileWhenTheStoreFails() async throws {
        secrets = InMemorySecretStore(failsWrites: true)
        let model = makeModel()
        await model.bootstrap()
        let backup = BackupArchive(playlists: [Playlist(name: "RD", source: .realDebrid(token: "TOKEN-123"))], library: UserLibrary())
        try? await model.importBackup(backup.encoded())

        let relaunched = makeModel()
        await relaunched.bootstrap()
        XCTAssertEqual(relaunched.playlists.first?.source.secret, "TOKEN-123", "the login must never be lost")
    }

    func testProfilesKeepSeparateFavorites() async throws {
        let model = try await modelWithPlaylist()
        let channel = try XCTUnwrap(model.content.channels.first)
        model.toggleFavorite(channel)
        XCTAssertEqual(model.favoriteChannels().map(\.id), [channel.id])

        let guest = model.addProfile(name: "Guest", colorIndex: 1, symbol: "star.fill", isKids: false, pin: nil)
        model.switchProfile(to: guest)
        XCTAssertTrue(model.favoriteChannels().isEmpty)

        let me = try XCTUnwrap(model.profiles.first { $0.id == Profile.defaultID })
        model.switchProfile(to: me)
        XCTAssertEqual(model.favoriteChannels().count, 1)
    }

    func testParentalPINLocksAdultCategories() async throws {
        let model = try await modelWithPlaylist()
        let adult = try XCTUnwrap(model.content.liveCategories.first { $0.looksAdult })
        XCTAssertTrue(model.isLockConfigured(adult), "adult categories are locked automatically")
        XCTAssertFalse(model.isLocked(adult), "no PIN yet: nothing is enforced")

        model.setParentalPIN("1234")
        XCTAssertTrue(model.isLocked(adult))
        XCTAssertTrue(model.excludedCategoryIDs.contains(adult.id))
        XCTAssertFalse(model.search("late").channels.contains { $0.categoryID == adult.id })

        XCTAssertFalse(model.verifyParentalPIN("0000"))
        XCTAssertTrue(model.verifyParentalPIN("1234"))
        XCTAssertFalse(model.isLocked(adult))
    }

    func testKidsProfileHidesAdultCategories() async throws {
        let model = try await modelWithPlaylist()
        let kid = model.addProfile(name: "Kid", colorIndex: 2, symbol: "face.smiling", isKids: true, pin: nil)
        model.switchProfile(to: kid)
        XCTAssertFalse(model.categories(for: .live).contains { $0.looksAdult })
        XCTAssertEqual(model.channels(in: nil).count, 2)
    }

    func testCustomLists() async throws {
        let model = try await modelWithPlaylist()
        let channel = try XCTUnwrap(model.content.channels.first)
        let list = model.createList(named: "Evening")
        model.toggle(.channel(channel), inList: list)
        let stored = try XCTUnwrap(model.library.customLists.first)
        XCTAssertEqual(model.items(in: stored).map(\.title), [channel.name])
        model.deleteList(list)
        XCTAssertTrue(model.library.customLists.isEmpty)
    }

    func testRemindersAreScheduledAndFollowTheLeadTime() async throws {
        let model = try await modelWithPlaylist()
        let channel = try XCTUnwrap(model.content.channels.first { $0.name == "Test News" })
        let now = Date()
        let program = EPGProgram(channelID: "news.test", title: "Late Edition", start: now.addingTimeInterval(3600), end: now.addingTimeInterval(5400))

        await model.toggleReminder(for: program, on: channel, now: now)
        XCTAssertTrue(model.hasReminder(for: program, on: channel))
        let id = try XCTUnwrap(model.reminderID(for: program, on: channel))
        XCTAssertEqual(notifications.scheduled[id], program.start.addingTimeInterval(-120), "default lead time is 2 minutes")

        model.setReminderLeadMinutes(10)
        await model.syncReminders(now: now)
        XCTAssertEqual(notifications.scheduled[id], program.start.addingTimeInterval(-600))

        // Past programmes cannot get a reminder.
        let past = EPGProgram(channelID: "news.test", title: "Morning", start: now.addingTimeInterval(-7200), end: now.addingTimeInterval(-3600))
        await model.toggleReminder(for: past, on: channel, now: now)
        XCTAssertFalse(model.hasReminder(for: past, on: channel))

        await model.toggleReminder(for: program, on: channel, now: now)
        XCTAssertFalse(model.hasReminder(for: program, on: channel))
        XCTAssertNil(notifications.scheduled[id])
    }

    func testReminderWithoutNotificationPermissionIsKeptAndExplained() async throws {
        notifications.isAuthorized = false
        let model = try await modelWithPlaylist()
        let channel = try XCTUnwrap(model.content.channels.first)
        let now = Date()
        let program = EPGProgram(channelID: "news.test", title: "Quiz", start: now.addingTimeInterval(600), end: now.addingTimeInterval(1800))
        await model.toggleReminder(for: program, on: channel, now: now)
        XCTAssertTrue(model.hasReminder(for: program, on: channel), "visible in the app even without notifications")
        XCTAssertNotNil(model.bannerMessage)
        XCTAssertEqual(model.upcomingReminders.map(\.title), ["Quiz"])
    }

    private func waitUntil(timeout: TimeInterval = 10, _ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() && Date() < deadline {
            try? await Task.sleep(for: .milliseconds(50))
        }
    }

    /// A playlist whose guide (local XMLTV file) only matches "Test News"; "other.guide" matches nothing.
    private func modelWithGuide(now: Date) async throws -> AppModel {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMddHHmmss Z"
        func stamp(_ offset: TimeInterval) -> String { formatter.string(from: now.addingTimeInterval(offset)) }
        let guide = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
        <channel id="news.test"><display-name>Test News</display-name></channel>
        <channel id="other.guide"><display-name>Cooking Channel</display-name></channel>
        <programme start="\(stamp(-600))" stop="\(stamp(1200))" channel="news.test"><title>Evening Bulletin</title></programme>
        <programme start="\(stamp(3600))" stop="\(stamp(5400))" channel="other.guide"><title>Cooking Show</title></programme>
        </tv>
        """
        let guideURL = root.appendingPathComponent("guide.xml")
        try Data(guide.utf8).write(to: guideURL)
        let text = Self.playlistText.replacingOccurrences(of: "#EXTM3U", with: "#EXTM3U url-tvg=\"\(guideURL.absoluteString)\"")
        let url = root.appendingPathComponent("guided.m3u")
        try Data(text.utf8).write(to: url)

        let model = makeModel()
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Guided", source: .remote(url), autoRefreshHours: 0))
        await waitUntil { !model.epg.isEmpty }
        return model
    }

    func testManualGuideMatchingAndProgrammeSearch() async throws {
        let now = Date()
        let model = try await modelWithGuide(now: now)
        let news = try XCTUnwrap(model.content.channels.first { $0.name == "Test News" })
        let sport = try XCTUnwrap(model.content.channels.first { $0.name == "Test Sport" })

        XCTAssertEqual(model.guideMatch(for: news), .automatic("news.test"))
        XCTAssertEqual(model.searchPrograms("bulletin", now: now).map(\.channel.id), [news.id])
        XCTAssertNil(model.epg.current(for: sport, at: now.addingTimeInterval(4000)))
        XCTAssertTrue(model.epg.guideChannelOptions().contains { $0.id == "other.guide" }, "unmatched guide channels can be picked")

        // Picking a guide channel the loader skipped reloads the guide with its programmes.
        await model.setGuideMatch("other.guide", for: sport)
        XCTAssertEqual(model.guideMatch(for: sport), .manual("other.guide"))
        await waitUntil { model.epg.current(for: sport, at: now.addingTimeInterval(4000)) != nil }
        XCTAssertEqual(model.epg.current(for: sport, at: now.addingTimeInterval(4000))?.title, "Cooking Show")
        XCTAssertEqual(model.searchPrograms("cooking", now: now).first?.channel.id, sport.id)

        // Turning the guide off, then back to automatic.
        await model.setGuideMatch("", for: news)
        XCTAssertEqual(model.guideMatch(for: news), .none)
        XCTAssertNil(model.epg.current(for: news, at: now))
        await model.setGuideMatch(nil, for: news)
        XCTAssertEqual(model.epg.current(for: news, at: now)?.title, "Evening Bulletin")

        // The choice is saved with the playlist.
        let relaunched = makeModel()
        await relaunched.bootstrap()
        XCTAssertEqual(relaunched.activePlaylist?.epgChannelOverrides[sport.id], "other.guide")
    }

    func testHidingRenamingAndRenumberingChannels() async throws {
        let model = try await modelWithPlaylist()
        let sport = try XCTUnwrap(model.content.channels.first { $0.name == "Test Sport" })
        model.toggleFavorite(sport)

        model.setHidden(true, channel: sport)
        XCTAssertFalse(model.channels(in: nil).contains { $0.id == sport.id })
        XCTAssertTrue(model.search("sport").channels.isEmpty)
        XCTAssertTrue(model.favoriteChannels().isEmpty)
        XCTAssertEqual(model.hiddenChannels().map(\.id), [sport.id])
        model.setHidden(false, channel: sport)
        XCTAssertTrue(model.channels(in: nil).contains { $0.id == sport.id })

        await model.setEdit(ChannelEdit(name: "  Arena  ", number: 42), for: sport)
        let renamed = try XCTUnwrap(model.index.channelsByID[sport.id])
        XCTAssertEqual(renamed.name, "Arena")
        XCTAssertEqual(renamed.number, 42)
        XCTAssertEqual(model.search("arena").channels.map(\.id), [sport.id])
        XCTAssertEqual(model.originalName(of: renamed), "Test Sport")

        // Saved with the playlist: a relaunch shows the new name.
        let relaunched = makeModel()
        await relaunched.bootstrap()
        await waitUntil { !relaunched.content.isEmpty }
        XCTAssertEqual(relaunched.index.channelsByID[sport.id]?.name, "Arena")

        // Empty fields restore the provider's values.
        await model.setEdit(ChannelEdit(name: "", number: nil), for: renamed)
        XCTAssertEqual(model.index.channelsByID[sport.id]?.name, "Test Sport")
        XCTAssertNil(model.index.channelsByID[sport.id]?.number)
        XCTAssertTrue(model.activePlaylist?.channelEdits.isEmpty ?? false)
    }

    func testDeletingThePlaylistCleansUp() async throws {
        let model = try await modelWithPlaylist()
        let playlist = try XCTUnwrap(model.activePlaylist)
        model.toggleFavorite(try XCTUnwrap(model.content.channels.first))
        await model.deletePlaylist(playlist)
        XCTAssertTrue(model.playlists.isEmpty)
        XCTAssertTrue(model.content.isEmpty)
        XCTAssertTrue(model.library.favorites.isEmpty)
    }
}

final class CastabilityTests: XCTestCase {
    private func item(_ urls: [String], headers: HTTPHeaders = .none, live: Bool = true) -> PlaybackItem {
        PlaybackItem(
            reference: MediaReference(playlistID: UUID(), type: live ? .live : .movie, itemID: "x"),
            title: "T",
            streamURL: URL(string: urls[0])!,
            fallbackURLs: urls.dropFirst().map { URL(string: $0)! },
            headers: headers,
            isLive: live
        )
    }

    func testPrefersHLSOverTransportStream() {
        let result = CastManager.castability(of: item(["http://h/live/u/p/1.ts", "https://h/live/u/p/1.m3u8"]))
        XCTAssertEqual(result, .ok(URL(string: "https://h/live/u/p/1.m3u8")!))
    }

    func testRawTransportStreamIsNotCastable() {
        guard case .impossible = CastManager.castability(of: item(["http://h/x.ts"])) else { return XCTFail("expected impossible") }
    }

    func testHeadersAndPlainHTTPProduceWarnings() {
        guard case .warning = CastManager.castability(of: item(["https://h/a.m3u8"], headers: HTTPHeaders(userAgent: "UA"))) else {
            return XCTFail("headers should warn")
        }
        guard case .warning = CastManager.castability(of: item(["http://h/a.m3u8"])) else { return XCTFail("http hls should warn") }
        XCTAssertEqual(CastManager.castability(of: item(["http://h/movie.mp4"], live: false)), .ok(URL(string: "http://h/movie.mp4")!))
    }

    func testDownloadedFilesAreNotCastable() {
        guard case .impossible = CastManager.castability(of: item(["file:///tmp/movie.mp4"], live: false)) else { return XCTFail("file URLs cannot be cast") }
    }
}
