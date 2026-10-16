import SwiftUI

#if os(iOS)
import UIKit

/// Lets the player force landscape on iPhone ("rotation per content type") and, in the TestFlight edition, hands the
/// events of finished background downloads to `DownloadManager`.
final class AppDelegate: NSObject, UIApplicationDelegate {
    static var orientationLock: UIInterfaceOrientationMask = .all

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        Self.orientationLock
    }

    #if TESTFLIGHT_DOWNLOADS || DEBUG
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        guard identifier == DownloadManager.sessionIdentifier else { return completionHandler() }
        DownloadManager.shared.backgroundCompletionHandler = completionHandler
    }
    #endif
}

@MainActor
enum OrientationController {
    static func lockLandscape() {
        apply(.landscape, request: .landscapeRight)
    }

    static func unlock() {
        let backToPortrait = UIDevice.current.orientation.isPortrait || UIDevice.current.orientation == .unknown
        apply(.all, request: backToPortrait ? .portrait : nil)
    }

    private static func apply(_ mask: UIInterfaceOrientationMask, request: UIInterfaceOrientationMask?) {
        AppDelegate.orientationLock = mask
        for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
            for window in scene.windows {
                var controller = window.rootViewController
                while let current = controller {
                    current.setNeedsUpdateOfSupportedInterfaceOrientations()
                    controller = current.presentedViewController
                }
            }
            if let request {
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: request)) { _ in }
            }
        }
    }

    static var screenBrightness: CGFloat {
        get {
            (UIApplication.shared.connectedScenes.first { $0 is UIWindowScene } as? UIWindowScene)?.screen.brightness ?? 0.5
        }
        set {
            (UIApplication.shared.connectedScenes.first { $0 is UIWindowScene } as? UIWindowScene)?.screen.brightness = min(max(newValue, 0), 1)
        }
    }
}
#endif

/// Touch layer of the player: tap shows or hides the controls, double tap seeks on the sides; in the middle (or
/// when the stream cannot seek) it toggles fill, full screen on the Mac. Horizontal drag scrubs, vertical drag
/// on the left adjusts brightness (iPhone / iPad).
struct PlayerGestureLayer: View {
    let controller: PlayerController
    var onTap: () -> Void
    var onDoubleTapCenter: () -> Void

    @State private var hud: String?
    @State private var hudIcon = "sun.max"
    @State private var dragMode: DragMode?
    @State private var dragStartValue: Double = 0
    @State private var hideHUDTask: Task<Void, Never>?

    private enum DragMode {
        case seek
        case brightness
    }

    var body: some View {
        GeometryReader { proxy in
            Color.clear
                .contentShape(Rectangle())
                .gesture(
                    SpatialTapGesture(count: 2).onEnded { value in
                        handleDoubleTap(at: value.location, width: proxy.size.width)
                    }
                    .exclusively(before: TapGesture().onEnded { onTap() })
                )
                #if os(iOS)
                .simultaneousGesture(
                    DragGesture(minimumDistance: 24)
                        .onChanged { value in handleDrag(value, size: proxy.size) }
                        .onEnded { value in endDrag(value, size: proxy.size) }
                )
                #endif
                .overlay {
                    if let hud {
                        Label(hud, systemImage: hudIcon)
                            .font(.title3.weight(.semibold).monospacedDigit())
                            .foregroundStyle(.white)
                            .padding(.horizontal, 18)
                            .padding(.vertical, 10)
                            .background(.black.opacity(0.6), in: Capsule())
                            .allowsHitTesting(false)
                    }
                }
        }
    }

    private func handleDoubleTap(at location: CGPoint, width: CGFloat) {
        guard controller.canSeek, width > 0 else {
            onDoubleTapCenter()
            return
        }
        let ratio = location.x / width
        if ratio < 0.35 {
            controller.skip(by: -10)
            flash("-10 s", icon: "gobackward.10")
        } else if ratio > 0.65 {
            controller.skip(by: 10)
            flash("+10 s", icon: "goforward.10")
        } else {
            onDoubleTapCenter()
        }
    }

    #if os(iOS)
    private func handleDrag(_ value: DragGesture.Value, size: CGSize) {
        if dragMode == nil {
            let horizontal = abs(value.translation.width) > abs(value.translation.height)
            if horizontal && controller.canSeek {
                dragMode = .seek
                dragStartValue = controller.currentTime
            } else if !horizontal && value.startLocation.x < size.width / 2 {
                dragMode = .brightness
                dragStartValue = Double(OrientationController.screenBrightness)
            } else {
                return
            }
        }
        switch dragMode {
        case .seek:
            let target = seekTarget(value, size: size)
            let delta = target - dragStartValue
            hudIcon = delta >= 0 ? "forward.fill" : "backward.fill"
            showHUD("\(Formatting.clock(target)) (\(delta >= 0 ? "+" : "-")\(Formatting.clock(abs(delta))))")
        case .brightness:
            let level = dragStartValue - Double(value.translation.height / max(size.height, 1))
            OrientationController.screenBrightness = CGFloat(level)
            hudIcon = "sun.max"
            showHUD("\(Int(min(max(level, 0), 1) * 100)) %")
        case nil:
            break
        }
    }

    private func endDrag(_ value: DragGesture.Value, size: CGSize) {
        if dragMode == .seek {
            controller.seek(to: seekTarget(value, size: size), tolerance: 1)
        }
        dragMode = nil
        scheduleHUDHide()
    }

    /// A full-width swipe moves three minutes (or the whole programme if shorter).
    private func seekTarget(_ value: DragGesture.Value, size: CGSize) -> Double {
        let span = min(180, max(controller.duration, 1))
        let delta = Double(value.translation.width / max(size.width, 1)) * span
        return min(max(dragStartValue + delta, 0), max(controller.duration - 1, 0))
    }
    #endif

    private func flash(_ text: String, icon: String) {
        hudIcon = icon
        showHUD(text)
        scheduleHUDHide()
    }

    private func showHUD(_ text: String) {
        hideHUDTask?.cancel()
        hud = text
    }

    private func scheduleHUDHide() {
        hideHUDTask?.cancel()
        hideHUDTask = Task {
            try? await Task.sleep(for: .milliseconds(900))
            guard !Task.isCancelled else { return }
            hud = nil
        }
    }
}
