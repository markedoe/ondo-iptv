import XCTest

/// Captures the App Store screenshots (run by the "App Store screenshots" workflow only).
/// The language comes from `SCREENSHOT_LANG` (set with `TEST_RUNNER_SCREENSHOT_LANG`).
final class AppStoreScreenshots: XCTestCase {
    private var app: XCUIApplication!
    private var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    override func setUp() {
        continueAfterFailure = true
        app = XCUIApplication()
        if isPad { XCUIDevice.shared.orientation = .landscapeLeft }
    }

    private func launch(_ extra: [String] = []) {
        let language = ProcessInfo.processInfo.environment["SCREENSHOT_LANG"] ?? "en"
        let locale = language == "fr" ? "fr_FR" : "en_US"
        app.launchArguments = ["-uitest-reset", "-uitest-fixture", "-uitest-showcase"] + extra
            + ["-AppleLanguages", "(\(language))", "-AppleLocale", locale]
        app.launch()
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Tab (iPhone: Home, Movies, Series, Live TV, Search) or sidebar row (iPad: Home, Movies, Series,
    /// Live TV, TV Guide, …) by index.
    private func open(section index: Int, sidebarIndex: Int) {
        if isPad {
            app.collectionViews.firstMatch.cells.element(boundBy: sidebarIndex).tap()
        } else {
            app.tabBars.buttons.element(boundBy: index).tap()
        }
        sleep(2)
    }

    private func openAllCategory() {
        let all = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'All' OR label BEGINSWITH 'Tout'")).firstMatch
        if all.waitForExistence(timeout: 10) { all.tap() }
        sleep(2)
    }

    /// Order of the listing: movies and series first, live TV after.
    func testScreenshots() {
        launch()
        let navigation = isPad ? app.collectionViews.firstMatch : app.tabBars.firstMatch
        XCTAssertTrue(navigation.waitForExistence(timeout: 30), "main interface")
        XCTAssertTrue(app.buttons["heroPlay"].waitForExistence(timeout: 15), "home carousel")
        sleep(6)
        capture("01-home")

        // A movie page.
        open(section: 1, sidebarIndex: 1)
        openAllCategory()
        let movie = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Northern Lights'")).firstMatch
        if movie.waitForExistence(timeout: 10) {
            movie.tap()
            sleep(3)
        }
        capture("02-movie")

        // A series with its seasons and episodes.
        open(section: 2, sidebarIndex: 2)
        openAllCategory()
        let show = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Harbor Lights'")).firstMatch
        if show.waitForExistence(timeout: 10) {
            show.tap()
            sleep(3)
            capture("03-series")
        }

        // Live TV: channels with what is on now.
        open(section: 3, sidebarIndex: 3)
        openAllCategory()
        capture("04-channels")

        // Player with its guide: restart, replay, reminders.
        let firstChannel = app.staticTexts["Ondo News"].firstMatch
        if firstChannel.waitForExistence(timeout: 10) {
            firstChannel.tap()
            sleep(6)
            let guide = app.buttons["playerGuide"]
            if guide.waitForExistence(timeout: 5) {
                guide.tap()
                sleep(2)
            }
            capture("05-player-guide")
            let close = app.buttons["panelClose"]
            if close.exists { close.tap() }
            app.buttons["playerClose"].tap()
            sleep(2)
        }

        // TV guide grid (iPhone: from the Live TV toolbar).
        if isPad {
            open(section: 0, sidebarIndex: 4)
        } else {
            let back = app.navigationBars.buttons.element(boundBy: 0)
            if back.exists { back.tap() }
            let guideLink = app.buttons.matching(NSPredicate(format: "label == 'TV Guide' OR label == 'Guide TV'")).firstMatch
            if guideLink.waitForExistence(timeout: 5) { guideLink.tap() }
        }
        sleep(3)
        capture("06-tv-guide")
    }

    func testMultiviewScreenshot() throws {
        try XCTSkipUnless(isPad, "multiview is an iPad / Mac feature")
        launch(["-uitest-multiview"])
        XCTAssertTrue(app.buttons["multiviewClose"].waitForExistence(timeout: 30))
        sleep(8)
        capture("07-multiview")
    }
}
