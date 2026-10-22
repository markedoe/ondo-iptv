import XCTest
@testable import IPTVCore

/// The Matroska reader behind the remux to the system player: a file made by ffmpeg (H.264 and AAC, 3 s), and
/// files built here for what ffmpeg does not write (lacing, header stripping, an index after the clusters).
final class MatroskaReaderTests: XCTestCase {
    // MARK: A file made by ffmpeg

    /// 160×96 H.264 at 25 frames per second with B-frames and a key frame a second, AAC mono at 48 kHz, 3 s.
    private func sample() throws -> DataByteSource {
        DataByteSource(try Fixture.data("remux-h264-aac.mkv"))
    }

    private func allFrames(_ source: DataByteSource, _ file: MatroskaFile) async throws -> [MatroskaFrame] {
        var frames: [MatroskaFrame] = []
        var offset = file.firstClusterOffset
        while offset < Int64(source.data.count) {
            guard let length = try? await MatroskaReader.clusterLength(at: offset, in: source) else { break }
            frames += try MatroskaReader.frames(inCluster: try await source.read(offset..<offset + length), file: file)
            offset += length
        }
        return frames
    }

    func testTracksDurationAndIndex() async throws {
        let file = try await MatroskaReader.open(try sample())
        XCTAssertEqual(file.timestampScale, 1_000_000)
        XCTAssertEqual(try XCTUnwrap(file.duration), 3.02, accuracy: 0.01)
        XCTAssertEqual(file.tracks.map(\.codecID), ["V_MPEG4/ISO/AVC", "A_AAC"])
        let video = file.tracks[0], audio = file.tracks[1]
        XCTAssertEqual(video.kind, .video)
        XCTAssertEqual(video.video?.width, 160)
        XCTAssertEqual(video.video?.height, 96)
        XCTAssertEqual(video.codecPrivate?.first, 1, "an avcC record")
        XCTAssertEqual(audio.kind, .audio)
        XCTAssertEqual(audio.audio?.samplingFrequency, 48_000)
        XCTAssertEqual(audio.audio?.channels, 1)
        // The index lists the video's key frames, one a second.
        XCTAssertEqual(file.cues.filter { $0.track == video.number }.map(\.time), [0, 1_000_000_000, 2_000_000_000])
        XCTAssertTrue(file.cues.allSatisfy { $0.clusterOffset >= file.firstClusterOffset })
    }

    func testEveryFrameIsRead() async throws {
        let source = try sample()
        let file = try await MatroskaReader.open(source)
        let frames = try await allFrames(source, file)
        let video = frames.filter { $0.track == 1 }, audio = frames.filter { $0.track == 2 }
        // The counts and sizes ffprobe gives for the same file.
        XCTAssertEqual(video.count, 75)
        XCTAssertEqual(audio.count, 142)
        XCTAssertEqual(video.reduce(0) { $0 + $1.data.count }, 35_750)
        XCTAssertEqual(audio.reduce(0) { $0 + $1.data.count }, 12_211)
        XCTAssertEqual(video.filter(\.isKeyframe).map(\.time), [0, 1_000_000_000, 2_000_000_000])
        // B-frames: stored in decoding order, so times are not increasing (0, 0.12, 0.04…).
        XCTAssertEqual(Array(video.prefix(3).map(\.time)), [0, 120_000_000, 40_000_000])
        // AAC's 1024 priming samples: the frames start at 0 and the track says how much the decoder drops (ffprobe
        // shows the first frame at -0.021 s).
        XCTAssertEqual(audio.first?.time, 0)
        XCTAssertEqual(file.tracks[1].codecDelay, 21_333_333)
        // The cue of each key frame points at the cluster that holds it.
        for cue in file.cues {
            let length = try await MatroskaReader.clusterLength(at: cue.clusterOffset, in: source)
            let cluster = try MatroskaReader.frames(inCluster: try await source.read(cue.clusterOffset..<cue.clusterOffset + length), file: file)
            XCTAssertTrue(cluster.contains { $0.track == cue.track && $0.time == cue.time && $0.isKeyframe })
        }
    }

    func testNotMatroskaFails() async {
        do {
            _ = try await MatroskaReader.open(DataByteSource(Data("#EXTM3U\n".utf8)))
            XCTFail("an M3U is not Matroska")
        } catch {
            XCTAssertTrue(error is RemuxError)
        }
    }

    // MARK: Files built here

    private func element(_ id: UInt32, _ body: Data) -> Data {
        MatroskaBytes.element(id, body)
    }

    private func element(_ id: UInt32, _ children: [Data]) -> Data {
        MatroskaBytes.element(id, children)
    }

    private func unsigned(_ id: UInt32, _ value: UInt64) -> Data {
        MatroskaBytes.unsigned(id, value)
    }

    private func string(_ id: UInt32, _ value: String) -> Data {
        MatroskaBytes.string(id, value)
    }

    /// A block of track 1 at `relative` ms in its cluster.
    private func block(relative: Int16, flags: UInt8, lacing: Data = Data(), frames: [Data]) -> Data {
        Data([0x81, UInt8(truncatingIfNeeded: UInt16(bitPattern: relative) >> 8), UInt8(truncatingIfNeeded: UInt16(bitPattern: relative)), flags])
            + lacing + frames.reduce(Data(), +)
    }

    private func frame(_ byte: UInt8, _ count: Int) -> Data {
        Data(repeating: byte, count: count)
    }

    /// Values of `builtFile`, changed one at a time to damage it.
    private struct Values {
        var defaultDuration: UInt64 = 32_000_000
        var clusterTime: UInt64 = 1_000
        var blockDuration: UInt64 = 96
        var cueTime: UInt64 = 1_000
        /// Where the seek head and the index say the index and the cluster are; nil for where they are.
        var cuesPosition: UInt64?
        var clusterPosition: UInt64?
    }

    /// An AC-3 track with header stripping (the sync word 0B 77 taken out of each frame), 32 ms frames, laced three
    /// ways in a cluster at 1 s, and its index after the clusters, found through the seek head.
    private func builtFile(compression: UInt64 = 3, withIndex: Bool = true, values: Values = Values()) -> Data {
        let header = element(0x1A45_DFA3, [string(0x4282, "matroska")])
        let info = element(0x1549_A966, [unsigned(0x2AD7B1, 1_000_000)])
        let track = element(0xAE, [
            unsigned(0xD7, 1), unsigned(0x83, 2), string(0x86, "A_AC3"), unsigned(0x23E383, values.defaultDuration),
            element(0xE1, [element(0xB5, Data([0x40, 0xE7, 0x70, 0x00, 0x00, 0x00, 0x00, 0x00])), unsigned(0x9F, 6)]),
            element(0x6D80, [element(0x6240, [element(0x5034, [unsigned(0x4254, compression), element(0x4255, Data([0x0B, 0x77]))])])]),
        ])
        let tracks = element(0x1654_AE6B, [track])
        // Xiph lacing: 3 frames of 300, 2 and 5 bytes (300 = 255 + 45).
        let xiph = block(relative: 0, flags: 0x80 | 0x02, lacing: Data([2, 255, 45, 2]), frames: [frame(1, 300), frame(2, 2), frame(3, 5)])
        // EBML lacing: 3 frames of 10, 12 and 7 bytes (sizes 10, then +2 as a signed 1-byte difference: 0x80 | (2 + 63)).
        let ebml = block(relative: 96, flags: 0x06, lacing: Data([2, 0x8A, 0x80 | 65] as [UInt8]), frames: [frame(4, 10), frame(5, 12), frame(6, 7)])
        // Fixed-size lacing: 2 frames of 4 bytes.
        let fixed = block(relative: 192, flags: 0x80 | 0x04, lacing: Data([1]), frames: [frame(7, 4), frame(8, 4)])
        let cluster = element(0x1F43_B675, [
            unsigned(0xE7, values.clusterTime), element(0xA3, xiph),
            element(0xA0, [element(0xA1, ebml), unsigned(0x9B, values.blockDuration)]),
            element(0xA3, fixed),
        ])
        // Positions are from the start of the segment's content: the seek head comes first, with a fixed size.
        let seekHeadLength = element(0x114D_9B74, [element(0x4DBB, [element(0x53AB, Data([0x1C, 0x53, 0xBB, 0x6B])), unsigned(0x53AC, 0)])]).count
        let cuesPosition = values.cuesPosition ?? UInt64(seekHeadLength + info.count + tracks.count + cluster.count)
        let clusterPosition = values.clusterPosition ?? UInt64(seekHeadLength + info.count + tracks.count)
        let seekHead = element(0x114D_9B74, [element(0x4DBB, [element(0x53AB, Data([0x1C, 0x53, 0xBB, 0x6B])), unsigned(0x53AC, cuesPosition)])])
        let cues = element(0x1C53_BB6B, [element(0xBB, [unsigned(0xB3, values.cueTime), element(0xB7, [unsigned(0xF7, 1), unsigned(0xF1, clusterPosition)])])])
        let segment = element(0x1853_8067, [seekHead, info, tracks, cluster] + (withIndex ? [cues] : []))
        return header + segment
    }

    func testLacingHeaderStrippingAndAnIndexAfterTheClusters() async throws {
        let source = DataByteSource(builtFile())
        let file = try await MatroskaReader.open(source)
        XCTAssertEqual(file.tracks.first?.strippedHeader, Data([0x0B, 0x77]))
        XCTAssertEqual(file.tracks.first?.audio?.samplingFrequency, 48_000)
        XCTAssertEqual(file.tracks.first?.audio?.channels, 6)
        XCTAssertEqual(file.cues, [MatroskaCuePoint(time: 1_000_000_000, track: 1, clusterOffset: file.firstClusterOffset)])
        let length = try await MatroskaReader.clusterLength(at: file.firstClusterOffset, in: source)
        let frames = try MatroskaReader.frames(inCluster: try await source.read(file.firstClusterOffset..<file.firstClusterOffset + length), file: file)
        XCTAssertEqual(frames.map { $0.data.count - 2 }, [300, 2, 5, 10, 12, 7, 4, 4])
        XCTAssertTrue(frames.allSatisfy { $0.data.prefix(2) == Data([0x0B, 0x77]) }, "the stripped header is put back")
        XCTAssertEqual(frames.map { $0.data.last }, [1, 2, 3, 4, 5, 6, 7, 8])
        // Laced frames follow each other by the track's frame duration.
        XCTAssertEqual(frames.map { $0.time / 1_000_000 }, [1_000, 1_032, 1_064, 1_096, 1_128, 1_160, 1_192, 1_224])
        XCTAssertEqual(frames.map(\.isKeyframe), [true, true, true, true, true, true, true, true], "a block group without reference is a key frame")
    }

    func testCompressedTracksAndFilesWithoutIndexAreLeftToVLC() async {
        for (data, reason) in [(builtFile(compression: 0), "compressed"), (builtFile(withIndex: false), "no index")] {
            do {
                _ = try await MatroskaReader.open(DataByteSource(data))
                XCTFail(reason)
            } catch let error as RemuxError {
                XCTAssertTrue(error.description.contains(reason), error.description)
            } catch {
                XCTFail("\(error)")
            }
        }
    }

    /// Positions and times past what 64 bits hold (8 bytes of FF) throw instead of overflowing: the app plays the
    /// file with VLC.
    func testPositionsAndTimesThatOverflowAreLeftToVLC() async throws {
        let damaged: [(String, Values)] = [
            ("seek position", Values(cuesPosition: .max)),
            ("cue position", Values(clusterPosition: .max)),
            ("cue time", Values(cueTime: .max)),
        ]
        for (name, values) in damaged {
            do {
                _ = try await MatroskaReader.open(DataByteSource(builtFile(values: values)))
                XCTFail(name)
            } catch {
                XCTAssertTrue(error is RemuxError, "\(name): \(error)")
            }
        }
        // In the cluster: its time, a block's duration, and the frames of a lace that follow each other by the track's
        // frame duration.
        for (name, values) in [
            ("cluster time", Values(clusterTime: .max)), ("block duration", Values(blockDuration: .max)),
            ("frame duration", Values(defaultDuration: .max)),
        ] {
            let source = DataByteSource(builtFile(values: values))
            let file = try await MatroskaReader.open(source)
            let length = try await MatroskaReader.clusterLength(at: file.firstClusterOffset, in: source)
            let bytes = try await source.read(file.firstClusterOffset..<file.firstClusterOffset + length)
            XCTAssertThrowsError(try MatroskaReader.frames(inCluster: bytes, file: file), name) { XCTAssertTrue($0 is RemuxError, "\(name): \($0)") }
        }
    }

    /// EBML lacing whose sizes add up past 64 bits (255 frames of 2^56 - 2 bytes) throws before adding them.
    func testLaceSizesThatOverflowAreRefused() {
        let block = Data([0x81, 0, 0, 0x06, 0xFF, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFE] + [UInt8](repeating: 0xBF, count: 254))
        var offset = 4
        XCTAssertThrowsError(try MatroskaReader.laceSizes(block, offset: &offset, lacing: 3)) { XCTAssertTrue($0 is RemuxError, "\($0)") }
        // A size past the block's bytes, and a difference below zero.
        offset = 4
        XCTAssertThrowsError(try MatroskaReader.laceSizes(Data([0x81, 0, 0, 0x06, 0x01, 0x90]) + Data(count: 8), offset: &offset, lacing: 3))
        offset = 4
        XCTAssertThrowsError(try MatroskaReader.laceSizes(Data([0x81, 0, 0, 0x06, 0x02, 0x84, 0x80]) + Data(count: 8), offset: &offset, lacing: 3))
    }

    func testVariableLengthIntegers() {
        XCTAssertEqual(EBML.vint(Data([0x81]), at: 0, isID: false)?.value, 1)
        XCTAssertEqual(EBML.vint(Data([0x40, 0x02]), at: 0, isID: false)?.value, 2)
        XCTAssertEqual(EBML.vint(Data([0xFF]), at: 0, isID: false)?.value, EBML.unknownSize)
        XCTAssertEqual(EBML.vint(Data([0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]), at: 0, isID: false)?.value, EBML.unknownSize)
        XCTAssertEqual(EBML.vint(Data([0x1A, 0x45, 0xDF, 0xA3]), at: 0, isID: true)?.value, 0x1A45_DFA3, "an ID keeps its marker")
        XCTAssertNil(EBML.vint(Data([0x00]), at: 0, isID: false))
        XCTAssertNil(EBML.vint(Data([0x40]), at: 0, isID: false), "cut short")
    }
}
