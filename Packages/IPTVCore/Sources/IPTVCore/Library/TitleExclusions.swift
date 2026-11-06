import Foundation

/// The language or country tags each title's and channel's own name starts or ends with (`CategoryTags.titleTag(of:)`,
/// `CategoryTags.trailingTag(of:)`), by id: only those that carry one; and the language of each title's description
/// from the provider in a category of no language tag (`PlotLanguage`, `CategoryTags.readsPlots`). Built once per
/// catalogue, off the main actor (with the catalogue's other indexes), so that hiding a language looks through the
/// tagged and described titles only, on the main actor. Same rule as the Kotlin `CategoryFilter.hiddenTitles`.
public struct TitleTags: Sendable {
    public private(set) var movies: [String: [String]] = [:]
    public private(set) var series: [String: [String]] = [:]
    public private(set) var channels: [String: [String]] = [:]
    /// The movies and series of each language, told by their description (`PlotLanguage.detect`).
    public private(set) var moviesByLanguage: [String: Set<String>] = [:]
    public private(set) var seriesByLanguage: [String: Set<String>] = [:]
    /// Tells two catalogues' tags apart, for a cache of what they hide.
    public let id = UUID()

    public init() {}

    public init(content: PlaylistContent) {
        func tags(_ name: String) -> [String]? {
            let leading: String? = CategoryTags.titleTag(of: name)
            let trailing: String? = CategoryTags.trailingTag(of: name)
            let found: [String] = [leading, trailing].compactMap { $0 }
            return found.isEmpty ? nil : found
        }
        for movie in content.movies { if let found = tags(movie.name) { movies[movie.id] = found } }
        for show in content.series { if let found = tags(show.name) { series[show.id] = found } }
        for channel in content.channels { if let found = tags(channel.name) { channels[channel.id] = found } }
        // The categories whose titles are told by their description: no language tag of their own.
        var readsPlots = Set<String>()
        for category in content.movieCategories + content.seriesCategories
        where CategoryTags.readsPlots(categoryTag: CategoryTags.tag(of: category.name)) {
            readsPlots.insert(category.id)
        }
        for movie in content.movies where readsPlots.contains(movie.categoryID) {
            if let language = PlotLanguage.detect(movie.plot) { moviesByLanguage[language, default: []].insert(movie.id) }
        }
        for show in content.series where readsPlots.contains(show.categoryID) {
            if let language = PlotLanguage.detect(show.plot) { seriesByLanguage[language, default: []].insert(show.id) }
        }
    }

    /// What these hidden languages (`hiddenTags`) leave out whatever the category: by name, and by description
    /// (`CategoryTags.isHiddenTitle`).
    public func hidden(by hiddenTags: Set<String>) -> HiddenTitles {
        guard !hiddenTags.isEmpty else { return HiddenTitles() }
        func ids(_ tags: [String: [String]]) -> Set<String> {
            Set(tags.lazy.filter { $0.value.contains(where: hiddenTags.contains) }.map { $0.key })
        }
        var hidden = HiddenTitles(movieIDs: ids(movies), seriesIDs: ids(series), channelIDs: ids(channels))
        for language in PlotLanguage.hiddenLanguages(hiddenTags) {
            hidden.movieIDs.formUnion(moviesByLanguage[language] ?? [])
            hidden.seriesIDs.formUnion(seriesByLanguage[language] ?? [])
        }
        return hidden
    }
}

/// The movies, series and channels whose own name carries a hidden language ("|PT| Tony Carreira" or "Os Amigos (PT)"
/// filed under "Concerts"), and the movies and series of a category of no language described in a hidden language.
public struct HiddenTitles: Hashable, Sendable {
    public var movieIDs: Set<String> = []
    public var seriesIDs: Set<String> = []
    public var channelIDs: Set<String> = []

    public init(movieIDs: Set<String> = [], seriesIDs: Set<String> = [], channelIDs: Set<String> = []) {
        self.movieIDs = movieIDs
        self.seriesIDs = seriesIDs
        self.channelIDs = channelIDs
    }

    public var isEmpty: Bool { movieIDs.isEmpty && seriesIDs.isEmpty && channelIDs.isEmpty }
}

/// What every list of titles leaves out: the hidden, locked or kids' categories (`categoryIDs`), and the titles whose
/// own name carries a hidden language (`byName`), whatever their category. One rule for every list.
public struct TitleExclusions: Hashable, Sendable {
    public var categoryIDs: Set<String>
    public var byName: HiddenTitles

    public init(categoryIDs: Set<String> = [], byName: HiddenTitles = HiddenTitles()) {
        self.categoryIDs = categoryIDs
        self.byName = byName
    }

    public func contains(_ movie: Movie) -> Bool {
        categoryIDs.contains(movie.categoryID) || byName.movieIDs.contains(movie.id)
    }

    public func contains(_ series: Series) -> Bool {
        categoryIDs.contains(series.categoryID) || byName.seriesIDs.contains(series.id)
    }

    public func contains(_ channel: Channel) -> Bool {
        categoryIDs.contains(channel.categoryID) || byName.channelIDs.contains(channel.id)
    }
}
