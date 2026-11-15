import Foundation

/// The movie and series pages of a playlist as the provider last returned them, kept between launches (one document
/// per playlist, with the caches). Pages and Home's backdrops show them at once; a copy that is too old is fetched
/// again, and still shown when the provider cannot be reached.
public struct DetailsArchive: Codable, Sendable, Equatable {
    public struct Entry<Value: Codable & Sendable & Equatable>: Codable, Sendable, Equatable {
        public var value: Value
        public var fetchedAt: Date

        public init(value: Value, fetchedAt: Date) {
            self.value = value
            self.fetchedAt = fetchedAt
        }
    }

    /// A movie's page hardly changes; a series gains episodes.
    public static let movieMaxAge: TimeInterval = 7 * 24 * 3600
    public static let seriesMaxAge: TimeInterval = 12 * 3600
    /// The most recently fetched are kept: a series with all its episodes takes far more room than a movie.
    public static let movieLimit = 1_000
    public static let seriesLimit = 300
    /// Other versions' details asked by films' pages (`versions`).
    public static let versionLimit = 300

    public var movies: [String: Entry<MediaDetails>]
    public var series: [String: Entry<SeriesDetails>]
    /// The details a film's page asked for its other versions, for what their streams are made of: kept apart, at most
    /// `versionLimit`, so that they never push the pages viewers opened out of `movies`.
    public var versions: [String: Entry<MediaDetails>]

    public init(
        movies: [String: Entry<MediaDetails>] = [:],
        series: [String: Entry<SeriesDetails>] = [:],
        versions: [String: Entry<MediaDetails>] = [:]
    ) {
        self.movies = movies
        self.series = series
        self.versions = versions
    }

    enum CodingKeys: String, CodingKey {
        case movies, series, versions
    }

    /// A document written before `versions` existed reads too.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        movies = try c.decodeIfPresent([String: Entry<MediaDetails>].self, forKey: .movies) ?? [:]
        series = try c.decodeIfPresent([String: Entry<SeriesDetails>].self, forKey: .series) ?? [:]
        versions = (try? c.decodeIfPresent([String: Entry<MediaDetails>].self, forKey: .versions)) ?? [:]
    }

    public static func documentName(for playlistID: UUID) -> String {
        "details-\(playlistID.uuidString)"
    }

    /// Fetched less than `maxAge` ago. A date in the future (the clock was changed) is not trusted.
    public static func isFresh(_ fetchedAt: Date?, maxAge: TimeInterval, now: Date = Date()) -> Bool {
        guard let fetchedAt else { return false }
        let age = now.timeIntervalSince(fetchedAt)
        return age >= 0 && age < maxAge
    }

    /// At most `movieLimit` movies, `seriesLimit` series and `versionLimit` versions: the most recently fetched.
    public func trimmed(movieLimit: Int = Self.movieLimit, seriesLimit: Int = Self.seriesLimit, versionLimit: Int = Self.versionLimit) -> DetailsArchive {
        DetailsArchive(
            movies: Self.newest(movies, limit: movieLimit),
            series: Self.newest(series, limit: seriesLimit),
            versions: Self.newest(versions, limit: versionLimit)
        )
    }

    private static func newest<Value>(_ entries: [String: Entry<Value>], limit: Int) -> [String: Entry<Value>] {
        guard entries.count > limit else { return entries }
        let kept = entries.sorted { $0.value.fetchedAt > $1.value.fetchedAt }.prefix(max(limit, 0))
        return Dictionary(uniqueKeysWithValues: kept.map { ($0.key, $0.value) })
    }
}
