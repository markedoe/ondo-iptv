import XCTest
@testable import IPTVCore

/// The remux of a Matroska film to HLS for the system player, on the ffmpeg-made sample (H.264 with B-frames and AAC,
/// 3 s, a key frame a second) cut into 1-second segments. ffmpeg plays the result in tools' checks; here, every frame
/// must land in exactly one segment, and decoding times must follow on from one segment to the next.
final class MatroskaRemuxTests: XCTestCase {
    private func remux() async throws -> MatroskaRemux {
        try await MatroskaRemux.open(DataByteSource(try Fixture.data("remux-h264-aac.mkv")), targetDuration: 1)
    }

    /// A box's type and body, and the boxes in a buffer.
    private struct Box {
        var type: String
        var body: Data
    }

    private func boxes(_ data: Data) -> [Box] {
        var result: [Box] = []
        var offset = data.startIndex
        while offset + 8 <= data.endIndex {
            let size = Int(data[offset..<offset + 4].reduce(UInt32(0)) { $0 << 8 | UInt32($1) })
            guard size >= 8, offset + size <= data.endIndex else { break }
            let type = String(decoding: data[offset + 4..<offset + 8], as: UTF8.self)
            result.append(Box(type: type, body: data.subdata(in: offset + 8..<offset + size)))
            offset += size
        }
        return result
    }

    private func u32(_ data: Data, _ at: Int) -> UInt32 {
        data[data.startIndex + at..<data.startIndex + at + 4].reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
    }

    /// Per track of a media segment: its decoding start, sample count and sample durations' sum.
    private func runs(_ segment: Data) -> [UInt32: (start: UInt64, count: Int, duration: UInt64, bytes: Int)] {
        var result: [UInt32: (start: UInt64, count: Int, duration: UInt64, bytes: Int)] = [:]
        guard let moof = boxes(segment).first(where: { $0.type == "moof" }) else { return result }
        for traf in boxes(moof.body) where traf.type == "traf" {
            let parts = boxes(traf.body)
            guard let tfhd = parts.first(where: { $0.type == "tfhd" }), let tfdt = parts.first(where: { $0.type == "tfdt" }),
                  let trun = parts.first(where: { $0.type == "trun" }) else { continue }
            let id = u32(tfhd.body, 4)
            let start = UInt64(u32(tfdt.body, 4)) << 32 | UInt64(u32(tfdt.body, 8))
            let count = Int(u32(trun.body, 4))
            var duration: UInt64 = 0, bytes = 0
            for sample in 0..<count {
                duration += UInt64(u32(trun.body, 12 + sample * 16))
                bytes += Int(u32(trun.body, 16 + sample * 16))
            }
            result[id] = (start, count, duration, bytes)
        }
        return result
    }

    func testTheFilmIsCutAtItsKeyFrames() async throws {
        let remux = try await remux()
        XCTAssertEqual(remux.segments.map(\.time), [0, 1_000_000_000, 2_000_000_000])
        for (duration, expected) in zip(remux.segments.map(\.duration), [1, 1, 1.021]) { XCTAssertEqual(duration, expected, accuracy: 0.001) }
        let playlist = remux.mediaPlaylist(initURI: "init.mp4") { "seg-\($0).m4s" }
        XCTAssertTrue(playlist.contains("#EXT-X-MAP:URI=\"init.mp4\""))
        XCTAssertTrue(playlist.contains("#EXTINF:1.000000,\nseg-0.m4s"), playlist)
        XCTAssertTrue(playlist.hasSuffix("#EXT-X-ENDLIST\n"))
        let multivariant = remux.multivariantPlaylist(media: "media.m3u8")
        XCTAssertTrue(multivariant.contains("CODECS=\"avc1.64000B,mp4a.40.2\""), multivariant)
        XCTAssertTrue(multivariant.contains("RESOLUTION=160x96"), multivariant)
        XCTAssertTrue(multivariant.contains("VIDEO-RANGE=SDR"), multivariant)
    }

    func testTheInitSegmentDescribesBothTracks() async throws {
        let remux = try await remux()
        let top = boxes(remux.initSegment)
        XCTAssertEqual(top.map(\.type), ["ftyp", "moov"])
        let moov = boxes(top[1].body)
        XCTAssertEqual(moov.map(\.type), ["mvhd", "trak", "trak", "mvex"])
        XCTAssertTrue(top[1].body.range(of: Data("avcC".utf8)) != nil)
        XCTAssertTrue(top[1].body.range(of: Data("esds".utf8)) != nil)
    }

    func testEveryFrameLandsInOneSegmentAndTimesFollowOn() async throws {
        let remux = try await remux()
        var video: [(start: UInt64, count: Int, duration: UInt64, bytes: Int)] = []
        var audio: [(start: UInt64, count: Int, duration: UInt64, bytes: Int)] = []
        for index in remux.segments.indices {
            let segment = try await remux.segment(index)
            XCTAssertEqual(boxes(segment).map(\.type), ["moof", "mdat"])
            let runs = runs(segment)
            video.append(try XCTUnwrap(runs[1]))
            audio.append(try XCTUnwrap(runs[2]))
            if let dump = ProcessInfo.processInfo.environment["REMUX_DUMP"] {
                try segment.write(to: URL(fileURLWithPath: dump).appendingPathComponent("seg-\(index).m4s"))
            }
        }
        if let dump = ProcessInfo.processInfo.environment["REMUX_DUMP"] {
            let folder = URL(fileURLWithPath: dump)
            try remux.initSegment.write(to: folder.appendingPathComponent("init.mp4"))
            try Data(remux.multivariantPlaylist(media: "media.m3u8").utf8).write(to: folder.appendingPathComponent("index.m3u8"))
            try Data(remux.mediaPlaylist(initURI: "init.mp4") { "seg-\($0).m4s" }.utf8).write(to: folder.appendingPathComponent("media.m3u8"))
        }
        // The counts and bytes ffprobe gives for the file, split between the segments.
        XCTAssertEqual(video.map(\.count), [25, 25, 25])
        XCTAssertEqual(video.reduce(0) { $0 + $1.bytes }, 35_750)
        XCTAssertEqual(audio.reduce(0) { $0 + $1.count }, 142)
        XCTAssertEqual(audio.reduce(0) { $0 + $1.bytes }, 12_211)
        // Each segment's decoding starts where the previous one's ended.
        for (previous, next) in zip(video, video.dropFirst()) { XCTAssertEqual(previous.start + previous.duration, next.start) }
        for (previous, next) in zip(audio, audio.dropFirst()) { XCTAssertEqual(previous.start + previous.duration, next.start) }
        // 25 frames a second on the 360 kHz clock; 1024 samples per AAC frame at 48 kHz.
        XCTAssertEqual(video[0].duration, 25 * 14_400)
        XCTAssertEqual(audio[0].duration, UInt64(audio[0].count) * 1_024)
    }

    /// HEVC 10-bit in PQ (HDR10) with open GOPs (pictures decoded after a key frame but shown before it) and E-AC-3 5.1,
    /// 3 s: the decoding times follow on from one segment to the next all the same.
    func testHEVCWithOpenGOPsAndDolbyDigitalPlus() async throws {
        let remux = try await MatroskaRemux.open(DataByteSource(try Fixture.data("remux-hevc-eac3.mkv")), targetDuration: 1)
        let multivariant = remux.multivariantPlaylist(media: "media.m3u8")
        XCTAssertTrue(multivariant.contains("CODECS=\"hvc1.2.4.L"), multivariant)
        XCTAssertTrue(multivariant.contains(",ec-3\""), multivariant)
        XCTAssertTrue(multivariant.contains("VIDEO-RANGE=PQ"), multivariant)
        let initSegment = remux.initSegment
        for box in ["hvc1", "hvcC", "colr", "ec-3", "dec3"] {
            XCTAssertNotNil(initSegment.range(of: Data(box.utf8)), box)
        }
        guard case .eac3(let dec3) = remux.tracks[1].codec else { return XCTFail("E-AC-3") }
        var bits = BitReader(dec3)
        XCTAssertEqual(try bits.read(13), 96, "kb/s")
        XCTAssertEqual(try bits.read(3), 0, "one independent substream")
        XCTAssertEqual(try bits.read(2), 0, "48 kHz")
        XCTAssertEqual(try bits.read(5), 16, "E-AC-3")
        try bits.skip(5)
        XCTAssertEqual(try bits.read(3), 7, "3/2: L, C, R, Ls, Rs")
        XCTAssertEqual(try bits.read(1), 1, "and LFE")
        XCTAssertEqual(remux.tracks[1].channels, 6)
        var video: [(start: UInt64, count: Int, duration: UInt64, bytes: Int)] = []
        var audio: [(start: UInt64, count: Int, duration: UInt64, bytes: Int)] = []
        for index in remux.segments.indices {
            let runs = runs(try await remux.segment(index))
            video.append(try XCTUnwrap(runs[1]))
            audio.append(try XCTUnwrap(runs[2]))
        }
        XCTAssertEqual(video.reduce(0) { $0 + $1.count }, 75)
        XCTAssertEqual(video.reduce(0) { $0 + $1.bytes }, 22_793)
        XCTAssertEqual(audio.reduce(0) { $0 + $1.count }, 94)
        XCTAssertEqual(audio.reduce(0) { $0 + $1.bytes }, 36_096)
        XCTAssertNotEqual(video.map(\.count), [25, 25, 25], "the leading pictures of a key frame go with it")
        for (previous, next) in zip(video, video.dropFirst()) { XCTAssertEqual(previous.start + previous.duration, next.start) }
        for (previous, next) in zip(audio, audio.dropFirst()) { XCTAssertEqual(previous.start + previous.duration, next.start) }
        XCTAssertEqual(audio[0].duration, UInt64(audio[0].count) * 1_536)
    }

    /// Remuxes any file for a check with ffmpeg: REMUX_INPUT=film.mkv REMUX_DUMP=folder swift test --filter testDump.
    func testDump() async throws {
        let environment = ProcessInfo.processInfo.environment
        guard let input = environment["REMUX_INPUT"], let dump = environment["REMUX_DUMP"] else { throw XCTSkip("REMUX_INPUT and REMUX_DUMP not set") }
        let remux = try await MatroskaRemux.open(DataByteSource(try Data(contentsOf: URL(fileURLWithPath: input))), targetDuration: 1)
        let folder = URL(fileURLWithPath: dump)
        try remux.initSegment.write(to: folder.appendingPathComponent("init.mp4"))
        try Data(remux.multivariantPlaylist(media: "media.m3u8").utf8).write(to: folder.appendingPathComponent("index.m3u8"))
        try Data(remux.mediaPlaylist(initURI: "init.mp4") { "seg-\($0).m4s" }.utf8).write(to: folder.appendingPathComponent("media.m3u8"))
        for index in remux.segments.indices {
            try await remux.segment(index).write(to: folder.appendingPathComponent("seg-\(index).m4s"))
        }
    }

    func testFilmsTheSystemPlayerCannotDecodeAreLeftToVLC() throws {
        let dts = MatroskaTrack(
            number: 2, kind: .audio, codecID: "A_DTS", codecPrivate: nil, language: "eng", name: nil, isDefault: true, isEnabled: true,
            defaultDuration: nil, codecDelay: 0, video: nil,
            audio: MatroskaTrack.Audio(samplingFrequency: 48_000, outputSamplingFrequency: nil, channels: 6, bitDepth: nil),
            strippedHeader: nil, blockAdditions: []
        )
        XCTAssertThrowsError(try MatroskaRemux.chooseAudio([dts], preferredLanguages: []))
        var aac = dts
        aac.number = 3
        aac.codecID = "A_AAC/MPEG4/LC"
        aac.language = "fre"
        aac.isDefault = false
        XCTAssertEqual(try MatroskaRemux.chooseAudio([dts, aac], preferredLanguages: []), aac, "the track it can play")
        XCTAssertEqual(MatroskaRemux.fragmentTrack(audio: aac)?.codec, .aac(Data([0x11, 0xB0])), "made from the codec ID: LC, 48 kHz, 6 channels")
    }

    func testThePreferredLanguageIsPlayed() throws {
        func track(_ number: UInt64, _ language: String, isDefault: Bool) -> MatroskaTrack {
            MatroskaTrack(
                number: number, kind: .audio, codecID: "A_AAC", codecPrivate: Data([0x11, 0x90]), language: language, name: nil,
                isDefault: isDefault, isEnabled: true, defaultDuration: nil, codecDelay: 0, video: nil,
                audio: MatroskaTrack.Audio(samplingFrequency: 48_000, outputSamplingFrequency: nil, channels: 2, bitDepth: nil),
                strippedHeader: nil, blockAdditions: []
            )
        }
        let tracks = [track(2, "eng", isDefault: true), track(3, "fre", isDefault: false)]
        XCTAssertEqual(try MatroskaRemux.chooseAudio(tracks, preferredLanguages: ["fr-FR", "en"])?.number, 3)
        XCTAssertEqual(try MatroskaRemux.chooseAudio(tracks, preferredLanguages: ["de"])?.number, 2)
    }
}
