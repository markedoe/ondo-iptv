import XCTest

/// Captures the Mac App Store screenshots (run by the "App Store screenshots" workflow only).
/// Each page is opened at launch with `-uitest-route` on the demo catalogue; `-uitest-window` gives the
/// window a 16:10 frame. The language comes from `SCREENSHOT_LANG` (set with `TEST_RUNNER_SCREENSHOT_LANG`).
final class MacAppStoreScreenshots: XCTestCase {
    private let pages = [
        ("01-home", "home"),
        ("02-movie", "movie:Northern Lights"),
        ("03-series", "series:Harbor Lights"),
        ("04-movies", "movies"),
        ("05-channels", "live"),
        ("06-tv-guide", "guide"),
    ]

    override func setUp() {
        continueAfterFailure = true
    }

    func testScreenshots() {
        let language = ProcessInfo.processInfo.environment["SCREENSHOT_LANG"] ?? "en"
        let locale = language == "fr" ? "fr_FR" : "en_US"
        for (name, route) in pages {
            let app = XCUIApplication()
            app.launchArguments = ["-uitest-reset", "-uitest-fixture", "-uitest-showcase",
                                   "-uitest-route", route, "-uitest-window", "1",
                                   "-AppleLanguages", "(\(language))", "-AppleLocale", locale]
            app.launch()
            let window = app.windows.firstMatch
            guard window.waitForExistence(timeout: 30) else {
                XCTFail("\(name): no window")
                add(XCTAttachment(screenshot: XCUIScreen.main.screenshot()))
                app.terminate()
                continue
            }
            sleep(12) // catalogue, artwork and guide
            let attachment = XCTAttachment(screenshot: window.screenshot())
            attachment.name = name
            attachment.lifetime = .keepAlways
            add(attachment)
            app.terminate()
        }
    }
}
