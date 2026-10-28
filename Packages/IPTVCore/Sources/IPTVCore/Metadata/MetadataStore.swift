import Foundation

// What TMDb answered about titles and people, kept on the device between launches (a SQLite file on Apple's systems,
// `SQLiteMetadataStore`): pages show it at once, and TMDb is only asked again for what is missing or old. It also
// holds the local index of people (who played in or made which title), filled from every source the app has, and
// the image addresses that led nowhere. Everything in it can be fetched again: it is a cache.

/// The keys of the store. A title's key does not depend on the playlist, so what TMDb said survives a change of
/// playlist or provider: `movie:603` when the provider gives the title's TMDb id, else the words of its title and its
/// year, `movie:sandstorm:2021` (the same words `CatalogueMatcher` matches trending titles with).
public enum MetadataKey {
    /// "movie" or "series".
    public static func kind(_ type: ContentType) -> String {
        type == .series ? "series" : "movie"
    }

    /// "movie:603", when `id` is a TMDb id.
    public static func tmdb(_ type: ContentType, id: String?) -> String? {
        guard let id = id.flatMap({ Int($0.trimmingCharacters(in: .whitespaces)) }), id > 0 else { return nil }
        return tmdb(type, id: id)
    }

    public static func tmdb(_ type: ContentType, id: Int) -> String {
        kind(type) + ":" + String(id)
    }

    /// "movie:sandstorm:2021" for "FR - Sandstorm (2021) 4K"; "movie:sandstorm:" without a year. The year is the
    /// list's, else the one in the name. Nil for a name without a word.
    public static func title(_ type: ContentType, name: String, year: Int?) -> String? {
        let words = CatalogueMatcher.key(name)
        guard !words.isEmpty else { return nil }
        let year = year ?? TitleParser.splitYear(name).1
        return kind(type) + ":" + words + ":" + (year.map(String.init) ?? "")
    }

    /// The keys a title is read under (the first one found wins) and written under: its TMDb id's, then its title's.
    public static func all(_ type: ContentType, tmdbID: String?, name: String, year: Int?) -> [String] {
        [tmdb(type, id: tmdbID), title(type, name: name, year: year)].compactMap { $0 }
    }

    /// A person's key: the name without case or accents (`TextNormalizer.normalize`), as the cast fields are matched.
    public static func person(_ name: String) -> String {
        TextNormalizer.normalize(name)
    }
}

/// How long what TMDb said is trusted. An older entry still shows while it is fetched again.
public enum MetadataFreshness {
    /// A title TMDb knows.
    public static let found: TimeInterval = 30 * 24 * 3600
    /// A title TMDb does not know: asked again sooner (TMDb grows, a provider fixes a title).
    public static let notFound: TimeInterval = 7 * 24 * 3600
    /// An image address that led nowhere.
    public static let deadImage: TimeInterval = 7 * 24 * 3600

    /// Whether `title` need not be fetched again. A date in the future (the clock was changed) is not trusted.
    public static func isFresh(_ title: TitleMetadata, now: Date = Date()) -> Bool {
        isFresh(title.fetchedAt, maxAge: title.status == .found ? found : notFound, now: now)
    }

    public static func isFresh(_ date: Date?, maxAge: TimeInterval, now: Date = Date()) -> Bool {
        DetailsArchive.isFresh(date, maxAge: maxAge, now: now)
    }
}

/// What TMDb said about a movie or a series.
public struct TitleMetadata: Codable, Hashable, Sendable {
    public enum Status: String, Codable, Sendable {
        case found
        /// TMDb answered, without such a title.
        case notFound = "not_found"
    }

    public var key: String
    /// "movie" or "series" (`MetadataKey.kind`).
    public var kind: String
    public var tmdbID: Int?
    public var backdropURL: URL?
    /// The YouTube id of its trailer.
    public var trailerKey: String?
    public var voteAverage: Double?
    public var voteCount: Int?
    public var fetchedAt: Date
    public var status: Status

    public init(key: String, kind: String, tmdbID: Int? = nil, backdropURL: URL? = nil, trailerKey: String? = nil,
                voteAverage: Double? = nil, voteCount: Int? = nil, fetchedAt: Date, status: Status = .found) {
        self.key = key
        self.kind = kind
        self.tmdbID = tmdbID
        self.backdropURL = backdropURL
        self.trailerKey = trailerKey
        self.voteAverage = voteAverage
        self.voteCount = voteCount
        self.fetchedAt = fetchedAt
        self.status = status
    }

    /// TMDb's answer, under `key`.
    public init(key: String, kind: String, info: TMDbTitleInfo?, fetchedAt: Date) {
        self.init(key: key, kind: kind, tmdbID: info?.tmdbID, backdropURL: info?.backdropURL, trailerKey: info?.trailerKey,
                  voteAverage: info?.voteAverage, voteCount: info?.voteCount, fetchedAt: fetchedAt, status: info == nil ? .notFound : .found)
    }

    /// "★ 7.1 (12,345)" needs both: a rating TMDb's users gave, and how many gave it.
    public var votes: (average: Double, count: Int)? {
        guard status == .found, let voteAverage, voteAverage > 0, let voteCount, voteCount > 0 else { return nil }
        return (voteAverage, voteCount)
    }
}

/// A director or an actor of a title, in its order.
public struct CreditRecord: Hashable, Sendable {
    public enum Role: String, Sendable {
        case director
        case cast
    }

    public var personKey: String
    public var displayName: String
    public var role: Role
    public var position: Int
    public var photoURL: URL?

    public init(personKey: String, displayName: String, role: Role, position: Int, photoURL: URL? = nil) {
        self.personKey = personKey
        self.displayName = displayName
        self.role = role
        self.position = position
        self.photoURL = photoURL
    }

    /// TMDb's directors then its cast, in their order.
    public static func credits(of info: TMDbTitleInfo) -> [CreditRecord] {
        func records(_ credits: [TMDbCredit], _ role: Role) -> [CreditRecord] {
            credits.enumerated().compactMap { position, credit in
                let key = MetadataKey.person(credit.name)
                guard !key.isEmpty else { return nil }
                return CreditRecord(personKey: key, displayName: credit.name, role: role, position: position, photoURL: credit.profileURL)
            }
        }
        return records(info.directors, .director) + records(info.cast, .cast)
    }
}

/// A person the store knows: from a title's credits (the provider's or TMDb's) or from TMDb.
public struct PersonRecord: Hashable, Sendable {
    public var personKey: String
    public var displayName: String
    public var tmdbID: Int?
    public var photoURL: URL?
    /// When TMDb last told about this person; nil when only a cast field named them.
    public var fetchedAt: Date?

    public init(personKey: String, displayName: String, tmdbID: Int? = nil, photoURL: URL? = nil, fetchedAt: Date? = nil) {
        self.personKey = personKey
        self.displayName = displayName
        self.tmdbID = tmdbID
        self.photoURL = photoURL
        self.fetchedAt = fetchedAt
    }

    /// `newer` over `self`, keeping what `newer` does not know (a photo, a TMDb id).
    public func updated(with newer: PersonRecord) -> PersonRecord {
        PersonRecord(personKey: personKey, displayName: newer.displayName.isEmpty ? displayName : newer.displayName,
                     tmdbID: newer.tmdbID ?? tmdbID, photoURL: newer.photoURL ?? photoURL, fetchedAt: newer.fetchedAt ?? fetchedAt)
    }
}

/// A person named in a title's credits: an entry of the local people index.
public struct PersonLink: Hashable, Sendable {
    public var personKey: String
    public var displayName: String
    public var titleKey: String

    public init(personKey: String, displayName: String, titleKey: String) {
        self.personKey = personKey
        self.displayName = displayName
        self.titleKey = titleKey
    }

    /// The people of the cast and director fields a provider sends ("A, B (role)…"), for each of the title's keys.
    public static func links(fields: [String?], titleKeys: [String]) -> [PersonLink] {
        var links: [PersonLink] = []
        var seen = Set<String>()
        for field in fields {
            for (name, key) in People.entries(in: field) where !key.isEmpty && seen.insert(key).inserted {
                links += titleKeys.map { PersonLink(personKey: key, displayName: name, titleKey: $0) }
            }
        }
        return links
    }
}

/// A person found in the local index, with how many titles name them.
public struct IndexedPerson: Hashable, Sendable {
    public var person: PersonRecord
    public var titleCount: Int

    public init(person: PersonRecord, titleCount: Int) {
        self.person = person
        self.titleCount = titleCount
    }
}

/// People search over the local index and TMDb, as `PeopleIndex` matches names.
public enum PeopleSearch {
    /// The query's words, when it can be searched (three letters at least).
    public static func tokens(_ query: String) -> [String]? {
        let normalized = TextNormalizer.normalize(query)
        let tokens = normalized.split(separator: " ").map(String.init)
        return normalized.count >= 3 && !tokens.isEmpty ? tokens : nil
    }

    /// The people whose name has every word of the query starting one of its words: names that start with the query
    /// first, then the others, the most often named first.
    public static func ranked(_ people: [IndexedPerson], query: String, limit: Int = 20) -> [IndexedPerson] {
        guard let tokens = tokens(query) else { return [] }
        let normalized = tokens.joined(separator: " ")
        let matching = people.filter { entry in
            let words = entry.person.personKey.split(separator: " ")
            return tokens.allSatisfy { token in words.contains { $0.hasPrefix(token) } }
        }
        return Array(matching.sorted { a, b in
            let aLeads = a.person.personKey.hasPrefix(normalized)
            let bLeads = b.person.personKey.hasPrefix(normalized)
            if aLeads != bLeads { return aLeads }
            if a.titleCount != b.titleCount { return a.titleCount > b.titleCount }
            return a.person.personKey < b.person.personKey
        }.prefix(limit))
    }

    /// Names from several sources, in order, each person once (case and accents ignored): the local ones first, then
    /// those TMDb adds.
    public static func merged(_ lists: [String]..., limit: Int = 30) -> [String] {
        var seen = Set<String>()
        var result: [String] = []
        for name in lists.joined() where result.count < limit {
            let key = MetadataKey.person(name)
            if !key.isEmpty, seen.insert(key).inserted { result.append(name) }
        }
        return result
    }
}

/// Where TMDb's answers and the people index are kept. Calls may come from any thread and are answered at once (a
/// file on disk: call them off the main thread).
public protocol MetadataStoring: AnyObject, Sendable {
    /// The titles kept under these keys, old ones included.
    func titles(_ keys: [String]) -> [String: TitleMetadata]
    /// Adds or replaces titles.
    func save(_ titles: [TitleMetadata])
    /// A title's directors and actors, in order.
    func credits(of titleKey: String) -> [CreditRecord]
    /// Replaces the credits of each of `titleKeys` (the keys of one title) and indexes their people.
    func saveCredits(_ credits: [CreditRecord], for titleKeys: [String])
    func people(_ keys: [String]) -> [String: PersonRecord]
    /// Adds people, or completes those known (`PersonRecord.updated(with:)`).
    func savePeople(_ people: [PersonRecord])
    /// Adds entries to the people index (and the people it names, when unknown).
    func index(_ links: [PersonLink])
    /// The keys of the titles the index links to a person.
    func titleKeys(ofPerson personKey: String) -> [String]
    /// The indexed people whose name matches the query (`PeopleSearch.ranked`).
    func people(matching query: String, limit: Int) -> [IndexedPerson]
    /// The image addresses that failed since `date`.
    func deadImages(since date: Date) -> Set<URL>
    func markDeadImage(_ url: URL, at date: Date)
    /// Empties the store (Clear Cache).
    func removeAll()
    /// Bytes on disk.
    func diskUsage() -> Int64
}

/// The store kept in memory: tests, and the app when the file cannot be opened.
public final class InMemoryMetadataStore: MetadataStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var titleRows: [String: TitleMetadata] = [:]
    private var creditRows: [String: [CreditRecord]] = [:]
    private var personRows: [String: PersonRecord] = [:]
    private var links: [String: Set<String>] = [:]
    private var dead: [URL: Date] = [:]

    public init() {}

    public func titles(_ keys: [String]) -> [String: TitleMetadata] {
        lock.withLock { titleRows.filter { keys.contains($0.key) } }
    }

    public func save(_ titles: [TitleMetadata]) {
        lock.withLock {
            for title in titles { titleRows[title.key] = title }
        }
    }

    public func credits(of titleKey: String) -> [CreditRecord] {
        lock.withLock { creditRows[titleKey] ?? [] }
    }

    public func saveCredits(_ credits: [CreditRecord], for titleKeys: [String]) {
        let people = credits.map { PersonRecord(personKey: $0.personKey, displayName: $0.displayName, photoURL: $0.photoURL) }
        // Directors first, each role in its order.
        let ordered = credits.sorted { ($0.role == .cast ? 1 : 0, $0.position) < ($1.role == .cast ? 1 : 0, $1.position) }
        lock.withLock {
            for key in titleKeys { creditRows[key] = ordered }
        }
        savePeople(people)
        index(credits.flatMap { credit in
            titleKeys.map { PersonLink(personKey: credit.personKey, displayName: credit.displayName, titleKey: $0) }
        })
    }

    public func people(_ keys: [String]) -> [String: PersonRecord] {
        lock.withLock { personRows.filter { keys.contains($0.key) } }
    }

    public func savePeople(_ people: [PersonRecord]) {
        lock.withLock {
            for person in people where !person.personKey.isEmpty {
                personRows[person.personKey] = personRows[person.personKey].map { $0.updated(with: person) } ?? person
            }
        }
    }

    public func index(_ newLinks: [PersonLink]) {
        lock.withLock {
            for link in newLinks where !link.personKey.isEmpty {
                links[link.personKey, default: []].insert(link.titleKey)
                if personRows[link.personKey] == nil {
                    personRows[link.personKey] = PersonRecord(personKey: link.personKey, displayName: link.displayName)
                }
            }
        }
    }

    public func titleKeys(ofPerson personKey: String) -> [String] {
        lock.withLock { (links[personKey] ?? []).sorted() }
    }

    public func people(matching query: String, limit: Int) -> [IndexedPerson] {
        let candidates = lock.withLock {
            personRows.values.compactMap { person -> IndexedPerson? in
                guard let titles = links[person.personKey], !titles.isEmpty else { return nil }
                return IndexedPerson(person: person, titleCount: titles.count)
            }
        }
        return PeopleSearch.ranked(candidates, query: query, limit: limit)
    }

    public func deadImages(since date: Date) -> Set<URL> {
        lock.withLock { Set(dead.filter { $0.value >= date }.keys) }
    }

    public func markDeadImage(_ url: URL, at date: Date) {
        lock.withLock { dead[url] = date }
    }

    public func removeAll() {
        lock.withLock {
            titleRows = [:]
            creditRows = [:]
            personRows = [:]
            links = [:]
            dead = [:]
        }
    }

    public func diskUsage() -> Int64 { 0 }
}

/// Spaces requests out: each `wait()` returns at least `interval` after the previous one did (TMDb's prefetch, a few
/// requests a second). A cancelled wait throws.
public actor RequestPacer {
    private let interval: Duration
    private var nextStart: ContinuousClock.Instant?

    public init(interval: Duration) {
        self.interval = interval
    }

    public func wait() async throws {
        let clock = ContinuousClock()
        let now = clock.now
        let start = max(now, nextStart ?? now)
        // The slot is taken before sleeping: the waits that come meanwhile line up behind it.
        nextStart = start.advanced(by: interval)
        if start > now {
            try await Task.sleep(until: start, clock: clock)
        }
    }
}

/// The titles waiting for TMDb in the background, in order, each once: a title the viewer opens goes first.
public struct MetadataQueue: Sendable {
    /// Past this many, the titles that have waited longest are dropped (rows scrolled long ago).
    public let capacity: Int
    public private(set) var ids: [String] = []

    public init(capacity: Int = 120) {
        self.capacity = capacity
    }

    public var isEmpty: Bool { ids.isEmpty }
    public var count: Int { ids.count }

    public func contains(_ id: String) -> Bool { ids.contains(id) }

    /// Adds a title at the end, or at the front when `urgent` (moving it there when it was waiting). False when it was
    /// already waiting and stays in its place.
    @discardableResult
    public mutating func add(_ id: String, urgent: Bool = false) -> Bool {
        if let index = ids.firstIndex(of: id) {
            guard urgent else { return false }
            ids.remove(at: index)
        }
        if urgent {
            ids.insert(id, at: 0)
            if ids.count > capacity { ids.removeLast(ids.count - capacity) }
        } else {
            ids.append(id)
            if ids.count > capacity { ids.removeFirst(ids.count - capacity) }
        }
        return true
    }

    /// The next title to fetch.
    public mutating func next() -> String? {
        ids.isEmpty ? nil : ids.removeFirst()
    }

    public mutating func remove(_ id: String) {
        ids.removeAll { $0 == id }
    }

    public mutating func removeAll() {
        ids.removeAll()
    }
}
