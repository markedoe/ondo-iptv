import Foundation
import IPTVCore

extension AwardsCatalogue {
    /// The awards bundled with the app (`awards.json`, from Wikidata), read once.
    static let bundled: AwardsCatalogue = {
        guard let url = Bundle.main.url(forResource: "awards", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let catalogue = try? AwardsCatalogue(data: data) else { return .empty }
        return catalogue
    }()

    /// The catalogue in use: the bundled one, or a newer download (see `AwardsUpdate`).
    @MainActor static var current: AwardsCatalogue = .bundled

    /// Festivals, where taking part is being "in competition"; elsewhere a film is "nominated".
    static let festivals: Set<String> = ["cannes", "venice", "berlin", "san-sebastian", "locarno"]
}

/// Newer awards data: the "Awards data" workflow publishes it once a month (from Wikidata) on the project's
/// `awards-data` branch; the app looks for it once a week, unless turned off in Settings. Nothing about the viewer
/// is sent.
enum AwardsUpdate {
    static let url = URL(string: "https://raw.githubusercontent.com/markedoe/ondo-iptv/awards-data/awards.json")!
    static let interval: TimeInterval = 7 * 24 * 3600
    /// The file is about half a megabyte: anything far larger is not it.
    static let maxSize = 8 << 20
    /// A file with fewer films is a partial answer.
    static let minFilms = 1_000
}

/// The festivals and ceremonies, as the search filter names them.
enum AwardGroup {
    static func name(_ group: String) -> String {
        switch group {
        case "cannes": return String(localized: "Cannes Film Festival")
        case "venice": return String(localized: "Venice Film Festival")
        case "berlin": return String(localized: "Berlin Film Festival")
        case "oscars": return String(localized: "Academy Awards")
        case "cesars": return String(localized: "César Awards")
        case "baftas": return String(localized: "BAFTA Awards")
        case "golden-globes": return String(localized: "Golden Globes")
        case "goyas": return String(localized: "Goya Awards")
        case "european-film-awards": return String(localized: "European Film Awards")
        case "san-sebastian": return String(localized: "San Sebastián Film Festival")
        case "locarno": return String(localized: "Locarno Film Festival")
        default: return group
        }
    }
}

/// An award of a movie, ready to show.
struct MovieAward: Identifiable, Hashable {
    let id: String
    /// "palme-dor": opens the movies of the playlist with this award.
    let awardID: String
    let name: String
    let year: Int
    let won: Bool
    let isFestival: Bool
}

extension AppModel {
    /// Once a week, unless turned off in Settings: a newer awards file replaces the one in use, and is kept in the
    /// caches so that the next launch starts from it.
    func updateAwardsIfNeeded(from url: URL = AwardsUpdate.url, now: Date = Date()) async {
        loadDownloadedAwards()
        guard settings.updateAwards else { return }
        if let checked = settings.awardsCheckedAt, now.timeIntervalSince(checked) < AwardsUpdate.interval { return }
        updateSettings { $0.awardsCheckedAt = now }
        guard let data = try? await http.data(from: url, headers: [:]), data.count <= AwardsUpdate.maxSize,
              let catalogue = try? AwardsCatalogue(data: data), catalogue.films.count >= AwardsUpdate.minFilms,
              catalogue.isNewer(than: AwardsCatalogue.current) else { return }
        try? data.write(to: downloadedAwardsURL, options: .atomic)
        use(catalogue)
    }

    /// The last download, when it is newer than the catalogue in use.
    func loadDownloadedAwards() {
        guard let data = try? Data(contentsOf: downloadedAwardsURL), let catalogue = try? AwardsCatalogue(data: data),
              catalogue.isNewer(than: AwardsCatalogue.current) else { return }
        use(catalogue)
    }

    private var downloadedAwardsURL: URL {
        cacheStore.directory.appendingPathComponent("awards.json")
    }

    /// Uses this catalogue and matches the playlist's movies against it again.
    private func use(_ catalogue: AwardsCatalogue) {
        AwardsCatalogue.current = catalogue
        let movies = content.movies
        let playlistID = activePlaylist?.id
        awardsTask?.cancel()
        awardsTask = Task {
            let awards = await Task.detached { catalogue.match(movies) }.value
            guard !Task.isCancelled, activePlaylist?.id == playlistID else { return }
            movieAwards = awards
        }
    }

    /// The app's language as the awards file names it ("fr", "zh-Hans").
    private var awardsLanguage: String {
        Bundle.main.preferredLocalizations.first ?? "en"
    }

    /// Awards and festival selections of a movie, wins first then the most recent.
    func awards(for movie: Movie) -> [MovieAward] {
        let catalogue = AwardsCatalogue.current
        let language = awardsLanguage
        return (movieAwards[movie.id] ?? []).compactMap { entry in
            guard let award = catalogue.award(entry.awardID) else { return nil }
            return MovieAward(
                id: "\(entry.awardID)-\(entry.year)",
                awardID: entry.awardID,
                name: award.name(language: language),
                year: entry.year,
                won: entry.won,
                isFestival: AwardsCatalogue.festivals.contains(award.group)
            )
        }
    }

    /// Movies of the playlist that won an award, the most recent award first.
    func awardWinners(limit: Int = 20) -> [Movie] {
        let excluded = excludedCategoryIDs
        let winners = movieAwards.compactMap { id, entries -> (Movie, Int)? in
            guard let win = entries.first(where: \.won), let movie = index.moviesByID[id], !excluded.contains(movie.categoryID) else { return nil }
            return (movie, win.year)
        }
        return winners.sorted { ($0.1, $0.0.name) > ($1.1, $1.0.name) }.prefix(limit).map(\.0)
    }

    /// The festivals and ceremonies some movie of the playlist took part in (the search filter's choices).
    func awardGroups() -> [String] {
        let catalogue = AwardsCatalogue.current
        var present = Set<String>()
        for entries in movieAwards.values {
            for entry in entries {
                if let group = catalogue.award(entry.awardID)?.group { present.insert(group) }
            }
        }
        return catalogue.groups.filter(present.contains)
    }

    /// Whether a movie won (or, unless `winnersOnly`, took part in) an award of this festival or ceremony.
    func movie(_ movie: Movie, hasAwardIn group: String, winnersOnly: Bool) -> Bool {
        let catalogue = AwardsCatalogue.current
        return (movieAwards[movie.id] ?? []).contains { entry in
            (!winnersOnly || entry.won) && catalogue.award(entry.awardID)?.group == group
        }
    }
}
