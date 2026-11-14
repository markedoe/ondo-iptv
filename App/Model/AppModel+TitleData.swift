import Foundation
import IPTVCore

/// What the app read from the provider for the title played, shown under More, Stream Statistics: the catalogue's raw
/// names, categories and ids, the tags the version rules found, what each version's chip says and which one plays
/// first. For photographing a provider's names when a chip reads wrong (docs/VERSIONS.md). Never an address, a login, a
/// password or a server: every text goes through `Redaction`. The same data as Android's `TitleData`.
struct TitleData: Equatable {
    enum Label: Equatable {
        /// The title the player shows.
        case shown
        /// The tags the version rules read.
        case tags
        /// The provider's own name of the field ("name", "category", "tmdb"…), shown as is.
        case raw(String)
    }

    struct Line: Equatable {
        var label: Label
        var value: String
    }

    struct Version: Equatable, Identifiable {
        /// What its chip says on the film's page; nil for one in a hidden category (no chip).
        var chip: String?
        var name: String
        /// "Films 4K (12)": the category's name and id.
        var category: String
        var id: String
        /// "MKV", "MP4", "HLS"…; the provider's extension as given when the app does not know it.
        var container: String?
        /// The tags the version rules read ("uhd, dolbyVision"); "–" for none.
        var tags: String
        var playing: Bool
        /// The version the film's card plays and its page selects first.
        var first: Bool
    }

    var lines: [Line]
    var versions: [Version] = []
    /// Why the film's card plays the version marked `first`; nil for an episode or a film alone.
    var reason: DefaultReason?

    /// Versions the statistics list at most, the one playing first.
    static let panelVersions = 4
}

extension AppModel {
    /// The title data of what `item` plays (a film or an episode); nil for a channel or a title the catalogue lacks.
    /// A few lookups and the labels of one film's versions: cheap, worked out once when the statistics show.
    func titleData(for item: PlaybackItem) -> TitleData? {
        let secrets = titleDataSecrets
        let clean = { (text: String) in Redaction.clean(text, secrets: secrets) }
        switch item.reference.type {
        case .movie:
            guard let movie = index.moviesByID[item.reference.itemID] else { return nil }
            return filmData(movie, shownTitle: item.title, clean: clean)
        case .series:
            guard let series = index.seriesByID[item.reference.itemID], let episodeID = item.reference.episodeID else { return nil }
            return episodeData(series, episodeID: episodeID, shownTitle: item.title, clean: clean)
        case .live:
            return nil
        }
    }

    private func filmData(_ movie: Movie, shownTitle: String, clean: (String) -> String) -> TitleData {
        let names = categoryNames
        let category = names[movie.categoryID]
        var lines = [
            TitleData.Line(label: .shown, value: clean(shownTitle)),
            TitleData.Line(label: .raw("name"), value: clean(movie.name)),
            TitleData.Line(label: .raw("id"), value: clean(movie.id)),
        ]
        if let stream = movie.xtreamStreamID { lines.append(.init(label: .raw("stream_id"), value: String(stream))) }
        lines.append(.init(label: .raw("category"), value: clean(Self.categoryText(category, movie.categoryID))))
        if let year = movie.year { lines.append(.init(label: .raw("year"), value: String(year))) }
        lines.append(.init(label: .tags, value: Self.tagText(movie.name, category)))
        let tmdb = [movie.tmdbID.map { String($0) }, movieDetailsCache[movie.id]?.tmdbID].map { value in value.map(clean) ?? "–" }
        lines.append(.init(label: .raw("tmdb"), value: tmdb.joined(separator: " · ")))
        lines.append(.init(label: .raw("container_extension"), value: clean(Self.containerText(movie))))
        var seen = Set<String>()
        let all = films.versions(of: movie).filter { seen.insert($0.id).inserted }
        guard all.count > 1 else { return TitleData(lines: lines) }
        let visible = visibleVersions(of: movie)
        let chips = visible.count > 1 ? Dictionary(zip(visible.map(\.id), versionLabels(visible)), uniquingKeysWith: { first, _ in first }) : [:]
        let choice = defaultChoice(of: movie, among: visible)
        let versions = all.map { version in
            let name = names[version.categoryID]
            let container = clean(Self.containerText(version))
            return TitleData.Version(
                chip: chips[version.id].map(clean),
                name: clean(version.name),
                category: clean(Self.categoryText(name, version.categoryID)),
                id: clean(version.id),
                container: container.isEmpty ? nil : container,
                tags: Self.tagText(version.name, name),
                playing: version.id == movie.id,
                first: version.id == choice.movie.id
            )
        }
        return TitleData(lines: lines, versions: versions, reason: choice.reason)
    }

    private func episodeData(_ series: Series, episodeID: String, shownTitle: String, clean: (String) -> String) -> TitleData {
        let details = seriesDetailsCache[series.id]
        let episode = details?.seasons.lazy.compactMap { season in season.episodes.first { $0.id == episodeID } }.first
            ?? series.episodes.first { $0.id == episodeID }
        let category = content.seriesCategories.first { $0.id == series.categoryID }?.name
        var lines = [
            TitleData.Line(label: .shown, value: clean(shownTitle)),
            TitleData.Line(label: .raw("series_name"), value: clean(series.name)),
            TitleData.Line(label: .raw("series_id"), value: clean(series.id)),
            TitleData.Line(label: .raw("category"), value: clean(Self.categoryText(category, series.categoryID))),
        ]
        if let year = series.year { lines.append(.init(label: .raw("year"), value: String(year))) }
        lines.append(.init(label: .raw("tmdb"), value: details?.info.tmdbID.map(clean) ?? "–"))
        if let episode { lines.append(.init(label: .raw("episode_title"), value: clean(episode.title))) }
        lines.append(.init(label: .raw("episode_id"), value: clean(episodeID)))
        if let episode {
            lines.append(.init(label: .raw("episode"), value: String(format: "S%02dE%02d", episode.season, episode.number)))
            lines.append(.init(label: .raw("container_extension"), value: MediaFormat.label(for: episode.streamURL) ?? "–"))
        }
        return TitleData(lines: lines)
    }

    /// The active playlist's login, password and server, and its address's query values: masked wherever they appear.
    private var titleDataSecrets: [String] {
        guard let playlist = activePlaylist else { return [] }
        var secrets: [String] = []
        var addresses = playlist.epgURLs
        switch playlist.source {
        case .xtream(let credentials):
            secrets += [credentials.username, credentials.password]
            addresses.append(credentials.server)
        case .remote(let url):
            addresses.append(url)
        case .local:
            break
        }
        for address in addresses {
            guard let parts = URLComponents(url: address, resolvingAgainstBaseURL: false) else { continue }
            secrets += [parts.host, parts.user, parts.password].compactMap { $0 }
            secrets += (parts.queryItems ?? []).compactMap { $0.value }.filter { !$0.isEmpty }
        }
        return secrets
    }

    private static func categoryText(_ name: String?, _ id: String) -> String { "\(name ?? "–") (\(id))" }

    private static func tagText(_ name: String, _ category: String?) -> String {
        let tags = FilmVersions.labelTags(name: name, categoryName: category).map { String(describing: $0) }
        return tags.isEmpty ? "–" : tags.joined(separator: ", ")
    }

    /// The provider's extension as given, with the format the app reads ("mkv → MKV"); the address's format else.
    private static func containerText(_ movie: Movie) -> String {
        let given = movie.containerExtension?.trimmingCharacters(in: .whitespaces)
        let format = FilmVersions.container(of: movie)
        switch (given?.isEmpty == false ? given : nil, format) {
        case let (given?, format?): return "\(given) → \(format)"
        case let (given?, nil): return given
        case let (nil, format?): return format
        default: return "–"
        }
    }
}
