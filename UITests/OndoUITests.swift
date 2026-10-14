import XCTest

/// UI smoke tests run by CI on the iPhone simulator. Screenshots are kept as attachments
/// and published by CI so every change can be reviewed visually.
final class OndoUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = true
        app = XCUIApplication()
    }

    private func launch(_ arguments: [String]) {
        app.launchArguments = arguments + ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
    }

    private func snapshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func closePlayer() {
        let close = app.buttons["playerClose"]
        for _ in 0..<3 where !(close.exists && close.isHittable) {
            app.tap()
            _ = close.waitForExistence(timeout: 2)
        }
        if close.exists { close.tap() }
    }

    /// First visible guide cell ("Title, 4:30 PM – 5:00 PM") starting at least two minutes from now.
    private func upcomingProgrammeCell() -> XCUIElement? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "h:mm a"
        let calendar = Calendar.current
        let now = Date()
        let cells = app.buttons.matching(NSPredicate(format: "label CONTAINS ' – '")).allElementsBoundByIndex
        // `isHittable` fails hard on cells scrolled out of view: keep cells whose centre is on screen.
        let screen = app.windows.firstMatch.frame
        for cell in cells where cell.frame.midX > screen.minX + 8 && cell.frame.midX < screen.maxX - 8 {
            let label = cell.label.replacingOccurrences(of: "\u{202F}", with: " ")
            guard let dash = label.range(of: " – "), let comma = label[..<dash.lowerBound].range(of: ", ", options: .backwards),
                  let time = formatter.date(from: String(label[comma.upperBound..<dash.lowerBound])) else { continue }
            let parts = calendar.dateComponents([.hour, .minute], from: time)
            guard var start = calendar.date(bySettingHour: parts.hour ?? 0, minute: parts.minute ?? 0, second: 0, of: now) else { continue }
            if start < now.addingTimeInterval(-12 * 3600) { start = start.addingTimeInterval(86_400) }
            if start > now.addingTimeInterval(120) { return cell }
        }
        return nil
    }

    /// What a user does first, end to end: add an M3U playlist by its URL through the form. The playlist
    /// is served over plain HTTP by a server in the test runner (same simulator) and its new release must
    /// show on the home screen.
    @MainActor
    func testAddingAPlaylistByURL() async throws {
        let year = Calendar.current.component(.year, from: .now)
        let playlist = """
        #EXTM3U
        #EXTINF:-1 tvg-id="harbor.news" group-title="News",Harbor News
        http://127.0.0.1:9/news.m3u8
        #EXTINF:5400 group-title="Movies | Drama",Quiet Water (\(year))
        http://127.0.0.1:9/quiet.mp4
        """
        let server = try LocalHTTPServer { request in
            request.path == "/list.m3u" ? .text(playlist, type: "audio/x-mpegurl") : .notFound
        }
        let address = try await server.start().appending(path: "list.m3u").absoluteString

        launch(["-uitest-reset"])
        let add = app.buttons["addPlaylistButton"]
        XCTAssertTrue(add.waitForExistence(timeout: 15))
        add.tap()
        app.segmentedControls.buttons["M3U URL"].tap()
        let name = app.textFields["playlistName"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        type("Home", into: name)
        let url = app.descendants(matching: .any).matching(identifier: "playlistURL").firstMatch
        type(address, into: url)
        snapshot("08-add-playlist")
        app.buttons["confirmAddPlaylist"].tap()

        let title = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'Quiet Water'")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 20), "the playlist's new release shows on the home screen")
        snapshot("09-playlist-added")
        XCTAssertEqual(server.requests.first?.path, "/list.m3u")
    }

    /// Taps a field until it has the keyboard focus, then types: the form redraws after the source type changes
    /// and a tap made meanwhile can be lost ("Neither element nor any descendant has keyboard focus").
    private func type(_ text: String, into field: XCUIElement) {
        let focused = NSPredicate(format: "hasKeyboardFocus == true")
        for _ in 0..<3 {
            field.tap()
            let expectation = XCTNSPredicateExpectation(predicate: focused, object: field)
            if XCTWaiter.wait(for: [expectation], timeout: 2) == .completed { break }
        }
        field.typeText(text)
    }

    func testWelcomeScreen() {
        launch(["-uitest-reset"])
        XCTAssertTrue(app.buttons["demoButton"].waitForExistence(timeout: 15))
        snapshot("01-welcome")
    }

    func testBrowseGuideAndPlay() throws {
        launch(["-uitest-reset", "-uitest-fixture"])

        let liveTab = app.tabBars.buttons["Live TV"]
        XCTAssertTrue(liveTab.waitForExistence(timeout: 30), "main interface")
        XCTAssertTrue(app.buttons["heroPlay"].waitForExistence(timeout: 15), "home carousel")
        sleep(3)
        snapshot("02-home")

        liveTab.tap()
        let all = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'All'")).firstMatch
        XCTAssertTrue(all.waitForExistence(timeout: 15), "categories")
        snapshot("03-live-categories")
        all.tap()

        let firstChannel = app.collectionViews.cells.element(boundBy: 0)
        XCTAssertTrue(firstChannel.waitForExistence(timeout: 15), "channel list")
        sleep(2)
        snapshot("04-channels")
        firstChannel.tap()
        sleep(8)
        snapshot("05-player")
        let close = app.buttons["playerClose"]
        if !(close.exists && close.isHittable) { app.tap(); sleep(1) }
        snapshot("06-player-controls")

        // Restart the programme from the archive (fixture news channels have catch-up), then go back live.
        let restart = app.buttons["restartProgram"]
        if restart.waitForExistence(timeout: 3) {
            restart.tap()
            sleep(1)
            snapshot("06b-player-replay")
            let backToLive = app.buttons["backToLive"]
            XCTAssertTrue(backToLive.waitForExistence(timeout: 5), "replay mode")
            if !backToLive.isHittable { app.tap() }
            if backToLive.waitForExistence(timeout: 2) { backToLive.tap() }
        } else {
            XCTFail("restart programme action")
        }

        // In-player guide.
        let guideButton = app.buttons["playerGuide"]
        if !(guideButton.exists && guideButton.isHittable) { app.tap() }
        if guideButton.waitForExistence(timeout: 3) {
            guideButton.tap()
            sleep(1)
            snapshot("06c-player-guide")
            let panelClose = app.buttons["panelClose"]
            if panelClose.exists { panelClose.tap() }
        } else {
            XCTFail("in-player guide button")
        }
        closePlayer()

        // The guide is in the Live TV toolbar: back from the channel list to the categories.
        let categories = app.navigationBars.buttons["Live TV"]
        if categories.waitForExistence(timeout: 5) { categories.tap() }
        let guide = app.buttons["TV Guide"].firstMatch
        XCTAssertTrue(guide.waitForExistence(timeout: 5), "guide link")
        guide.tap()
        sleep(3)
        snapshot("07-guide")

        // An upcoming programme: its sheet offers a reminder (in-memory notifications in UI tests). At some
        // times of day every next programme is long enough to start off screen: scroll the grid forward.
        var upcoming = upcomingProgrammeCell()
        for _ in 0..<3 where upcoming == nil {
            // Slowly, and let the grid stop: a cell found while it still glides is not where the tap lands.
            app.swipeLeft(velocity: .slow)
            sleep(2)
            upcoming = upcomingProgrammeCell()
        }
        if let later = upcoming {
            later.tap()
            let remind = app.buttons["reminderButton"]
            if remind.waitForExistence(timeout: 5) {
                remind.tap()
                XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Reminder Set'")).firstMatch.waitForExistence(timeout: 5), "reminder set")
            } else {
                XCTFail("programme sheet with a reminder button")
            }
            snapshot("07b-program-reminder")
            let closeSheet = app.buttons["Close"].firstMatch
            if closeSheet.exists { closeSheet.tap() }
            sleep(1)
        } else {
            XCTFail("upcoming programme in the guide grid")
        }
        app.navigationBars.buttons.element(boundBy: 0).tap()

        app.tabBars.buttons["Movies"].tap()
        let allMovies = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'All'")).firstMatch
        XCTAssertTrue(allMovies.waitForExistence(timeout: 10), "all movies")
        allMovies.tap()
        sleep(2)
        snapshot("08-movies")
        let poster = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Northern Lights'")).firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 5), "movie poster")
        poster.tap()
        XCTAssertTrue(app.navigationBars["Northern Lights"].waitForExistence(timeout: 5), "movie detail on top")
        sleep(1)
        snapshot("09-movie-detail")

        app.tabBars.buttons["Series"].tap()
        let allSeries = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'All'")).firstMatch
        XCTAssertTrue(allSeries.waitForExistence(timeout: 10), "all series")
        allSeries.tap()
        let show = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Harbor Lights'")).firstMatch
        XCTAssertTrue(show.waitForExistence(timeout: 5), "series poster")
        show.tap()
        XCTAssertTrue(app.navigationBars["Harbor Lights"].waitForExistence(timeout: 5), "series detail on top")
        sleep(1)
        snapshot("10-series-detail")

        app.tabBars.buttons["Search"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "search field")
        field.tap()
        field.typeText("news")
        sleep(2)
        snapshot("11-search")
        // The keyboard covers the tab bar: cancel the search (iOS 18) or submit it (iOS 26 has no Cancel).
        let cancel = app.buttons["Cancel"]
        if cancel.exists { cancel.tap() } else { field.typeText("\n") }
        sleep(1)

        app.tabBars.buttons["Home"].tap()
        sleep(1)
        snapshot("12-home-after-watching")
        let settings = app.buttons["Settings"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 5), "settings button")
        settings.tap()
        sleep(1)
        snapshot("13-settings")
    }
}
