import XCTest

/// Arabic, right to left: the TV guide runs from right to left, its channel column stays at the right edge while
/// the grid scrolls, and a tap there plays the channel. Screenshots are published by CI with the others.
final class RightToLeftUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments = ["-uitest-reset", "-uitest-fixture", "-AppleLanguages", "(ar)", "-AppleLocale", "ar_SA"]
        app.launch()
    }

    private func snapshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testTheGuideRunsRightToLeft() {
        // "Live TV" and "TV Guide" in Arabic.
        let liveTab = app.tabBars.buttons["البث المباشر"]
        XCTAssertTrue(liveTab.waitForExistence(timeout: 30), "main interface")
        liveTab.tap()
        let guide = app.buttons["دليل البرامج"].firstMatch
        XCTAssertTrue(guide.waitForExistence(timeout: 10), "guide link")
        guide.tap()
        let channel = app.buttons["guideChannel"].firstMatch
        XCTAssertTrue(channel.waitForExistence(timeout: 10), "channel column")
        sleep(3)
        snapshot("07-guide-ar")

        // Later programmes are to the left: the grid scrolls under the column, which stays at the right edge.
        app.swipeRight(velocity: .slow)
        sleep(2)
        snapshot("07c-guide-ar-scrolled")
        let window = app.windows.firstMatch
        window.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: window.frame.width - 40, dy: channel.frame.midY))
            .tap()
        XCTAssertTrue(app.buttons["playerClose"].waitForExistence(timeout: 10), "player from the guide's channel column")
    }
}
