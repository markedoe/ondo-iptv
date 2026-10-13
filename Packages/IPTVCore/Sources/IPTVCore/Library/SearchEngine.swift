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

/// Accent / case insensitive, word-prefix search over a playlist. Names are normalised once at init.
public struct SearchEngine: Sendable {
    private let channels: [(key: String, item: Channel)]
    private let movies: [(key: String, item: Movie)]
    private let series: [(key: String, item: Series)]

    public init(content: PlaylistContent) {
        channels = content.channels.map { (TextNormalizer.normalize($0.name), $0) }
        movies = content.movies.map { (TextNormalizer.normalize($0.name), $0) }
        series = content.series.map { (TextNormalizer.normalize($0.name), $0) }
    }

    public func search(_ query: String, limitPerType: Int = 200, excludedCategoryIDs: Set<String> = []) -> SearchResults {
        let normalized = TextNormalizer.normalize(query)
        guard !normalized.isEmpty else { return SearchResults() }
        let tokens = normalized.split(separator: " ")

        func run<T>(_ list: [(key: String, item: T)], categoryID: (T) -> String) -> [T] {
            var exact: [T] = []
            var prefix: [T] = []
            var other: [T] = []
            for entry in list {
                guard !excludedCategoryIDs.contains(categoryID(entry.item)),
                      TextNormalizer.matches(normalizedText: entry.key, queryTokens: tokens) else { continue }
                if entry.key == normalized {
                    exact.append(entry.item)
                } else if entry.key.hasPrefix(normalized) {
                    prefix.append(entry.item)
                } else {
                    other.append(entry.item)
                }
                if exact.count + prefix.count >= limitPerType { break }
            }
            return Array((exact + prefix + other).prefix(limitPerType))
        }

        return SearchResults(
            channels: run(channels, categoryID: \.categoryID),
            movies: run(movies, categoryID: \.categoryID),
            series: run(series, categoryID: \.categoryID)
        )
    }
}
