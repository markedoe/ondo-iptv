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
        let tf1 = try XCTUnwrap(data.programs["TF1.fr"])
        XCTAssertEqual(tf1.map(\.title), ["Too old", "Le JT", "Film du soir"], "sorted by start")
        let jt = tf1[1]
        XCTAssertEqual(jt.summary, "L'actualité & la météo")
        XCTAssertEqual(jt.category, "News")
        XCTAssertEqual(jt.start, Date.utc(2025, 10, 9, 18))
        XCTAssertEqual(jt.end, Date.utc(2025, 10, 9, 18, 45))
        let film = tf1[2]
        XCTAssertEqual(film.subtitle, "Inception")
        XCTAssertEqual(film.episode, "S02E05")
        XCTAssertEqual(film.rating, "-12")
        XCTAssertEqual(film.iconURL?.absoluteString, "http://epg.example.com/film.jpg")
        XCTAssertEqual(data.programs["france2"]?.first?.title, "N'oubliez pas les paroles", "CDATA")
        XCTAssertEqual(data.channels["TF1.fr"]?.iconURL?.absoluteString, "http://epg.example.com/tf1.png")
    }

    func testFilteringByWindowAndChannels() throws {
        let filter = EPGFilter(
            window: DateInterval(start: Date.utc(2025, 10, 9, 0), end: Date.utc(2025, 10, 10, 0)),
            channelIDs: ["tf1.FR"],
            channelNameKeys: [TextNormalizer.channelMatchKey("FR: France 2 FHD")]
        )
        let data = try XMLTVParser.parse(try Fixture.data("epg.xml.gz"), filter: filter)
        XCTAssertEqual(data.programs["TF1.fr"]?.count, 2, "old programme dropped by the window")
        XCTAssertEqual(data.programs["france2"]?.count, 1, "matched by display name")
        XCTAssertNil(data.programs["unused.fr"])
    }

    func testTimeShift() throws {
        let data = try XMLTVParser.parse(try Fixture.data("epg.xml"), timeShift: 3600)
        XCTAssertEqual(data.programs["france2"]?.first?.start, Date.utc(2025, 10, 9, 19))
    }

    func testIndexMatchingAndLookups() throws {
        let index = EPGIndex(data: try XMLTVParser.parse(try Fixture.data("epg.xml")))
        let byID = Channel(id: "1", name: "Whatever", categoryID: "c", streamURL: URL(string: "http://x/1")!, epgID: "tf1.fr")
        let byName = Channel(id: "2", name: "FR | France 2 HD", categoryID: "c", streamURL: URL(string: "http://x/2")!)
        let unknown = Channel(id: "3", name: "Nope", categoryID: "c", streamURL: URL(string: "http://x/3")!)

        XCTAssertEqual(index.guideID(for: byID), "TF1.fr")
        XCTAssertEqual(index.guideID(for: byName), "france2")
        XCTAssertNil(index.guideID(for: unknown))
        XCTAssertEqual(index.iconURL(for: byID)?.absoluteString, "http://epg.example.com/tf1.png")

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

    func testInsertShortEPG() {
        var index = EPGIndex.empty
        let channel = Channel(id: "x1", name: "TF1", categoryID: "c", streamURL: URL(string: "http://x/1")!, epgID: "TF1.fr")
        index.insert([EPGProgram(channelID: "TF1.fr", title: "JT", start: .utc(2025, 1, 1, 10), end: .utc(2025, 1, 1, 11))], for: channel)
        XCTAssertEqual(index.current(for: channel, at: .utc(2025, 1, 1, 10, 30))?.title, "JT")
    }

    func testGzipRoundTripErrors() throws {
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
            "tf1.fr": EPGChannel(id: "tf1.fr", displayNames: ["TF1"]),
            "tf1series.fr": EPGChannel(id: "tf1series.fr", displayNames: ["TF1 Séries Films"]),
            "france2.fr": EPGChannel(id: "france2.fr", displayNames: ["France 2"]),
            "arte.fr": EPGChannel(id: "arte.fr", displayNames: ["Arte"]),
        ]
        data.programs = [
            "tf1.fr": [program("tf1.fr", "Journal de 20h", hour: 0), program("tf1.fr", "Le Grand Match", hour: 1)],
            "tf1series.fr": [program("tf1series.fr", "Série policière", hour: 0)],
            "france2.fr": [program("france2.fr", "Journal", hour: 0, subtitle: "Édition du soir")],
            "arte.fr": [program("arte.fr", "Documentaire", hour: 3)],
        ]
        return EPGIndex(data: data)
    }

    private func channel(_ name: String, id: String = "c", epgID: String? = nil) -> Channel {
        Channel(id: id, name: name, categoryID: "x", streamURL: URL(string: "http://example.com/\(id)")!, epgID: epgID)
    }

    func testOverridesWinAndCanDisableTheGuide() {
        var index = self.index
        let tf1 = channel("FR: TF1 HD", id: "1")
        XCTAssertEqual(index.guideID(for: tf1), "tf1.fr", "automatic name match")

        index.overrides = ["1": "france2.fr"]
        XCTAssertEqual(index.guideID(for: tf1), "france2.fr")
        XCTAssertEqual(index.automaticGuideID(for: tf1), "tf1.fr")
        XCTAssertEqual(index.current(for: tf1, at: start.addingTimeInterval(60))?.title, "Journal")

        index.overrides = ["1": ""]
        XCTAssertNil(index.guideID(for: tf1))
        XCTAssertTrue(index.programs(for: tf1).isEmpty)
    }

    func testSuggestionsRankSimilarNamesFirst() {
        let suggestions = index.suggestions(for: channel("FR | TF1 FHD (backup)"))
        XCTAssertEqual(suggestions.first?.id, "tf1.fr")
        XCTAssertTrue(suggestions.contains { $0.id == "tf1series.fr" })
        XCTAssertFalse(suggestions.contains { $0.id == "arte.fr" })
        XCTAssertTrue(index.suggestions(for: channel("HD")).isEmpty, "tags alone identify nothing")
    }

    func testOptionsIncludeChannelsWithoutLoadedProgrammes() {
        var data = index.data
        data.channels["m6.fr"] = EPGChannel(id: "m6.fr", displayNames: ["M6"])
        let index = EPGIndex(data: data)
        let options = index.guideChannelOptions()
        XCTAssertEqual(options.map(\.name), ["Arte", "France 2", "M6", "TF1", "TF1 Séries Films"])
        XCTAssertEqual(options.first { $0.id == "m6.fr" }?.programCount, 0)
        XCTAssertEqual(index.suggestions(for: channel("M6 HD")).first?.id, "m6.fr", "suggested before its programmes are loaded")
    }

    func testProgrammeSearch() {
        let window = DateInterval(start: start, duration: 2 * 3600)
        let journal = index.searchPrograms("journal", in: window)
        XCTAssertEqual(Set(journal.map(\.guideID)), ["tf1.fr", "france2.fr"])
        XCTAssertEqual(index.searchPrograms("edition soir", in: window).map(\.guideID), ["france2.fr"], "subtitle, accents folded")
        XCTAssertTrue(index.searchPrograms("documentaire", in: window).isEmpty, "outside the window")
        XCTAssertEqual(index.searchPrograms("grand match", in: window).first?.program.title, "Le Grand Match")
        XCTAssertTrue(index.searchPrograms("  ", in: window).isEmpty)
    }

    func testPlaylistOverridesSurviveEncoding() throws {
        var playlist = Playlist(name: "P", source: .remote(URL(string: "http://example.com/p.m3u")!))
        playlist.epgChannelOverrides = ["1": "tf1.fr", "2": ""]
        let decoded = try JSONDecoder().decode(Playlist.self, from: JSONEncoder().encode(playlist))
        XCTAssertEqual(decoded.epgChannelOverrides, ["1": "tf1.fr", "2": ""])
    }
}
