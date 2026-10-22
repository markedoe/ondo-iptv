import Foundation

/// Turns a Matroska film into HLS made of fragmented MP4 for the system player, segment by segment as the player asks
/// for them: no transcoding, the frames are copied. The system player then shows HDR10, HLG and Dolby Vision, plays
/// Dolby Atmos and goes to Picture in Picture as with any HLS stream.
///
/// Segments start at the video's key frames, from the file's index, and last at least `targetDuration`. A segment's
/// frames are those stored between its key frame's block and the next segment's, so every frame lands in exactly one
/// segment, read in one range request. MKV keeps presentation times only: decoding times are rebuilt on a grid of the
/// frame duration, and audio runs on a grid of its frame length, both anchored on the first segment made.
public actor MatroskaRemux {
    public struct Segment: Sendable, Equatable {
        /// Nanoseconds: the key frame it starts with.
        public var time: Int64
        /// Seconds, for the playlist.
        public var duration: Double
        /// Where its key frame is in the index, and where the next segment's is (nil for the last).
        public var start: MatroskaCuePoint?
        public var end: MatroskaCuePoint?
    }

    /// Segments last at least this long (seconds), as Apple's authoring rules ask.
    public static let targetDuration = 6.0
    /// Added to every time, so that decoding times before the first frame stay positive (nanoseconds).
    static let timeOffset: Int64 = 10_000_000_000
    /// The video's ticks per second: a whole number of ticks per frame at 23.976, 24, 25, 29.97, 30, 50, 59.94 and 60
    /// frames per second, and per millisecond.
    static let videoTimescale: Int64 = 360_000
    /// Video ticks per frame the remux works with: up to a frame every 10 seconds.
    static let frameTicksRange: ClosedRange<Int64> = 1...videoTimescale * 10
    /// A segment is read and held whole: past this many bytes (an index with too few key frames), the film is left to VLC.
    static let largestSegment: Int64 = 128 * 1024 * 1024

    public nonisolated let file: MatroskaFile
    public nonisolated let video: MatroskaTrack
    public nonisolated let audio: MatroskaTrack?
    public nonisolated let segments: [Segment]
    public nonisolated let tracks: [FragmentTrack]
    private let source: ByteSource
    /// Video ticks per frame, from the track or measured on the first segment.
    private var frameTicks: Int64?
    private var grid: Grid?
    private var made: [Int: Data] = [:]
    private var fetches: [Int: Task<Data, Error>] = [:]

    /// The decoding-time grid, set by the first segment made.
    struct Grid {
        /// Video: decoding time of a key frame = its presentation time minus `reorder`, on the frame grid at `phase`.
        var videoPhase: Int64
        var reorder: Int64
        /// Audio: frames start on multiples of the frame length plus `audioPhase` (audio ticks).
        var audioPhase: Int64
    }

    /// Reads the file's header and index and checks that the system player can play its tracks: H.264 or HEVC video,
    /// AAC audio. Throws otherwise, and the app plays the file with VLC.
    public static func open(
        _ source: ByteSource, preferredLanguages: [String] = [], targetDuration: Double = targetDuration
    ) async throws -> MatroskaRemux {
        let file = try await MatroskaReader.open(source)
        let video = try chooseVideo(file.tracks)
        var candidates = try audioCandidates(file.tracks, preferredLanguages: preferredLanguages)
        var audio: (track: MatroskaTrack, fragment: FragmentTrack, frameLength: Int64)?
        // The first track whose description can be made: Dolby's is read from its first frame.
        while audio == nil, !candidates.isEmpty {
            let track = candidates.removeFirst()
            if let fragment = fragmentTrack(audio: track) {
                audio = (track, fragment, track.audio?.outputSamplingFrequency.map { $0 > (track.audio?.samplingFrequency ?? 0) } == true ? 2_048 : 1_024)
            } else if let frame = try? await firstFrame(of: track, in: file, source: source),
                      let made = try? dolbyTrack(track, frame: frame) {
                audio = (track, made.fragment, made.frameLength)
            }
        }
        if audio == nil, file.tracks.contains(where: { $0.kind == .audio && $0.isEnabled }) { throw RemuxError("no audio track can be described") }
        let segments = try plan(file, video: video, targetDuration: targetDuration)
        return MatroskaRemux(source: source, file: file, video: video, audio: audio, segments: segments)
    }

    private init(
        source: ByteSource, file: MatroskaFile, video: MatroskaTrack, audio: (track: MatroskaTrack, fragment: FragmentTrack, frameLength: Int64)?,
        segments: [Segment]
    ) {
        self.source = source
        self.file = file
        self.video = video
        self.audio = audio?.track
        audioFrameTicks = audio?.frameLength ?? 1_024
        self.segments = segments
        tracks = [Self.fragmentTrack(video)] + (audio.map { [$0.fragment] } ?? [])
        frameTicks = Self.frameTicks(video)
    }

    // MARK: Tracks

    static func chooseVideo(_ tracks: [MatroskaTrack]) throws -> MatroskaTrack {
        guard let video = tracks.first(where: { $0.kind == .video && $0.isEnabled }) else { throw RemuxError("no video") }
        // MP4 gives a picture's size on 16 bits.
        if let size = video.video, size.width > 0xFFFF || size.height > 0xFFFF { throw RemuxError("picture of \(size.width)×\(size.height)") }
        switch video.codecID {
        case "V_MPEG4/ISO/AVC":
            guard let config = video.codecPrivate, config.first == 1 else { throw RemuxError("H.264 without its configuration") }
        case "V_MPEGH/ISO/HEVC":
            guard let config = video.codecPrivate, config.count > 22, config.first == 1 else { throw RemuxError("HEVC without its configuration") }
        default:
            throw RemuxError("video codec \(video.codecID)")
        }
        return video
    }

    /// Codecs the system player decodes in HLS.
    static func isPlayable(_ codecID: String) -> Bool {
        codecID.hasPrefix("A_AAC") || codecID == "A_AC3" || codecID == "A_EAC3"
    }

    /// The audio track to play: the first language of `preferredLanguages` the file has, else its default track, among
    /// those the system player decodes. A film whose audio it cannot decode at all is left to VLC.
    static func chooseAudio(_ tracks: [MatroskaTrack], preferredLanguages: [String]) throws -> MatroskaTrack? {
        try audioCandidates(tracks, preferredLanguages: preferredLanguages).first
    }

    /// The tracks the system player decodes, best first.
    static func audioCandidates(_ tracks: [MatroskaTrack], preferredLanguages: [String]) throws -> [MatroskaTrack] {
        let audio = tracks.filter { $0.kind == .audio && $0.isEnabled }
        guard !audio.isEmpty else { return [] }
        let playable = audio.filter { isPlayable($0.codecID) && ($0.audio?.samplingFrequency ?? 0) > 0 }
        guard !playable.isEmpty else { throw RemuxError("audio codec \(audio.map(\.codecID).joined(separator: ", "))") }
        func rank(_ track: MatroskaTrack) -> Int {
            if let language = preferredLanguages.firstIndex(where: { matches(track.language, $0) }) { return language }
            return preferredLanguages.count + (track.isDefault ? 0 : 1)
        }
        // Stable: the file's order among equals.
        return playable.enumerated().sorted { (rank($0.element), $0.offset) < (rank($1.element), $1.offset) }.map(\.element)
    }

    /// AC-3 and E-AC-3: described from their first frame.
    static func dolbyTrack(_ audio: MatroskaTrack, frame: Data) throws -> (fragment: FragmentTrack, frameLength: Int64) {
        let config = audio.codecID == "A_AC3" ? try DolbyAudio.ac3(frame) : try DolbyAudio.eac3(frame)
        let codec: FragmentTrack.Codec = audio.codecID == "A_AC3" ? .ac3(config.box) : .eac3(config.box)
        var track = FragmentTrack(id: 2, codec: codec, timescale: UInt32(config.sampleRate))
        track.channels = config.channels
        track.sampleRate = config.sampleRate
        track.language = iso639(audio.language)
        return (track, Int64(config.frameLength))
    }

    /// The first frame of `track`, from the start of the clusters.
    static func firstFrame(of track: MatroskaTrack, in file: MatroskaFile, source: ByteSource) async throws -> Data {
        for size: Int64 in [256 * 1024, 4 * 1024 * 1024] {
            let bytes = try await source.read(file.firstClusterOffset..<min(file.firstClusterOffset.adding(size), file.clustersEnd))
            let read = try MatroskaReader.frames(in: bytes, from: file.firstClusterOffset, file: file)
            if let frame = read.frames.first(where: { $0.track == track.number }) { return frame.data }
            if read.complete { break }
        }
        throw RemuxError("no frame of track \(track.number) at the start")
    }

    /// "fre", "fra" and "fr-FR" are all French.
    static func matches(_ trackLanguage: String?, _ language: String) -> Bool {
        guard let trackLanguage else { return false }
        let wanted = String(language.lowercased().prefix { $0 != "-" && $0 != "_" })
        let have = String(trackLanguage.lowercased().prefix { $0 != "-" && $0 != "_" })
        if wanted == have { return true }
        let iso3: [String: Set<String>] = [
            "en": ["eng"], "fr": ["fre", "fra"], "es": ["spa"], "pt": ["por"], "zh": ["chi", "zho"], "ar": ["ara"],
            "hi": ["hin"], "de": ["ger", "deu"], "it": ["ita"], "ja": ["jpn"], "ko": ["kor"], "ru": ["rus"], "nl": ["dut", "nld"],
        ]
        return iso3[wanted]?.contains(have) == true || iso3[have]?.contains(wanted) == true
    }

    static func fragmentTrack(_ video: MatroskaTrack) -> FragmentTrack {
        let config = video.codecPrivate ?? Data()
        let codec: FragmentTrack.Codec = video.codecID == "V_MPEG4/ISO/AVC" ? .avc(config) : .hevc(config, dolbyVision: dolbyVision(video))
        var track = FragmentTrack(id: 1, codec: codec, timescale: UInt32(videoTimescale))
        track.width = video.video?.width ?? 0
        track.height = video.video?.height ?? 0
        track.colour = video.video?.colour
        track.language = iso639(video.language)
        return track
    }

    /// Dolby Vision's configuration record, for the profiles the system player shows: 5 (Dolby Vision only) and 8 (on
    /// an HDR10, SDR or HLG base). Profile 7 (two layers, from Blu-ray discs) plays its HDR10 base layer.
    static func dolbyVision(_ video: MatroskaTrack) -> Data? {
        // BlockAddIDType "dvcC" or "dvvC".
        guard let record = video.blockAdditions.first(where: { $0.type == 0x6476_6343 || $0.type == 0x6476_7643 })?.extraData,
              let profile = FragmentWriter.dolbyVisionProfile(record), profile == 5 || profile == 8 else { return nil }
        return record
    }

    /// An AAC track as fragmented MP4; nil for other codecs (Dolby's are described from a frame), and for a sampling
    /// frequency no audio has.
    static func fragmentTrack(audio: MatroskaTrack) -> FragmentTrack? {
        guard let info = audio.audio, var rate = sampleRate(info.samplingFrequency) else { return nil }
        let codec: FragmentTrack.Codec
        if audio.codecID.hasPrefix("A_AAC") {
            if let config = audio.codecPrivate, config.count >= 2 {
                codec = .aac(config)
            } else if let config = aacConfig(codecID: audio.codecID, rate: rate, channels: info.channels) {
                codec = .aac(config)
            } else {
                return nil
            }
            // HE-AAC: the output rate is twice the core's, and so is a frame's length.
            if let output = info.outputSamplingFrequency, output > info.samplingFrequency || output.isNaN {
                guard let outputRate = sampleRate(output) else { return nil }
                rate = outputRate
            }
        } else {
            return nil
        }
        var track = FragmentTrack(id: 2, codec: codec, timescale: UInt32(rate))
        track.channels = info.channels
        track.sampleRate = rate
        track.language = iso639(audio.language)
        return track
    }

    /// Hertz, from a track's sampling frequency: nil when it is not a number, below 1 Hz or past 768 kHz.
    static func sampleRate(_ frequency: Double) -> Int? {
        guard frequency.isFinite, frequency >= 1, frequency <= 768_000 else { return nil }
        return Int(frequency.rounded())
    }

    /// An AudioSpecificConfig for the old codec IDs that carry none ("A_AAC/MPEG4/LC").
    static func aacConfig(codecID: String, rate: Int, channels: Int) -> Data? {
        let rates = [96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350]
        guard let index = rates.firstIndex(of: rate), (1...7).contains(channels) else { return nil }
        let objectType: Int
        switch codecID.split(separator: "/").last {
        case "MAIN": objectType = 1
        case "SSR": objectType = 3
        case "LTP": objectType = 4
        default: objectType = 2
        }
        let bits = objectType << 11 | index << 7 | channels << 3
        return Data([UInt8(bits >> 8), UInt8(bits & 0xFF)])
    }

    static func iso639(_ language: String?) -> String {
        guard let language, language.count == 3 else { return "und" }
        return language.lowercased()
    }

    /// Audio ticks per frame: 1024 samples for AAC (2048 at the output rate for HE-AAC), 1536 for AC-3.
    nonisolated let audioFrameTicks: Int64

    // MARK: Segments

    /// Segments from the index's key frames of the video, each at least `targetDuration` long.
    static func plan(_ file: MatroskaFile, video: MatroskaTrack, targetDuration: Double) throws -> [Segment] {
        var keys: [MatroskaCuePoint] = []
        for cue in file.cues where cue.track == video.number {
            if let last = keys.last, cue.time <= last.time { continue }
            keys.append(cue)
        }
        guard !keys.isEmpty else { throw RemuxError("no key frame in the index") }
        // Nanoseconds: a duration that is not a number, negative or past 64 bits is damage.
        let seconds = file.duration ?? 0
        guard seconds >= 0, let total = Int64(exactly: (seconds * 1e9).rounded()) else { throw RemuxError("duration of \(seconds) s") }
        var segments: [Segment] = []
        // The first segment starts with the first cluster, whatever the index says.
        var current = Segment(time: 0, duration: 0, start: nil, end: nil)
        let target = Int64(targetDuration * 1e9)
        for key in keys.dropFirst() where key.time - current.time >= target {
            current.end = key
            current.duration = Double(key.time - current.time) / 1e9
            segments.append(current)
            current = Segment(time: key.time, duration: 0, start: key, end: nil)
        }
        current.duration = max(Double(total - current.time) / 1e9, 0.1)
        segments.append(current)
        // A segment is read in one go: one known to run past the limit is left to VLC before playing. When the index
        // does not say where the next key frame's block is, the segment runs at least to its cluster; `.max` is unknown.
        for segment in segments {
            let start = rangeStart(segment, file: file)
            let end = try segment.end.map { next in try next.clusterOffset.adding(next.relativePosition.map { try $0.adding(12) } ?? 0) }
                ?? file.clustersEnd
            if end < .max, end - start > largestSegment { throw RemuxError("segment of \(end - start) bytes") }
        }
        return segments
    }

    // MARK: Playlists

    public nonisolated var initSegment: Data {
        FragmentWriter.initSegment(tracks: tracks)
    }

    /// The multivariant playlist: one stream, with its codecs, size, frame rate and dynamic range.
    public nonisolated func multivariantPlaylist(media: String) -> String {
        let codecs = tracks.map { FragmentWriter.codecName($0.codec) }.joined(separator: ",")
        let seconds = max(file.duration ?? 0, 1)
        // Bits per second, up to a terabit (past any film) when a server gives a size no file has.
        let average = file.fileSize.map { Int(min(max(Double($0), 0) * 8 / seconds, 1e12)) } ?? 8_000_000
        var attributes = "BANDWIDTH=\(average * 2),AVERAGE-BANDWIDTH=\(average),CODECS=\"\(codecs)\""
        if let supplemental = FragmentWriter.supplementalCodecs(tracks[0].codec) { attributes += ",SUPPLEMENTAL-CODECS=\"\(supplemental)\"" }
        if let size = video.video, size.width > 0 { attributes += ",RESOLUTION=\(size.width)x\(size.height)" }
        if Self.frameTicks(video) != nil, let duration = video.defaultDuration { attributes += String(format: ",FRAME-RATE=%.3f", 1e9 / Double(duration)) }
        attributes += ",VIDEO-RANGE=\(videoRange)"
        return "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-STREAM-INF:\(attributes)\n\(media)\n"
    }

    /// PQ for HDR10 and Dolby Vision, HLG, or SDR.
    public nonisolated var videoRange: String {
        if case .hevc(_, let dolbyVision?) = tracks[0].codec {
            switch (FragmentWriter.dolbyVisionProfile(dolbyVision), FragmentWriter.dolbyVisionCompatibility(dolbyVision)) {
            case (5, _), (8, 1): return "PQ"
            case (8, 4): return "HLG"
            default: break
            }
        }
        switch video.video?.colour?.transfer {
        case 16: return "PQ"
        case 18: return "HLG"
        default: return "SDR"
        }
    }

    /// The media playlist: every segment of the film, with `initURI` before them.
    public nonisolated func mediaPlaylist(initURI: String, segmentURI: (Int) -> String) -> String {
        let longest = segments.map(\.duration).max() ?? Self.targetDuration
        var lines = [
            "#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-TARGETDURATION:\(Int(longest.rounded(.up)))", "#EXT-X-MEDIA-SEQUENCE:0",
            "#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-INDEPENDENT-SEGMENTS", "#EXT-X-MAP:URI=\"\(initURI)\"",
        ]
        for (index, segment) in segments.enumerated() {
            lines.append(String(format: "#EXTINF:%.6f,", segment.duration))
            lines.append(segmentURI(index))
        }
        lines.append("#EXT-X-ENDLIST")
        return lines.joined(separator: "\n") + "\n"
    }

    // MARK: Making segments

    /// The media segment `index` (moof and mdat), read from the file and kept for a retry; the next one is read ahead.
    public func segment(_ index: Int) async throws -> Data {
        guard segments.indices.contains(index) else { throw RemuxError("no segment \(index)") }
        if let data = made[index] { return data }
        let bytes = try await fetch(index)
        let data = try build(index, bytes: bytes)
        made = made.filter { abs($0.key - index) <= 1 }
        made[index] = data
        if segments.indices.contains(index + 1), fetches[index + 1] == nil, made[index + 1] == nil {
            let next = segments[index + 1]
            fetches[index + 1] = Task { [source, file] in try await Self.read(next, file: file, source: source) }
        }
        return data
    }

    private func fetch(_ index: Int) async throws -> (data: Data, offset: Int64) {
        let segment = segments[index]
        let task = fetches[index] ?? Task { [source, file] in try await Self.read(segment, file: file, source: source) }
        fetches[index] = nil
        // Reads ahead that the player went past are dropped.
        for (key, stale) in fetches where key < index || key > index + 1 {
            stale.cancel()
            fetches[key] = nil
        }
        let data = try await task.value
        return (data, Self.rangeStart(segments[index], file: file))
    }

    static func rangeStart(_ segment: Segment, file: MatroskaFile) -> Int64 {
        segment.start?.clusterOffset ?? file.firstClusterOffset
    }

    /// The bytes of a segment: from its key frame's cluster to the next segment's key frame (a cluster header's worth
    /// past its block, to read where the cluster's content starts), or to the end of the clusters.
    static func read(_ segment: Segment, file: MatroskaFile, source: ByteSource) async throws -> Data {
        let start = rangeStart(segment, file: file)
        let end: Int64
        if let next = segment.end {
            if let relative = next.relativePosition {
                end = try next.clusterOffset.adding(12).adding(relative)
            } else {
                end = try await next.clusterOffset.adding(MatroskaReader.clusterLength(at: next.clusterOffset, in: source))
            }
        } else {
            end = file.clustersEnd
        }
        guard end > start else { throw RemuxError("empty segment") }
        // No more than the limit is asked for, also when the end of the clusters is unknown.
        let length = end - start
        guard length <= largestSegment || end == .max else { throw RemuxError("segment of \(length) bytes") }
        let data = try await source.read(start..<(length > largestSegment ? start + largestSegment + 1 : end))
        guard !data.isEmpty else { throw RemuxError("nothing read") }
        guard data.count <= largestSegment else { throw RemuxError("segment past \(largestSegment) bytes") }
        return data
    }

    /// Where a segment's frames start (or end, for the next segment's key frame): the key frame's block.
    private static func cut(_ key: MatroskaCuePoint, clusters: [MatroskaReader.ClusterMark], frames: [MatroskaFrame], video: UInt64) throws -> Int64 {
        if let relative = key.relativePosition, let cluster = clusters.first(where: { $0.offset == key.clusterOffset }) {
            return try cluster.offset.adding(Int64(cluster.headerLength)).adding(relative)
        }
        guard let frame = frames.first(where: { $0.track == video && $0.isKeyframe && $0.time == key.time }) else {
            throw RemuxError("key frame at \(key.time) not found")
        }
        return frame.position
    }

    func build(_ index: Int, bytes: (data: Data, offset: Int64)) throws -> Data {
        let segment = segments[index]
        let read = try MatroskaReader.frames(in: bytes.data, from: bytes.offset, file: file)
        let lower = try segment.start.map { try Self.cut($0, clusters: read.clusters, frames: read.frames, video: video.number) } ?? 0
        let upper = try segment.end.map { try Self.cut($0, clusters: read.clusters, frames: read.frames, video: video.number) } ?? .max
        let frames = read.frames.filter { $0.position >= lower && $0.position < upper }
        var videoFrames = frames.filter { $0.track == video.number }
        // Before the first key frame of the file, nothing can be decoded.
        if segment.start == nil, let first = videoFrames.firstIndex(where: \.isKeyframe) { videoFrames.removeFirst(first) }
        guard let key = videoFrames.first, key.isKeyframe else { throw RemuxError("segment \(index) does not start with a key frame") }
        let audioFrames = audio.map { track in frames.filter { $0.track == track.number } } ?? []

        let videoTimes = try videoFrames.map { Self.ticks(try $0.time.adding(Self.timeOffset), Self.videoTimescale) }
        let step = try frameTicks ?? Self.measureFrameTicks(videoTimes)
        guard Self.frameTicksRange.contains(step) else { throw RemuxError("frame duration of \(step) ticks") }
        frameTicks = step
        let audioRate = Int64(tracks.count > 1 ? tracks[1].timescale : 48_000)
        let audioTimes = try audioFrames.map { Self.ticks(try $0.time.adding(Self.timeOffset), audioRate) }
        let grid = self.grid ?? Self.makeGrid(videoTimes: videoTimes, step: step, audioTimes: audioTimes, audioStep: audioFrameTicks)
        self.grid = grid

        // Video: decoding times on the frame grid from the segment's earliest presentation time (with an open GOP, the
        // pictures decoded after the key frame but shown before it), so that they follow on from the previous segment's;
        // composition offsets to each presentation time.
        let firstDecode = Self.snap((videoTimes.min() ?? videoTimes[0]) - grid.reorder, phase: grid.videoPhase, step: step)
        var runs: [(track: FragmentTrack, baseDecodeTime: UInt64, samples: [FragmentSample])] = []
        let videoSamples = videoFrames.enumerated().map { offset, frame in
            FragmentSample(
                data: frame.data, duration: UInt32(clamping: step),
                compositionOffset: Int32(clamping: videoTimes[offset] - (firstDecode + Int64(offset) * step)), isSync: frame.isKeyframe
            )
        }
        runs.append((tracks[0], UInt64(max(firstDecode, 0)), videoSamples))
        if tracks.count > 1, let first = audioTimes.first {
            let audioStep = audioFrameTicks
            let start = Self.snap(first, phase: grid.audioPhase, step: audioStep)
            let samples = audioFrames.map { FragmentSample(data: $0.data, duration: UInt32(clamping: audioStep), isSync: true) }
            runs.append((tracks[1], UInt64(max(start, 0)), samples))
        }
        return try FragmentWriter.mediaSegment(sequence: UInt32(index + 1), runs: runs)
    }

    /// The first segment made sets the grid: how far decoding runs ahead of presentation (the deepest reordering of
    /// its frames, plus a frame), and the phases of the video and audio grids.
    static func makeGrid(videoTimes: [Int64], step: Int64, audioTimes: [Int64], audioStep: Int64) -> Grid {
        let earliest = videoTimes.min() ?? 0
        let deepest = videoTimes.enumerated().map { earliest + Int64($0.offset) * step - $0.element }.max() ?? 0
        let reorder = (max(deepest, 0) + step - 1) / step * step + step
        return Grid(videoPhase: earliest - reorder, reorder: reorder, audioPhase: phase(audioTimes, step: audioStep))
    }

    /// The offset of a run of frames on a grid of `step`, averaged over the frames (their times are rounded to the
    /// millisecond in the file).
    static func phase(_ times: [Int64], step: Int64) -> Int64 {
        guard let first = times.first else { return 0 }
        let origin = first % step
        let deviation = times.map { time -> Double in
            var offset = (time - origin) % step
            if offset > step / 2 { offset -= step }
            if offset < -step / 2 { offset += step }
            return Double(offset)
        }.reduce(0, +) / Double(times.count)
        return origin + Int64(deviation.rounded())
    }

    static func snap(_ time: Int64, phase: Int64, step: Int64) -> Int64 {
        phase + Int64((Double(time - phase) / Double(step)).rounded()) * step
    }

    /// The track's frame duration in video ticks; nil when it has none, or one of 0 (or below a tick) or past the
    /// range, which are measured on the frames instead.
    static func frameTicks(_ video: MatroskaTrack) -> Int64? {
        guard let duration = video.defaultDuration else { return nil }
        let step = ticks(Int64(clamping: duration), videoTimescale)
        return frameTicksRange.contains(step) ? step : nil
    }

    /// Without a frame duration in the file: the most common gap between presentation times.
    static func measureFrameTicks(_ times: [Int64]) throws -> Int64 {
        let sorted = times.sorted()
        let gaps = zip(sorted, sorted.dropFirst()).map { $1 - $0 }.filter { $0 > 0 }
        guard !gaps.isEmpty else { throw RemuxError("frame rate unknown") }
        let counts = Dictionary(gaps.map { ($0, 1) }, uniquingKeysWith: +)
        return counts.max { $0.value < $1.value || ($0.value == $1.value && $0.key > $1.key) }?.key ?? gaps[0]
    }

    /// Nanoseconds to ticks of `timescale`, rounded.
    static func ticks(_ nanoseconds: Int64, _ timescale: Int64) -> Int64 {
        Int64((Double(nanoseconds) * Double(timescale) / 1e9).rounded())
    }
}
