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
        ("05-tv-guide", "guide"),
        ("06-channels", "live"),
    ]

    override func setUp() {
        continueAfterFailure = true
    }

    func testScreenshots() {
        let language = ProcessInfo.processInfo.environment["SCREENSHOT_LANG"] ?? "en"
        let locale = language == "fr" ? "fr_FR" : "en_US"
        for (name, route) in pages {
            let app = XCUIApplication()
            // Key / value pairs only: macOS reads `-key value`, and a leftover word (here the route) would be
            // taken for a document to open, in which case the app starts without its main window.
            app.launchArguments = ["-uitest-reset", "YES", "-uitest-fixture", "YES", "-uitest-showcase", "YES",
                                   "-uitest-route", route, "-uitest-window", "1",
                                   "-AppleLanguages", "(\(language))", "-AppleLocale", locale]
            app.launch()
            let window = app.windows.firstMatch
            if !window.waitForExistence(timeout: 30) {
                // Diagnostics: what the app shows, then a new window as a user would open one (File > New Window).
                let hierarchy = XCTAttachment(string: app.debugDescription)
                hierarchy.name = "App UI hierarchy (\(name), state \(app.state.rawValue))"
                hierarchy.lifetime = .keepAlways
                add(hierarchy)
                app.activate()
                app.typeKey("n", modifierFlags: .command)
            }
            guard window.waitForExistence(timeout: 15) else {
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
