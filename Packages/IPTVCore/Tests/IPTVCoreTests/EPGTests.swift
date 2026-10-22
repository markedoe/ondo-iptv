import XCTest
@testable import IPTVCore

final class EPGTests: XCTestCase {
    func testDateParsing() {
        XCTAssertEqual(XMLTVParser.parseDate("20251009200000 +0200"), Date.utc(2025, 10, 9, 18))
        XCTAssertEqual(XMLTVParser.parseDate("20251009200000+0200"), Date.utc(2025, 10, 9, 18))
        XCTAssertEqual(XMLTVParser.parseDate("20251009200000 -0130"), Date.utc(2025, 10, 9, 21, 30))
        XCTAssertEqual(XMLTVParser.parseDate("20251009200000"), Date.utc(2025, 10, 9, 20))
        XCTAssertEqual(XMLTVParser.parseDate("202510092000"), Date.utc(2025, 10, 9, 20))
        XCTAssertEqual(XMLTVParser.parseDate("20240229000000 +0000"), Date.utc(2024, 2, 29, 0))
        XCTAssertNil(XMLTVParser.parseDate("garbage"))
        XCTAssertNil(XMLTVParser.parseDate("20251309200000"))
    }

    func testParsesGzipAndPlainIdentically() throws {
        let plain = try XMLTVParser.parse(try Fixture.data("epg.xml"))
        let gzipped = try XMLTVParser.parse(try Fixture.data("epg.xml.gz"))
        XCTAssertEqual(plain.programs, gzipped.programs)
        XCTAssertEqual(plain.channels.count, 3)
        XCTAssertEqual(plain.programCount, 5)
    }

    func testProgrammeFields() throws {
        let data = try XMLTVParser.parse(try Fixture.data("epg.xml"))
        let tv1 = try XCTUnwrap(data.programs["TV1.fr"])
        XCTAssertEqual(tv1.map(\.title), ["Too old", "Le JT", "Film du soir"], "sorted by start")
        let jt = tv1[1]
        XCTAssertEqual(jt.summary, "L'actualité & la météo")
        XCTAssertEqual(jt.category, "News")
        XCTAssertEqual(jt.start, Date.utc(2025, 10, 9, 18))
        XCTAssertEqual(jt.end, Date.utc(2025, 10, 9, 18, 45))
        let film = tv1[2]
        XCTAssertEqual(film.subtitle, "Lighthouse")
        XCTAssertEqual(film.episode, "S02E05")
        XCTAssertEqual(film.rating, "-12")
        XCTAssertEqual(film.iconURL?.absoluteString, "http://epg.example.com/film.jpg")
        XCTAssertEqual(data.programs["nation2"]?.first?.title, "Chantez maintenant", "CDATA")
        XCTAssertEqual(data.channels["TV1.fr"]?.iconURL?.absoluteString, "http://epg.example.com/tv1.png")
    }

    func testFilteringByWindowAndChannels() throws {
        let filter = EPGFilter(
            window: DateInterval(start: Date.utc(2025, 10, 9, 0), end: Date.utc(2025, 10, 10, 0)),
            channelIDs: ["tv1.FR"],
            channelNameKeys: [TextNormalizer.channelMatchKey("FR: Nation 2 FHD")]
        )
        let data = try XMLTVParser.parse(try Fixture.data("epg.xml.gz"), filter: filter)
        XCTAssertEqual(data.programs["TV1.fr"]?.count, 2, "old programme dropped by the window")
        XCTAssertEqual(data.programs["nation2"]?.count, 1, "matched by display name")
        XCTAssertNil(data.programs["unused.fr"])
    }

    func testTimeShift() throws {
        let data = try XMLTVParser.parse(try Fixture.data("epg.xml"), timeShift: 3600)
        XCTAssertEqual(data.programs["nation2"]?.first?.start, Date.utc(2025, 10, 9, 19))
    }

    func testIndexMatchingAndLookups() throws {
        let index = EPGIndex(data: try XMLTVParser.parse(try Fixture.data("epg.xml")))
        let byID = Channel(id: "1", name: "Whatever", categoryID: "c", streamURL: URL(string: "http://x/1")!, epgID: "tv1.fr")
        let byName = Channel(id: "2", name: "FR | Nation 2 HD", categoryID: "c", streamURL: URL(string: "http://x/2")!)
        let unknown = Channel(id: "3", name: "Nope", categoryID: "c", streamURL: URL(string: "http://x/3")!)

        XCTAssertEqual(index.guideID(for: byID), "TV1.fr")
        XCTAssertEqual(index.guideID(for: byName), "nation2")
        XCTAssertNil(index.guideID(for: unknown))
        XCTAssertEqual(index.iconURL(for: byID)?.absoluteString, "http://epg.example.com/tv1.png")

        let now = Date.utc(2025, 10, 9, 18, 50)
        XCTAssertEqual(index.current(for: byID, at: now)?.title, "Film du soir")
        XCTAssertEqual(index.upcoming(for: byID, at: Date.utc(2025, 10, 9, 18, 10)).map(\.title), ["Le JT", "Film du soir"])
        XCTAssertEqual(index.upcoming(for: byID, at: Date.utc(2025, 10, 9, 17)).map(\.title), ["Le JT", "Film du soir"], "gap: next programmes")
        XCTAssertNil(index.current(for: byID, at: Date.utc(2025, 10, 10, 12)))
        let program = try XCTUnwrap(index.current(for: byID, at: now))
        XCTAssertEqual(program.progress(at: Date.utc(2025, 10, 9, 19, 52).addingTimeInterval(30)), 0.5, accuracy: 0.0001)
        XCTAssertTrue(program.isLive(at: now))
        XCTAssertFalse(program.isPast(at: now))
    }

    func testMergeKeepsFirstSourceOnOverlap() {
        let a = EPGData(programs: ["c": [EPGProgram(channelID: "c", title: "A", start: .utc(2025, 1, 1, 10), end: .utc(2025, 1, 1, 11))]])
        let b = EPGData(programs: [
            "c": [
                EPGProgram(channelID: "c", title: "B", start: .utc(2025, 1, 1, 10, 30), end: .utc(2025, 1, 1, 11, 30)),
                EPGProgram(channelID: "c", title: "C", start: .utc(2025, 1, 1, 11), end: .utc(2025, 1, 1, 12)),
            ],
            "d": [EPGProgram(channelID: "d", title: "D", start: .utc(2025, 1, 1, 10), end: .utc(2025, 1, 1, 11))],
        ])
        var merged = a
        merged.merge(b)
        XCTAssertEqual(merged.programs["c"]?.map(\.title), ["A", "C"])
        XCTAssertEqual(merged.programs["d"]?.count, 1)
        merged.prune(before: .utc(2025, 1, 1, 11, 30))
        XCTAssertEqual(merged.programs["c"]?.map(\.title), ["C"])
        XCTAssertNil(merged.programs["d"])
    }

    /// A long programme holding a short one (a film around a news flash) still blocks what overlaps its end.
    func testMergeSeesOverlapsBehindANestedProgramme() {
        var merged = EPGData(programs: ["c": [
            EPGProgram(channelID: "c", title: "Long", start: .utc(2025, 1, 1, 10), end: .utc(2025, 1, 1, 14)),
            EPGProgram(channelID: "c", title: "Flash", start: .utc(2025, 1, 1, 11), end: .utc(2025, 1, 1, 12)),
        ]])
        merged.merge(EPGData(programs: ["c": [
            EPGProgram(channelID: "c", title: "Inside", start: .utc(2025, 1, 1, 12, 30), end: .utc(2025, 1, 1, 13)),
            EPGProgram(channelID: "c", title: "Before", start: .utc(2025, 1, 1, 9), end: .utc(2025, 1, 1, 10)),
            EPGProgram(channelID: "c", title: "After", start: .utc(2025, 1, 1, 14), end: .utc(2025, 1, 1, 15)),
        ]]))
        XCTAssertEqual(merged.programs["c"]?.map(\.title), ["Before", "Long", "Flash", "After"])
    }

    /// An error page answered with a 200 is reported, not taken for an empty guide; a damaged guide keeps what
    /// could be read.
    func testDamagedGuides() throws {
        for page in ["<html><body>Forbidden</body></html>", "<html><body>Forbidden</body>", "Forbidden", ""] {
            XCTAssertThrowsError(try XMLTVParser.parse(Data(page.utf8)), page) { error in
                XCTAssertTrue(error is XMLTVParser.ParseError, "\(error)")
            }
        }
        XCTAssertEqual(try XMLTVParser.parse(Data("<?xml version=\"1.0\"?><tv></tv>".utf8)).programCount, 0, "an empty guide is not an error")
        let xml = try Fixture.data("epg.xml")
        let truncated = try XMLTVParser.parse(xml.prefix(xml.count - 200))
        XCTAssertEqual(truncated.programs["TV1.fr"]?.map(\.title), ["Le JT", "Film du soir"], "what was read before the damage is kept")
    }

    func testInsertShortEPG() {
        var index = EPGIndex.empty
        let channel = Channel(id: "x1", name: "TV1", categoryID: "c", streamURL: URL(string: "http://x/1")!, epgID: "TV1.fr")
        index.insert([EPGProgram(channelID: "TV1.fr", title: "JT", start: .utc(2025, 1, 1, 10), end: .utc(2025, 1, 1, 11))], for: channel)
        XCTAssertEqual(index.current(for: channel, at: .utc(2025, 1, 1, 10, 30))?.title, "JT")
    }

    /// A long programme around shorter ones: it airs between and after them, and what is over is not upcoming.
    func testOverlappingProgrammes() {
        var index = EPGIndex.empty
        let channel = Channel(id: "x1", name: "TV1", categoryID: "c", streamURL: URL(string: "http://x/1")!, epgID: "TV1.fr")
        index.insert([
            EPGProgram(channelID: "TV1.fr", title: "A", start: .utc(2025, 1, 1, 0), end: .utc(2025, 1, 1, 6)),
            EPGProgram(channelID: "TV1.fr", title: "B", start: .utc(2025, 1, 1, 1), end: .utc(2025, 1, 1, 2)),
            EPGProgram(channelID: "TV1.fr", title: "C", start: .utc(2025, 1, 1, 2), end: .utc(2025, 1, 1, 3)),
        ], for: channel)
        XCTAssertEqual(index.current(for: channel, at: .utc(2025, 1, 1, 4))?.title, "A")
        XCTAssertEqual(index.upcoming(for: channel, at: .utc(2025, 1, 1, 4)).map(\.title), ["A"])
        XCTAssertEqual(index.current(for: channel, at: .utc(2025, 1, 1, 2, 30))?.title, "C", "the latest to start")
        XCTAssertEqual(index.current(for: channel, at: .utc(2025, 1, 1, 1, 30))?.title, "B")
        XCTAssertEqual(index.upcoming(for: channel, at: .utc(2025, 1, 1, 1, 30)).map(\.title), ["B", "C"])
        XCTAssertEqual(index.upcoming(for: channel, at: .utc(2025, 1, 1, 0, 30), count: 3).map(\.title), ["A", "B", "C"])
        XCTAssertNil(index.current(for: channel, at: .utc(2025, 1, 1, 7)))
        XCTAssertEqual(index.upcoming(for: channel, at: .utc(2025, 1, 1, 7)), [])
        XCTAssertEqual(index.current(guideID: "TV1.fr", at: .utc(2025, 1, 1, 5))?.title, "A")
    }

    func testPlainDataIsLeftAloneAndBrokenGzipThrows() throws {
        XCTAssertFalse(Data("plain".utf8).isGzipped)
        XCTAssertEqual(try Data("plain".utf8).gunzippedIfNeeded(), Data("plain".utf8))
        XCTAssertThrowsError(try Data([0x1f, 0x8b, 0x08, 0x00, 0xff, 0xff]).gunzipped())
    }

    func testGzipMembersPaddingAndJunk() throws {
        let compressed = try Fixture.data("epg.xml.gz")
        let xml = try Fixture.data("epg.xml")
        XCTAssertEqual(try compressed.gunzipped(), xml)
        XCTAssertEqual(try (compressed + compressed).gunzipped(), xml + xml, "concatenated members")
        XCTAssertEqual(try (compressed + Data(count: 16)).gunzipped(), xml, "padding after the file")
        XCTAssertEqual(try (compressed + Data("<html>junk</html>".utf8)).gunzipped(), xml, "junk after the file")
    }

    func testGzipStopsAtTheSizeLimit() throws {
        let compressed = try Fixture.data("epg.xml.gz")
        XCTAssertThrowsError(try compressed.gunzipped(limit: 100)) { error in
            XCTAssertEqual(error as? GzipError, .outputTooLarge(100))
        }
    }

    func testABrokenGuideIsTheProvidersFault() {
        XCTAssertEqual(ErrorAttribution.classify(GzipError.inflateFailed(-3)), .provider, "a broken file is the provider's")
        XCTAssertEqual(ErrorAttribution.classify(XMLTVParser.ParseError.invalidXML("x")), .provider)
    }
}

final class EPGMatchingTests: XCTestCase {
    private let start = Date.utc(2025, 10, 9, 20, 0)

    private func program(_ channel: String, _ title: String, hour: Double, subtitle: String? = nil) -> EPGProgram {
        EPGProgram(channelID: channel, title: title, subtitle: subtitle,
                   start: start.addingTimeInterval(hour * 3600), end: start.addingTimeInterval((hour + 1) * 3600))
    }

    private var index: EPGIndex {
        var data = EPGData()
        data.channels = [
            "tv1.fr": EPGChannel(id: "tv1.fr", displayNames: ["TV1"]),
            "tv1series.fr": EPGChannel(id: "tv1series.fr", displayNames: ["TV1 Séries Films"]),
            "nation2.fr": EPGChannel(id: "nation2.fr", displayNames: ["Nation 2"]),
            "aria.fr": EPGChannel(id: "aria.fr", displayNames: ["Aria"]),
        ]
        data.programs = [
            "tv1.fr": [program("tv1.fr", "Journal de 20h", hour: 0), program("tv1.fr", "Le Grand Match", hour: 1)],
            "tv1series.fr": [program("tv1series.fr", "Série policière", hour: 0)],
            "nation2.fr": [program("nation2.fr", "Journal", hour: 0, subtitle: "Édition du soir")],
            "aria.fr": [program("aria.fr", "Documentaire", hour: 3)],
        ]
        return EPGIndex(data: data)
    }

    private func channel(_ name: String, id: String = "c", epgID: String? = nil) -> Channel {
        Channel(id: id, name: name, categoryID: "x", streamURL: URL(string: "http://example.com/\(id)")!, epgID: epgID)
    }

    func testOverridesWinAndCanDisableTheGuide() {
        var index = self.index
        let tv1 = channel("FR: TV1 HD", id: "1")
        XCTAssertEqual(index.guideID(for: tv1), "tv1.fr", "automatic name match")

        index.overrides = ["1": "nation2.fr"]
        XCTAssertEqual(index.guideID(for: tv1), "nation2.fr")
        XCTAssertEqual(index.automaticGuideID(for: tv1), "tv1.fr")
        XCTAssertEqual(index.current(for: tv1, at: start.addingTimeInterval(60))?.title, "Journal")

        index.overrides = ["1": ""]
        XCTAssertNil(index.guideID(for: tv1))
        XCTAssertTrue(index.programs(for: tv1).isEmpty)
    }

    /// Guide channels sharing a name ("TV1 HD", "TV1 FHD") or an id up to case must not take turns from one
    /// launch to the next: the index is built from dictionaries, whose order changes every time.
    func testMatchingIsTheSameAtEveryBuild() {
        for _ in 0..<30 {
            var data = EPGData()
            for (id, name) in [("b.tv1", "TV1 HD"), ("a.tv1", "TV1 FHD"), ("c.tv1", "TV1")] {
                data.channels[id] = EPGChannel(id: id, displayNames: [name])
            }
            for id in ["b.tv1", "c.tv1", "a.tv1", "nation2.FR", "Nation2.fr", "aria.hd", "aria-hd"] {
                data.programs[id] = [program(id, "News", hour: 0)]
            }
            let index = EPGIndex(data: data)
            XCTAssertEqual(index.guideID(for: channel("FR: TV1 HD")), "a.tv1", "same display name: the smallest id")
            XCTAssertEqual(index.guideID(for: channel("Nation", epgID: "NATION2.fr")), "Nation2.fr", "same id up to case")
            XCTAssertEqual(index.guideID(for: channel("Aria")), "aria-hd", "same name from the id")
        }
    }

    func testInsertingProgrammesMatchesLikeABuild() {
        var inserted = EPGIndex(data: EPGData(channels: ["tv1.fr": EPGChannel(id: "tv1.fr", displayNames: ["TV1"])]))
        let tv1 = channel("FR: TV1 HD", id: "1", epgID: "x-tv1")
        XCTAssertNil(inserted.guideID(for: tv1), "a guide channel without programmes is not matched by its name")
        inserted.insert([program("tv1.fr", "Journal", hour: 0)], for: channel("TV1", id: "2", epgID: "tv1.fr"))
        XCTAssertEqual(inserted.guideID(for: tv1), "tv1.fr", "its programmes make its display name count")
        XCTAssertEqual(inserted.current(for: tv1, at: start.addingTimeInterval(60))?.title, "Journal")
        XCTAssertEqual(EPGIndex(data: inserted.data).guideID(for: tv1), "tv1.fr")
    }

    func testSuggestionsRankSimilarNamesFirst() {
        let suggestions = index.suggestions(for: channel("FR | TV1 FHD (backup)"))
        XCTAssertEqual(suggestions.first?.id, "tv1.fr")
        XCTAssertTrue(suggestions.contains { $0.id == "tv1series.fr" })
        XCTAssertFalse(suggestions.contains { $0.id == "aria.fr" })
        XCTAssertTrue(index.suggestions(for: channel("HD")).isEmpty, "tags alone identify nothing")
    }

    func testOptionsIncludeChannelsWithoutLoadedProgrammes() {
        var data = index.data
        data.channels["q9.fr"] = EPGChannel(id: "q9.fr", displayNames: ["Q9"])
        let index = EPGIndex(data: data)
        let options = index.guideChannelOptions()
        XCTAssertEqual(options.map(\.name), ["Aria", "Nation 2", "Q9", "TV1", "TV1 Séries Films"])
        XCTAssertEqual(options.first { $0.id == "q9.fr" }?.programCount, 0)
        XCTAssertEqual(index.suggestions(for: channel("Q9 HD")).first?.id, "q9.fr", "suggested before its programmes are loaded")
    }

    func testProgrammeSearch() {
        let window = DateInterval(start: start, duration: 2 * 3600)
        let journal = index.searchPrograms("journal", in: window)
        XCTAssertEqual(Set(journal.map(\.guideID)), ["tv1.fr", "nation2.fr"])
        XCTAssertEqual(index.searchPrograms("edition soir", in: window).map(\.guideID), ["nation2.fr"], "subtitle, accents folded")
        XCTAssertTrue(index.searchPrograms("documentaire", in: window).isEmpty, "outside the window")
        XCTAssertEqual(index.searchPrograms("grand match", in: window).first?.program.title, "Le Grand Match")
        XCTAssertTrue(index.searchPrograms("  ", in: window).isEmpty)
    }

    func testPlaylistOverridesSurviveEncoding() throws {
        var playlist = Playlist(name: "P", source: .remote(URL(string: "http://example.com/p.m3u")!))
        playlist.epgChannelOverrides = ["1": "tv1.fr", "2": ""]
        let decoded = try JSONDecoder().decode(Playlist.self, from: JSONEncoder().encode(playlist))
        XCTAssertEqual(decoded.epgChannelOverrides, ["1": "tv1.fr", "2": ""])
    }
}
