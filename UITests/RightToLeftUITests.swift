import XCTest

/// Arabic, right to left: the app's screens are mirrored, and the TV guide's grid keeps its timeline running left to
/// right with the channel column at the left edge while the grid scrolls; a tap there plays the channel. Screenshots
/// are published by CI with the others.
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

    func testTheGuideKeepsItsTimelineInArabic() {
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

        // Later programmes are to the right: the grid scrolls under the column, which stays at the left edge.
        app.swipeLeft(velocity: .slow)
        sleep(2)
        snapshot("07c-guide-ar-scrolled")
        app.windows.firstMatch.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: 40, dy: channel.frame.midY))
            .tap()
        XCTAssertTrue(app.buttons["playerClose"].waitForExistence(timeout: 10), "player from the guide's channel column")
    }
}
