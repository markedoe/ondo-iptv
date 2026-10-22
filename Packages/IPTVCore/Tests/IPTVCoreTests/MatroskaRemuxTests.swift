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

    /// Dolby Vision as Apple's HLS rules describe it: profile 5 as dvh1, profile 8.1 as HDR10 HEVC with Dolby Vision in
    /// SUPPLEMENTAL-CODECS, and profile 7 (Blu-ray, two layers) as its HDR10 base layer.
    func testDolbyVisionProfiles() throws {
        func record(profile: UInt8, level: UInt8, compatibility: UInt8) -> Data {
            var bytes = [UInt8](repeating: 0, count: 24)
            bytes[0] = 1
            bytes[2] = profile << 1 | level >> 5
            bytes[3] = (level & 0x1F) << 3 | 0b101
            bytes[4] = compatibility << 4
            return Data(bytes)
        }
        let hvcC = Data([1, 0x02, 0x20, 0, 0, 0, 0x90, 0, 0, 0, 0, 0, 153] + [UInt8](repeating: 0, count: 10))
        func track(_ dolbyVision: Data) -> MatroskaTrack {
            MatroskaTrack(
                number: 1, kind: .video, codecID: "V_MPEGH/ISO/HEVC", codecPrivate: hvcC, language: nil, name: nil, isDefault: true,
                isEnabled: true, defaultDuration: 41_708_333, codecDelay: 0,
                video: MatroskaTrack.Video(width: 3_840, height: 2_160, colour: nil), audio: nil, strippedHeader: nil,
                blockAdditions: [MatroskaTrack.BlockAddition(type: 0x6476_6343, extraData: dolbyVision)]
            )
        }
        let profile8 = MatroskaRemux.fragmentTrack(track(record(profile: 8, level: 6, compatibility: 1)))
        XCTAssertEqual(FragmentWriter.codecName(profile8.codec), "hvc1.2.4.L153.90")
        XCTAssertEqual(FragmentWriter.supplementalCodecs(profile8.codec), "dvh1.08.06/db1p")
        XCTAssertNotNil(FragmentWriter.initSegment(tracks: [profile8]).range(of: Data("hvc1".utf8)))
        XCTAssertNotNil(FragmentWriter.initSegment(tracks: [profile8]).range(of: Data("dvvC".utf8)))

        let profile5 = MatroskaRemux.fragmentTrack(track(record(profile: 5, level: 9, compatibility: 0)))
        XCTAssertEqual(FragmentWriter.codecName(profile5.codec), "dvh1.05.09")
        XCTAssertNil(FragmentWriter.supplementalCodecs(profile5.codec))
        XCTAssertNotNil(FragmentWriter.initSegment(tracks: [profile5]).range(of: Data("dvh1".utf8)))
        XCTAssertNotNil(FragmentWriter.initSegment(tracks: [profile5]).range(of: Data("dvcC".utf8)))

        let profile7 = MatroskaRemux.fragmentTrack(track(record(profile: 7, level: 6, compatibility: 6)))
        XCTAssertEqual(profile7.codec, .hevc(hvcC, dolbyVision: nil), "the HDR10 base layer")
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

    // MARK: Damaged files

    /// The sample with the bytes after `marker` replaced.
    private func patched(_ marker: [UInt8], _ bytes: [UInt8]) throws -> Data {
        var data = try Fixture.data("remux-h264-aac.mkv")
        let at = try XCTUnwrap(data.range(of: Data(marker))).upperBound
        data.replaceSubrange(at..<at + bytes.count, with: bytes)
        return data
    }

    private func assertLeftToVLC(_ data: Data, _ message: String, file: StaticString = #filePath, line: UInt = #line) async {
        do {
            _ = try await MatroskaRemux.open(DataByteSource(data), targetDuration: 1)
            XCTFail(message, file: file, line: line)
        } catch {
            XCTAssertTrue(error is RemuxError, "\(message): \(error)", file: file, line: line)
        }
    }

    /// A frame duration of 0, or too short for a tick of the video clock, is unknown: the frame rate is measured, and
    /// the film comes out as with its real one.
    func testAFrameDurationOfZeroIsMeasured() async throws {
        let original = try await remux()
        for nanoseconds: UInt32 in [0, 1_000] {
            // DefaultDuration, 4 bytes: 40 ms in the sample.
            let data = try patched([0x23, 0xE3, 0x83, 0x84], withUnsafeBytes(of: nanoseconds.bigEndian) { [UInt8]($0) })
            let remux = try await MatroskaRemux.open(DataByteSource(data), targetDuration: 1)
            for index in remux.segments.indices {
                let segment = try await remux.segment(index)
                let expected = try await original.segment(index)
                XCTAssertEqual(segment, expected, "segment \(index) with \(nanoseconds) ns")
            }
        }
        // Built here, without B-frames.
        for duration: UInt64? in [nil, 0, 1_000] {
            let remux = try await MatroskaRemux.open(DataByteSource(MatroskaBytes.film(defaultDuration: duration)), targetDuration: 1)
            let segment = try await remux.segment(1)
            let video = try XCTUnwrap(runs(segment)[1])
            XCTAssertEqual(video.count, 25)
            XCTAssertEqual(video.duration, 25 * 14_400, "\(String(describing: duration))")
        }
    }

    /// Times close to the end of 64 bits (a cluster 292 years in) throw instead of overflowing once the remux's
    /// offset is added.
    func testFrameTimesThatOverflowAreLeftToVLC() async throws {
        let remux = try await MatroskaRemux.open(DataByteSource(MatroskaBytes.film(clusterTimes: [9_223_372_030_000])), targetDuration: 1)
        do {
            _ = try await remux.segment(0)
            XCTFail("times past 64 bits")
        } catch {
            XCTAssertTrue(error is RemuxError, "\(error)")
        }
    }

    /// A duration that is not a number, infinite, negative or beyond 64 bits of nanoseconds.
    func testImpossibleDurationsAreLeftToVLC() async throws {
        for duration in [Double.nan, .infinity, -1, 1e30] {
            // Duration, 8 bytes, in milliseconds.
            let data = try patched([0x44, 0x89, 0x88], withUnsafeBytes(of: duration.bitPattern.bigEndian) { [UInt8]($0) })
            await assertLeftToVLC(data, "\(duration)")
        }
    }

    /// A file size no film has, from a server: the playlist's bandwidth stays a number.
    func testAnImpossibleFileSizeKeepsThePlaylistValid() async throws {
        struct Sized: ByteSource {
            let base: DataByteSource
            func size() async throws -> Int64? { .max }
            func read(_ range: Range<Int64>) async throws -> Data { try await base.read(range) }
        }
        let remux = try await MatroskaRemux.open(Sized(base: DataByteSource(MatroskaBytes.film())), targetDuration: 1)
        let playlist = remux.multivariantPlaylist(media: "media.m3u8")
        XCTAssertTrue(playlist.contains("BANDWIDTH="), playlist)
    }

    func testImpossibleSampleRatesAndSizes() throws {
        var aac = MatroskaTrack(
            number: 2, kind: .audio, codecID: "A_AAC", codecPrivate: Data([0x11, 0x90]), language: nil, name: nil, isDefault: true,
            isEnabled: true, defaultDuration: nil, codecDelay: 0, video: nil,
            audio: MatroskaTrack.Audio(samplingFrequency: 48_000, outputSamplingFrequency: nil, channels: 2, bitDepth: nil),
            strippedHeader: nil, blockAdditions: []
        )
        XCTAssertNotNil(MatroskaRemux.fragmentTrack(audio: aac))
        for rate in [Double.infinity, .nan, 1e10, 1e300] {
            aac.audio?.samplingFrequency = rate
            XCTAssertNil(MatroskaRemux.fragmentTrack(audio: aac), "\(rate) Hz")
            aac.audio?.samplingFrequency = 24_000
            aac.audio?.outputSamplingFrequency = rate
            XCTAssertNil(MatroskaRemux.fragmentTrack(audio: aac), "HE-AAC at \(rate) Hz")
            aac.audio?.outputSamplingFrequency = nil
        }
        // Wider than MP4 describes: refused, and never a crash when writing its header.
        var video = MatroskaTrack(
            number: 1, kind: .video, codecID: "V_MPEG4/ISO/AVC", codecPrivate: Data([1, 0x64, 0, 0x0B]), language: nil, name: nil,
            isDefault: true, isEnabled: true, defaultDuration: 40_000_000, codecDelay: 0,
            video: MatroskaTrack.Video(width: 1 << 40, height: 96, colour: nil), audio: nil, strippedHeader: nil, blockAdditions: []
        )
        XCTAssertThrowsError(try MatroskaRemux.chooseVideo([video]))
        XCTAssertFalse(FragmentWriter.initSegment(tracks: [MatroskaRemux.fragmentTrack(video)]).isEmpty)
        video.video?.width = 3_840
        XCTAssertNoThrow(try MatroskaRemux.chooseVideo([video]))
    }

    /// A segment is read and held whole: an index with too few key frames (one for the whole film) is left to VLC
    /// rather than read into memory.
    func testSegmentsPastTheLimitAreLeftToVLC() async throws {
        let base = try await MatroskaReader.open(DataByteSource(MatroskaBytes.film()))
        let video = base.tracks[0]
        let limit = MatroskaRemux.largestSegment
        func file(cues: [MatroskaCuePoint], clustersEnd: Int64) -> MatroskaFile {
            var file = base
            file.cues = cues
            file.clustersEnd = clustersEnd
            return file
        }
        let start = base.firstClusterOffset
        let first = MatroskaCuePoint(time: 0, track: video.number, clusterOffset: start)
        // A key frame 7 s in, past the limit: refused when planning, before anything is read.
        let far = MatroskaCuePoint(time: 7_000_000_000, track: video.number, clusterOffset: start + limit + 1)
        XCTAssertThrowsError(try MatroskaRemux.plan(file(cues: [first, far], clustersEnd: start + limit + 2_000), video: video, targetDuration: 6))
        XCTAssertThrowsError(try MatroskaRemux.plan(file(cues: [first], clustersEnd: start + limit + 1), video: video, targetDuration: 6))
        // Positions whose sum overflows.
        var overflowing = far
        overflowing.clusterOffset = start + 1_000
        overflowing.relativePosition = .max
        XCTAssertThrowsError(try MatroskaRemux.plan(file(cues: [first, overflowing], clustersEnd: start + 4_000), video: video, targetDuration: 6))
        XCTAssertEqual(try MatroskaRemux.plan(file(cues: [first], clustersEnd: start + limit), video: video, targetDuration: 6).count, 1, "up to the limit")

        // Where the clusters end is unknown: no more than the limit is asked for.
        final class Recorder: ByteSource, @unchecked Sendable {
            private let lock = NSLock()
            private var ranges: [Range<Int64>] = []
            var asked: [Range<Int64>] { lock.withLock { ranges } }
            func size() async throws -> Int64? { nil }
            func read(_ range: Range<Int64>) async throws -> Data {
                lock.withLock { ranges.append(range) }
                return Data(count: 16)
            }
        }
        let recorder = Recorder()
        let open = file(cues: [first], clustersEnd: .max)
        let segments = try MatroskaRemux.plan(open, video: video, targetDuration: 6)
        let read = try await MatroskaRemux.read(segments[0], file: open, source: recorder)
        XCTAssertEqual(read.count, 16)
        XCTAssertEqual(recorder.asked.map { $0.upperBound - $0.lowerBound }, [limit + 1])
        // Known to be past the limit: nothing is read.
        var block = far
        block.relativePosition = 100
        let untouched = Recorder()
        do {
            let segment = MatroskaRemux.Segment(time: 0, duration: 7, start: nil, end: block)
            _ = try await MatroskaRemux.read(segment, file: file(cues: [first, block], clustersEnd: .max), source: untouched)
            XCTFail("a segment past the limit")
        } catch {
            XCTAssertTrue(error is RemuxError, "\(error)")
        }
        XCTAssertEqual(untouched.asked, [])
    }

    /// AC-3's dac3 box from a syncframe: 48 kHz, 448 kb/s, 3/2 with LFE (ETSI TS 102 366, F.4).
    func testDolbyDigitalDescription() throws {
        var frame = BitWriter()
        frame.write(0x0B77, 16)
        frame.write(0, 16)  // crc1
        frame.write(0, 2)  // fscod: 48 kHz
        frame.write(28, 6)  // frmsizecod: 448 kb/s
        frame.write(8, 5)  // bsid
        frame.write(0, 3)  // bsmod
        frame.write(7, 3)  // acmod: 3/2
        frame.write(1, 2)  // cmixlev
        frame.write(1, 2)  // surmixlev
        frame.write(1, 1)  // lfeon
        frame.write(0, 32)
        let config = try DolbyAudio.ac3(frame.data)
        XCTAssertEqual(config.box, Data([0x10, 0x3D, 0xC0]), "fscod, bsid 8, bsmod, acmod 7, LFE, bit rate code 14")
        XCTAssertEqual(config.sampleRate, 48_000)
        XCTAssertEqual(config.channels, 6)
        XCTAssertEqual(config.frameLength, 1_536)
        XCTAssertThrowsError(try DolbyAudio.ac3(Data([0x0B, 0x78] + [UInt8](repeating: 0, count: 8))), "no sync word")
        var reserved = frame.data
        reserved[4] |= 0xC0  // fscod 3: reserved
        XCTAssertThrowsError(try DolbyAudio.ac3(reserved))
    }

    /// E-AC-3 whose first independent substream is not program 0 says nothing of the frame's length: refused, rather
    /// than an audio clock with no ticks per frame.
    func testDolbyDigitalPlusWithoutItsFirstProgramIsRefused() throws {
        func syncframe(substream: UInt32) -> Data {
            var bits = BitWriter()
            bits.write(0x0B77, 16)
            bits.write(0, 2)  // strmtyp: independent
            bits.write(substream, 3)
            bits.write(3, 11)  // frmsiz: 8 bytes
            bits.write(0, 2)  // fscod: 48 kHz
            bits.write(3, 2)  // numblkscod: 6 blocks
            bits.write(2, 3)  // acmod: 2/0
            bits.write(0, 1)  // lfeon
            bits.write(16, 5)  // bsid
            bits.write(0, 19)  // dialnorm, compre, mixmdate, infomdate, addbsie, up to the frame's 8 bytes
            return bits.data
        }
        XCTAssertEqual(try DolbyAudio.eac3(syncframe(substream: 0)).frameLength, 1_536)
        XCTAssertThrowsError(try DolbyAudio.eac3(syncframe(substream: 1)))
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
