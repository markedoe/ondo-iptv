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
        _ = engine.search("item 59")
        print("search index + query in \(Date().timeIntervalSince(searchStart))s")
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

    private func stamp(_ date: Date) -> String {
        CatchupURLBuilder.format(date, pattern: "YmdHMS") + " +0000"
    }
}
