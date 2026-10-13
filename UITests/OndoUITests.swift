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

        // An upcoming programme: its sheet offers a reminder (in-memory notifications in UI tests).
        if let later = upcomingProgrammeCell() {
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
        if allMovies.waitForExistence(timeout: 10) {
            allMovies.tap()
            sleep(2)
            snapshot("08-movies")
            let poster = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Northern Lights'")).firstMatch
            if poster.waitForExistence(timeout: 5) {
                poster.tap()
                XCTAssertTrue(app.navigationBars["Northern Lights"].waitForExistence(timeout: 5), "movie detail on top")
                sleep(1)
                snapshot("09-movie-detail")
            }
        }

        app.tabBars.buttons["Series"].tap()
        let allSeries = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'All'")).firstMatch
        if allSeries.waitForExistence(timeout: 10) {
            allSeries.tap()
            let show = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Harbor Lights'")).firstMatch
            if show.waitForExistence(timeout: 5) {
                show.tap()
                XCTAssertTrue(app.navigationBars["Harbor Lights"].waitForExistence(timeout: 5), "series detail on top")
                sleep(1)
                snapshot("10-series-detail")
            }
        }

        app.tabBars.buttons["Search"].tap()
        let field = app.searchFields.firstMatch
        if field.waitForExistence(timeout: 10) {
            field.tap()
            field.typeText("news")
            sleep(2)
            snapshot("11-search")
            // The keyboard covers the tab bar.
            let cancel = app.buttons["Cancel"]
            if cancel.exists { cancel.tap() }
        }

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
