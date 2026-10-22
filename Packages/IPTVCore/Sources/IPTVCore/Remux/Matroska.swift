import Foundation

/// A track of a Matroska (MKV, WebM) file.
public struct MatroskaTrack: Sendable, Equatable {
    public enum Kind: Sendable, Equatable {
        case video, audio, subtitle, other
    }

    public struct Video: Sendable, Equatable {
        public var width: Int
        public var height: Int
        public var colour: Colour?
    }

    /// HDR and colour description (Matroska's Colour element), with the ISO/ITU code points.
    public struct Colour: Sendable, Equatable {
        public var matrix: Int?
        public var bitsPerChannel: Int?
        public var range: Int?
        public var transfer: Int?
        public var primaries: Int?
        public var maxCLL: Int?
        public var maxFALL: Int?
        public var colourVolume: ColourVolume?
    }

    /// The colour volume of the display the film was graded on (SMPTE ST 2086, Matroska's MasteringMetadata):
    /// chromaticities as CIE 1931 x/y, luminance in cd/m².
    public struct ColourVolume: Sendable, Equatable {
        public var primaries: [Double]  // R x, R y, G x, G y, B x, B y
        public var whitePoint: [Double]  // x, y
        public var maxLuminance: Double
        public var minLuminance: Double
    }

    public struct Audio: Sendable, Equatable {
        public var samplingFrequency: Double
        public var outputSamplingFrequency: Double?
        public var channels: Int
        public var bitDepth: Int?
    }

    /// Extra data a block carries for a codec (BlockAdditionMapping): Dolby Vision's configuration ("dvcC", "dvvC").
    public struct BlockAddition: Sendable, Equatable {
        public var type: UInt32
        public var extraData: Data
    }

    public var number: UInt64
    public var kind: Kind
    public var codecID: String
    public var codecPrivate: Data?
    public var language: String?
    public var name: String?
    public var isDefault: Bool
    public var isEnabled: Bool
    /// Nanoseconds per frame, when constant.
    public var defaultDuration: UInt64?
    /// Nanoseconds the decoder drops at the start (Opus).
    public var codecDelay: UInt64
    public var video: Video?
    public var audio: Audio?
    /// Bytes removed from the start of each frame (header stripping), put back when reading.
    public var strippedHeader: Data?
    public var blockAdditions: [BlockAddition]
}

/// An entry of the file's index: where the cluster holding a key frame of `track` at `time` starts.
public struct MatroskaCuePoint: Sendable, Equatable {
    /// Nanoseconds.
    public var time: Int64
    public var track: UInt64
    /// From the start of the file.
    public var clusterOffset: Int64
    /// Where the key frame's block starts in its cluster's content, when the index says.
    public var relativePosition: Int64?
}

/// A frame of a track, as stored: its presentation time and its bytes.
public struct MatroskaFrame: Sendable, Equatable {
    public var track: UInt64
    /// Nanoseconds.
    public var time: Int64
    /// Nanoseconds, when the file says (BlockDuration, or a frame of a lace followed by another).
    public var duration: Int64?
    public var isKeyframe: Bool
    public var data: Data
    /// Where its block starts in the file: frames are split between segments by it.
    public var position: Int64 = 0
}

/// What the file says about itself before its first cluster: its tracks, duration and index.
public struct MatroskaFile: Sendable, Equatable {
    /// Nanoseconds per timestamp unit.
    public var timestampScale: UInt64
    /// Seconds.
    public var duration: Double?
    public var tracks: [MatroskaTrack]
    public var cues: [MatroskaCuePoint]
    /// Where the segment's content starts in the file (the origin of its positions).
    public var segmentOffset: Int64
    public var firstClusterOffset: Int64
    /// Where the clusters end: the index or the tags after them, or the end of the file.
    public var clustersEnd: Int64
    public var fileSize: Int64?
}

/// Reads Matroska files for the remux to the system player: their header, index and clusters. Anything it does not
/// support (compressed or encrypted tracks, a damaged structure, no index) throws, and the app plays the file with
/// VLC instead.
public enum MatroskaReader {
    enum IDs {
        static let ebml: UInt32 = 0x1A45_DFA3
        static let docType: UInt32 = 0x4282
        static let segment: UInt32 = 0x1853_8067
        static let seekHead: UInt32 = 0x114D_9B74
        static let seek: UInt32 = 0x4DBB
        static let seekID: UInt32 = 0x53AB
        static let seekPosition: UInt32 = 0x53AC
        static let info: UInt32 = 0x1549_A966
        static let timestampScale: UInt32 = 0x2AD7B1
        static let duration: UInt32 = 0x4489
        static let tracks: UInt32 = 0x1654_AE6B
        static let trackEntry: UInt32 = 0xAE
        static let trackNumber: UInt32 = 0xD7
        static let trackType: UInt32 = 0x83
        static let flagEnabled: UInt32 = 0xB9
        static let flagDefault: UInt32 = 0x88
        static let defaultDuration: UInt32 = 0x23E383
        static let name: UInt32 = 0x536E
        static let language: UInt32 = 0x22B59C
        static let languageBCP47: UInt32 = 0x22B59D
        static let codecID: UInt32 = 0x86
        static let codecPrivate: UInt32 = 0x63A2
        static let codecDelay: UInt32 = 0x56AA
        static let video: UInt32 = 0xE0
        static let pixelWidth: UInt32 = 0xB0
        static let pixelHeight: UInt32 = 0xBA
        static let colour: UInt32 = 0x55B0
        static let audio: UInt32 = 0xE1
        static let samplingFrequency: UInt32 = 0xB5
        static let outputSamplingFrequency: UInt32 = 0x78B5
        static let channels: UInt32 = 0x9F
        static let bitDepth: UInt32 = 0x6264
        static let contentEncodings: UInt32 = 0x6D80
        static let contentEncoding: UInt32 = 0x6240
        static let contentCompression: UInt32 = 0x5034
        static let contentCompAlgo: UInt32 = 0x4254
        static let contentCompSettings: UInt32 = 0x4255
        static let contentEncryption: UInt32 = 0x5035
        static let contentEncodingType: UInt32 = 0x5033
        static let blockAdditionMapping: UInt32 = 0x41E4
        static let blockAddIDType: UInt32 = 0x41E7
        static let blockAddIDExtraData: UInt32 = 0x41ED
        static let cues: UInt32 = 0x1C53_BB6B
        static let cuePoint: UInt32 = 0xBB
        static let cueTime: UInt32 = 0xB3
        static let cueTrackPositions: UInt32 = 0xB7
        static let cueTrack: UInt32 = 0xF7
        static let cueClusterPosition: UInt32 = 0xF1
        static let cueRelativePosition: UInt32 = 0xF0
        static let cluster: UInt32 = 0x1F43_B675
        static let clusterTimestamp: UInt32 = 0xE7
        static let simpleBlock: UInt32 = 0xA3
        static let blockGroup: UInt32 = 0xA0
        static let block: UInt32 = 0xA1
        static let blockDuration: UInt32 = 0x9B
        static let referenceBlock: UInt32 = 0xFB
    }

    /// Top-level elements larger than this are not read whole (attachments, a stray cluster): the header is.
    static let largestHeaderElement = 16 * 1024 * 1024

    // MARK: Header

    /// The file's tracks, duration and index, read from its start (and from where its index says the cues are).
    public static func open(_ source: ByteSource) async throws -> MatroskaFile {
        let fileSize = try await source.size()
        let head = try await source.read(0..<64 * 1024)
        guard let ebml = EBML.header(head, at: 0), ebml.id == IDs.ebml, let ebmlEnd = ebml.end, ebmlEnd <= head.count else {
            throw RemuxError("not a Matroska file")
        }
        let docType = try EBML.children(head, in: ebml.dataOffset..<ebmlEnd).first { $0.id == IDs.docType }
            .map { try EBML.string(head, $0) }
        guard docType == "matroska" || docType == "webm" else { throw RemuxError("not a Matroska file (\(docType ?? "no type"))") }
        guard let segment = EBML.header(head, at: ebmlEnd), segment.id == IDs.segment else { throw RemuxError("no segment") }
        let segmentOffset = Int64(segment.dataOffset)
        let segmentEnd = segment.hasUnknownSize ? fileSize ?? .max : segmentOffset + Int64(clamping: segment.size)

        var file = MatroskaFile(
            timestampScale: 1_000_000, duration: nil, tracks: [], cues: [], segmentOffset: segmentOffset, firstClusterOffset: 0,
            clustersEnd: segmentEnd, fileSize: fileSize
        )
        let scan = try await topLevel(source, from: segmentOffset, to: segmentEnd)
        let seekPositions = scan.seekPositions
        file.firstClusterOffset = scan.firstCluster ?? 0
        file.timestampScale = scan.scale
        let rawDuration = scan.duration
        let seenTracks = scan.tracks != nil, seenCues = scan.cues != nil
        file.tracks = scan.tracks ?? []
        file.cues = scan.cues ?? []
        guard file.firstClusterOffset > 0 else { throw RemuxError("no cluster") }
        guard seenTracks else { throw RemuxError("no tracks") }
        // The first element the seek head places after the clusters ends them.
        let after = try seekPositions.values.map { try segmentOffset.adding($0) }.filter { $0 > file.firstClusterOffset }
        if let end = after.min() { file.clustersEnd = min(file.clustersEnd, end) }
        // The index is usually after the clusters: the seek head says where.
        if !seenCues, let position = seekPositions[IDs.cues] {
            let at = try segmentOffset.adding(position)
            let headerBytes = try await source.read(at..<at.adding(12))
            guard let header = EBML.header(headerBytes, at: 0), header.id == IDs.cues, !header.hasUnknownSize,
                  header.size <= largestHeaderElement else { throw RemuxError("no index where the seek head says") }
            let bodyStart = at + Int64(header.headerLength)
            let body = try await source.read(bodyStart..<bodyStart.adding(Int64(header.size)))
            guard body.count == Int(header.size) else { throw RemuxError("file ends inside its index") }
            file.cues = try cues(body, segmentOffset: segmentOffset, scale: 1)
        }
        guard !file.cues.isEmpty else { throw RemuxError("no index") }
        // Cue times are in timestamp units until the scale is known.
        let scale = Int64(clamping: file.timestampScale)
        file.cues = try file.cues.map { cue in
            var cue = cue
            cue.time = try cue.time.multiplied(by: scale)
            return cue
        }.sorted { $0.time < $1.time }
        file.duration = rawDuration.map { $0 * Double(file.timestampScale) / 1e9 }
        return file
    }

    /// What the segment's top-level elements say, up to the first cluster.
    struct TopLevel {
        var seekPositions: [UInt32: Int64] = [:]
        var scale: UInt64 = 1_000_000
        var duration: Double?
        var tracks: [MatroskaTrack]?
        var cues: [MatroskaCuePoint]?
        var firstCluster: Int64?
    }

    static func topLevel(_ source: ByteSource, from start: Int64, to end: Int64) async throws -> TopLevel {
        var scan = TopLevel()
        var offset = start
        while offset < end {
            let headerBytes = try await source.read(offset..<offset.adding(12))
            guard let header = EBML.header(headerBytes, at: 0) else { throw RemuxError("broken element at \(offset)") }
            if header.id == IDs.cluster {
                scan.firstCluster = offset
                break
            }
            guard !header.hasUnknownSize else { throw RemuxError("top-level element of unknown size") }
            let bodyStart = offset + Int64(header.headerLength)
            let bodyEnd = try bodyStart.adding(Int64(clamping: header.size))
            offset = bodyEnd
            // Void, Chapters, Tags, Attachments… are skipped.
            guard [IDs.seekHead, IDs.info, IDs.tracks, IDs.cues].contains(header.id) else { continue }
            guard header.size <= largestHeaderElement else { throw RemuxError("header element too large") }
            let body = try await source.read(bodyStart..<bodyEnd)
            guard body.count == Int(header.size) else { throw RemuxError("file ends inside its header") }
            switch header.id {
            case IDs.seekHead: scan.seekPositions.merge(try seekHead(body), uniquingKeysWith: { first, _ in first })
            case IDs.info: (scan.scale, scan.duration) = try info(body)
            case IDs.tracks: scan.tracks = try tracks(body)
            default: scan.cues = try cues(body, segmentOffset: start, scale: 1)
            }
        }
        return scan
    }

    static func seekHead(_ body: Data) throws -> [UInt32: Int64] {
        var positions: [UInt32: Int64] = [:]
        for seek in try EBML.children(body, in: 0..<body.count) where seek.id == IDs.seek {
            var id: UInt32?, position: Int64?
            for child in try EBML.children(body, in: seek.dataOffset..<(seek.end ?? seek.dataOffset)) {
                switch child.id {
                case IDs.seekID: id = UInt32(truncatingIfNeeded: try EBML.unsigned(body, child))
                case IDs.seekPosition: position = Int64(clamping: try EBML.unsigned(body, child))
                default: break
                }
            }
            if let id, let position, positions[id] == nil { positions[id] = position }
        }
        return positions
    }

    static func info(_ body: Data) throws -> (scale: UInt64, duration: Double?) {
        var scale: UInt64 = 1_000_000
        var duration: Double?
        for child in try EBML.children(body, in: 0..<body.count) {
            switch child.id {
            case IDs.timestampScale: scale = try EBML.unsigned(body, child)
            case IDs.duration: duration = try EBML.float(body, child)
            default: break
            }
        }
        guard scale > 0 else { throw RemuxError("no timestamp scale") }
        return (scale, duration)
    }

    static func tracks(_ body: Data) throws -> [MatroskaTrack] {
        try EBML.children(body, in: 0..<body.count).filter { $0.id == IDs.trackEntry }.map { try track(body, $0) }
    }

    // swiftlint:disable:next cyclomatic_complexity
    static func track(_ body: Data, _ entry: EBML.Header) throws -> MatroskaTrack {
        var track = MatroskaTrack(
            number: 0, kind: .other, codecID: "", codecPrivate: nil, language: nil, name: nil, isDefault: true, isEnabled: true,
            defaultDuration: nil, codecDelay: 0, video: nil, audio: nil, strippedHeader: nil, blockAdditions: []
        )
        var bcp47: String?
        for child in try EBML.children(body, in: entry.dataOffset..<(entry.end ?? entry.dataOffset)) {
            switch child.id {
            case IDs.trackNumber: track.number = try EBML.unsigned(body, child)
            case IDs.trackType:
                switch try EBML.unsigned(body, child) {
                case 1: track.kind = .video
                case 2: track.kind = .audio
                case 17: track.kind = .subtitle
                default: track.kind = .other
                }
            case IDs.flagEnabled: track.isEnabled = try EBML.unsigned(body, child) != 0
            case IDs.flagDefault: track.isDefault = try EBML.unsigned(body, child) != 0
            case IDs.defaultDuration: track.defaultDuration = try EBML.unsigned(body, child)
            case IDs.name: track.name = try EBML.string(body, child)
            case IDs.language: track.language = try EBML.string(body, child)
            case IDs.languageBCP47: bcp47 = try EBML.string(body, child)
            case IDs.codecID: track.codecID = try EBML.string(body, child)
            case IDs.codecPrivate: track.codecPrivate = try EBML.binary(body, child)
            case IDs.codecDelay: track.codecDelay = try EBML.unsigned(body, child)
            case IDs.video: track.video = try video(body, child)
            case IDs.audio: track.audio = try audio(body, child)
            case IDs.contentEncodings: track.strippedHeader = try strippedHeader(body, child)
            case IDs.blockAdditionMapping:
                var type: UInt32?, extra: Data?
                for field in try EBML.children(body, in: child.dataOffset..<(child.end ?? child.dataOffset)) {
                    if field.id == IDs.blockAddIDType { type = UInt32(truncatingIfNeeded: try EBML.unsigned(body, field)) }
                    if field.id == IDs.blockAddIDExtraData { extra = try EBML.binary(body, field) }
                }
                if let type, let extra { track.blockAdditions.append(MatroskaTrack.BlockAddition(type: type, extraData: extra)) }
            default: break
            }
        }
        if let bcp47, !bcp47.isEmpty { track.language = bcp47 }
        guard track.number > 0 else { throw RemuxError("track without a number") }
        return track
    }

    static func video(_ body: Data, _ element: EBML.Header) throws -> MatroskaTrack.Video {
        var video = MatroskaTrack.Video(width: 0, height: 0, colour: nil)
        for child in try EBML.children(body, in: element.dataOffset..<(element.end ?? element.dataOffset)) {
            switch child.id {
            case IDs.pixelWidth: video.width = Int(clamping: try EBML.unsigned(body, child))
            case IDs.pixelHeight: video.height = Int(clamping: try EBML.unsigned(body, child))
            case IDs.colour: video.colour = try colour(body, child)
            default: break
            }
        }
        return video
    }

    static func colour(_ body: Data, _ element: EBML.Header) throws -> MatroskaTrack.Colour {
        var colour = MatroskaTrack.Colour()
        for child in try EBML.children(body, in: element.dataOffset..<(element.end ?? element.dataOffset)) {
            func value() throws -> Int { Int(clamping: try EBML.unsigned(body, child)) }
            switch child.id {
            case 0x55B1: colour.matrix = try value()
            case 0x55B2: colour.bitsPerChannel = try value()
            case 0x55B9: colour.range = try value()
            case 0x55BA: colour.transfer = try value()
            case 0x55BB: colour.primaries = try value()
            case 0x55BC: colour.maxCLL = try value()
            case 0x55BD: colour.maxFALL = try value()
            case 0x55D0:
                var values: [UInt32: Double] = [:]
                for field in try EBML.children(body, in: child.dataOffset..<(child.end ?? child.dataOffset)) {
                    values[field.id] = try EBML.float(body, field)
                }
                let primaries: [UInt32] = [0x55D1, 0x55D2, 0x55D3, 0x55D4, 0x55D5, 0x55D6]
                if primaries.allSatisfy({ values[$0] != nil }), let whiteX = values[0x55D7], let whiteY = values[0x55D8],
                   let max = values[0x55D9] {
                    colour.colourVolume = MatroskaTrack.ColourVolume(
                        primaries: primaries.compactMap { values[$0] }, whitePoint: [whiteX, whiteY], maxLuminance: max,
                        minLuminance: values[0x55DA] ?? 0
                    )
                }
            default: break
            }
        }
        return colour
    }

    static func audio(_ body: Data, _ element: EBML.Header) throws -> MatroskaTrack.Audio {
        var audio = MatroskaTrack.Audio(samplingFrequency: 8_000, outputSamplingFrequency: nil, channels: 1, bitDepth: nil)
        for child in try EBML.children(body, in: element.dataOffset..<(element.end ?? element.dataOffset)) {
            switch child.id {
            case IDs.samplingFrequency: audio.samplingFrequency = try EBML.float(body, child)
            case IDs.outputSamplingFrequency: audio.outputSamplingFrequency = try EBML.float(body, child)
            case IDs.channels: audio.channels = Int(clamping: try EBML.unsigned(body, child))
            case IDs.bitDepth: audio.bitDepth = Int(clamping: try EBML.unsigned(body, child))
            default: break
            }
        }
        return audio
    }

    /// Header stripping is the only content encoding the remux undoes: compressed or encrypted tracks throw.
    static func strippedHeader(_ body: Data, _ element: EBML.Header) throws -> Data? {
        var stripped: Data?
        for encoding in try EBML.children(body, in: element.dataOffset..<(element.end ?? element.dataOffset))
        where encoding.id == IDs.contentEncoding {
            for child in try EBML.children(body, in: encoding.dataOffset..<(encoding.end ?? encoding.dataOffset)) {
                switch child.id {
                case IDs.contentEncodingType:
                    if try EBML.unsigned(body, child) != 0 { throw RemuxError("encrypted track") }
                case IDs.contentEncryption:
                    throw RemuxError("encrypted track")
                case IDs.contentCompression:
                    var algorithm: UInt64 = 0
                    var settings = Data()
                    for field in try EBML.children(body, in: child.dataOffset..<(child.end ?? child.dataOffset)) {
                        if field.id == IDs.contentCompAlgo { algorithm = try EBML.unsigned(body, field) }
                        if field.id == IDs.contentCompSettings { settings = try EBML.binary(body, field) }
                    }
                    guard algorithm == 3 else { throw RemuxError("compressed track (algorithm \(algorithm))") }
                    stripped = (stripped ?? Data()) + settings
                default: break
                }
            }
        }
        return stripped
    }

    /// The index's entries, with times in timestamp units (`open` scales them).
    static func cues(_ body: Data, segmentOffset: Int64, scale: Int64) throws -> [MatroskaCuePoint] {
        var points: [MatroskaCuePoint] = []
        for point in try EBML.children(body, in: 0..<body.count) where point.id == IDs.cuePoint {
            var time: Int64?
            var positions: [(UInt64, Int64, Int64?)] = []
            for child in try EBML.children(body, in: point.dataOffset..<(point.end ?? point.dataOffset)) {
                switch child.id {
                case IDs.cueTime: time = Int64(clamping: try EBML.unsigned(body, child))
                case IDs.cueTrackPositions:
                    var track: UInt64?, cluster: Int64?, relative: Int64?
                    for field in try EBML.children(body, in: child.dataOffset..<(child.end ?? child.dataOffset)) {
                        if field.id == IDs.cueTrack { track = try EBML.unsigned(body, field) }
                        if field.id == IDs.cueClusterPosition { cluster = Int64(clamping: try EBML.unsigned(body, field)) }
                        if field.id == IDs.cueRelativePosition { relative = Int64(clamping: try EBML.unsigned(body, field)) }
                    }
                    if let track, let cluster { positions.append((track, cluster, relative)) }
                default: break
                }
            }
            guard let time else { continue }
            for (track, cluster, relative) in positions {
                points.append(
                    MatroskaCuePoint(time: try time.multiplied(by: scale), track: track, clusterOffset: try segmentOffset.adding(cluster), relativePosition: relative)
                )
            }
        }
        return points
    }

    // MARK: Clusters

    /// The size of the cluster starting at `offset` (header included), to read it in one request.
    public static func clusterLength(at offset: Int64, in source: ByteSource) async throws -> Int64 {
        let headerBytes = try await source.read(offset..<offset.adding(12))
        guard let header = EBML.header(headerBytes, at: 0), header.id == IDs.cluster else { throw RemuxError("no cluster at \(offset)") }
        guard !header.hasUnknownSize else { throw RemuxError("cluster of unknown size") }
        return Int64(header.headerLength) + Int64(clamping: header.size)
    }

    /// A cluster met while reading a range: where it starts, the length of its header and its time (in units).
    public struct ClusterMark: Sendable, Equatable {
        public var offset: Int64
        public var headerLength: Int
        public var time: Int64
    }

    /// The frames of a whole cluster (`bytes` starts with its header, at `offset` in the file), in file order.
    public static func frames(inCluster bytes: Data, at offset: Int64 = 0, file: MatroskaFile) throws -> [MatroskaFrame] {
        let read = try frames(in: bytes, from: offset, file: file)
        guard read.clusters.count == 1, read.complete else { throw RemuxError("incomplete cluster") }
        return read.frames
    }

    /// The frames of the clusters in `bytes`, read from `from` in the file, which must be the start of a cluster. The
    /// last cluster may be cut short: its blocks are read up to the cut. Reading stops at an element that is not a
    /// cluster (the index, tags).
    public static func frames(in bytes: Data, from offset: Int64, file: MatroskaFile) throws -> (frames: [MatroskaFrame], clusters: [ClusterMark], complete: Bool) {
        let bytes = Data(bytes)
        let scale = Int64(clamping: file.timestampScale)
        let tracks = Dictionary(file.tracks.map { ($0.number, $0) }, uniquingKeysWith: { first, _ in first })
        var frames: [MatroskaFrame] = []
        var clusters: [ClusterMark] = []
        var position = 0
        while position < bytes.count {
            guard let cluster = EBML.header(bytes, at: position) else { return (frames, clusters, false) }
            guard cluster.id == IDs.cluster else { break }
            guard !cluster.hasUnknownSize else { throw RemuxError("cluster of unknown size") }
            let clusterEnd = cluster.dataOffset + Int(clamping: cluster.size)
            var mark = ClusterMark(offset: offset + Int64(position), headerLength: cluster.headerLength, time: 0)
            var child = cluster.dataOffset
            var timeSeen = false
            while child < min(clusterEnd, bytes.count) {
                guard let header = EBML.header(bytes, at: child), let end = header.end else {
                    if !timeSeen { throw RemuxError("cluster without its time") }
                    clusters.append(mark)
                    return (frames, clusters, false)
                }
                guard end <= clusterEnd else { throw RemuxError("element runs past its cluster") }
                guard end <= bytes.count else { break }
                switch header.id {
                case IDs.clusterTimestamp:
                    mark.time = Int64(clamping: try EBML.unsigned(bytes, header))
                    timeSeen = true
                case IDs.simpleBlock, IDs.blockGroup:
                    guard timeSeen else { throw RemuxError("block before the cluster's time") }
                    let context = BlockContext(clusterTime: mark.time, scale: scale, tracks: tracks)
                    var read = try blockFrames(bytes, header, in: context)
                    for index in read.indices { read[index].position = offset + Int64(child) }
                    frames += read
                default:
                    break
                }
                child = end
            }
            clusters.append(mark)
            guard clusterEnd <= bytes.count else { return (frames, clusters, false) }
            position = clusterEnd
        }
        return (frames, clusters, true)
    }

    /// The frames of a SimpleBlock or a BlockGroup.
    static func blockFrames(_ bytes: Data, _ header: EBML.Header, in context: BlockContext) throws -> [MatroskaFrame] {
        guard header.id == IDs.blockGroup else {
            return try block(try EBML.binary(bytes, header), in: context, duration: nil, simple: true, referenced: false)
        }
        var block: Data?, duration: Int64?, referenced = false
        for field in try EBML.children(bytes, in: header.dataOffset..<(header.end ?? header.dataOffset)) {
            switch field.id {
            case IDs.block: block = try EBML.binary(bytes, field)
            case IDs.blockDuration: duration = try Int64(clamping: try EBML.unsigned(bytes, field)).multiplied(by: context.scale)
            case IDs.referenceBlock: referenced = true
            default: break
            }
        }
        guard let block else { return [] }
        return try Self.block(block, in: context, duration: duration, simple: false, referenced: referenced)
    }

    /// Where blocks are read: their cluster's time, the file's timestamp scale and its tracks.
    struct BlockContext {
        var clusterTime: Int64
        var scale: Int64
        var tracks: [UInt64: MatroskaTrack]
    }

    /// A block's frames: one, or several when laced (Xiph, EBML or fixed-size lacing). `simple` for a SimpleBlock, whose
    /// flags say whether it is a key frame; a Block is one when its group references no other.
    static func block(_ block: Data, in context: BlockContext, duration: Int64?, simple: Bool, referenced: Bool) throws -> [MatroskaFrame] {
        guard let number = EBML.vint(block, at: 0, isID: false), block.count >= number.length + 3 else { throw RemuxError("short block") }
        // Frames of tracks the file does not declare are skipped, as players do.
        guard let track = context.tracks[number.value] else { return [] }
        let relative = Int16(bitPattern: UInt16(block[number.length]) << 8 | UInt16(block[number.length + 1]))
        let flags = block[number.length + 2]
        let time = try context.clusterTime.adding(Int64(relative)).multiplied(by: context.scale)
        let isKeyframe = simple ? flags & 0x80 != 0 : !referenced
        var offset = number.length + 3
        let sizes = try laceSizes(block, offset: &offset, lacing: (flags >> 1) & 0x03)
        let step = track.defaultDuration.map { Int64(clamping: $0) }
        var frames: [MatroskaFrame] = []
        for (index, size) in sizes.enumerated() {
            var data = block.subdata(in: offset..<offset + size)
            offset += size
            if let header = track.strippedHeader { data = header + data }
            let frameTime = try time.adding(Int64(index).multiplied(by: step ?? 0))
            let frameDuration = sizes.count == 1 ? duration : step
            frames.append(MatroskaFrame(track: track.number, time: frameTime, duration: frameDuration, isKeyframe: isKeyframe, data: data))
        }
        return frames
    }

    /// The sizes of a block's frames, from `offset` (moved past the lacing header).
    static func laceSizes(_ block: Data, offset: inout Int, lacing: UInt8) throws -> [Int] {
        var sizes: [Int] = []
        switch lacing {
        case 0:
            sizes = [block.count - offset]
        case 1:  // Xiph: each size as a run of 255s and a last byte.
            guard offset < block.count else { throw RemuxError("short lace") }
            let count = Int(block[offset]) + 1
            offset += 1
            for _ in 0..<count - 1 {
                var size = 0
                while true {
                    guard offset < block.count else { throw RemuxError("short lace") }
                    let byte = block[offset]
                    offset += 1
                    size += Int(byte)
                    if byte != 255 { break }
                }
                sizes.append(size)
            }
            sizes.append(block.count - offset - sizes.reduce(0, +))
        case 3:  // EBML: the first size, then signed differences.
            guard offset < block.count else { throw RemuxError("short lace") }
            let count = Int(block[offset]) + 1
            offset += 1
            var size = 0, total = 0
            for index in 0..<count - 1 {
                guard let raw = EBML.vint(block, at: offset, isID: false), raw.value != EBML.unknownSize else { throw RemuxError("bad lace") }
                offset += raw.length
                if index == 0 {
                    size = Int(clamping: raw.value)
                } else {
                    let bias = (Int64(1) << (7 * raw.length - 1)) - 1
                    size += Int(Int64(clamping: raw.value) - bias)
                }
                // Each size within the bytes left, so that they add up without overflowing.
                guard size >= 0, size <= block.count - offset - total else { throw RemuxError("bad lace sizes") }
                sizes.append(size)
                total += size
            }
            sizes.append(block.count - offset - total)
        default:  // Fixed size.
            guard offset < block.count else { throw RemuxError("short lace") }
            let count = Int(block[offset]) + 1
            offset += 1
            guard (block.count - offset) % count == 0 else { throw RemuxError("bad fixed lace") }
            sizes = Array(repeating: (block.count - offset) / count, count: count)
        }
        guard sizes.allSatisfy({ $0 >= 0 }), offset + sizes.reduce(0, +) == block.count else { throw RemuxError("bad lace sizes") }
        return sizes
    }
}
