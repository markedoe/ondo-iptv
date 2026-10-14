#if DEBUG
import SwiftUI
import IPTVCore

/// Mac App Store screenshots (`MacUITests/MacAppStoreScreenshots.swift`): `-uitest-route` opens a page
/// (`home`, `movies`, `series`, `live`, `guide`, `movie:<name>`, `series:<name>`) and `-uitest-window`
/// gives the main window a 16:10 frame at the top left of the screen.
enum ScreenshotRoute {
    enum Detail {
        case movie(String)
        case series(String)
    }

    private static let route = UserDefaults.standard.string(forKey: "uitest-route")
    static let sizesWindow = UserDefaults.standard.string(forKey: "uitest-window") != nil

    static var section: AppSection? {
        guard let route else { return nil }
        if route.hasPrefix("movie:") { return .movies }
        if route.hasPrefix("series:") { return .series }
        return AppSection(rawValue: route)
    }

    static var detail: Detail? {
        guard let route, let colon = route.firstIndex(of: ":") else { return nil }
        let name = String(route[route.index(after: colon)...])
        return route.hasPrefix("movie:") ? .movie(name) : .series(name)
    }
}

/// Pushes the movie or series named by `-uitest-route` once the catalogue is loaded.
struct ScreenshotRouteOpener: ViewModifier {
    @Environment(AppModel.self) private var model
    @Environment(NavigationRouter.self) private var router: NavigationRouter?
    @State private var opened = false

    func body(content: Content) -> some View {
        content.task(id: model.content.movies.count + model.content.series.count) {
            guard !opened, let detail = ScreenshotRoute.detail else { return }
            switch detail {
            case .movie(let name):
                guard let movie = model.content.movies.first(where: { $0.name == name }) else { return }
                router?.push(movie)
            case .series(let name):
                guard let series = model.content.series.first(where: { $0.name == name }) else { return }
                router?.push(series)
            }
            opened = true
        }
    }
}

#if os(macOS)
import AppKit

/// Gives the window the largest 16:10 frame that fits the screen and prints where it is.
struct ScreenshotWindowSizer: NSViewRepresentable {
    /// Acts once the view is in its window (it is created before).
    final class Probe: NSView {
        private var placed = false

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard let window, !placed else { return }
            placed = true
            NSLog("Screenshot window probe in %@", window.description)
            // Resized once SwiftUI has finished setting the window up, and again once its content has settled.
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(1))
                ScreenshotWindowSizer.place(window)
                try? await Task.sleep(for: .seconds(2))
                ScreenshotWindowSizer.place(window)
            }
        }
    }

    func makeNSView(context: Context) -> NSView { Probe() }

    func updateNSView(_ nsView: NSView, context: Context) {}

    static func place(_ window: NSWindow) {
        guard let screen = window.screen ?? NSScreen.main else { return }
        let visible = screen.visibleFrame
        let sizes = [CGSize(width: 1440, height: 900), CGSize(width: 1280, height: 800),
                     CGSize(width: 1152, height: 720), CGSize(width: 1024, height: 640)]
        let size = sizes.first { $0.width <= visible.width && $0.height <= visible.height } ?? sizes[sizes.count - 1]
        window.setFrame(NSRect(x: visible.minX, y: visible.maxY - size.height, width: size.width, height: size.height), display: true)
    }
}
#endif
#endif
