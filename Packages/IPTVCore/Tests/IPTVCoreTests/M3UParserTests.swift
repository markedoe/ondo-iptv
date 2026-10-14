import XCTest
@testable import IPTVCore

final class M3UParserTests: XCTestCase {
    func testParsesHeaderAndEntries() throws {
        let playlist = M3UParser.parse(try Fixture.data("sample.m3u"))
        XCTAssertEqual(playlist.entries.count, 12)
        XCTAssertEqual(playlist.epgURLs.map(\.absoluteString), [
            "http://epg.example.com/guide.xml.gz",
            "http://epg2.example.com/g.xml",
        ])

        let tv1 = playlist.entries[0]
        XCTAssertEqual(tv1.title, "TV1 HD")
        XCTAssertEqual(tv1.tvgID, "TV1.fr")
        XCTAssertEqual(tv1.tvgName, "TV1")
        XCTAssertEqual(tv1.groupTitle, "France, TNT", "commas inside quoted attributes must not end the attribute list")
        XCTAssertEqual(tv1.channelNumber, 1)
        XCTAssertEqual(tv1.logo?.absoluteString, "http://logos.example.com/tv1.png")
    }

    func testSingleQuotesUnquotedValuesAndHeaders() throws {
        let entries = M3UParser.parse(try Fixture.data("sample.m3u")).entries
        XCTAssertEqual(entries[1].logo?.absoluteString, "http://logos.example.com/f2.png")

        let stadia = entries[2]
        XCTAssertEqual(stadia.groupTitle, "Sports")
        XCTAssertNil(stadia.tvgID, "empty tvg-id is treated as missing")
        XCTAssertEqual(stadia.headers.userAgent, "MyAgent/1.0")
        XCTAssertEqual(stadia.headers.referrer, "http://ref.example.com/")

        let aria = entries[3]
        XCTAssertEqual(aria.url.absoluteString, "http://aria.example.com/live.m3u8")
        XCTAssertEqual(aria.headers.userAgent, "ExamplePlayer/20")
        XCTAssertEqual(aria.headers.referrer, "http://aria.example.com")
    }

    func testExtGroupAndBareExtInf() throws {
        let entries = M3UParser.parse(try Fixture.data("sample.m3u")).entries
        let radio = entries[4]
        XCTAssertEqual(radio.title, "Wave Radio")
        XCTAssertEqual(radio.groupTitle, "Radio")
    }

    func testExtInfTitleWithoutAttributes() {
        let info = M3UParser.parseExtInf("-1,Channel, with comma")
        XCTAssertEqual(info.duration, -1)
        XCTAssertEqual(info.title, "Channel, with comma")
        XCTAssertTrue(info.attributes.isEmpty)
    }

    func testPlaylistWithoutHeaderAndCRLF() {
        let text = "#EXTINF:-1 group-title=\"A\",One\r\nhttp://a.example.com/1.m3u8\r\n\r\n#EXTINF:-1,Two\r\nhttp://a.example.com/2.m3u8\r\n"
        let playlist = M3UParser.parse(text)
        XCTAssertEqual(playlist.entries.map(\.title), ["One", "Two"])
        XCTAssertEqual(playlist.entries.first?.groupTitle, "A")
    }

    func testFormatDetection() throws {
        XCTAssertEqual(PlaylistFormat.detect(try Fixture.data("sample.m3u")), .m3u)
        XCTAssertEqual(PlaylistFormat.detect(try Fixture.data("sample.xspf")), .xspf)
        XCTAssertEqual(PlaylistFormat.detect(try Fixture.data("sample.json")), .json)
        XCTAssertEqual(PlaylistFormat.detect(Data("http://a/1.m3u8\nhttp://a/2.m3u8".utf8)), .plainList)
        XCTAssertNil(PlaylistFormat.detect(Data("<html><body>Forbidden</body></html>".utf8)))
    }
}
