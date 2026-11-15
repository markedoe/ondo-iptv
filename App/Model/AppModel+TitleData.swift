import Foundation
import IPTVCore

/// What the app read from the provider for the title played, shown under More, Stream Statistics: the catalogue's raw
/// names, categories and ids, the tags the version rules found, what each version's chip says and which one plays
/// first. For photographing a provider's names when a chip reads wrong (docs/VERSIONS.md). Never an address, a login, a
/// password or a server: every text goes through `Redaction`, and an id shows only when it is a provider's number (the
/// M3U reader's ids hash the stream's address, its login and password included). The same data as Android's `TitleData`.
struct TitleData: Equatable, Sendable {
    enum Label: Equatable, Sendable {
        /// The title as the app shows it: without the provider's tags around the name (`CatalogueMatcher.displayTitle`).
        case shown
        /// The tags the version rules read.
        case tags
        /// The provider's own name of the field ("name", "category", "tmdb"…), shown as is.
        case raw(String)
        /// What the stream is made of, named by where it comes from: measured, the provider's, or (nil) its name's tags.
        case specs(SpecSource?)
    }

    struct Line: Equatable, Sendable {
        var label: Label
        var value: String
    }

    struct Version: Equatable, Identifiable, Sendable {
        /// What its chip says on the film's page; nil for one in a hidden category (no chip).
        var chip: String?
        var name: String
        /// "Films 4K (12)": the category's name and id.
        var category: String
        /// Its stream's id in the catalogue, for the app only (a hash of its address in an M3U playlist).
        var id: String
        /// `id` when the panel may show it (`Redaction.shownID`).
        var shownID: String?
        /// "MKV", "MP4", "HLS"…; the provider's extension as given when the app does not know it.
        var container: String?
        /// The tags the version rules read ("uhd, dolbyVision"); "–" for none.
        var tags: String
        var playing: Bool
        /// The version the film's card plays and its page selects first.
        var first: Bool
        /// One chip with another version that plays the same thing (`FilmVersions.choices`): that one's id as the panel
        /// shows it, else its name; nil for the one its chip plays, or a version alone on its chip.
        var sameAs: String?
        /// What its stream is made of ("1920×802 · 1080p · SDR · H.264 · 5.1 AAC"), as far as it is known; "–" for nothing.
        var specs = "–"
        /// Where `specs` come from: measured by the player, the provider's details, or (nil) its name's tags.
        var specSource: SpecSource?
    }

    var lines: [Line]
    var versions: [Version] = []
    /// Why the film's card plays the version marked `first`; nil for an episode or a film alone.
    var reason: DefaultReason?

    /// Versions the statistics list at most, the one playing first.
    static let panelVersions = 4

    /// What the title data is worked out from, gathered on the main actor (a few lookups and the film's cached chips),
    /// then read off it (`build`).
    struct Input: Sendable {
        enum Title: Sendable {
            case film(movie: Movie, versions: [Movie], chips: [String: String], sameAs: [String: String], first: String?, reason: DefaultReason?, detailsTMDb: String?)
            case episode(series: Series, episodeID: String, episode: Episode?, detailsTMDb: String?, category: String?)
        }

        var title: Title
        var shownTitle: String
        var categoryNames: [String: String]
        var secrets: [String]
        /// What is known of each version's stream, and where it comes from (AppModel+StreamSpecs).
        var specs: [String: StreamSpecs] = [:]
        var specSources: [String: SpecSource] = [:]
    }

    /// The title data of `input`, off the main actor.
    static func build(_ input: Input) -> TitleData {
        let clean = { (text: String) in Redaction.clean(text, secrets: input.secrets) }
        switch input.title {
        case let .film(movie, all, chips, sameAs, first, reason, detailsTMDb):
            return film(movie, all: all, chips: chips, sameAs: sameAs, first: first, reason: reason, detailsTMDb: detailsTMDb, input: input, clean: clean)
        case let .episode(series, episodeID, episode, detailsTMDb, category):
            var lines = [
                Line(label: .shown, value: clean(CatalogueMatcher.displayTitle(clean(input.shownTitle)))),
                Line(label: .raw("series_name"), value: clean(series.name)),
            ]
            if let id = Redaction.shownID(series.id) { lines.append(.init(label: .raw("series_id"), value: id)) }
            lines.append(.init(label: .raw("category"), value: clean(categoryText(category, series.categoryID))))
            if let year = series.year { lines.append(.init(label: .raw("year"), value: String(year))) }
            lines.append(.init(label: .raw("tmdb"), value: detailsTMDb.map(clean) ?? "–"))
            if let episode { lines.append(.init(label: .raw("episode_title"), value: clean(episode.title))) }
            if let id = Redaction.shownID(episodeID) { lines.append(.init(label: .raw("episode_id"), value: id)) }
            if let episode {
                lines.append(.init(label: .raw("episode"), value: String(format: "S%02dE%02d", episode.season, episode.number)))
                lines.append(.init(label: .raw("container_extension"), value: MediaFormat.label(for: episode.streamURL) ?? "–"))
            }
            return TitleData(lines: lines)
        }
    }

    // swiftlint:disable:next function_parameter_count
    private static func film(
        _ movie: Movie,
        all: [Movie],
        chips: [String: String],
        sameAs: [String: String],
        first: String?,
        reason: DefaultReason?,
        detailsTMDb: String?,
        input: Input,
        clean: (String) -> String
    ) -> TitleData {
        let names = input.categoryNames
        let category = names[movie.categoryID]
        // The tags as the chips read them: a title word shared by every version ("The English Orchard") is no tag.
        let groupTags = all.count > 1
            ? Dictionary(zip(all.map(\.id), FilmVersions.labelTags(all, categoryName: { names[$0] })), uniquingKeysWith: { first, _ in first })
            : [:]
        func tags(of version: Movie) -> String {
            tagText(groupTags[version.id] ?? FilmVersions.labelTags(name: version.name, categoryName: names[version.categoryID]))
        }
        var lines = [
            Line(label: .shown, value: clean(CatalogueMatcher.displayTitle(clean(input.shownTitle)))),
            Line(label: .raw("name"), value: clean(movie.name)),
        ]
        // Never the M3U reader's ids: a hash of the stream's address, its login and password included.
        if let id = Redaction.shownID(movie.id) { lines.append(.init(label: .raw("id"), value: id)) }
        if let stream = movie.xtreamStreamID { lines.append(.init(label: .raw("stream_id"), value: String(stream))) }
        lines.append(.init(label: .raw("category"), value: clean(categoryText(category, movie.categoryID))))
        if let year = movie.year { lines.append(.init(label: .raw("year"), value: String(year))) }
        lines.append(.init(label: .tags, value: tags(of: movie)))
        let tmdb = [movie.tmdbID.map { String($0) }, detailsTMDb].map { value in value.map(clean) ?? "–" }
        lines.append(.init(label: .raw("tmdb"), value: tmdb.joined(separator: " · ")))
        lines.append(.init(label: .raw("container_extension"), value: clean(containerText(movie))))
        func specs(of version: Movie) -> String {
            if let known = input.specs[version.id] { return known.summary() }
            let tags = groupTags[version.id] ?? FilmVersions.labelTags(name: version.name, categoryName: names[version.categoryID])
            let named = StreamSpecs.badges(nil, nameTags: tags).joined(separator: " · ")
            return named.isEmpty ? "–" : named
        }
        lines.append(.init(label: .specs(input.specSources[movie.id]), value: specs(of: movie)))
        guard all.count > 1 else { return TitleData(lines: lines) }
        let versions = all.map { version in
            let container = clean(containerText(version))
            return Version(
                chip: chips[version.id].map(clean),
                name: clean(version.name),
                category: clean(categoryText(names[version.categoryID], version.categoryID)),
                id: version.id,
                shownID: Redaction.shownID(version.id),
                container: container.isEmpty ? nil : container,
                tags: tags(of: version),
                playing: version.id == movie.id,
                first: version.id == first,
                sameAs: sameAs[version.id].map(clean),
                specs: specs(of: version),
                specSource: input.specSources[version.id]
            )
        }
        return TitleData(lines: lines, versions: versions, reason: reason)
    }

    private static func categoryText(_ name: String?, _ id: String) -> String { "\(name ?? "–") (\(id))" }

    private static func tagText(_ tags: [VersionTag]) -> String {
        tags.isEmpty ? "–" : tags.map { String(describing: $0) }.joined(separator: ", ")
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

extension AppModel {
    /// The title data of what `item` plays (a film or an episode), worked out off the main actor; nil for a channel or a
    /// title the catalogue lacks.
    func titleData(for item: PlaybackItem) async -> TitleData? {
        guard let input = titleDataInput(for: item) else { return nil }
        return await Task.detached(priority: .userInitiated) { TitleData.build(input) }.value
    }

    /// What `titleData(for:)` reads, gathered here: lookups, the film's chips (cached) and the version it plays first.
    func titleDataInput(for item: PlaybackItem) -> TitleData.Input? {
        let secrets = titleDataSecrets
        switch item.reference.type {
        case .movie:
            guard let movie = index.moviesByID[item.reference.itemID] else { return nil }
            var seen = Set<String>()
            let all = films.versions(of: movie).filter { seen.insert($0.id).inserted }
            var chips: [String: String] = [:]
            var sameAs: [String: String] = [:]
            var choice: (movie: Movie, reason: DefaultReason)?
            if all.count > 1 {
                let visible = visibleVersions(of: movie)
                // Every version, those one chip holds together too (marked "same as" the one it plays).
                // One chip for them all ("Standard", not shown on the page) still names each, never "hidden".
                let choices = visible.count > 1 ? versionChoices(visible) : []
                for chip in choices {
                    let shown = Redaction.shownID(chip.preferred.id) ?? chip.preferred.name
                    for member in chip.members {
                        chips[member.id] = chip.label
                        if member.id != chip.preferred.id { sameAs[member.id] = shown }
                    }
                }
                choice = defaultChoice(of: movie, among: visible)
            }
            var specs: [String: StreamSpecs] = [:]
            var sources: [String: SpecSource] = [:]
            for version in all {
                if let known = streamSpecs(for: version.id) { specs[version.id] = known }
                if let source = specSource(for: version.id) { sources[version.id] = source }
            }
            return TitleData.Input(
                title: .film(
                    movie: movie, versions: all, chips: chips, sameAs: sameAs, first: choice?.movie.id, reason: choice?.reason,
                    detailsTMDb: movieDetailsCache[movie.id]?.tmdbID
                ),
                shownTitle: item.title, categoryNames: categoryNames, secrets: secrets, specs: specs, specSources: sources
            )
        case .series:
            guard let series = index.seriesByID[item.reference.itemID], let episodeID = item.reference.episodeID else { return nil }
            let details = seriesDetailsCache[series.id]
            let episode = details?.seasons.lazy.compactMap { season in season.episodes.first { $0.id == episodeID } }.first
                ?? series.episodes.first { $0.id == episodeID }
            let category = content.seriesCategories.first { $0.id == series.categoryID }?.name
            return TitleData.Input(
                title: .episode(series: series, episodeID: episodeID, episode: episode, detailsTMDb: details?.info.tmdbID, category: category),
                shownTitle: item.title, categoryNames: [:], secrets: secrets
            )
        case .live:
            return nil
        }
    }

    /// The active playlist's login, password and server, and its addresses' user parts and query values, as written and
    /// percent-decoded (`Redaction.secrets`): masked wherever they appear.
    private var titleDataSecrets: [String] {
        guard let playlist = activePlaylist else { return [] }
        var addresses = playlist.epgURLs.map(\.absoluteString)
        var login: [String?] = []
        switch playlist.source {
        case .xtream(let credentials):
            login = [credentials.username, credentials.password]
            addresses.append(credentials.server.absoluteString)
        case .remote(let url):
            addresses.append(url.absoluteString)
        case .local:
            break
        }
        return Redaction.secrets(addresses: addresses, login: login)
    }
}
