import XCTest

/// On the Mac, the credits of a movie page open their pages: the director, the genre and the release year each lead
/// to the titles of the playlist sharing them (text links once showed there but opened nothing). Run by the
/// "App Store screenshots" workflow with the Mac screenshots.
final class MacCreditLinksTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = true
    }

    func testCreditsOpenTheirPages() {
        let year = String(Calendar.current.component(.year, from: .now))
        for (value, page) in [("Maren Holt", "Maren Holt"), ("Drama", "Drama"), (year, "Released in \(year)")] {
            let app = XCUIApplication()
            app.launchArguments = ["-uitest-reset", "YES", "-uitest-fixture", "YES",
                                   "-uitest-route", "movie:Northern Lights", "-uitest-window", "1",
                                   "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
            app.launch()
            let window = app.windows.firstMatch
            if !window.waitForExistence(timeout: 30) {
                // The app may start without its main window: open one as a user would (File > New Window).
                app.activate()
                app.typeKey("n", modifierFlags: .command)
            }
            let link = app.buttons["credit-\(value)"].firstMatch
            guard link.waitForExistence(timeout: 30) else {
                XCTFail("\(value): no link on the movie page")
                attachHierarchy(of: app, value)
                app.terminate()
                continue
            }
            link.click()
            // The window takes the title of the page it shows.
            let opened = XCTNSPredicateExpectation(predicate: NSPredicate(format: "title == %@", page), object: window)
            if XCTWaiter.wait(for: [opened], timeout: 10) != .completed {
                XCTFail("\(value): the window shows \"\(window.title)\", not \"\(page)\"")
                attachHierarchy(of: app, value)
            }
            app.terminate()
        }
    }

    /// What XCTest saw, printed in the workflow log by `collect_appstore_screenshots.py` (no image: any other
    /// picture of this test target would be taken for a store screenshot).
    private func attachHierarchy(of app: XCUIApplication, _ value: String) {
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "App UI hierarchy (credit \(value))"
        hierarchy.lifetime = .keepAlways
        add(hierarchy)
    }
}
