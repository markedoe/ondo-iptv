import Foundation

/// The names IPTVCore gives in English where the provider gives none: "Uncategorized" (an M3U entry without
/// `group-title`, an Xtream category without a name), "Other" and "Other 2" (categories the panel's items use but its
/// lists leave out), "Channel 12", "Movie 7" and "Series 3" (an Xtream item without a name, by its id) and "Episode 4".
/// They stay in English where they are kept (category ids, saved catalogues and details, sync), and the app shows them
/// in its language with these words. Same rules as the Android core's `PlaceholderNames`.
public struct PlaceholderNames: Sendable {
    public var uncategorized: String
    public var other: String
    public var otherNumbered: @Sendable (Int) -> String
    public var channel: @Sendable (Int) -> String
    public var movie: @Sendable (Int) -> String
    public var series: @Sendable (Int) -> String
    public var episode: @Sendable (Int) -> String

    public init(
        uncategorized: String,
        other: String,
        otherNumbered: @escaping @Sendable (Int) -> String,
        channel: @escaping @Sendable (Int) -> String,
        movie: @escaping @Sendable (Int) -> String,
        series: @escaping @Sendable (Int) -> String,
        episode: @escaping @Sendable (Int) -> String
    ) {
        self.uncategorized = uncategorized
        self.other = other
        self.otherNumbered = otherNumbered
        self.channel = channel
        self.movie = movie
        self.series = series
        self.episode = episode
    }

    /// IPTVCore's own names (`M3UContentBuilder`, `XtreamDTO`, `XtreamClient`).
    public static let uncategorizedName = "Uncategorized"
    static let otherName = "Other"
    static let channelWord = "Channel"
    static let movieWord = "Movie"
    static let seriesWord = "Series"
    static let episodeWord = "Episode"

    /// IPTVCore's names as they are.
    public static let english = PlaceholderNames(
        uncategorized: uncategorizedName,
        other: otherName,
        otherNumbered: { "\(otherName) \($0)" },
        channel: { "\(channelWord) \($0)" },
        movie: { "\(movieWord) \($0)" },
        series: { "\(seriesWord) \($0)" },
        episode: { "\(episodeWord) \($0)" }
    )

    public func renaming(_ category: MediaCategory) -> MediaCategory { renamed(category) ?? category }
    public func renaming(_ channel: Channel) -> Channel { renamed(channel) ?? channel }
    public func renaming(_ movie: Movie) -> Movie { renamed(movie) ?? movie }
    /// The series and the episodes that come with it (M3U).
    public func renaming(_ series: Series) -> Series { renamed(series) ?? series }
    public func renaming(_ episode: Episode) -> Episode { renamed(episode) ?? episode }

    public func renaming(_ details: SeriesDetails) -> SeriesDetails {
        var result = details
        for index in result.seasons.indices {
            rename(&result.seasons[index].episodes) { renamed($0) }
        }
        return result
    }

    public func renaming(_ content: PlaylistContent) -> PlaylistContent {
        var result = content
        rename(&result.liveCategories) { renamed($0) }
        rename(&result.movieCategories) { renamed($0) }
        rename(&result.seriesCategories) { renamed($0) }
        rename(&result.channels) { renamed($0) }
        rename(&result.movies) { renamed($0) }
        rename(&result.series) { renamed($0) }
        return result
    }

    // The renamed item, or nil when it keeps its name.

    private func renamed(_ category: MediaCategory) -> MediaCategory? {
        let name: String
        if category.name == Self.uncategorizedName {
            name = uncategorized
        } else if category.name == Self.otherName {
            name = other
        } else if let number = Self.number(after: Self.otherName + " ", in: category.name), number >= 2 {
            name = otherNumbered(number)
        } else {
            return nil
        }
        guard name != category.name else { return nil }
        var result = category
        result.name = name
        return result
    }

    private func renamed(_ channel: Channel) -> Channel? {
        guard let name = numbered(channel.name, word: Self.channelWord, number: channel.xtreamStreamID, in: self.channel) else { return nil }
        var result = channel
        result.name = name
        return result
    }

    private func renamed(_ movie: Movie) -> Movie? {
        guard let name = numbered(movie.name, word: Self.movieWord, number: movie.xtreamStreamID, in: self.movie) else { return nil }
        var result = movie
        result.name = name
        return result
    }

    private func renamed(_ series: Series) -> Series? {
        let name = numbered(series.name, word: Self.seriesWord, number: series.xtreamSeriesID, in: self.series)
        var episodes = series.episodes
        let episodesRenamed = rename(&episodes) { renamed($0) }
        guard name != nil || episodesRenamed else { return nil }
        var result = series
        if let name { result.name = name }
        if episodesRenamed { result.episodes = episodes }
        return result
    }

    private func renamed(_ episode: Episode) -> Episode? {
        guard let title = numbered(episode.title, word: Self.episodeWord, number: episode.number, in: self.episode) else { return nil }
        var result = episode
        result.title = title
        return result
    }

    /// Writes only the items that change: a catalogue without such names is not copied. True when one changed.
    @discardableResult
    private func rename<T>(_ items: inout [T], _ transform: (T) -> T?) -> Bool {
        var changed = false
        for index in items.indices {
            if let renamed = transform(items[index]) {
                items[index] = renamed
                changed = true
            }
        }
        return changed
    }

    /// The name in these words when `name` is IPTVCore's "<word> <number>", else nil.
    private func numbered(_ name: String, word: String, number: Int?, in words: (Int) -> String) -> String? {
        guard let number, name.hasPrefix(word), name == "\(word) \(number)" else { return nil }
        let renamed = words(number)
        return renamed == name ? nil : renamed
    }

    private static func number(after prefix: String, in name: String) -> Int? {
        guard name.hasPrefix(prefix) else { return nil }
        let digits = name.dropFirst(prefix.count)
        guard !digits.isEmpty, digits.count <= 9, !digits.hasPrefix("0"), digits.allSatisfy({ ("0"..."9").contains($0) }) else { return nil }
        return Int(digits)
    }
}
