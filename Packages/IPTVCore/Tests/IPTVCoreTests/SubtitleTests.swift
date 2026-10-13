import XCTest
@testable import IPTVCore

final class SubtitleTests: XCTestCase {
    func testSRT() {
        let srt = "\u{FEFF}1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Hello</i> there\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nSecond\r\nline two\r\n\r\n3\r\nbroken timing\r\ntext\r\n"
        let cues = SubtitleParser.parse(srt)
        XCTAssertEqual(cues.count, 2)
        XCTAssertEqual(cues[0], SubtitleCue(start: 1, end: 2.5, text: "Hello there"))
        XCTAssertEqual(cues[1].text, "Second\nline two")
        XCTAssertEqual(SubtitleParser.cue(at: 1.2, in: cues)?.text, "Hello there")
        XCTAssertNil(SubtitleParser.cue(at: 2.7, in: cues))
        XCTAssertEqual(SubtitleParser.cue(at: 3.5, in: cues)?.text, "Second\nline two")
        XCTAssertNil(SubtitleParser.cue(at: 0.5, in: cues))
    }

    func testWebVTT() {
        let vtt = "WEBVTT\n\nintro\n00:01.000 --> 00:02.000 align:start\n{\\an8}Short <b>form</b>\n\n01:00:00.000 --> 01:00:01.000\nHour\n"
        let cues = SubtitleParser.parse(vtt)
        XCTAssertEqual(cues.map(\.start), [1, 3600])
        XCTAssertEqual(cues[0].text, "Short form")
    }
}
