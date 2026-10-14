import SwiftUI
import UniformTypeIdentifiers

#if os(iOS)
import UIKit
typealias PlatformImage = UIImage
#elseif os(macOS)
import AppKit
typealias PlatformImage = NSImage
#endif

extension Image {
    init(platformImage: PlatformImage) {
        #if os(iOS)
        self.init(uiImage: platformImage)
        #else
        self.init(nsImage: platformImage)
        #endif
    }
}

enum Platform {
    static var isMac: Bool {
        #if os(macOS)
        return true
        #else
        return false
        #endif
    }

    static var isPad: Bool {
        #if os(iOS)
        return UIDevice.current.userInterfaceIdiom == .pad
        #else
        return false
        #endif
    }
}

extension Color {
    /// Window / screen background, used where artwork fades into the page.
    static var appBackground: Color {
        #if os(iOS)
        Color(uiColor: .systemBackground)
        #else
        Color(nsColor: .windowBackgroundColor)
        #endif
    }
}

extension View {
    /// `navigationBarTitleDisplayMode(.inline)` on iOS, no-op on macOS.
    @ViewBuilder
    func inlineNavigationTitle() -> some View {
        #if os(iOS)
        navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }

    /// Hides the status bar and home indicator while the player is shown (iOS).
    @ViewBuilder
    func immersive() -> some View {
        #if os(iOS)
        statusBarHidden(true).persistentSystemOverlays(.hidden)
        #else
        self
        #endif
    }
}

/// Copies text to the system pasteboard.
enum Pasteboard {
    /// Copies text that may hold provider logins (stream addresses do): it stays on this device (no
    /// Universal Clipboard), expires after ten minutes on iPhone and iPad, and is marked concealed on the Mac
    /// so clipboard history tools skip it.
    static func copy(_ string: String) {
        #if os(iOS)
        UIPasteboard.general.setItems([[UTType.plainText.identifier: string]],
                                      options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(600)])
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(string, forType: .string)
        NSPasteboard.general.setString("", forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
        #endif
    }
}
