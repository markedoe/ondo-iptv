import XCTest
@testable import IPTVCore

final class CatchupTests: XCTestCase {
    let start = Date.utc(2025, 10, 9, 18, 0)
    let end = Date.utc(2025, 10, 9, 18, 45)
    let now = Date.utc(2025, 10, 9, 20, 0)

    private func channel(_ url: String, _ catchup: CatchupInfo?, xtreamID: Int? = nil) -> Channel {
        Channel(id: "c", name: "C", categoryID: "x", streamURL: URL(string: url)!, catchup: catchup, xtreamStreamID: xtreamID)
    }

    func testTemplateTokens() {
        let template = "{utc}|${start}|{utcend}|{lutc}|{duration}|{duration:60}|{offset}|{offset:60}|{Y}-{m}-{d} {H}:{M}:{S}|{utc:Y-m-d:H-M}|{end:YmdHMS}|{unknown}"
        let filled = CatchupURLBuilder.fill(template: template, start: start, end: end, now: now)
        XCTAssertEqual(filled, "1760032800|1760032800|1760035500|1760040000|2700|45|7200|120|2025-10-09 18:00:00|2025-10-09:18-00|20251009184500|{unknown}")
    }

    func testDefaultMode() {
        let c = channel("http://a.example.com/live.m3u8", CatchupInfo(mode: .default, days: 3, source: "http://archive.example.com/arte?start={utc}&end={utcend}"))
        XCTAssertEqual(CatchupURLBuilder.urls(channel: c, start: start, end: end, now: now).map(\.absoluteString),
                       ["http://archive.example.com/arte?start=1760032800&end=1760035500"])
    }

    func testAppendMode() {
        let c = channel("http://a.example.com/live.m3u8?token=1", CatchupInfo(mode: .append, days: 3, source: "?utc={utc}&lutc={lutc}"))
        XCTAssertEqual(CatchupURLBuilder.urls(channel: c, start: start, end: end, now: now).first?.absoluteString,
                       "http://a.example.com/live.m3u8?token=1&utc=1760032800&lutc=1760040000")
    }

    func testShiftMode() {
        let c = channel("http://a.example.com/live.m3u8", CatchupInfo(mode: .shift, days: 3))
        XCTAssertEqual(CatchupURLBuilder.urls(channel: c, start: start, end: end, now: now).first?.absoluteString,
                       "http://a.example.com/live.m3u8?utc=1760032800&lutc=1760040000")
    }

    func testFlussonicModes() {
        let hls = channel("http://fl.example.com/france2/index.m3u8?token=abc", CatchupInfo(mode: .flussonic, days: 3))
        XCTAssertEqual(CatchupURLBuilder.urls(channel: hls, start: start, end: end, now: now).first?.absoluteString,
                       "http://fl.example.com/france2/index-1760032800-2700.m3u8?token=abc")
        let ts = channel("http://fl.example.com/france2/mpegts?token=abc", CatchupInfo(mode: .flussonic, days: 3))
        XCTAssertEqual(CatchupURLBuilder.urls(channel: ts, start: start, end: end, now: now).first?.absoluteString,
                       "http://fl.example.com/france2/timeshift_abs-1760032800.ts?token=abc")
    }

    func testXtreamModeFromAccount() {
        let credentials = XtreamCredentials(server: URL(string: "http://p.example.com:8080")!, username: "u", password: "p")
        let c = channel("http://p.example.com:8080/live/u/p/101.m3u8", CatchupInfo(mode: .xtream, days: 7), xtreamID: 101)
        let context = CatchupURLBuilder.Context(xtream: credentials, serverTimeZone: TimeZone(identifier: "Europe/Paris"), outputFormat: .m3u8)
        let urls = CatchupURLBuilder.urls(channel: c, start: start, end: end, now: now, context: context).map(\.absoluteString)
        XCTAssertEqual(urls.first, "http://p.example.com:8080/timeshift/u/p/45/2025-10-09:20-00/101.m3u8")
        XCTAssertEqual(urls.count, 3)
        XCTAssertTrue(urls[2].contains("timeshift.php"))
    }

    func testXtreamModeFromPlainURL() {
        let c = channel("http://p.example.com:8080/u/p/7", CatchupInfo(mode: .xtream, days: 7))
        let urls = CatchupURLBuilder.urls(channel: c, start: start, end: end, now: now, context: .init(outputFormat: .ts))
        XCTAssertEqual(urls.first?.absoluteString, "http://p.example.com:8080/timeshift/u/p/45/2025-10-09:18-00/7.ts")
    }

    func testAvailability() {
        let c = channel("http://x/1.m3u8", CatchupInfo(mode: .shift, days: 1))
        let past = EPGProgram(channelID: "c", title: "Old", start: now.addingTimeInterval(-3 * 3600), end: now.addingTimeInterval(-2 * 3600))
        let tooOld = EPGProgram(channelID: "c", title: "Too old", start: now.addingTimeInterval(-3 * 86_400), end: now.addingTimeInterval(-3 * 86_400 + 3600))
        let future = EPGProgram(channelID: "c", title: "Later", start: now.addingTimeInterval(3600), end: now.addingTimeInterval(7200))
        XCTAssertTrue(CatchupURLBuilder.isAvailable(channel: c, program: past, now: now))
        XCTAssertFalse(CatchupURLBuilder.isAvailable(channel: c, program: tooOld, now: now))
        XCTAssertFalse(CatchupURLBuilder.isAvailable(channel: c, program: future, now: now))
        XCTAssertFalse(CatchupURLBuilder.isAvailable(channel: channel("http://x/1.m3u8", nil), program: past, now: now))
    }

    func testPlaybackFactory() {
        let playlist = Playlist(name: "P", source: .remote(URL(string: "http://x/list.m3u")!), headers: HTTPHeaders(userAgent: "Global"))
        let factory = PlaybackFactory(playlist: playlist)
        var c = channel("http://p.example.com:8080/live/u/p/101.ts", nil)
        c.headers = HTTPHeaders(referrer: "http://ref")
        let item = factory.item(for: c)
        XCTAssertTrue(item.isLive)
        XCTAssertEqual(item.candidateURLs.map(\.absoluteString), [
            "http://p.example.com:8080/live/u/p/101.ts",
            "http://p.example.com:8080/live/u/p/101.m3u8",
        ])
        XCTAssertEqual(item.headers.dictionary, ["User-Agent": "Global", "Referer": "http://ref"])
        XCTAssertEqual(item.reference.type, .live)

        var backup = channel("http://backup.example.com/tf1.m3u8", nil)
        backup.id = "backup"
        let withAlternates = factory.item(for: c, alternates: [c, backup])
        XCTAssertEqual(withAlternates.candidateURLs.map(\.absoluteString), [
            "http://p.example.com:8080/live/u/p/101.ts",
            "http://p.example.com:8080/live/u/p/101.m3u8",
            "http://backup.example.com/tf1.m3u8",
        ])
    }
}
