#if TESTFLIGHT_DOWNLOADS
import SwiftUI

/// The "Beta" capsule of the TestFlight edition, for its testers. The App Store edition is built without it: betas
/// belong on TestFlight, not on the App Store (App Review Guideline 2.2, checked by check_app_store.py).
struct BetaBadge: View {
    var body: some View {
        Text("Beta")
            .font(.caption2.weight(.bold))
            .textCase(.uppercase)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .foregroundStyle(.white)
            .background(Color.orange, in: Capsule())
            .fixedSize()
            .accessibilityIdentifier("betaBadge")
    }

    /// The version as Settings shows it: "1.1.0 · Beta".
    static func version(_ version: String) -> String {
        "\(version) · \(String(localized: "Beta"))"
    }
}
#endif
