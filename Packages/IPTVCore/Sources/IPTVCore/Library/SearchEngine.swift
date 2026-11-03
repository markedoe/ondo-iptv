import Foundation

public struct SearchResults: Sendable {
    public var channels: [Channel]
    public var movies: [Movie]
    public var series: [Series]

    public init(channels: [Channel] = [], movies: [Movie] = [], series: [Series] = []) {
        self.channels = channels
        self.movies = movies
        self.series = series
    }

    public var isEmpty: Bool { channels.isEmpty && movies.isEmpty && series.isEmpty }
    public var totalCount: Int { channels.count + movies.count + series.count }
}

/// Accent and case insensitive search over a playlist: every query word must appear in the name. Exact names
/// rank first, then names starting with the query, then the others. Names are normalised once at init.
public struct SearchEngine: Sendable {
    private let channels: [(key: String, item: Channel)]
    private let movies: [(key: String, item: Movie)]
    private let series: [(key: String, item: Series)]

    public init(content: PlaylistContent) {
        channels = content.channels.map { (TextNormalizer.normalize($0.name), $0) }
        movies = content.movies.map { (TextNormalizer.normalize($0.name), $0) }
        series = content.series.map { (TextNormalizer.normalize($0.name), $0) }
    }

    /// `hiddenTags`: hidden languages; a title or a channel whose own name carries one is left out whatever its
    /// category (`CategoryTags.isHidden(name:hiddenTags:)`).
    public func search(_ query: String, limitPerType: Int = 200, excludedCategoryIDs: Set<String> = [], hiddenTags: Set<String> = []) -> SearchResults {
        let normalized = TextNormalizer.normalize(query)
        guard !normalized.isEmpty else { return SearchResults() }
        let tokens = normalized.split(separator: " ")

        func run<T>(_ list: [(key: String, item: T)], categoryID: (T) -> String, name: (T) -> String) -> [T] {
            var exact: [T] = []
            var prefix: [T] = []
            var other: [T] = []
            for entry in list {
                guard !excludedCategoryIDs.contains(categoryID(entry.item)),
                      TextNormalizer.matches(normalizedText: entry.key, queryTokens: tokens),
                      !CategoryTags.isHidden(name: name(entry.item), hiddenTags: hiddenTags) else { continue }
                // Only exact names fill the limit for good: a later exact name ranks before any prefix match.
                if entry.key == normalized {
                    exact.append(entry.item)
                    if exact.count >= limitPerType { break }
                } else if entry.key.hasPrefix(normalized) {
                    if prefix.count < limitPerType { prefix.append(entry.item) }
                } else if other.count < limitPerType {
                    other.append(entry.item)
                }
            }
            return Array((exact + prefix + other).prefix(limitPerType))
        }

        return SearchResults(
            channels: run(channels, categoryID: \.categoryID, name: \.name),
            movies: run(movies, categoryID: \.categoryID, name: \.name),
            series: run(series, categoryID: \.categoryID, name: \.name)
        )
    }
}
