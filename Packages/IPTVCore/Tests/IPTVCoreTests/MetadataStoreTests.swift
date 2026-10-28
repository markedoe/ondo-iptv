import XCTest
@testable import IPTVCore

/// What TMDb said, kept between launches: the keys, how long an answer is trusted, the people index, and the stores.
final class MetadataStoreTests: XCTestCase {
    private let day: TimeInterval = 24 * 3600

    // MARK: - Keys

    func testATitlesKeyIsItsTMDbIDElseItsWordsAndYear() {
        XCTAssertEqual(MetadataKey.tmdb(.movie, id: " 910001 "), "movie:910001")
        XCTAssertEqual(MetadataKey.tmdb(.series, id: "7"), "series:7", "movies and series have ids of their own")
        XCTAssertNil(MetadataKey.tmdb(.movie, id: "abc"))
        XCTAssertNil(MetadataKey.tmdb(.movie, id: "0"))
        XCTAssertNil(MetadataKey.tmdb(.movie, id: nil))

        XCTAssertEqual(MetadataKey.title(.movie, name: "Sandstorm (2021)", year: nil), "movie:sandstorm:2021", "the year in the name")
        XCTAssertEqual(MetadataKey.title(.movie, name: "FR - Sandstorm [MULTI]", year: 2021), "movie:sandstorm:2021",
                       "another playlist's name for the same film: the same key")
        XCTAssertEqual(MetadataKey.title(.series, name: "Copper Valley (US)", year: nil), "series:coppervalley:")
        XCTAssertNotEqual(MetadataKey.title(.movie, name: "Sandstorm", year: 1988), MetadataKey.title(.movie, name: "Sandstorm", year: 2021))
        XCTAssertNil(MetadataKey.title(.movie, name: " - ", year: 2021), "no word, no key")

        XCTAssertEqual(MetadataKey.all(.movie, tmdbID: "603", name: "Sandstorm", year: 2021), ["movie:603", "movie:sandstorm:2021"],
                       "read by id first, then by title")
        XCTAssertEqual(MetadataKey.all(.movie, tmdbID: nil, name: "Sandstorm", year: 2021), ["movie:sandstorm:2021"])
        XCTAssertEqual(MetadataKey.person("Zoë  ÁDLER"), "zoe adler")
    }

    // MARK: - Freshness

    func testAnAnswerIsTrustedThirtyDaysANotFoundSevenAndNothingFromTheFuture() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        func title(_ age: TimeInterval, _ status: TitleMetadata.Status) -> TitleMetadata {
            TitleMetadata(key: "movie:1", kind: "movie", fetchedAt: now.addingTimeInterval(-age), status: status)
        }
        XCTAssertTrue(MetadataFreshness.isFresh(title(29 * day, .found), now: now))
        XCTAssertFalse(MetadataFreshness.isFresh(title(31 * day, .found), now: now))
        XCTAssertTrue(MetadataFreshness.isFresh(title(6 * day, .notFound), now: now))
        XCTAssertFalse(MetadataFreshness.isFresh(title(8 * day, .notFound), now: now), "TMDb is asked again sooner")
        XCTAssertFalse(MetadataFreshness.isFresh(title(-day, .found), now: now), "a date in the future is not trusted")
        XCTAssertTrue(MetadataFreshness.isFresh(now.addingTimeInterval(-6 * day), maxAge: MetadataFreshness.deadImage, now: now))
        XCTAssertFalse(MetadataFreshness.isFresh(now.addingTimeInterval(-8 * day), maxAge: MetadataFreshness.deadImage, now: now))
    }

    func testVotesNeedARatingAndACount() {
        let now = Date()
        XCTAssertEqual(TitleMetadata(key: "k", kind: "movie", voteAverage: 7.1, voteCount: 12_345, fetchedAt: now).votes?.count, 12_345)
        XCTAssertNil(TitleMetadata(key: "k", kind: "movie", voteAverage: 7.1, voteCount: 0, fetchedAt: now).votes)
        XCTAssertNil(TitleMetadata(key: "k", kind: "movie", voteAverage: nil, voteCount: 40, fetchedAt: now).votes)
        XCTAssertNil(TitleMetadata(key: "k", kind: "movie", voteAverage: 7.1, voteCount: 40, fetchedAt: now, status: .notFound).votes)
        let missing = TitleMetadata(key: "k", kind: "series", info: nil, fetchedAt: now)
        XCTAssertEqual(missing.status, .notFound)
    }

    // MARK: - People

    func testPeopleFromSeveralSourcesAreMergedOnce() {
        let merged = PeopleSearch.merged(["Ada Vance", "Tom Ellery"], ["ADA VANCE", "Zoë Adler"], ["Zoe Adler", "Lena Ward"])
        XCTAssertEqual(merged, ["Ada Vance", "Tom Ellery", "Zoë Adler", "Lena Ward"], "the first spelling met wins, in order")
        XCTAssertEqual(PeopleSearch.merged(["A1", "B2", "C3"], limit: 2), ["A1", "B2"])
        XCTAssertEqual(PeopleSearch.merged(["", "  "], ["Ada"]), ["Ada"])
    }

    func testIndexedPeopleAreRankedLikeThePlaylistsNames() {
        func person(_ name: String, _ count: Int) -> IndexedPerson {
            IndexedPerson(person: PersonRecord(personKey: MetadataKey.person(name), displayName: name), titleCount: count)
        }
        let people = [person("Mara Vance", 9), person("Ada Vance", 2), person("Vance Holt", 1), person("Evan Cole", 30)]
        XCTAssertEqual(PeopleSearch.ranked(people, query: "vance").map(\.person.displayName), ["Vance Holt", "Mara Vance", "Ada Vance"],
                       "a name that starts with the query first, then the most often named")
        XCTAssertEqual(PeopleSearch.ranked(people, query: "ada van").map(\.person.displayName), ["Ada Vance"])
        XCTAssertTrue(PeopleSearch.ranked(people, query: "va").isEmpty, "three letters at least")
        XCTAssertTrue(PeopleSearch.ranked(people, query: "ance").isEmpty, "words are matched from their start")
    }

    func testProviderFieldsBecomeIndexEntriesForEachKey() {
        let links = PersonLink.links(fields: ["Ada Vance (Mara), Tom Ellery", "ada vance"], titleKeys: ["movie:1", "movie:quietwater:2021"])
        XCTAssertEqual(links.count, 4, "each person once, under each key")
        XCTAssertEqual(Set(links.map(\.personKey)), ["ada vance", "tom ellery"])
        XCTAssertEqual(links.first?.displayName, "Ada Vance")
    }

    // MARK: - Stores

    func testTheInMemoryStore() {
        exercise(InMemoryMetadataStore())
    }

    #if canImport(SQLite3) && canImport(Darwin)
    func testTheSQLiteStoreKeepsItsDataAndStartsAgainFromADamagedFile() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("MetadataStoreTests-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        exercise(try SQLiteMetadataStore(directory: folder))

        // Reopened: what was saved is there.
        let reopened = try SQLiteMetadataStore(directory: folder)
        XCTAssertEqual(reopened.titles(["movie:603"])["movie:603"]?.trailerKey, "trailerEN01")
        XCTAssertGreaterThan(reopened.diskUsage(), 0)
        let values = try folder.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true, "a cache is left out of backups")

        // A file that is not a database is made again.
        let file = reopened.fileURL
        for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(at: URL(fileURLWithPath: file.path + suffix)) }
        try Data("not a database".utf8).write(to: file)
        let rebuilt = try SQLiteMetadataStore(directory: folder)
        XCTAssertTrue(rebuilt.titles(["movie:603"]).isEmpty)
        rebuilt.save([TitleMetadata(key: "movie:1", kind: "movie", fetchedAt: Date())])
        XCTAssertEqual(rebuilt.titles(["movie:1"]).count, 1)
    }
    #endif

    /// What every store must do.
    private func exercise(_ store: any MetadataStoring, file: StaticString = #filePath, line: UInt = #line) {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let old = now.addingTimeInterval(-40 * day)
        let backdrop = URL(string: "https://image.tmdb.org/t/p/w1280/sandstorm.jpg")!

        // Titles: an old one is still read (it shows while it is fetched again), a newer one replaces it.
        store.save([
            TitleMetadata(key: "movie:603", kind: "movie", tmdbID: 603, backdropURL: backdrop, trailerKey: "trailerOLD1", voteAverage: 7.1,
                          voteCount: 12_345, fetchedAt: old),
            TitleMetadata(key: "movie:sandstorm:2021", kind: "movie", tmdbID: 603, fetchedAt: old),
            TitleMetadata(key: "series:nothing:", kind: "series", fetchedAt: now, status: .notFound),
        ])
        var read = store.titles(["movie:603", "series:nothing:", "movie:unknown:"])
        XCTAssertEqual(read.count, 2, file: file, line: line)
        XCTAssertEqual(read["movie:603"]?.backdropURL, backdrop, file: file, line: line)
        XCTAssertEqual(read["movie:603"]?.voteCount, 12_345, file: file, line: line)
        XCTAssertEqual(read["movie:603"]?.fetchedAt, old, file: file, line: line)
        XCTAssertEqual(read["series:nothing:"]?.status, .notFound, file: file, line: line)
        store.save([TitleMetadata(key: "movie:603", kind: "movie", tmdbID: 603, backdropURL: backdrop, trailerKey: "trailerEN01", fetchedAt: now)])
        read = store.titles(["movie:603"])
        XCTAssertEqual(read["movie:603"]?.trailerKey, "trailerEN01", file: file, line: line)
        XCTAssertNil(read["movie:603"]?.voteCount, "replaced whole", file: file, line: line)

        // Credits: directors first, in order; their people join the index under each of the title's keys.
        let photo = URL(string: "https://image.tmdb.org/t/p/w185/ada.jpg")!
        let credits = [
            CreditRecord(personKey: "ada vance", displayName: "Ada Vance", role: .cast, position: 0, photoURL: photo),
            CreditRecord(personKey: "tom ellery", displayName: "Tom Ellery", role: .cast, position: 1),
            CreditRecord(personKey: "mira holt", displayName: "Mira Holt", role: .director, position: 0),
        ]
        store.saveCredits(credits, for: ["movie:603", "movie:sandstorm:2021"])
        XCTAssertEqual(store.credits(of: "movie:sandstorm:2021").map(\.displayName), ["Mira Holt", "Ada Vance", "Tom Ellery"], file: file, line: line)
        store.saveCredits(Array(credits.prefix(1)), for: ["movie:603"])
        XCTAssertEqual(store.credits(of: "movie:603").map(\.displayName), ["Ada Vance"], "credits are replaced", file: file, line: line)
        XCTAssertEqual(store.titleKeys(ofPerson: "ada vance"), ["movie:603", "movie:sandstorm:2021"], file: file, line: line)
        XCTAssertEqual(store.people(["ada vance"])["ada vance"]?.photoURL, photo, file: file, line: line)

        // The provider's cast fields: people without a photo, a known photo kept.
        store.index(PersonLink.links(fields: ["Ada Vance, Lena Ward"], titleKeys: ["series:coppervalley:"]))
        store.savePeople([PersonRecord(personKey: "ada vance", displayName: "Ada Vance", tmdbID: 42)])
        let ada = store.people(["ada vance", "lena ward"])
        XCTAssertEqual(ada["ada vance"]?.photoURL, photo, "a photo is not forgotten", file: file, line: line)
        XCTAssertEqual(ada["ada vance"]?.tmdbID, 42, file: file, line: line)
        XCTAssertEqual(ada["lena ward"]?.displayName, "Lena Ward", file: file, line: line)
        XCTAssertEqual(store.titleKeys(ofPerson: "ada vance").count, 3, file: file, line: line)

        // Search in the index: only people linked to a title, the most often named first.
        store.savePeople([PersonRecord(personKey: "ada vancouver", displayName: "Ada Vancouver", fetchedAt: now)])
        XCTAssertEqual(store.people(matching: "vance", limit: 10).map(\.person.displayName), ["Ada Vance"], file: file, line: line)
        XCTAssertEqual(store.people(matching: "VANCE", limit: 10).first?.titleCount, 3, file: file, line: line)
        XCTAssertEqual(store.people(matching: "ward", limit: 10).map(\.person.displayName), ["Lena Ward"], file: file, line: line)
        XCTAssertTrue(store.people(matching: "va", limit: 10).isEmpty, file: file, line: line)

        // Dead images: those of the last days.
        let dead = URL(string: "http://img.example.com/dead.jpg")!
        let older = URL(string: "http://img.example.com/older.jpg")!
        store.markDeadImage(dead, at: now)
        store.markDeadImage(older, at: old)
        XCTAssertEqual(store.deadImages(since: now.addingTimeInterval(-MetadataFreshness.deadImage)), [dead], file: file, line: line)

        // Clear Cache.
        store.removeAll()
        XCTAssertTrue(store.titles(["movie:603"]).isEmpty, file: file, line: line)
        XCTAssertTrue(store.credits(of: "movie:603").isEmpty, file: file, line: line)
        XCTAssertTrue(store.titleKeys(ofPerson: "ada vance").isEmpty, file: file, line: line)
        XCTAssertTrue(store.deadImages(since: .distantPast).isEmpty, file: file, line: line)

        // Saved again afterwards (the SQLite test reopens it).
        store.save([TitleMetadata(key: "movie:603", kind: "movie", tmdbID: 603, trailerKey: "trailerEN01", fetchedAt: now)])
    }

    // MARK: - Prefetch

    func testTheQueueKeepsEachTitleOnceAndPutsAnOpenedOneFirst() {
        var queue = MetadataQueue(capacity: 3)
        XCTAssertTrue(queue.add("a"))
        XCTAssertTrue(queue.add("b"))
        XCTAssertFalse(queue.add("a"), "already waiting")
        XCTAssertTrue(queue.add("b", urgent: true))
        XCTAssertEqual(queue.ids, ["b", "a"])
        queue.add("c")
        queue.add("d")
        XCTAssertEqual(queue.ids, ["a", "c", "d"], "past its capacity, the title that waited longest goes")
        queue.add("e", urgent: true)
        XCTAssertEqual(queue.ids, ["e", "a", "c"], "an opened title is never the one dropped")
        XCTAssertEqual(queue.next(), "e")
        queue.remove("c")
        XCTAssertEqual(queue.next(), "a")
        XCTAssertNil(queue.next())
        XCTAssertTrue(queue.isEmpty)
    }

    func testThePacerSpacesRequestsOut() async throws {
        let pacer = RequestPacer(interval: .milliseconds(40))
        let clock = ContinuousClock()
        let start = clock.now
        for _ in 0..<4 { try await pacer.wait() }
        XCTAssertGreaterThanOrEqual(clock.now - start, .milliseconds(120), "three intervals between four requests")
    }
}
