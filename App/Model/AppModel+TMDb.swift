import Foundation

/// Which credential TMDb calls use: the viewer's own key when they entered one, else Ondo's token, and none when
/// TMDb is turned off in Settings.
extension AppModel {
    /// Ondo's TMDb read access token, written into the app's Info.plist by fastlane from the TMDB_READ_TOKEN secret
    /// (never in the repository). Nil in builds made without it: Settings then asks for a key.
    static let builtInTMDbToken: String? = {
        let value = (Bundle.main.object(forInfoDictionaryKey: "OndoTMDbReadToken") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.isEmpty || value.hasPrefix("$(") ? nil : value
    }()

    /// TMDb is on and there is something to call it with.
    var usesTMDb: Bool {
        settings.useTMDb && (hasTMDbKey || Self.builtInTMDbToken != nil)
    }

    func tmdbCredential() -> String? {
        guard settings.useTMDb else { return nil }
        return secrets.get(Keychain.tmdbKey) ?? Self.builtInTMDbToken
    }
}
