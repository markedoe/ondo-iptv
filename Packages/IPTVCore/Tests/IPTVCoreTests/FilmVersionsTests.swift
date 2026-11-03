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
        let hd = movie("Le Transport", 2020, tmdb: 7)
        let longer = movie("Le Transport : Héritage", nil, tmdb: 7)
        let matrix = movie("The Matrix", 1999, tmdb: 603)
        let short = movie("Matrix VOSTFR", 1999, tmdb: 603)
        let versions = FilmVersions.of([uhd, hd, longer, matrix, short])
        XCTAssertTrue(together(versions, uhd, hd), "the same id: years are not compared")
        XCTAssertTrue(together(versions, uhd, longer), "one title starts with the other")
        XCTAssertTrue(together(versions, matrix, short), "half the words shared")
        XCTAssertEqual(versions.groupCount, 2)
        XCTAssertEqual(versions.diagnostics.groupedByID, 3)
        XCTAssertEqual(versions.diagnostics.idTitleMismatches, 0)
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
        XCTAssertFalse(FilmVersions.titlesAgree("the matrix", "the godfather"), "\"the\" is no shared word")
        XCTAssertFalse(FilmVersions.titlesAgree("alien", "aliens"), "a word is not a prefix of another")
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
                    id: "s\(index)",
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
                for a in group {
                    let justified = group.contains { b in
                        guard b.id != a.id else { return false }
                        let aID = a.tmdbID.flatMap { $0 > 0 ? $0 : nil }
                        let bID = b.tmdbID.flatMap { $0 > 0 ? $0 : nil }
                        let byID = aID != nil && aID == bID && FilmVersions.titlesAgree(FilmVersions.titleKey(a.name), FilmVersions.titleKey(b.name))
                        let byTitle = (aID == nil || bID == nil) && a.year != nil && a.year == b.year
                            && !FilmVersions.titleKey(a.name).isEmpty && FilmVersions.titleKey(a.name) == FilmVersions.titleKey(b.name)
                        return byID || byTitle
                    }
                    XCTAssertTrue(justified, "round \(round): \(a.name) in \(group.map(\.name)) by no rule")
                }
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
