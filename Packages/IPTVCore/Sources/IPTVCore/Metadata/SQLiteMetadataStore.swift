#if canImport(SQLite3) && canImport(Darwin)
import Foundation
import SQLite3

/// The metadata store in a SQLite file (the system's SQLite, on Apple's systems): `metadata.sqlite` in its own folder,
/// left out of backups. It only holds what can be fetched again, so a file of another schema version, or one that
/// cannot be read, is deleted and made again.
///
/// Tables: `title` (key, kind, tmdb_id, backdrop_url, trailer_key, vote_average, vote_count, fetched_at, status),
/// `credit` (title_key, person_key, display_name, role, position, photo_url), `person` (person_key, display_name,
/// tmdb_id, photo_url, fetched_at), `person_title` (person_key, title_key: the local people index) and `dead_image`
/// (url, failed_at). Dates are seconds since 1970.
public final class SQLiteMetadataStore: MetadataStoring, @unchecked Sendable {
    /// Bumped when the tables change.
    static let schemaVersion: Int32 = 1

    public let fileURL: URL
    /// One connection, used by one call at a time.
    private let lock = NSLock()
    private var db: OpaquePointer?

    public init(directory: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var folder = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? folder.setResourceValues(values)
        fileURL = directory.appendingPathComponent("metadata.sqlite", isDirectory: false)
        do {
            try openAndPrepare()
        } catch {
            // Unreadable (damaged, not a database): made again.
            close()
            Self.removeFiles(fileURL)
            try openAndPrepare()
        }
    }

    deinit {
        close()
    }

    private func close() {
        if let db { sqlite3_close_v2(db) }
        db = nil
    }

    private static func removeFiles(_ url: URL) {
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(at: URL(fileURLWithPath: url.path + suffix))
        }
    }

    private func openAndPrepare() throws {
        var handle: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX
        guard sqlite3_open_v2(fileURL.path, &handle, flags, nil) == SQLITE_OK, let handle else {
            if let handle { sqlite3_close_v2(handle) }
            throw SQLiteError.open
        }
        db = handle
        sqlite3_busy_timeout(handle, 2_000)
        var version: Int32 = 0
        try run("PRAGMA user_version") { version = sqlite3_column_int($0, 0) }
        if version != Self.schemaVersion {
            // Another version's tables (or none yet): the cache starts again.
            for table in ["title", "credit", "person", "person_title", "dead_image"] {
                try run("DROP TABLE IF EXISTS \(table)")
            }
            try createTables()
        }
        try run("PRAGMA journal_mode=WAL") { _ in }
    }

    private func createTables() throws {
        try run("""
            CREATE TABLE title (key TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, tmdb_id INTEGER, backdrop_url TEXT,
            trailer_key TEXT, vote_average REAL, vote_count INTEGER, fetched_at REAL NOT NULL, status TEXT NOT NULL)
            """)
        try run("""
            CREATE TABLE credit (title_key TEXT NOT NULL, person_key TEXT NOT NULL, display_name TEXT NOT NULL,
            role TEXT NOT NULL, position INTEGER NOT NULL, photo_url TEXT, PRIMARY KEY (title_key, role, position))
            """)
        try run("""
            CREATE TABLE person (person_key TEXT PRIMARY KEY NOT NULL, display_name TEXT NOT NULL, tmdb_id INTEGER,
            photo_url TEXT, fetched_at REAL)
            """)
        try run("""
            CREATE TABLE person_title (person_key TEXT NOT NULL, title_key TEXT NOT NULL,
            PRIMARY KEY (person_key, title_key)) WITHOUT ROWID
            """)
        try run("CREATE INDEX person_title_by_title ON person_title (title_key)")
        try run("CREATE TABLE dead_image (url TEXT PRIMARY KEY NOT NULL, failed_at REAL NOT NULL)")
        try run("PRAGMA user_version = \(Self.schemaVersion)")
    }

    // MARK: - MetadataStoring

    public func titles(_ keys: [String]) -> [String: TitleMetadata] {
        var found: [String: TitleMetadata] = [:]
        locked {
            for chunk in Self.chunks(Array(Set(keys))) {
                let sql = "SELECT key, kind, tmdb_id, backdrop_url, trailer_key, vote_average, vote_count, fetched_at, status FROM title "
                    + "WHERE key IN (\(Self.placeholders(chunk.count)))"
                try run(sql, chunk.map { Value.text($0) }) { row in
                    guard let key = Self.text(row, 0) else { return }
                    found[key] = TitleMetadata(
                        key: key,
                        kind: Self.text(row, 1) ?? "",
                        tmdbID: Self.int(row, 2),
                        backdropURL: Self.text(row, 3).flatMap(URL.init(string:)),
                        trailerKey: Self.text(row, 4),
                        voteAverage: Self.double(row, 5),
                        voteCount: Self.int(row, 6),
                        fetchedAt: Date(timeIntervalSince1970: Self.double(row, 7) ?? 0),
                        status: TitleMetadata.Status(rawValue: Self.text(row, 8) ?? "") ?? .notFound
                    )
                }
            }
        }
        return found
    }

    public func save(_ titles: [TitleMetadata]) {
        guard !titles.isEmpty else { return }
        locked {
            try transaction {
                for title in titles {
                    try run("""
                        INSERT OR REPLACE INTO title (key, kind, tmdb_id, backdrop_url, trailer_key, vote_average, vote_count, fetched_at, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, [.text(title.key), .text(title.kind), .integer(title.tmdbID.map(Int64.init)), .text(title.backdropURL?.absoluteString),
                              .text(title.trailerKey), .real(title.voteAverage), .integer(title.voteCount.map(Int64.init)),
                              .real(title.fetchedAt.timeIntervalSince1970), .text(title.status.rawValue)])
                }
            }
        }
    }

    public func credits(of titleKey: String) -> [CreditRecord] {
        var credits: [CreditRecord] = []
        locked {
            let sql = "SELECT person_key, display_name, role, position, photo_url FROM credit WHERE title_key = ? ORDER BY role = 'cast', position"
            try run(sql, [.text(titleKey)]) { row in
                guard let key = Self.text(row, 0), let role = Self.text(row, 2).flatMap(CreditRecord.Role.init(rawValue:)) else { return }
                credits.append(CreditRecord(personKey: key, displayName: Self.text(row, 1) ?? key, role: role, position: Self.int(row, 3) ?? 0,
                                            photoURL: Self.text(row, 4).flatMap(URL.init(string:))))
            }
        }
        return credits
    }

    public func saveCredits(_ credits: [CreditRecord], for titleKeys: [String]) {
        guard !titleKeys.isEmpty else { return }
        locked {
            try transaction {
                for titleKey in titleKeys {
                    try run("DELETE FROM credit WHERE title_key = ?", [.text(titleKey)])
                    for credit in credits {
                        try run("""
                            INSERT OR REPLACE INTO credit (title_key, person_key, display_name, role, position, photo_url) VALUES (?, ?, ?, ?, ?, ?)
                            """, [.text(titleKey), .text(credit.personKey), .text(credit.displayName), .text(credit.role.rawValue),
                                  .integer(Int64(credit.position)), .text(credit.photoURL?.absoluteString)])
                        try run("INSERT OR IGNORE INTO person_title (person_key, title_key) VALUES (?, ?)", [.text(credit.personKey), .text(titleKey)])
                    }
                }
                for credit in credits {
                    try upsert(PersonRecord(personKey: credit.personKey, displayName: credit.displayName, photoURL: credit.photoURL))
                }
            }
        }
    }

    public func people(_ keys: [String]) -> [String: PersonRecord] {
        var found: [String: PersonRecord] = [:]
        locked {
            for chunk in Self.chunks(Array(Set(keys))) {
                let sql = "SELECT person_key, display_name, tmdb_id, photo_url, fetched_at FROM person WHERE person_key IN (\(Self.placeholders(chunk.count)))"
                try run(sql, chunk.map { Value.text($0) }) { row in
                    if let person = Self.person(row) { found[person.personKey] = person }
                }
            }
        }
        return found
    }

    public func savePeople(_ people: [PersonRecord]) {
        guard !people.isEmpty else { return }
        locked {
            try transaction {
                for person in people where !person.personKey.isEmpty { try upsert(person) }
            }
        }
    }

    public func index(_ links: [PersonLink]) {
        guard !links.isEmpty else { return }
        locked {
            try transaction {
                for link in links where !link.personKey.isEmpty {
                    try run("INSERT OR IGNORE INTO person (person_key, display_name) VALUES (?, ?)", [.text(link.personKey), .text(link.displayName)])
                    try run("INSERT OR IGNORE INTO person_title (person_key, title_key) VALUES (?, ?)", [.text(link.personKey), .text(link.titleKey)])
                }
            }
        }
    }

    public func titleKeys(ofPerson personKey: String) -> [String] {
        var keys: [String] = []
        locked {
            try run("SELECT title_key FROM person_title WHERE person_key = ? ORDER BY title_key", [.text(personKey)]) { row in
                if let key = Self.text(row, 0) { keys.append(key) }
            }
        }
        return keys
    }

    public func people(matching query: String, limit: Int) -> [IndexedPerson] {
        // The longest word narrows the rows read; `PeopleSearch.ranked` applies the rule.
        guard let tokens = PeopleSearch.tokens(query), let longest = tokens.max(by: { $0.count < $1.count }) else { return [] }
        var candidates: [IndexedPerson] = []
        locked {
            let sql = """
                SELECT p.person_key, p.display_name, p.tmdb_id, p.photo_url, p.fetched_at, COUNT(*) FROM person p
                JOIN person_title t ON t.person_key = p.person_key WHERE p.person_key LIKE ? GROUP BY p.person_key
                """
            try run(sql, [.text("%" + longest + "%")]) { row in
                if let person = Self.person(row) {
                    candidates.append(IndexedPerson(person: person, titleCount: Self.int(row, 5) ?? 0))
                }
            }
        }
        return PeopleSearch.ranked(candidates, query: query, limit: limit)
    }

    public func deadImages(since date: Date) -> Set<URL> {
        var urls = Set<URL>()
        locked {
            try run("SELECT url FROM dead_image WHERE failed_at >= ?", [.real(date.timeIntervalSince1970)]) { row in
                if let url = Self.text(row, 0).flatMap(URL.init(string:)) { urls.insert(url) }
            }
        }
        return urls
    }

    public func markDeadImage(_ url: URL, at date: Date) {
        locked {
            try run("INSERT OR REPLACE INTO dead_image (url, failed_at) VALUES (?, ?)", [.text(url.absoluteString), .real(date.timeIntervalSince1970)])
        }
    }

    public func removeAll() {
        locked {
            try transaction {
                for table in ["title", "credit", "person", "person_title", "dead_image"] {
                    try run("DELETE FROM \(table)")
                }
            }
            try run("VACUUM")
        }
    }

    public func diskUsage() -> Int64 {
        var pages: Int64 = 0
        var pageSize: Int64 = 0
        locked {
            try run("PRAGMA page_count") { pages = sqlite3_column_int64($0, 0) }
            try run("PRAGMA page_size") { pageSize = sqlite3_column_int64($0, 0) }
        }
        return pages * pageSize
    }

    // MARK: - SQLite

    private enum SQLiteError: Error {
        case open
        case statement(String)
    }

    private enum Value {
        case text(String?)
        case integer(Int64?)
        case real(Double?)
    }

    /// SQLite copies the bound text (`SQLITE_TRANSIENT`, a C macro Swift does not import).
    private static var transient: sqlite3_destructor_type {
        unsafeBitCast(OpaquePointer(bitPattern: -1), to: sqlite3_destructor_type.self)
    }

    /// Runs `body` alone on the connection. A failure leaves the store as it was: it is a cache.
    private func locked(_ body: () throws -> Void) {
        lock.lock()
        defer { lock.unlock() }
        guard db != nil else { return }
        try? body()
    }

    private func transaction(_ body: () throws -> Void) throws {
        try run("BEGIN IMMEDIATE")
        do {
            try body()
            try run("COMMIT")
        } catch {
            try? run("ROLLBACK")
            throw error
        }
    }

    /// Runs one statement with its values, calling `row` for each row it returns.
    private func run(_ sql: String, _ values: [Value] = [], row: ((OpaquePointer) -> Void)? = nil) throws {
        var prepared: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &prepared, nil) == SQLITE_OK, let statement = prepared else {
            if let prepared { sqlite3_finalize(prepared) }
            throw SQLiteError.statement(errorMessage)
        }
        defer { sqlite3_finalize(statement) }
        for (index, value) in values.enumerated() {
            let position = Int32(index + 1)
            switch value {
            case .text(let text?): sqlite3_bind_text(statement, position, text, -1, Self.transient)
            case .integer(let number?): sqlite3_bind_int64(statement, position, number)
            case .real(let number?): sqlite3_bind_double(statement, position, number)
            default: sqlite3_bind_null(statement, position)
            }
        }
        while true {
            switch sqlite3_step(statement) {
            case SQLITE_ROW: row?(statement)
            case SQLITE_DONE: return
            default: throw SQLiteError.statement(errorMessage)
            }
        }
    }

    private var errorMessage: String {
        sqlite3_errmsg(db).map { String(cString: $0) } ?? "SQLite error"
    }

    private func upsert(_ person: PersonRecord) throws {
        try run("""
            INSERT INTO person (person_key, display_name, tmdb_id, photo_url, fetched_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (person_key) DO UPDATE SET
            display_name = CASE WHEN excluded.display_name = '' THEN person.display_name ELSE excluded.display_name END,
            tmdb_id = COALESCE(excluded.tmdb_id, person.tmdb_id), photo_url = COALESCE(excluded.photo_url, person.photo_url),
            fetched_at = COALESCE(excluded.fetched_at, person.fetched_at)
            """, [.text(person.personKey), .text(person.displayName), .integer(person.tmdbID.map(Int64.init)),
                  .text(person.photoURL?.absoluteString), .real(person.fetchedAt?.timeIntervalSince1970)])
    }

    private static func person(_ row: OpaquePointer) -> PersonRecord? {
        guard let key = text(row, 0) else { return nil }
        return PersonRecord(personKey: key, displayName: text(row, 1) ?? key, tmdbID: int(row, 2), photoURL: text(row, 3).flatMap(URL.init(string:)),
                            fetchedAt: double(row, 4).map(Date.init(timeIntervalSince1970:)))
    }

    private static func text(_ row: OpaquePointer, _ column: Int32) -> String? {
        guard sqlite3_column_type(row, column) != SQLITE_NULL, let bytes = sqlite3_column_text(row, column) else { return nil }
        return String(cString: bytes)
    }

    private static func int(_ row: OpaquePointer, _ column: Int32) -> Int? {
        sqlite3_column_type(row, column) == SQLITE_NULL ? nil : Int(sqlite3_column_int64(row, column))
    }

    private static func double(_ row: OpaquePointer, _ column: Int32) -> Double? {
        sqlite3_column_type(row, column) == SQLITE_NULL ? nil : sqlite3_column_double(row, column)
    }

    private static func placeholders(_ count: Int) -> String {
        Array(repeating: "?", count: count).joined(separator: ", ")
    }

    /// SQLite takes a limited number of values per statement.
    private static func chunks(_ keys: [String], size: Int = 500) -> [[String]] {
        stride(from: 0, to: keys.count, by: size).map { Array(keys[$0..<min($0 + size, keys.count)]) }
    }
}
#endif
