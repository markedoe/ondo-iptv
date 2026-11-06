import XCTest
@testable import IPTVCore

/// Guards against accidental quadratic behaviour on provider-sized inputs.
final class PerformanceTests: XCTestCase {
    func testLargePlaylistParsesQuickly() throws {
        var text = "#EXTM3U\n"
        for i in 0..<60_000 {
            let group = i % 3 == 0 ? "Films \(i % 40)" : (i % 3 == 1 ? "Séries \(i % 30)" : "Live \(i % 50)")
            let title = i % 3 == 1 ? "Show \(i % 500) S0\(i % 9 + 1)E\(i % 20 + 1)" : "Item \(i)"
            let ext = i % 3 == 2 ? "ts" : "mkv"
            text += "#EXTINF:-1 tvg-id=\"id\(i)\" tvg-logo=\"http://img.example.com/\(i).png\" group-title=\"\(group)\",\(title)\n"
            text += "http://host.example.com:8080/x/u/p/\(i).\(ext)\n"
        }
        let start = Date()
        let content = try PlaylistLoader(http: MockHTTPClient(), localFileURL: { URL(fileURLWithPath: $0) }).parse(Data(text.utf8))
        let elapsed = Date().timeIntervalSince(start)
        XCTAssertEqual(content.channels.count + content.movies.count + content.series.flatMap(\.episodes).count, 60_000)
        print("60k entries parsed in \(elapsed)s")
        XCTAssertLessThan(elapsed, 20)

        let searchStart = Date()
        let engine = SearchEngine(content: content)
        let results = engine.search("item 59999")
        let searchElapsed = Date().timeIntervalSince(searchStart)
        print("search index + query in \(searchElapsed)s")
        XCTAssertEqual(results.channels.map(\.name), ["Item 59999"])
        XCTAssertLessThan(searchElapsed, 10)
    }

    /// Hidden languages read every description of a catalogue of a large panel (100k titles) once, off the main actor
    /// (`TitleTags`, with the catalogue's other indexes); hiding a language then only gathers what was found.
    func testAHundredThousandDescriptionsAreReadQuickly() {
        let plots: [String] = [
            "Un joven abogado descubre que su padre esconde un secreto que pondrá en peligro a toda la familia cuando regresa a su pueblo natal después de diez años.",
            "Um jovem advogado descobre que o pai esconde um segredo que vai colocar toda a família em perigo quando ele volta para a sua cidade natal.",
            "A young lawyer discovers that his father is hiding a secret that will put the whole family in danger when he returns to his hometown after ten years.",
            "يكتشف محامٍ شاب أن والده يخفي سرًا سيعرّض العائلة كلها للخطر عندما يعود إلى مدينته بعد عشر سنوات.",
        ]
        let categories = (0..<40).map { MediaCategory(id: "c\($0)", name: "Netflix \($0)", type: .series) }
        // Long descriptions too: only their start is read.
        let series = (0..<100_000).map { i -> Series in
            let plot: String = String(repeating: plots[i % plots.count], count: 1 + i % 3)
            return Series(id: "s\(i)", name: "Title \(i) (\(i % 7 == 0 ? "ES" : "2024"))", categoryID: "c\(i % 40)", plot: plot)
        }
        let content = PlaylistContent(seriesCategories: categories, series: series)
        let start = Date()
        let tags = TitleTags(content: content)
        let elapsed = Date().timeIntervalSince(start)
        let hideStart = Date()
        let hidden = tags.hidden(by: ["ES", "AR"])
        let hideElapsed = Date().timeIntervalSince(hideStart)
        print("100k titles and descriptions read in \(elapsed)s, hidden in \(hideElapsed)s")
        // Spanish and Arabic descriptions, and the English and Portuguese ones named "(ES)".
        let named: Int = (0..<100_000).filter { $0 % 7 == 0 && (1...2).contains($0 % 4) }.count
        XCTAssertEqual(hidden.seriesIDs.count, 50_000 + named)
        XCTAssertLessThan(elapsed, 20)
        XCTAssertLessThan(hideElapsed, 2)
    }

    /// 100,000 films, a third of them in three versions (two with the TMDb id, one without): grouped in one pass.
    func testAHundredThousandFilmsAreGroupedQuickly() {
        let tags = ["|FR| %@ 4K DV", "%@ VOSTFR", "%@ (MULTI)"]
        let movies = (0..<100_000).map { i -> Movie in
            let film = i < 30_000 ? i / 3 : i
            let name = i < 30_000 ? String(format: tags[i % 3], "Film \(film)") : "Film \(film)"
            return Movie(
                id: "x\(i)", name: name, categoryID: "c\(i % 40)", streamURL: URL(string: "http://host.example.com/\(i).mkv")!,
                year: 1950 + film % 70, tmdbID: i < 30_000 && i % 3 == 1 ? nil : 10_000 + film
            )
        }
        let start = Date()
        let versions = FilmVersions.of(movies)
        let elapsed = Date().timeIntervalSince(start)
        print("100k films grouped in \(elapsed)s: \(versions.diagnostics)")
        XCTAssertEqual(versions.groupCount, 80_000)
        XCTAssertEqual(versions.diagnostics.groupsWithVersions, 10_000)
        XCTAssertLessThan(elapsed, 30)
    }

    /// A long-running series lists thousands of episodes: adding each one must not copy the ones before it.
    func testASeriesWithManyEpisodesBuildsQuickly() {
        let url = URL(string: "http://example.com/s/1.mkv")!
        let entries = (0..<30_000).map { i in
            M3UEntry(duration: -1, title: "Daily Soap S\(i / 1000 + 1)E\(i % 1000 + 1)", attributes: ["group-title": "Series"],
                     url: url.deletingLastPathComponent().appendingPathComponent("\(i).mkv"), extGroup: nil, headers: .none)
        }
        let start = Date()
        let content = M3UContentBuilder.build(from: M3UPlaylist(headerAttributes: [:], entries: entries))
        let elapsed = Date().timeIntervalSince(start)
        XCTAssertEqual(content.series.count, 1)
        XCTAssertEqual(content.series.first?.episodes.count, 30_000)
        print("one series of 30k episodes built in \(elapsed)s")
        XCTAssertLessThan(elapsed, 15)
    }

    func testLargeGuideParsesQuickly() throws {
        var xml = "<tv>"
        let base = Date.utc(2025, 10, 9, 0)
        for c in 0..<500 {
            xml += "<channel id=\"ch\(c)\"><display-name>Channel \(c)</display-name></channel>"
        }
        for c in 0..<500 {
            for p in 0..<200 {
                let start = base.addingTimeInterval(Double(p) * 1800)
                let stop = start.addingTimeInterval(1800)
                xml += "<programme start=\"\(stamp(start))\" stop=\"\(stamp(stop))\" channel=\"ch\(c)\"><title>Show \(p)</title><desc>Description of show \(p) on channel \(c)</desc></programme>"
            }
        }
        xml += "</tv>"
        let start = Date()
        let data = try XMLTVParser.parse(Data(xml.utf8), filter: EPGFilter(channelIDs: Set((0..<250).map { "ch\($0)" })))
        let elapsed = Date().timeIntervalSince(start)
        XCTAssertEqual(data.programCount, 250 * 200)
        print("100k programmes parsed (50k kept) in \(elapsed)s")
        XCTAssertLessThan(elapsed, 30)
    }

    /// A user guide and the provider's covering the same channels: overlap checks must not scan every programme.
    func testMergingTwoLargeGuidesIsQuick() {
        let base = Date.utc(2025, 10, 9, 0)
        func guide(from first: Int) -> EPGData {
            var data = EPGData()
            for c in 0..<200 {
                data.programs["ch\(c)"] = (first..<(first + 1000)).map { p in
                    EPGProgram(channelID: "ch\(c)", title: "Show \(p)",
                               start: base.addingTimeInterval(Double(p) * 1800), end: base.addingTimeInterval(Double(p + 1) * 1800))
                }
            }
            return data
        }
        var merged = guide(from: 0)
        let later = guide(from: 500)  // half of it overlaps, half is new
        let start = Date()
        merged.merge(later)
        let elapsed = Date().timeIntervalSince(start)
        print("200 channels x 1000 programmes merged in \(elapsed)s")
        XCTAssertEqual(merged.programCount, 200 * 1500)
        XCTAssertEqual(merged.programs["ch0"]?.map(\.title).suffix(2), ["Show 1498", "Show 1499"])
        XCTAssertLessThan(elapsed, 5)
    }

    private func stamp(_ date: Date) -> String {
        CatchupURLBuilder.format(date, pattern: "YmdHMS") + " +0000"
    }
}
