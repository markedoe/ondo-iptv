import Foundation
import IPTVCore
import Observation

/// The backdrop addresses that led nowhere: those of this session, and those of the last days kept in the metadata
/// store (`dead_image`), so a broken provider picture is not tried again at each launch.
@MainActor
@Observable
final class BackdropStore {
    var dead: Set<URL> = []
}

extension AppModel {
    /// Wide artwork: the provider's backdrop unless it led nowhere, else the one found on TMDb, else the trending one.
    func backdropURL(for item: ShowcaseItem) -> URL? {
        if let url = cachedDetails(for: item)?.backdropURL, !backdrops.dead.contains(url) { return url }
        if let url = tmdbTitle(for: item)?.backdropURL, !backdrops.dead.contains(url) { return url }
        let trendingURL = trending.first { match in
            switch (match, item) {
            case (.movie(let a, _), .movie(let b)): return a.id == b.id
            case (.series(let a, _), .series(let b)): return a.id == b.id
            default: return false
            }
        }?.trending.backdropURL
        return trendingURL.flatMap { backdrops.dead.contains($0) ? nil : $0 }
    }

    /// A backdrop that did not load: the title shows TMDb's instead when a key is set, else its blurred poster. An
    /// address that answered with an error, or with something that is not a picture, is remembered for some days
    /// (`MetadataFreshness.deadImage`); one the network could not reach (offline, a timeout) only for this session.
    func backdropFailed(_ url: URL, for item: ShowcaseItem, permanently: Bool = true) {
        guard backdrops.dead.insert(url).inserted else { return }
        if permanently {
            let store = metadataStore
            let now = Date()
            Task.detached(priority: .utility) { store.markDeadImage(url, at: now) }
        }
        Task { await loadTMDbMetadata(for: item) }
    }
}
