import XCTest
@testable import IPTVCore

/// What a stream is made of and how the apps say it: the table written by hand in `fixtures/stream-specs/specs.json`,
/// the same file Android's `StreamSpecsTest` reads.
final class StreamSpecsTests: XCTestCase {
    private struct Table: Decodable {
        struct Resolution: Decodable {
            var width: Int?
            var height: Int?
            var resolutionClass: String?
            enum CodingKeys: String, CodingKey {
                case width, height
                case resolutionClass = "class"
            }
        }
        struct Audio: Decodable {
            var codec: String?
            var channels: Int?
            var atmos: Bool
            var facts: String?
            var chip: String?
        }
        struct Badge: Decodable {
            var name: String
            var specs: StreamSpecs?
            var badges: [String]
            var description: String
        }
        struct Provider: Decodable {
            var about: String
            var specs: StreamSpecs?
        }
        struct Codecs: Decodable {
            var video: [[String?]]
            var audio: [[String?]]
            var layouts: [Layout]
        }
        /// ["5.1(side)", 6]
        struct Layout: Decodable {
            var raw: String
            var channels: Int?
            init(from decoder: Decoder) throws {
                var c = try decoder.unkeyedContainer()
                raw = try c.decode(String.self)
                channels = try c.decodeIfPresent(Int.self)
            }
        }
        var resolution: [Resolution]
        var audio: [Audio]
        var codecs: Codecs
        var badges: [Badge]
        var provider: [Provider]
    }

    private static var file: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/stream-specs/specs.json")
    }

    private func table() throws -> Table {
        try JSONDecoder().decode(Table.self, from: Data(contentsOf: Self.file))
    }

    /// 1920×802 (scope) is 1080p, 1440×1080 (anamorphic) too, 3840×1600 4K; zero is unknown.
    func testResolutionClasses() throws {
        let cases = try table().resolution
        XCTAssertFalse(cases.isEmpty)
        for test in cases {
            XCTAssertEqual(StreamSpecs.resolution(width: test.width, height: test.height), test.resolutionClass, "\(String(describing: test.width))x\(String(describing: test.height))")
        }
    }

    func testSoundLabels() throws {
        for test in try table().audio {
            let specs = StreamSpecs(audioCodec: test.codec, channels: test.channels, atmos: test.atmos)
            XCTAssertEqual(specs.audioLabel, test.facts, "\(test)")
            XCTAssertEqual(specs.chipAudio, test.chip, "\(test)")
        }
    }

    func testCodecNames() throws {
        let codecs = try table().codecs
        for pair in codecs.video { XCTAssertEqual(StreamSpecs.videoCodec(pair[0]), pair[1], "\(pair)") }
        for pair in codecs.audio { XCTAssertEqual(StreamSpecs.audioCodec(pair[0]), pair[1], "\(pair)") }
        for layout in codecs.layouts { XCTAssertEqual(StreamSpecs.channels(ofLayout: layout.raw), layout.channels, layout.raw) }
    }

    /// The facts line's badges: specs where they know, the name's tags elsewhere, nothing invented.
    func testBadgesAndWhatAScreenReaderSays() throws {
        for test in try table().badges {
            let tags = FilmVersions.labelTags(name: test.name, categoryName: nil)
            XCTAssertEqual(StreamSpecs.badges(test.specs, nameTags: tags), test.badges, "\(test.name) \(String(describing: test.specs))")
            XCTAssertEqual(StreamSpecs.description(test.specs, nameTags: tags), test.description, test.name)
        }
    }

    /// The `video`, `audio` and `bitrate` of a panel's `get_vod_info`, whatever their shape.
    func testProviderBlocks() throws {
        let data = try Data(contentsOf: Self.file)
        let cases = try table().provider
        XCTAssertGreaterThanOrEqual(cases.count, 8)
        // The raw `info` objects, decoded as the client decodes them.
        let raw = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let infos = try XCTUnwrap(raw["provider"] as? [[String: Any]]).map { $0["info"] ?? [:] }
        XCTAssertEqual(infos.count, cases.count)
        for (test, info) in zip(cases, infos) {
            let json = try JSONSerialization.data(withJSONObject: info)
            let decoded = try JSONDecoder().decode(XtreamInfoDTO.self, from: json)
            XCTAssertEqual(decoded.streamSpecs, test.specs, test.about)
        }
    }

    /// The details of a film carry the provider's specs, kept with them; details kept before specs existed still read.
    func testMovieDetailsKeepTheSpecs() throws {
        let details = MediaDetails(title: "Sand Harbor", streamSpecs: StreamSpecs(width: 1920, height: 1080, audioCodec: "AAC", channels: 6))
        let data = try JSONEncoder().encode(details)
        XCTAssertEqual(try JSONDecoder().decode(MediaDetails.self, from: data), details)
        XCTAssertNil(try JSONDecoder().decode(MediaDetails.self, from: Data(#"{"title":"Sand Harbor"}"#.utf8)).streamSpecs)
        // What Android wrote reads the same.
        let android = #"{"width":1920,"height":802,"range":"DOLBY_VISION","videoCodec":"HEVC","audioCodec":null,"channels":null,"atmos":false,"bitrate":null,"source":"MEASURED"}"#
        let specs = try JSONDecoder().decode(StreamSpecs.self, from: Data(android.utf8))
        XCTAssertEqual(specs, StreamSpecs(width: 1920, height: 802, range: .dolbyVision, videoCodec: "HEVC", source: .measured))
    }

    /// Measured first, the provider's where the player read nothing; nothing known is empty.
    func testMergingKeepsTheBestKnown() {
        let measured = StreamSpecs(width: 1920, height: 1080, range: .sdr, source: .measured)
        let provider = StreamSpecs(width: 3840, height: 2160, range: .hdr10, audioCodec: "AC-3", channels: 6, atmos: true, bitrate: 9000)
        let merged = measured.orElse(provider)
        XCTAssertEqual(merged.resolution, "1080p")
        XCTAssertEqual(merged.range, .sdr)
        XCTAssertEqual(merged.audioLabel, "Atmos")
        XCTAssertEqual(merged.bitrate, 9000)
        XCTAssertEqual(merged.source, .measured)
        XCTAssertTrue(StreamSpecs().isEmpty)
        XCTAssertTrue(StreamSpecs(bitrate: 3000).isEmpty)
        XCTAssertEqual(
            StreamSpecs(width: 1920, height: 802, range: .sdr, videoCodec: "H.264", audioCodec: "AAC", channels: 6, bitrate: 4521).summary(),
            "1920×802 · 1080p · SDR · H.264 · 5.1 AAC · 4521 kbit/s"
        )
    }

    /// What AVFoundation names a track: its FourCC (Apple's player).
    func testPlayerTrackNames() {
        XCTAssertEqual(StreamSpecs.videoCodec("hvc1"), "HEVC")
        XCTAssertEqual(StreamSpecs.videoCodec("avc1"), "H.264")
        XCTAssertEqual(StreamSpecs.videoCodec("dvh1"), "HEVC")
        XCTAssertTrue(StreamSpecs.isDolbyVision(fourCC: "dvh1"))
        XCTAssertTrue(StreamSpecs.isDolbyVision(fourCC: "dvhe"))
        XCTAssertFalse(StreamSpecs.isDolbyVision(fourCC: "hvc1"))
        XCTAssertEqual(StreamSpecs.audioCodec("ec-3"), "E-AC-3")
        XCTAssertEqual(StreamSpecs.audioCodec("ac-3"), "AC-3")
        XCTAssertEqual(StreamSpecs.audioCodec("aac"), "AAC")
        XCTAssertEqual(StreamSpecs.audioCodec(".mp3"), "MP3")
        XCTAssertEqual(StreamSpecs.audioCodec("lpcm"), "PCM")
    }
}

/// What the player measured, kept by stream: bounded, written only on change, read back. The same as Android's
/// MeasuredSpecsTest.
final class MeasuredSpecsTests: XCTestCase {
    private let hd = StreamSpecs(width: 1920, height: 1080, range: .sdr, videoCodec: "H.264", audioCodec: "AAC", channels: 2)

    private func hdHigh(_ height: Int) -> StreamSpecs {
        var specs = hd
        specs.height = height
        return specs
    }

    func testRecordsOnlyWhatChanged() {
        var store = MeasuredSpecs()
        let key = MeasuredSpecs.key(playlistID: "p1", streamID: "42")
        XCTAssertTrue(store.record(key, hd))
        XCTAssertEqual(store[key]?.source, .measured)
        XCTAssertFalse(store.record(key, hd), "the same specs: nothing to save")
        XCTAssertFalse(store.record(key, StreamSpecs()), "nothing known: nothing kept")
        // A later reading that lacks the sound keeps the sound read before; a new size replaces the old one.
        XCTAssertTrue(store.record(key, StreamSpecs(width: 3840, height: 2160)))
        XCTAssertEqual(store[key]?.resolution, "4K")
        XCTAssertEqual(store[key]?.audioLabel, "2.0 AAC")
        XCTAssertNil(store[MeasuredSpecs.key(playlistID: "p2", streamID: "42")], "another playlist's stream 42 is another stream")
    }

    func testKeepsTheStreamsPlayedLast() {
        var store = MeasuredSpecs(capacity: 3)
        for i in 1...4 { store.record("p|\(i)", hdHigh(1000 + i)) }
        XCTAssertEqual(store.count, 3)
        XCTAssertNil(store["p|1"], "the oldest is forgotten")
        // Played again: kept longer than one played after it.
        store.record("p|2", hdHigh(1002))
        store.record("p|5", hd)
        XCTAssertEqual(store.keys, ["p|4", "p|2", "p|5"])
    }

    func testReadsBackWhatItWrote() {
        var store = MeasuredSpecs()
        for i in 0..<2100 { store.record("p|\(i)", hdHigh(700 + i % 5)) }
        XCTAssertEqual(store.count, MeasuredSpecs.defaultCapacity)
        let read = MeasuredSpecs(decoding: store.encoded())
        XCTAssertEqual(read, store)
        XCTAssertEqual(read.keys, store.keys)
        // A smaller bound keeps the latest.
        XCTAssertEqual(MeasuredSpecs(decoding: store.encoded(), capacity: 1).keys, ["p|2099"])
        XCTAssertEqual(MeasuredSpecs(decoding: Data("not json".utf8)).count, 0)
        XCTAssertEqual(MeasuredSpecs(decoding: nil).count, 0)
    }
}
