#if os(macOS)
import AppKit
import SwiftUI
import Observation

/// The window a Mac player is shown in, and whether it is full screen. Full screen acts on this window,
/// not on whichever window is key (several players can be open, and the key window can be another one).
@MainActor
@Observable
final class PlayerWindowState {
    private(set) var isFullScreen = false
    @ObservationIgnored private weak var window: NSWindow?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    func attach(_ window: NSWindow?) {
        guard window !== self.window else { return }
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers = []
        self.window = window
        isFullScreen = window?.styleMask.contains(.fullScreen) == true
        guard let window else { return }
        for name in [NSWindow.didEnterFullScreenNotification, NSWindow.didExitFullScreenNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: window, queue: .main) { [weak self] note in
                let fullScreen = (note.object as? NSWindow)?.styleMask.contains(.fullScreen) == true
                MainActor.assumeIsolated { self?.isFullScreen = fullScreen }
            })
        }
    }

    func toggleFullScreen() {
        (window ?? NSApp.keyWindow)?.toggleFullScreen(nil)
    }
}

/// Hands the hosting `NSWindow` of a SwiftUI view to `onWindow` (nil when the view leaves its window).
struct WindowReader: NSViewRepresentable {
    let onWindow: (NSWindow?) -> Void

    func makeNSView(context: Context) -> WindowReportingView {
        WindowReportingView(onWindow: onWindow)
    }

    func updateNSView(_ nsView: WindowReportingView, context: Context) {}
}

final class WindowReportingView: NSView {
    private let onWindow: (NSWindow?) -> Void

    init(onWindow: @escaping (NSWindow?) -> Void) {
        self.onWindow = onWindow
        super.init(frame: .zero)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        onWindow(window)
    }
}
#endif
