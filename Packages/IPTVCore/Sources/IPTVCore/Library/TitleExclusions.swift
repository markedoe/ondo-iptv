import Foundation

/// The language or country tag each title's and channel's own name starts with (`CategoryTags.titleTag(of:)`), by id:
/// only those that carry one. Built once per catalogue, off the main actor (with the catalogue's other indexes), so
/// that hiding a language looks through the tagged titles only. Same rule as the Kotlin `CategoryFilter.hiddenTitles`.
public struct TitleTags: Sendable {
    public private(set) var movies: [String: String] = [:]
    public private(set) var series: [String: String] = [:]
    public private(set) var channels: [String: String] = [:]
    /// Tells two catalogues' tags apart, for a cache of what they hide.
    public let id = UUID()

    public init() {}

    public init(content: PlaylistContent) {
        for movie in content.movies { if let tag = CategoryTags.titleTag(of: movie.name) { movies[movie.id] = tag } }
        for show in content.series { if let tag = CategoryTags.titleTag(of: show.name) { series[show.id] = tag } }
        for channel in content.channels { if let tag = CategoryTags.titleTag(of: channel.name) { channels[channel.id] = tag } }
    }

    /// What these hidden languages (`hiddenTags`) leave out by name, whatever the category.
    public func hidden(by hiddenTags: Set<String>) -> HiddenTitles {
        guard !hiddenTags.isEmpty else { return HiddenTitles() }
        func ids(_ tags: [String: String]) -> Set<String> {
            Set(tags.lazy.filter { hiddenTags.contains($0.value) }.map { $0.key })
        }
        return HiddenTitles(movieIDs: ids(movies), seriesIDs: ids(series), channelIDs: ids(channels))
    }
}

/// The movies, series and channels whose own name carries a hidden language ("|PT| Tony Carreira" filed under
/// "Concerts").
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
