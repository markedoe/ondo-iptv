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
        let audio = try chooseAudio(file.tracks, preferredLanguages: preferredLanguages)
        let segments = try plan(file, video: video, targetDuration: targetDuration)
        return MatroskaRemux(source: source, file: file, video: video, audio: audio, segments: segments)
    }

    private init(source: ByteSource, file: MatroskaFile, video: MatroskaTrack, audio: MatroskaTrack?, segments: [Segment]) {
        self.source = source
        self.file = file
        self.video = video
        self.audio = audio
        self.segments = segments
        var tracks = [Self.fragmentTrack(video)]
        if let audio, let track = Self.fragmentTrack(audio: audio) { tracks.append(track) }
        self.tracks = tracks
        frameTicks = video.defaultDuration.map { Self.ticks(Int64(clamping: $0), Self.videoTimescale) }
    }

    // MARK: Tracks

    static func chooseVideo(_ tracks: [MatroskaTrack]) throws -> MatroskaTrack {
        guard let video = tracks.first(where: { $0.kind == .video && $0.isEnabled }) else { throw RemuxError("no video") }
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

    /// The audio track to play: the first language of `preferredLanguages` the file has, else its default track, among
    /// those the system player decodes. A film whose audio it cannot decode at all is left to VLC.
    static func chooseAudio(_ tracks: [MatroskaTrack], preferredLanguages: [String]) throws -> MatroskaTrack? {
        let audio = tracks.filter { $0.kind == .audio && $0.isEnabled }
        guard !audio.isEmpty else { return nil }
        let playable = audio.filter { fragmentTrack(audio: $0) != nil }
        guard !playable.isEmpty else { throw RemuxError("audio codec \(audio.map(\.codecID).joined(separator: ", "))") }
        for language in preferredLanguages {
            if let track = playable.first(where: { matches($0.language, language) }) { return track }
        }
        return playable.first(where: \.isDefault) ?? playable[0]
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
        let codec: FragmentTrack.Codec = video.codecID == "V_MPEG4/ISO/AVC" ? .avc(config) : .hevc(config, dolbyVision: nil)
        var track = FragmentTrack(id: 1, codec: codec, timescale: UInt32(videoTimescale))
        track.width = video.video?.width ?? 0
        track.height = video.video?.height ?? 0
        track.colour = video.video?.colour
        track.language = iso639(video.language)
        return track
    }

    /// The audio track as fragmented MP4, or nil when the system player does not decode it.
    static func fragmentTrack(audio: MatroskaTrack) -> FragmentTrack? {
        guard let info = audio.audio, info.samplingFrequency > 0 else { return nil }
        let codec: FragmentTrack.Codec
        var rate = Int(info.samplingFrequency.rounded())
        if audio.codecID.hasPrefix("A_AAC") {
            if let config = audio.codecPrivate, config.count >= 2 {
                codec = .aac(config)
            } else if let config = aacConfig(codecID: audio.codecID, rate: rate, channels: info.channels) {
                codec = .aac(config)
            } else {
                return nil
            }
            // HE-AAC: the output rate is twice the core's, and so is a frame's length.
            if let output = info.outputSamplingFrequency, output > info.samplingFrequency { rate = Int(output.rounded()) }
        } else {
            return nil
        }
        var track = FragmentTrack(id: 2, codec: codec, timescale: UInt32(rate))
        track.channels = info.channels
        track.sampleRate = rate
        track.language = iso639(audio.language)
        return track
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

    /// Audio ticks per frame: 1024 samples for AAC, 2048 at the output rate for HE-AAC.
    nonisolated var audioFrameTicks: Int64 {
        guard let audio, let info = audio.audio else { return 1_024 }
        if let output = info.outputSamplingFrequency, output > info.samplingFrequency { return 2_048 }
        return 1_024
    }

    // MARK: Segments

    /// Segments from the index's key frames of the video, each at least `targetDuration` long.
    static func plan(_ file: MatroskaFile, video: MatroskaTrack, targetDuration: Double) throws -> [Segment] {
        var keys: [MatroskaCuePoint] = []
        for cue in file.cues where cue.track == video.number {
            if let last = keys.last, cue.time <= last.time { continue }
            keys.append(cue)
        }
        guard !keys.isEmpty else { throw RemuxError("no key frame in the index") }
        let total = Int64(((file.duration ?? 0) * 1e9).rounded())
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
        let average = file.fileSize.map { Int(Double($0) * 8 / seconds) } ?? 8_000_000
        var attributes = "BANDWIDTH=\(average * 2),AVERAGE-BANDWIDTH=\(average),CODECS=\"\(codecs)\""
        if let size = video.video, size.width > 0 { attributes += ",RESOLUTION=\(size.width)x\(size.height)" }
        if let duration = video.defaultDuration, duration > 0 { attributes += String(format: ",FRAME-RATE=%.3f", 1e9 / Double(duration)) }
        attributes += ",VIDEO-RANGE=\(videoRange)"
        return "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-STREAM-INF:\(attributes)\n\(media)\n"
    }

    /// PQ for HDR10 and Dolby Vision, HLG, or SDR.
    nonisolated var videoRange: String {
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
                end = next.clusterOffset + 12 + relative
            } else {
                end = next.clusterOffset + (try await MatroskaReader.clusterLength(at: next.clusterOffset, in: source))
            }
        } else {
            end = file.clustersEnd
        }
        guard end > start else { throw RemuxError("empty segment") }
        let data = try await source.read(start..<end)
        guard !data.isEmpty else { throw RemuxError("nothing read") }
        return data
    }

    /// Where a segment's frames start (or end, for the next segment's key frame): the key frame's block.
    private static func cut(_ key: MatroskaCuePoint, clusters: [MatroskaReader.ClusterMark], frames: [MatroskaFrame], video: UInt64) throws -> Int64 {
        if let relative = key.relativePosition, let cluster = clusters.first(where: { $0.offset == key.clusterOffset }) {
            return cluster.offset + Int64(cluster.headerLength) + relative
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

        let videoTimes = videoFrames.map { Self.ticks($0.time + Self.timeOffset, Self.videoTimescale) }
        let step = try frameTicks ?? Self.measureFrameTicks(videoTimes)
        frameTicks = step
        let audioRate = Int64(tracks.count > 1 ? tracks[1].timescale : 48_000)
        let audioTimes = audioFrames.map { Self.ticks($0.time + Self.timeOffset, audioRate) }
        let grid = self.grid ?? Self.makeGrid(videoTimes: videoTimes, step: step, audioTimes: audioTimes, audioStep: audioFrameTicks)
        self.grid = grid

        // Video: decoding times on the frame grid from the key frame's, composition offsets to each presentation time.
        let firstDecode = Self.snap(videoTimes[0] - grid.reorder, phase: grid.videoPhase, step: step)
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
        return FragmentWriter.mediaSegment(sequence: UInt32(index + 1), runs: runs)
    }

    /// The first segment made sets the grid: how far decoding runs ahead of presentation (the deepest reordering of
    /// its frames, plus a frame), and the phases of the video and audio grids.
    static func makeGrid(videoTimes: [Int64], step: Int64, audioTimes: [Int64], audioStep: Int64) -> Grid {
        let key = videoTimes[0]
        let deepest = videoTimes.enumerated().map { key + Int64($0.offset) * step - $0.element }.max() ?? 0
        let reorder = (max(deepest, 0) + step - 1) / step * step + step
        return Grid(videoPhase: key - reorder, reorder: reorder, audioPhase: phase(audioTimes, step: audioStep))
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
