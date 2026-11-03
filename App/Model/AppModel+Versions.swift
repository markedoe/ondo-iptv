import AVFoundation
import Foundation
import IPTVCore
import VideoToolbox
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// A film's versions (4K, Dolby Vision, HD, VOSTFR…): grouped once per catalogue in `ContentIndex` (`FilmVersions`,
/// IPTVCore), each film once in the lists, its versions as badges on its page. Favourites, history and progress stay per
/// stream; a film's state comes from its versions. "Group versions" off (Settings): each stream as the playlist lists it.
extension AppModel {
    private static let noVersions = FilmVersions.none()

    /// The versions of the active catalogue's films, or each stream alone when Settings' "Group versions" is off.
    var films: FilmVersions { settings.groupVersions ? index.films : Self.noVersions }

    /// The versions of `movie`'s film the viewer can see (`movie` itself, and the others not in a hidden, locked or kids'
    /// category), in catalogue order.
    func visibleVersions(of movie: Movie) -> [Movie] {
        let versions = films.versions(of: movie)
        guard versions.count > 1 else { return versions }
        let excluded = exclusions
        // A stream a panel lists twice (the same id) is one version.
        var seen = Set<String>()
        return versions.filter { ($0.id == movie.id || !excluded.contains($0)) && seen.insert($0.id).inserted }
    }

    /// The version a film's card plays and its page selects first (`FilmVersions.defaultVersion`): the one in progress,
    /// else the one chosen last on this device, else the best for the screen, else the first.
    func defaultVersion(of movie: Movie, among known: [Movie]? = nil) -> Movie {
        let versions = known ?? visibleVersions(of: movie)
        guard versions.count > 1 else { return movie }
        let inProgress = versions.compactMap { version -> (movie: Movie, watched: Date)? in
            guard let entry = progress(movie: version), entry.isResumable else { return nil }
            return (version, entry.lastWatchedAt)
        }.max { $0.watched < $1.watched }?.movie
        let names = categoryNames
        return FilmVersions.defaultVersion(
            versions, inProgress: inProgress?.id, chosen: settings.chosenVersions, support: Self.pictureSupport,
            categoryName: { names[$0] }
        ) ?? movie
    }

    /// The badges of a film's versions, in their order (`FilmVersions.badges`).
    func versionBadges(_ versions: [Movie]) -> [VersionBadge] {
        let names = categoryNames
        return FilmVersions.badges(versions) { names[$0] }
    }

    private var categoryNames: [String: String] {
        Dictionary(content.movieCategories.map { ($0.id, $0.name) }, uniquingKeysWith: { first, _ in first })
    }

    /// `items` with each film once: the first of its versions stands for it; series stay as they are.
    func distinctFilms(_ items: [ShowcaseItem]) -> [ShowcaseItem] {
        let films = self.films
        guard films.isGrouped, items.count > 1 else { return items }
        var seen = Set<Int>()
        return items.filter { item in
            guard case .movie(let movie) = item, let group = films.group(of: movie) else { return true }
            return seen.insert(group).inserted
        }
    }

    /// What a Continue Watching card says of its stream when its film has several versions: its badge ("4K DV", else
    /// "Version" with its number); nil for a film of one version, a series or a channel. Continue Watching stays per stream.
    func versionLabel(for entry: HistoryEntry) -> String? {
        guard entry.reference.type == .movie, let movie = index.moviesByID[entry.reference.itemID] else { return nil }
        let versions = visibleVersions(of: movie)
        guard versions.count > 1, let index = versions.firstIndex(where: { $0.id == movie.id }) else { return nil }
        return VersionChips.label(versionBadges(versions)[index])
    }

    /// Whether a film is in My List: when one of its versions is.
    func isFavoriteFilm(_ movie: Movie) -> Bool {
        visibleVersions(of: movie).contains { isFavorite(.movie, id: $0.id) }
    }

    /// My List for a film: out of it, every version kept goes; into it, `version` (the one its page selects), else the
    /// film's default version. Each stream is kept as before.
    func toggleFavoriteFilm(_ movie: Movie, version: Movie? = nil) {
        let versions = visibleVersions(of: movie)
        let kept = versions.filter { isFavorite(.movie, id: $0.id) }
        if kept.isEmpty {
            toggleFavorite(version ?? defaultVersion(of: movie, among: versions))
        } else {
            for stream in kept { toggleFavorite(stream) }
        }
    }

    /// A film's card: its default version plays.
    func playFilm(_ movie: Movie) {
        play(defaultVersion(of: movie))
    }

    /// A version picked on a film's page, remembered on this device only (settings are not synced): its card and page
    /// start from it.
    func rememberVersion(_ movie: Movie) {
        updateSettings { settings in
            guard settings.chosenVersions.first != movie.id else { return }
            settings.chosenVersions = Array(([movie.id] + settings.chosenVersions.filter { $0 != movie.id }).prefix(AppSettings.maxChosenVersions))
        }
    }

    /// What the screen shows, for the version of a film that plays best: HDR (AVFoundation), Dolby Vision (a hardware
    /// decoder for it on an HDR screen), and a 4K screen.
    static let pictureSupport: PictureSupport = {
        let hdr = AVPlayer.eligibleForHDRPlayback
        let dolbyVision = hdr && VTIsHardwareDecodeSupported(kCMVideoCodecType_DolbyVisionHEVC)
        #if canImport(UIKit)
        let size = UIScreen.main.nativeBounds.size
        #elseif canImport(AppKit)
        let size = NSScreen.main.map { $0.convertRectToBacking($0.frame).size } ?? .zero
        #else
        let size = CGSize.zero
        #endif
        return PictureSupport(hdr: hdr, dolbyVision: dolbyVision, uhd: max(size.width, size.height) >= 3840)
    }()
}
