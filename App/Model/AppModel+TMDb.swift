import Foundation
import IPTVCore

/// What TMDb said about the token the viewer typed in Settings.
enum TMDbCheck: Equatable {
    case checking
    case accepted
    case refused
    case unreachable
}

extension AppModel {
    /// Saves the viewer's TMDb API Read Access Token (or API Key) only once TMDb has accepted it.
    func checkAndSaveTMDbCredential(_ value: String) async -> TMDbCheck {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return .refused }
        do {
            guard try await TMDbClient(credential: trimmed, http: http).isAccepted() else { return .refused }
        } catch {
            return .unreachable
        }
        setTMDbCredential(trimmed)
        return .accepted
    }
}
