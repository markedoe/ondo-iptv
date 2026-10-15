import Foundation
import IPTVCore
import Observation

/// Backdrops found on TMDb (with the viewer's key) for the titles whose provider gave none that loads, and the
/// provider addresses that led nowhere during this session.
@MainActor
@Observable
final class BackdropStore {
    var fromTMDb: [String: URL] = [:]
    var dead: Set<URL> = []
    @ObservationIgnored var searched: Set<String> = []
}

extension AppModel {
    /// Wide artwork: the provider's backdrop unless it led nowhere, else the one found on TMDb, else the trending one.
    func backdropURL(for item: ShowcaseItem) -> URL? {
        if let url = cachedDetails(for: item)?.backdropURL, !backdrops.dead.contains(url) { return url }
        if let url = backdrops.fromTMDb[item.id] { return url }
        let trendingURL = trending.first { match in
            switch (match, item) {
            case (.movie(let a, _), .movie(let b)): return a.id == b.id
            case (.series(let a, _), .series(let b)): return a.id == b.id
            default: return false
            }
        }?.trending.backdropURL
        return trendingURL.flatMap { backdrops.dead.contains($0) ? nil : $0 }
    }

    /// A backdrop that did not load (dead address, refused, not an image): the title shows TMDb's instead when TMDb
    /// is on, else its blurred poster.
    func backdropFailed(_ url: URL, for item: ShowcaseItem) {
        guard backdrops.dead.insert(url).inserted else { return }
        Task { await loadTMDbBackdropIfNeeded(for: item) }
    }

    /// With TMDb on, looks for the backdrop of a title that has none that loads, once a session: by the
    /// TMDb id the provider gave, else by title and year. Only the title, its year and its TMDb id are sent.
    func loadTMDbBackdropIfNeeded(for item: ShowcaseItem) async {
        guard usesTMDb, backdropURL(for: item) == nil, !backdrops.searched.contains(item.id),
              let credential = tmdbCredential() else { return }
        backdrops.searched.insert(item.id)
        let details = cachedDetails(for: item)
        let title = details.map(\.title).flatMap { $0.isEmpty ? nil : $0 } ?? item.title
        let year = item.year ?? details?.releaseDate.flatMap { Int($0.prefix(4)) }
        let client = TMDbClient(credential: credential, http: http, language: Locale.preferredLanguages.first ?? "en-US")
        guard let found = try? await client.backdropURL(of: item.contentType, tmdbID: details?.tmdbID, title: title, year: year)
        else { return }
        backdrops.fromTMDb[item.id] = found
    }
}
