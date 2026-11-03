import XCTest
@testable import IPTVCore

/// The same cases as Android's `FilmVersionsTest` (android/core).
final class FilmVersionsTests: XCTestCase {
    private var next = 0

    private func movie(_ name: String, _ year: Int? = nil, tmdb: Int? = nil, category: String = "c", id: String? = nil) -> Movie {
        next += 1
        return Movie(
            id: id ?? "m\(next)", name: name, categoryID: category, streamURL: URL(string: "http://stream.example.com/\(next)")!,
            year: year, tmdbID: tmdb
        )
    }

    private func groups(_ versions: FilmVersions) -> [[String]] {
        (0..<versions.groupCount).map { versions.versions(group: $0).map(\.name) }
    }

    private func together(_ versions: FilmVersions, _ a: Movie, _ b: Movie) -> Bool {
        versions.group(of: a.id) == versions.group(of: b.id)
    }

    func testATMDbIDIsAPositiveNumberOnly() {
        XCTAssertEqual(FilmVersions.tmdbID("603"), 603)
        XCTAssertEqual(FilmVersions.tmdbID(" 42 "), 42)
        for raw in [nil, "", " ", "0", "000", "abc", "-3", "+3", "12.5", "1e3", "99999999999", "2147483648"] as [String?] {
            XCTAssertNil(FilmVersions.tmdbID(raw), "\"\(raw ?? "nil")\" is no id")
        }
    }

    func testTheXtreamListGivesTheTMDbID() throws {
        let json = #"[{"stream_id":1,"name":"A","tmdb":"603"},{"stream_id":2,"name":"B","tmdb":0},{"stream_id":3,"name":"C","tmdb":true},{"stream_id":4,"name":"D","tmdb_id":77},{"stream_id":5,"name":"E","tmdb":""}]"#
        let dtos = try JSONDecoder().decode([XtreamVODStreamDTO].self, from: Data(json.utf8))
        XCTAssertEqual(dtos.map(\.tmdbID), [603, nil, nil, 77, nil])
    }

    func testRuleAGroupsOneIDWhenTheTitlesAgree() {
        let uhd = movie("|FR| Le Transport 4K DV", 2021, tmdb: 7)
        let hd = movie("Le Transport", nil, tmdb: 7)
        let longer = movie("Le Transport : Héritage", 2021, tmdb: 7)
        let matrix = movie("The Matrix", 1999, tmdb: 603)
        let short = movie("Matrix VOSTFR", 1999, tmdb: 603)
        let versions = FilmVersions.of([uhd, hd, longer, matrix, short])
        XCTAssertTrue(together(versions, uhd, hd), "the same title, one year missing")
        XCTAssertTrue(together(versions, uhd, longer), "one title starts with the other, the same year on both")
        XCTAssertTrue(together(versions, matrix, short), "the same words, \"the\" left out")
        XCTAssertEqual(versions.groupCount, 2)
        XCTAssertEqual(versions.diagnostics.groupedByID, 3)
        XCTAssertEqual(versions.diagnostics.idTitleMismatches, 0)
    }

    /// The review's probes: under one id, a remake, a sequel or a saga's next film is another film.
    func testFranchisesUnderOneIDStayApart() {
        let pairs: [(String, Int?, String, Int?)] = [
            ("Dune", 1984, "Dune", 2021), ("Dune", 2021, "Dune 2", 2024), ("Dune", nil, "Dune 2", nil), ("Dune", 2024, "Dune: Part Two", 2024),
            ("Alien", 1979, "Alien: Covenant", 2017), ("Alien", nil, "Alien: Covenant", nil), ("Toy Story", 1995, "Toy Story 3", 1995),
            ("It", 2017, "It Chapter Two", 2017), ("The Matrix", 1999, "The Matrix Reloaded", 2003), ("Matrix", nil, "Matrix Resurrections", nil),
            ("Harry Potter and the Chamber of Secrets", 2002, "Harry Potter and the Prisoner of Azkaban", 2002),
            ("Harry Potter and the Chamber of Secrets", nil, "Harry Potter and the Goblet of Fire", nil),
            ("Star Wars Episode IV", nil, "Star Wars Episode V", nil), ("Spider-Man", 2002, "Spider-Man 2", 2002),
            ("Mission Impossible", nil, "Mission Impossible Fallout", nil), ("Halloween", 2018, "Halloween Kills", 2021),
            ("Scream", 2022, "Scream VI", 2022), ("Rocky", 1976, "Rocky Balboa", 2006), ("John Wick", 2014, "John Wick Chapter 4", 2014),
            ("Fast and Furious 7", 2015, "Fast and Furious 8", 2015), ("Le Transport", 2015, "Le Transport 2", 2015),
            ("Le Transport", nil, "Le Transport : Héritage", nil), ("Le Film", 2010, "Le Film deux", 2010),
        ]
        for (a, aYear, b, bYear) in pairs {
            XCTAssertFalse(FilmVersions.titlesAgree(FilmVersions.titleKey(a), aYear, FilmVersions.titleKey(b), bYear), "\(a) / \(b)")
        }
        let saga = ["Harry Potter and the Chamber of Secrets", "Harry Potter and the Prisoner of Azkaban", "Harry Potter and the Goblet of Fire",
                    "Harry Potter and the Order of the Phoenix", "Harry Potter and the Half-Blood Prince"]
            .enumerated().map { movie($0.element, 2002 + $0.offset * 2, tmdb: 1241) }
        XCTAssertEqual(FilmVersions.of(saga).groupCount, 5, "a saga under one collection id")
        let remake = [movie("Dune", 1984, tmdb: 438631), movie("Dune", 2021, tmdb: 438631), movie("Dune 2", 2024, tmdb: 438631)]
        let versions = FilmVersions.of(remake)
        XCTAssertEqual(versions.groupCount, 3)
        XCTAssertEqual(versions.expand([remake[1].id]), [remake[1].id], "watching one does not hide the others")
    }

    /// A stream without a year never brings two years into one group.
    func testOneGroupHoldsOneYear() {
        let none = movie("Dune", nil, tmdb: 1)
        let first = movie("Dune", 1984, tmdb: 1)
        let second = movie("Dune", 2021, tmdb: 1)
        let versions = FilmVersions.of([none, first, second])
        XCTAssertTrue(together(versions, none, first))
        XCTAssertFalse(together(versions, first, second))
    }

    /// Names made of the providers' tags only, or a bare number, never group.
    func testTagOnlyNamesNeverGroup() {
        let list = [movie("4K", 2020), movie("4K", 2020), movie("[FR] 4K", 2020), movie("FR - 2021", 2021), movie("FR - 2021", 2021),
                    movie("MULTI", 2020, tmdb: 9), movie("MULTI 4K", 2020, tmdb: 9)]
        XCTAssertEqual(FilmVersions.of(list).groupCount, list.count)
        XCTAssertFalse(FilmVersions.groupable("fr 4k"))
        XCTAssertTrue(FilmVersions.groupable("1917"), "a title of digits is a title: rule A may group it")
    }

    /// "French Kiss" is not "Kiss", "Johnny English" not "Johnny".
    func testWordsThatAreAlsoTagsStayWords() {
        XCTAssertEqual(FilmVersions.titleKey("French Kiss"), "french kiss")
        XCTAssertEqual(FilmVersions.titleKey("Johnny English"), "johnny english")
        XCTAssertEqual(FilmVersions.of([movie("French Kiss", 1995), movie("Kiss", 1995)]).groupCount, 2)
    }

    /// A stream a panel lists twice (two categories) keeps each row in its own group.
    func testAStreamListedTwiceKeepsEachRow() {
        let a = movie("Sandstorm", 2021, tmdb: 5, category: "x", id: "dup")
        let b = movie("Ocean Drift", 2020, tmdb: 9, category: "y", id: "dup")
        let c = movie("Ocean Drift 4K", 2020, tmdb: 9, category: "y")
        let versions = FilmVersions.of([a, b, c])
        XCTAssertEqual(versions.distinct([b, c]), [b])
        XCTAssertEqual(versions.versions(of: b), [b, c])
        XCTAssertEqual(versions.versions(of: a), [a])
        XCTAssertEqual(versions.expand(["dup"]), ["dup", c.id])
    }

    func testRuleAKeepsApartOneIDWhoseTitlesDisagree() {
        let list = [
            movie("Le Transport", 2021, tmdb: 7), movie("Ocean Drift", 2021, tmdb: 7),
            movie("The Matrix", 1999, tmdb: 9), movie("The Godfather", 1999, tmdb: 9),
            movie("Alien", 1979, tmdb: 348), movie("Aliens", 1979, tmdb: 348),
        ]
        let versions = FilmVersions.of(list)
        XCTAssertEqual(versions.groupCount, 6, "unrelated titles of one id are not grouped")
        XCTAssertEqual(versions.diagnostics.idTitleMismatches, 3)
        XCTAssertFalse(FilmVersions.titlesAgree("the matrix", 1999, "the godfather", 1999), "\"the\" is no shared word")
        XCTAssertFalse(FilmVersions.titlesAgree("alien", 1979, "aliens", 1979), "a word is not a prefix of another")
    }

    func testTwoIDsAreNeverGrouped() {
        let a = movie("Dune", 2021, tmdb: 438631)
        let b = movie("Dune", 2021, tmdb: 841)
        XCTAssertFalse(together(FilmVersions.of([a, b]), a, b))
    }

    func testRuleBGroupsTheSameTitleAndYear() {
        let versions = FilmVersions.of([movie("Sandstorm 4K", 2021), movie("[VOSTFR] Sandstorm", 2021), movie("Sandstorm (MULTI)", 2021, tmdb: 0)])
        XCTAssertEqual(groups(versions), [["Sandstorm 4K", "[VOSTFR] Sandstorm", "Sandstorm (MULTI)"]], "an id of 0 is no id")
        XCTAssertEqual(versions.diagnostics.groupedByTitleAndYear, 2)
    }

    func testRuleBNeedsTheSameTitleAndAYearOnBoth() {
        let cases: [([Movie], String)] = [
            ([movie("Sandstorm"), movie("Sandstorm 4K")], "no year"),
            ([movie("Sandstorm", 2021), movie("Sandstorm 4K")], "one year"),
            ([movie("Sandstorm", 2021), movie("Sandstorm", 2022)], "years one apart"),
            ([movie("Sandstorm", 2021), movie("Ocean Drift", 2021)], "other titles"),
            ([movie("Alien", 1986), movie("Aliens", 1986)], "near titles"),
            ([movie("Sandstorm", 2021), movie("Sandstorm 2", 2021)], "a sequel"),
            ([movie("Dune", 1984), movie("Dune", 2021)], "a remake"),
            ([movie("Dune", 2021, tmdb: 1), movie("Dune", 2021, tmdb: 2)], "two ids"),
        ]
        for (list, name) in cases { XCTAssertEqual(FilmVersions.of(list).groupCount, 2, name) }
    }

    func testAStreamWithoutIDJoinsTheOnlyIDOfItsTitle() {
        let withID = movie("Sandstorm 4K", 2021, tmdb: 5)
        let without = movie("Sandstorm VOSTFR", 2021)
        let versions = FilmVersions.of([without, withID])
        XCTAssertTrue(together(versions, withID, without))
        XCTAssertEqual(groups(versions), [["Sandstorm VOSTFR", "Sandstorm 4K"]], "groups in catalogue order")
    }

    func testAStreamWithoutIDNeverBridgesTwoIDs() {
        let one = movie("Dune", 2021, tmdb: 1)
        let two = movie("Dune 4K", 2021, tmdb: 2)
        let without = movie("Dune VOSTFR", 2021)
        let alsoWithout = movie("Dune MULTI", 2021)
        let versions = FilmVersions.of([one, without, two, alsoWithout])
        XCTAssertFalse(together(versions, one, two))
        XCTAssertFalse(together(versions, one, without))
        XCTAssertFalse(together(versions, two, without))
        XCTAssertTrue(together(versions, without, alsoWithout), "the streams without an id stay together")
        XCTAssertEqual(versions.diagnostics.ambiguousTitles, 2)
    }

    func testOffIsTheCatalogueAsItIs() {
        let list = [movie("Sandstorm 4K", 2021, id: "x"), movie("Sandstorm", 2021, id: "x"), movie("Dune", 2021, tmdb: 1)]
        let versions = FilmVersions.of(list, grouped: false)
        XCTAssertFalse(versions.isGrouped)
        XCTAssertEqual(versions.distinct(list), list)
        XCTAssertEqual(versions.expand(["x"]), ["x"])
        XCTAssertEqual(versions.versions(of: list[1]), [list[1]])
        XCTAssertEqual(versions.groupCount, 3)
        XCTAssertFalse(versions.hasVersions(list[0]))
    }

    func testAFilmIsInEveryCategoryOfItsVersions() {
        let action = movie("Sandstorm 4K", 2021, category: "action")
        let other = movie("Ocean Drift", 2020, category: "action")
        let french = movie("Sandstorm VFF", 2021, category: "french")
        let list = [action, other, french]
        let versions = FilmVersions.of(list)
        XCTAssertEqual(versions.distinct(list), [action, other])
        XCTAssertEqual(versions.distinct(list.filter { $0.categoryID == "french" }), [french])
        XCTAssertEqual(versions.versions(of: french), [action, french])
        XCTAssertTrue(versions.hasVersions(french))
        XCTAssertEqual(versions.expand([french.id]), [action.id, french.id])
        XCTAssertEqual(versions.expand(["unknown"]), ["unknown"])
    }

    func testBadgesComeFromTheNameAndTheCategory() {
        XCTAssertEqual(FilmVersions.tags(name: "|FR| Sandstorm 4K DV VFF", categoryName: nil), [.uhd, .dolbyVision, .vff])
        XCTAssertEqual(FilmVersions.tags(name: "Sandstorm 2160p HDR10 H.265", categoryName: nil), [.uhd, .hdr, .hevc])
        XCTAssertEqual(FilmVersions.tags(name: "Sandstorm Dolby Vision", categoryName: nil), [.dolbyVision])
        XCTAssertEqual(FilmVersions.tags(name: "Sandstorm", categoryName: "FILMS 1080p VOSTFR"), [.hd, .vostfr])
        XCTAssertEqual(FilmVersions.tags(name: "Sandstorm FHD", categoryName: "UHD"), [.uhd], "4K leaves HD out")
        XCTAssertEqual(FilmVersions.tags(name: "Sandstorm", categoryName: "Action"), [])
        let list = [movie("Sandstorm 4K", 2021), movie("Sandstorm", 2021), movie("Sandstorm 4K", 2021), movie("Sandstorm", 2021)]
        let badges = FilmVersions.badges(list) { _ in nil }
        XCTAssertEqual(badges.map(\.label), ["4K", nil, "4K 2", nil])
        XCTAssertEqual(badges.map(\.ordinal), [1, 1, 2, 2], "two versions of the same tags are two badges")
    }

    func testTheDefaultVersion() {
        let dv = movie("Sandstorm 4K DV", 2021)
        let uhd = movie("Sandstorm 4K", 2021)
        let hd = movie("Sandstorm HD", 2021)
        let vostfr = movie("Sandstorm VOSTFR", 2021)
        let list = [vostfr, dv, uhd, hd]
        let names: (String) -> String? = { _ in nil }
        let everything = PictureSupport(hdr: true, dolbyVision: true, uhd: true)
        XCTAssertEqual(FilmVersions.defaultVersion(list, inProgress: hd.id, chosen: [dv.id], support: everything, categoryName: names), hd, "the one in progress first")
        XCTAssertEqual(FilmVersions.defaultVersion(list, inProgress: "elsewhere", chosen: ["gone", vostfr.id, dv.id], support: everything, categoryName: names), vostfr, "then the one chosen last")
        XCTAssertEqual(FilmVersions.defaultVersion(list, inProgress: nil, chosen: [], support: everything, categoryName: names), dv, "then the best for the screen")
        XCTAssertEqual(FilmVersions.defaultVersion(list, inProgress: nil, chosen: [], support: PictureSupport(uhd: true), categoryName: names), uhd, "no Dolby Vision without the screen for it")
        XCTAssertEqual(FilmVersions.defaultVersion(list, inProgress: nil, chosen: [], support: PictureSupport(), categoryName: names), hd, "HD on an HD screen")
        XCTAssertEqual(FilmVersions.defaultVersion([vostfr, movie("Sandstorm VFF", 2021)], inProgress: nil, chosen: [], support: everything, categoryName: names), vostfr, "else the first")
        XCTAssertEqual(FilmVersions.defaultVersion([dv], inProgress: nil, chosen: [], support: PictureSupport(), categoryName: names), dv)
        XCTAssertNil(FilmVersions.defaultVersion([], inProgress: nil, chosen: [], support: everything, categoryName: names))
    }

    /// Random catalogues: every stream in exactly one group, never two ids together, every grouping justified by a rule.
    func testRandomCataloguesKeepEveryStreamOnce() {
        let titles = ["Sandstorm", "Alien", "Aliens", "Le Transport", "The Matrix", "Matrix", "The Godfather", "Dune", "Dune 2", "Ocean Drift", "Up", "[4K]"]
        let decorations = ["%@", "%@ 4K", "%@ VOSTFR", "|FR| %@", "%@ (MULTI)", "[DV] %@ HDR", "FR - %@", "%@ 2"]
        let tmdbIDs: [Int?] = [nil, nil, 0, -1, 1, 2, 3, 603]
        let years: [Int?] = [nil, 2020, 2021]
        for round in 0..<300 {
            var random = SplitMix(seed: UInt64(round))
            let list = (0..<Int(random.next() % 60)).map { index in
                Movie(
                    id: index % 7 == 6 ? "s\(index - 1)" : "s\(index)",  // a stream listed twice now and then
                    name: String(format: decorations[random.index(decorations.count)], titles[random.index(titles.count)]),
                    categoryID: "c\(random.index(3))",
                    streamURL: URL(string: "http://stream.example.com/\(index)")!,
                    year: years[random.index(years.count)],
                    tmdbID: tmdbIDs[random.index(tmdbIDs.count)]
                )
            }
            let versions = FilmVersions.of(list)
            let seen = (0..<versions.groupCount).flatMap { versions.versions(group: $0).map(\.id) }
            XCTAssertEqual(seen.sorted(), list.map(\.id).sorted(), "round \(round): every stream once")
            for g in 0..<versions.groupCount {
                let group = versions.versions(group: g)
                let ids = Set(group.compactMap { $0.tmdbID.flatMap { $0 > 0 ? $0 : nil } })
                XCTAssertLessThanOrEqual(ids.count, 1, "round \(round): two ids in \(group.map(\.name))")
                guard group.count > 1 else { continue }
                // Forbidden whatever the code: two years, or two different numbers, in one group.
                XCTAssertLessThanOrEqual(Set(group.compactMap(\.year)).count, 1, "round \(round): two years in \(group.map(\.name))")
                let numbers = Set(group.map { FilmVersions.titleKey($0.name).split(separator: " ").map(String.init).filter(FilmVersions.isNumber).sorted() })
                XCTAssertEqual(numbers.count, 1, "round \(round): two numbers in \(group.map(\.name))")
                // A stream without an id is with an id only when no other id has its title and year.
                if let groupID = ids.first {
                    for member in group where member.tmdbID.flatMap({ $0 > 0 ? $0 : nil }) == nil {
                        let key = FilmVersions.titleKey(member.name)
                        let others = list.filter { other in
                            guard let id = other.tmdbID, id > 0, id != groupID else { return false }
                            return other.year == member.year && FilmVersions.titleKey(other.name) == key
                        }
                        XCTAssertTrue(others.isEmpty, "round \(round): \(member.name) joined id \(groupID) though another id has its title")
                    }
                }
                for a in group {
                    let justified = group.contains { b in
                        guard b != a else { return false }
                        let aID = a.tmdbID.flatMap { $0 > 0 ? $0 : nil }
                        let bID = b.tmdbID.flatMap { $0 > 0 ? $0 : nil }
                        let byID = aID != nil && aID == bID && FilmVersions.titleKey(a.name) == FilmVersions.titleKey(b.name)
                            || aID != nil && aID == bID && FilmVersions.titlesAgree(FilmVersions.titleKey(a.name), a.year, FilmVersions.titleKey(b.name), b.year)
                        let byTitle = (aID == nil || bID == nil) && a.year != nil && a.year == b.year
                            && !FilmVersions.titleKey(a.name).isEmpty && FilmVersions.titleKey(a.name) == FilmVersions.titleKey(b.name)
                        return byID || byTitle
                    }
                    XCTAssertTrue(justified, "round \(round): \(a.name) in \(group.map(\.name)) by no rule")
                }
            }
            // A category's list shows each film it has exactly once.
            for category in Set(list.map(\.categoryID)) {
                let sub = list.filter { $0.categoryID == category }
                let shown = versions.distinct(sub)
                XCTAssertEqual(Set(sub.compactMap { versions.group(of: $0) }).count, shown.count, "round \(round): category \(category)")
            }
            let off = FilmVersions.of(list, grouped: false)
            XCTAssertEqual(off.distinct(list), list, "round \(round): off is the catalogue")
            XCTAssertEqual(off.groupCount, list.count)
            XCTAssertEqual(versions.distinct(list).count, versions.groupCount, "round \(round): one card per film")
        }
    }

    func testAPanelGivingOneIDToEverythingStaysQuick() {
        let list = (0..<20_000).map {
            Movie(id: "s\($0)", name: "T\($0)", categoryID: "c", streamURL: URL(string: "http://stream.example.com/\($0)")!, year: 2020, tmdbID: 1)
        }
        let started = Date()
        let versions = FilmVersions.of(list)
        XCTAssertLessThan(Date().timeIntervalSince(started), 10, "linear, not quadratic")
        XCTAssertEqual(versions.groupCount, list.count, "titles that disagree stay apart")
        XCTAssertEqual(versions.diagnostics.idTitleMismatches, list.count - 1)
    }
}

/// A small seeded generator: the same catalogues at every run.
private struct SplitMix {
    var state: UInt64
    init(seed: UInt64) { state = seed }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    mutating func index(_ count: Int) -> Int { Int(next() % UInt64(count)) }
}
