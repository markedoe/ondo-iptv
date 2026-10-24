import XCTest
import IPTVCore
import Observation
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

    private func makeModel(reminders: ReminderScheduling? = nil) -> AppModel {
        AppModel(storageRoot: root, secrets: secrets, reminderScheduler: reminders ?? notifications)
    }

    private func playlistFile() throws -> URL {
        let url = root.appendingPathComponent("list.m3u")
        try Data(Self.playlistText.utf8).write(to: url)
        return url
    }

    private func modelWithPlaylist(reminders: ReminderScheduling? = nil) async throws -> AppModel {
        let model = makeModel(reminders: reminders)
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

    func testAnEditedAddressIsCheckedBeforeItIsSaved() async throws {
        let model = try await modelWithPlaylist()
        let original = try XCTUnwrap(model.activePlaylist)
        // An address that answers nothing: the edit is refused and the playlist keeps its working address.
        var broken = original
        broken.source = .remote(root.appendingPathComponent("missing.m3u"))
        do {
            try await model.saveEditedPlaylist(broken)
            XCTFail("A playlist that cannot be loaded must not be saved")
        } catch {}
        XCTAssertEqual(model.playlists.first?.source, original.source)
        let relaunched = makeModel()
        await relaunched.bootstrap()
        XCTAssertEqual(relaunched.playlists.first?.source, original.source)

        // A working new address is saved, and its catalogue shown.
        let other = root.appendingPathComponent("other.m3u")
        try Data("#EXTM3U\n#EXTINF:-1 group-title=\"News\",Other News\nhttp://127.0.0.1:9/other.m3u8\n".utf8).write(to: other)
        var moved = original
        moved.source = .remote(other)
        try await model.saveEditedPlaylist(moved)
        XCTAssertEqual(model.playlists.first?.source, .remote(other))
        XCTAssertEqual(model.content.channels.map(\.name), ["Other News"])
    }

    func testSecretsLeaveThePlaylistFileWhenTheStoreWorks() async throws {
        let model = makeModel()
        await model.bootstrap()
        let credentials = XtreamCredentials(server: URL(string: "http://127.0.0.1:9")!, username: "user", password: "s3cret")
        let backup = BackupArchive(playlists: [Playlist(name: "X", source: .xtream(credentials))], library: UserLibrary())
        try await model.importBackup(backup.encoded())

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
        let credentials = XtreamCredentials(server: URL(string: "http://127.0.0.1:9")!, username: "user", password: "TOKEN-123")
        let backup = BackupArchive(playlists: [Playlist(name: "X", source: .xtream(credentials))], library: UserLibrary())
        try await model.importBackup(backup.encoded())

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

    /// A reminder, a download or the last channel at launch can reach a locked channel without its list: playing it
    /// still asks for the PIN.
    func testPlayingALockedChannelWaitsForThePIN() async throws {
        let model = try await modelWithPlaylist()
        model.setParentalPIN("1234")
        let adult = try XCTUnwrap(model.content.channels.first { $0.name == "Late Night" })
        model.play(adult)
        XCTAssertNil(model.playbackRequest)
        let pending = try XCTUnwrap(model.pendingLockedPlayback)

        model.resumeLockedPlayback(pending)
        XCTAssertNil(model.playbackRequest, "still locked: nothing plays")
        XCTAssertTrue(model.verifyParentalPIN("1234"))
        model.resumeLockedPlayback(pending)
        XCTAssertNotNil(model.playbackRequest)
        XCTAssertNil(model.pendingLockedPlayback)
        model.playbackRequest.map(model.endSession)

        let news = try XCTUnwrap(model.content.channels.first { $0.name == "Test News" })
        model.lockParentalControl()
        model.play(news)
        XCTAssertNotNil(model.playbackRequest, "an open category plays at once")
        model.playbackRequest.map(model.endSession)
    }

    func testWrongPINsMakeTheNextAttemptWait() async throws {
        let model = makeModel()
        await model.bootstrap()
        model.setParentalPIN("1234")
        let start = Date(timeIntervalSince1970: 1_800_000_000)

        for attempt in 1...4 {
            XCTAssertFalse(model.verifyParentalPIN("0000", now: start))
            XCTAssertNil(model.pinRetryDate(now: start), "attempt \(attempt) does not wait yet")
        }
        XCTAssertFalse(model.verifyParentalPIN("0000", now: start))
        XCTAssertEqual(model.pinRetryDate(now: start), start.addingTimeInterval(30))
        XCTAssertFalse(model.verifyParentalPIN("1234", now: start.addingTimeInterval(10)), "even the right PIN waits")
        XCTAssertFalse(model.isParentalUnlocked)

        // Relaunching the app does not reset the count.
        let relaunched = makeModel()
        await relaunched.bootstrap()
        XCTAssertNotNil(relaunched.pinRetryDate(now: start.addingTimeInterval(10)))
        XCTAssertFalse(relaunched.verifyParentalPIN("0000", now: start.addingTimeInterval(31)))
        XCTAssertEqual(relaunched.pinRetryDate(now: start.addingTimeInterval(31)), start.addingTimeInterval(31 + 60), "the wait doubles")

        XCTAssertTrue(relaunched.verifyParentalPIN("1234", now: start.addingTimeInterval(200)))
        XCTAssertNil(relaunched.pinRetryDate(now: start.addingTimeInterval(200)))
        XCTAssertFalse(relaunched.verifyParentalPIN("0000", now: start.addingTimeInterval(200)))
        XCTAssertNil(relaunched.pinRetryDate(now: start.addingTimeInterval(200)), "a right PIN starts the count again")
    }

    func testPINWaitsDoubleUpToAnHour() {
        XCTAssertNil(AppModel.pinDelay(afterFailures: 4))
        XCTAssertEqual(AppModel.pinDelay(afterFailures: 5), 30)
        XCTAssertEqual(AppModel.pinDelay(afterFailures: 6), 60)
        XCTAssertEqual(AppModel.pinDelay(afterFailures: 12), 3600)
        XCTAssertEqual(AppModel.pinDelay(afterFailures: 1000), 3600)
    }

    func testProfilePINsWaitAfterWrongAttemptsToo() async throws {
        let model = makeModel()
        await model.bootstrap()
        let guarded = model.addProfile(name: "Parent", colorIndex: 1, symbol: "star.fill", isKids: false, pin: "4321")
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        for _ in 1...5 { XCTAssertFalse(model.verifyPIN("0000", for: guarded, now: now)) }
        XCTAssertFalse(model.verifyPIN("4321", for: guarded, now: now))
        XCTAssertTrue(model.verifyPIN("4321", for: guarded, now: now.addingTimeInterval(31)))
    }

    func testSettingsThatCouldSwitchParentalControlOffNeedThePIN() async throws {
        let model = makeModel()
        await model.bootstrap()
        XCTAssertFalse(model.settingsNeedParentalUnlock, "no PIN: nothing to protect")

        model.setParentalPIN("1234")
        XCTAssertTrue(model.settingsNeedParentalUnlock)
        XCTAssertTrue(model.verifyParentalPIN("1234"))
        XCTAssertFalse(model.settingsNeedParentalUnlock)
        model.lockParentalControl()
        XCTAssertTrue(model.settingsNeedParentalUnlock)
    }

    func testAKidsProfileCannotUnlockItselfWithoutAPIN() async throws {
        let model = makeModel()
        await model.bootstrap()
        let kid = model.addProfile(name: "Kid", colorIndex: 2, symbol: "face.smiling", isKids: true, pin: nil)
        model.switchProfile(to: kid)
        XCTAssertTrue(model.settingsNeedParentalUnlock, "a parent's profile exists: the kid switches to it, or stays")
        XCTAssertFalse(model.verifyParentalPIN("1234"), "without a PIN, no PIN unlocks")
        XCTAssertTrue(model.isParentalControlActive)

        // A single kids profile and no PIN: nobody could ever leave it, so its settings stay open.
        model.deleteProfile(try XCTUnwrap(model.profiles.first { $0.id != kid.id }))
        XCTAssertEqual(model.profiles.map(\.id), [kid.id])
        XCTAssertFalse(model.settingsNeedParentalUnlock)
    }

    func testABackupBringsBackLocksHiddenChannelsAndReminders() async throws {
        let model = try await modelWithPlaylist()
        let sports = try XCTUnwrap(model.content.liveCategories.first { $0.name == "Sports" })
        model.setLocked(true, category: sports)
        let news = try XCTUnwrap(model.content.channels.first { $0.name == "Test News" })
        let sport = try XCTUnwrap(model.content.channels.first { $0.name == "Test Sport" })
        model.setHidden(true, channel: sport)
        let now = Date()
        let program = EPGProgram(channelID: "news.test", title: "Late Edition", start: now.addingTimeInterval(3600), end: now.addingTimeInterval(5400))
        await model.toggleReminder(for: program, on: news, now: now)
        let backup = try model.exportBackup()

        // Another device.
        let otherRoot = root.appendingPathComponent("OtherDevice", isDirectory: true)
        try FileManager.default.createDirectory(at: otherRoot, withIntermediateDirectories: true)
        let otherNotifications = InMemoryReminderScheduler()
        let other = AppModel(storageRoot: otherRoot, secrets: InMemorySecretStore(), reminderScheduler: otherNotifications)
        await other.bootstrap()
        try await other.importBackup(backup)

        XCTAssertTrue(other.isLockConfigured(sports), "locks are kept for the device, outside the library")
        XCTAssertTrue(other.isHidden(try XCTUnwrap(other.content.channels.first { $0.name == "Test Sport" })))
        let restoredNews = try XCTUnwrap(other.content.channels.first { $0.name == "Test News" })
        XCTAssertTrue(other.hasReminder(for: program, on: restoredNews))
        await other.syncReminders(now: now)
        XCTAssertEqual(otherNotifications.scheduled.count, 1, "restored reminders notify")
    }

    func testReorderingFavoritesMovesTheItemShownNotTheOneStoredAtThatPlace() async throws {
        let model = try await modelWithPlaylist()
        let channels = model.content.channels
        let news = try XCTUnwrap(channels.first { $0.name == "Test News" })
        let sport = try XCTUnwrap(channels.first { $0.name == "Test Sport" })
        let late = try XCTUnwrap(channels.first { $0.name == "Late Night" })
        for channel in [late, news, sport] { model.toggleFavorite(channel) }
        model.setParentalPIN("1234")  // the adult category locks: "Late Night" is no longer shown
        XCTAssertEqual(model.favorites(.live).map(\.title), ["Test News", "Test Sport"])

        model.moveFavorites(.live, fromOffsets: [1], toOffset: 0)  // drag "Test Sport" above "Test News"

        XCTAssertEqual(model.favorites(.live).map(\.title), ["Test Sport", "Test News"])
    }

    func testRestoredFileNamesStayInTheImportFolder() {
        let folder = AppPaths.importedPlaylists.standardizedFileURL
        for name in ["..", ".", "", "/", "../Ondo", "../../Library", "a/../../b.m3u"] {
            let url = AppPaths.importedPlaylist(name).standardizedFileURL
            XCTAssertEqual(url.deletingLastPathComponent(), folder, name)
            XCTAssertNotEqual(url, folder, name)
        }
        XCTAssertEqual(AppPaths.importedPlaylist("list.m3u").lastPathComponent, "list.m3u")
    }

    func testDownloadsStayOutOfDeviceBackups() throws {
        let values = try DownloadManager.directory.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
    }

    /// The hero and the continue-watching cards show cached details: when the details arrive after them
    /// (Xtream info calls), they must redraw, or they keep the blurred poster and no plot.
    func testDetailsThatArriveRedrawTheScreensShowingThem() async throws {
        let model = try await modelWithPlaylist()
        let series = Series(id: "harbor", name: "Harbor", categoryID: "c")
        let item = ShowcaseItem.series(series)
        let redrawn = Flag()
        withObservationTracking { _ = model.cachedDetails(for: item) } onChange: { redrawn.set() }
        _ = try await model.details(for: series)
        XCTAssertTrue(redrawn.value)
        XCTAssertEqual(model.cachedDetails(for: item)?.title, "Harbor")
    }

    func testSubtitleKeysGoToTheKeychainAndEnableTheirService() async throws {
        let model = makeModel()
        await model.bootstrap()
        XCTAssertEqual(model.subtitleServices, [.addic7ed], "no key yet: only the service that needs none")

        model.setSubtitleCredential("  key-example-0001 \n", for: .openSubtitlesKey)
        model.setSubtitleCredential("key-example-0002", for: .subSourceKey)
        XCTAssertEqual(secrets.values[SubtitleCredential.openSubtitlesKey.rawValue], "key-example-0001", "trimmed, kept with the secrets")
        XCTAssertEqual(model.subtitleServices, [.openSubtitles, .subSource, .addic7ed])

        model.setSubtitleCredential("", for: .subSourceKey)
        model.setAddic7edSubtitles(false)
        XCTAssertNil(secrets.values[SubtitleCredential.subSourceKey.rawValue], "an empty field removes the key")
        XCTAssertEqual(model.subtitleServices, [.openSubtitles])
        let saved = try String(contentsOf: root.appendingPathComponent("Support/settings.json"), encoding: .utf8)
        XCTAssertFalse(saved.contains("key-example"), "keys never reach the settings file")
    }

    func testTheSubtitleSearchStartsFromWhatIsPlaying() async throws {
        let model = try await modelWithPlaylist()
        let movie = try XCTUnwrap(model.content.movies.first)
        let item = PlaybackItem(reference: MediaReference(playlistID: UUID(), type: .movie, itemID: movie.id), title: "Quiet Water",
                                streamURL: movie.streamURL, isLive: false)
        let query = model.subtitleQuery(for: item)
        XCTAssertEqual(query.title, "Quiet Water")
        XCTAssertEqual(query.year, 2021)
        XCTAssertNil(query.season)

        let episode = PlaybackItem(reference: MediaReference(playlistID: UUID(), type: .series, itemID: "unknown", episodeID: "e"),
                                   title: "Harbor Lights", subtitle: "S02E05 · Tide", streamURL: movie.streamURL, isLive: false)
        let episodeQuery = model.subtitleQuery(for: episode)
        XCTAssertEqual(episodeQuery.title, "Harbor Lights")
        XCTAssertEqual(episodeQuery.season, 2)
        XCTAssertEqual(episodeQuery.episode, 5)
    }

    func testSubtitleLanguagesFollowTheLastSearchThenThePreferences() {
        XCTAssertEqual(AppModel.subtitleLanguages(saved: ["es"], preferred: "fr", device: ["fr-FR"]), ["es"])
        XCTAssertEqual(AppModel.subtitleLanguages(saved: [], preferred: nil, device: ["fr-FR", "en-GB"]), ["fr", "en"])
        XCTAssertEqual(AppModel.subtitleLanguages(saved: [], preferred: "de", device: ["fr-FR"]), ["de", "fr"])
        XCTAssertEqual(AppModel.subtitleLanguages(saved: [], preferred: nil, device: ["en-US"]), ["en"])
        XCTAssertNil(AppModel.episodeNumbers(in: "Arrival"))
        XCTAssertEqual(AppModel.episodeNumbers(in: "s1e12 · Storm").map { [$0.season, $0.episode] }, [1, 12])
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

    /// Changing the lead time syncs in the background; removing the reminder right after syncs again. When
    /// the first sync is the slower one, it must not bring the removed reminder back.
    func testARemovedReminderStaysRemovedWhenAnEarlierSyncFinishesLast() async throws {
        let slow = SlowReminderScheduler()
        let model = try await modelWithPlaylist(reminders: slow)
        let channel = try XCTUnwrap(model.content.channels.first { $0.name == "Test News" })
        let now = Date()
        let program = EPGProgram(channelID: "news.test", title: "Late Edition", start: now.addingTimeInterval(3600), end: now.addingTimeInterval(5400))
        await model.toggleReminder(for: program, on: channel, now: now)
        XCTAssertEqual(slow.scheduled.count, 1)

        let before = slow.startedSyncs
        model.setReminderLeadMinutes(10)
        await waitUntil { slow.startedSyncs > before }  // the background sync has read the reminder and is scheduling
        XCTAssertGreaterThan(slow.startedSyncs, before, "the lead time change started its sync")
        await model.toggleReminder(for: program, on: channel, now: now)
        try await Task.sleep(for: .milliseconds(500))  // longer than the slow sync
        XCTAssertTrue(slow.scheduled.isEmpty, "a notification is left for a removed reminder: \(slow.scheduled)")
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
        <channel id="other.guide"><display-name>Kitchen Guide</display-name></channel>
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

    /// A playlist deleted while its catalogue downloads again leaves no catalogue on disk: Clear Cache only knows the
    /// playlists there are, so one written after the deletion would stay for good.
    func testAPlaylistDeletedWhileItRefreshesLeavesNoCatalogue() async throws {
        let held = HeldAnswers()
        defer { held.release() }
        let text = Self.playlistText
        let server = try LocalHTTPServer { _ in
            held.waitUnlessFirst()
            return LocalHTTPServer.Response.text(text)
        }
        let address = try await server.start().appending(path: "list.m3u")
        let model = makeModel()
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(address), autoRefreshHours: 0))
        let playlist = try XCTUnwrap(model.activePlaylist)
        let catalogue = model.cacheStore.url(for: "content-\(playlist.id.uuidString)")
        XCTAssertTrue(FileManager.default.fileExists(atPath: catalogue.path))

        let refresh = Task { await model.refreshActivePlaylist() }
        let deadline = Date().addingTimeInterval(10)
        while server.requests.count < 2 {
            guard Date() < deadline else { return XCTFail("the refresh never asked for the playlist") }
            try await Task.sleep(for: .milliseconds(20))
        }
        await model.deletePlaylist(playlist)
        XCTAssertFalse(FileManager.default.fileExists(atPath: catalogue.path))
        held.release()
        await refresh.value
        XCTAssertFalse(FileManager.default.fileExists(atPath: catalogue.path), "the deleted playlist's catalogue is written again")
        XCTAssertTrue(model.playlists.isEmpty)
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

/// Takes longer to schedule notifications than to cancel them all, like a busy notification centre.
private final class SlowReminderScheduler: ReminderScheduling, @unchecked Sendable {
    private let lock = NSLock()
    private var pending: [String: Date] = [:]
    private var started = 0

    var scheduled: [String: Date] { lock.withLock { pending } }
    /// Syncs with reminders that have started (each then takes 300 ms).
    var startedSyncs: Int { lock.withLock { started } }

    func requestAuthorization() async -> Bool { true }

    func reschedule(_ reminders: [ProgramReminder], leadTime: TimeInterval, now: Date) async {
        if !reminders.isEmpty {
            lock.withLock { started += 1 }
            try? await Task.sleep(for: .milliseconds(300))
        }
        let wanted = Dictionary(reminders.map { ($0.id, $0.start.addingTimeInterval(-leadTime)) }, uniquingKeysWith: { first, _ in first })
        lock.withLock { pending = wanted }
    }
}

/// Lets the first answer of a server go at once and holds the next ones until the test releases them.
private final class HeldAnswers: @unchecked Sendable {
    private let lock = NSLock()
    private var answered = 0
    private let gate = DispatchSemaphore(value: 0)

    /// On the server's queue.
    func waitUnlessFirst() {
        let first = lock.withLock { () -> Bool in
            answered += 1
            return answered == 1
        }
        if !first { gate.wait() }
    }

    func release() { gate.signal() }
}

/// Set from an observation callback, which may run on any thread.
private final class Flag: @unchecked Sendable {
    private let lock = NSLock()
    private var isSet = false
    var value: Bool { lock.withLock { isSet } }
    func set() { lock.withLock { isSet = true } }
}
