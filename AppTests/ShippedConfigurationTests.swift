import XCTest
@testable import Ondo

/// Regression guards on what ships in the app bundle. Each of these keys, when it goes missing, breaks a
/// feature without any error (Cast finds no receiver, sound stops with the screen locked) or holds up
/// every TestFlight build. HTTP is covered by AppTransportSecurityTests; the privacy manifest's
/// coverage of the code by .github/scripts/check_privacy_manifest.py.
final class ShippedConfigurationTests: XCTestCase {
    private let bundle = Bundle(for: AppModel.self)

    private func info(_ key: String) -> Any? {
        bundle.object(forInfoDictionaryKey: key)
    }

    /// Without both Bonjour services (and the local network text iOS shows when asking), the Cast button
    /// never finds a receiver.
    func testChromecastDiscoveryIsDeclared() {
        let services = info("NSBonjourServices") as? [String] ?? []
        XCTAssertTrue(services.contains("_googlecast._tcp"), "\(services)")
        XCTAssertTrue(services.contains("_CC1AD845._googlecast._tcp"), "default receiver: \(services)")
        XCTAssertFalse((info("NSLocalNetworkUsageDescription") as? String ?? "").isEmpty)
    }

    /// Without its Bonjour service, sync on the local network finds no device (docs/SYNC.md), and without a word on it
    /// the local network prompt does not say why it is asked.
    func testLocalNetworkSyncIsDeclared() {
        let services = info("NSBonjourServices") as? [String] ?? []
        XCTAssertTrue(services.contains("_ondo._tcp"), "\(services)")
        XCTAssertTrue((info("NSLocalNetworkUsageDescription") as? String ?? "").localizedCaseInsensitiveContains("sync"))
    }

    /// Sound goes on with the screen locked, and picture in picture works.
    func testBackgroundAudioIsDeclared() {
        XCTAssertTrue((info("UIBackgroundModes") as? [String] ?? []).contains("audio"))
    }

    /// Without it, App Store Connect asks the export compliance question for every build before testers get it.
    func testNoExportComplianceQuestion() {
        XCTAssertEqual(info("ITSAppUsesNonExemptEncryption") as? Bool, false)
    }

    /// App Store Connect rejects an upload whose privacy manifest is missing (ITMS-91053).
    func testPrivacyManifestShipsInTheApp() throws {
        let url = try XCTUnwrap(bundle.url(forResource: "PrivacyInfo", withExtension: "xcprivacy"), "PrivacyInfo.xcprivacy not in the app")
        let manifest = try XCTUnwrap(PropertyListSerialization.propertyList(from: Data(contentsOf: url), format: nil) as? [String: Any])
        XCTAssertEqual(manifest["NSPrivacyTracking"] as? Bool, false)
        let types = (manifest["NSPrivacyAccessedAPITypes"] as? [[String: Any]] ?? []).compactMap { $0["NSPrivacyAccessedAPIType"] as? String }
        XCTAssertTrue(types.contains("NSPrivacyAccessedAPICategoryFileTimestamp"), "\(types)")
    }
}
