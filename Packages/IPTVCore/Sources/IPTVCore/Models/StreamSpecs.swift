import Foundation

/// Where a stream's specs come from, best first (docs/VERSIONS.md, "What a version is made of").
public enum SpecSource: String, Codable, Hashable, Sendable {
    /// The player read them from the stream's tracks while it played.
    case measured = "MEASURED"
    /// The provider's `get_vod_info` (`info.video`, `info.audio`, `info.bitrate`: what its server's ffprobe read).
    case provider = "PROVIDER"
}

/// The picture's dynamic range. `sdr` is known too: a measured SDR stream is no HDR stream, whatever its name says.
public enum PictureRange: String, Codable, Hashable, Sendable {
    case sdr = "SDR"
    case hdr10 = "HDR10"
    case hdr10Plus = "HDR10_PLUS"
    case hlg = "HLG"
    case dolbyVision = "DOLBY_VISION"
}

/// What a stream is made of: its picture's size, range and codec, its sound's codec and channels. Each field is nil (or
/// false) when unknown: nothing is guessed. Measured by the player (`.measured`) or given by the provider (`.provider`);
/// the tags of the name (`FilmVersions.labelTags`) come last. The same rules and words as Android's `StreamSpecs`; the
/// shared tables are `fixtures/stream-specs/specs.json` and `fixtures/film-versions/labels.json`.
public struct StreamSpecs: Codable, Hashable, Sendable {
    public var width: Int?
    public var height: Int?
    public var range: PictureRange?
    /// "H.264", "HEVC", "AV1"… (`videoCodec(_:)`).
    public var videoCodec: String?
    /// "AAC", "AC-3", "E-AC-3", "DTS", "TrueHD"… (`audioCodec(_:)`).
    public var audioCodec: String?
    public var channels: Int?
    /// The provider's layout of them ("5.1", "4.1", "3.0": `layout(of:)`); nil when it gives none.
    public var channelLayout: String?
    /// Dolby Atmos: E-AC-3 JOC, or a provider's profile naming Atmos.
    public var atmos: Bool
    /// The provider's whole bitrate, in kbit/s.
    public var bitrate: Int?
    public var source: SpecSource

    public init(
        width: Int? = nil,
        height: Int? = nil,
        range: PictureRange? = nil,
        videoCodec: String? = nil,
        audioCodec: String? = nil,
        channels: Int? = nil,
        channelLayout: String? = nil,
        atmos: Bool = false,
        bitrate: Int? = nil,
        source: SpecSource = .provider
    ) {
        self.width = width
        self.height = height
        self.range = range
        self.videoCodec = videoCodec
        self.audioCodec = audioCodec
        self.channels = channels
        self.channelLayout = channelLayout
        self.atmos = atmos
        self.bitrate = bitrate
        self.source = source
    }

    enum CodingKeys: String, CodingKey {
        case width, height, range, videoCodec, audioCodec, channels, channelLayout, atmos, bitrate, source
    }

    /// Every field may be missing (Android writes them all; a field it left out is unknown).
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        width = try? c.decodeIfPresent(Int.self, forKey: .width)
        height = try? c.decodeIfPresent(Int.self, forKey: .height)
        range = try? c.decodeIfPresent(PictureRange.self, forKey: .range)
        videoCodec = try? c.decodeIfPresent(String.self, forKey: .videoCodec)
        audioCodec = try? c.decodeIfPresent(String.self, forKey: .audioCodec)
        channels = try? c.decodeIfPresent(Int.self, forKey: .channels)
        channelLayout = try? c.decodeIfPresent(String.self, forKey: .channelLayout)
        atmos = (try? c.decodeIfPresent(Bool.self, forKey: .atmos)) ?? false
        bitrate = try? c.decodeIfPresent(Int.self, forKey: .bitrate)
        source = (try? c.decodeIfPresent(SpecSource.self, forKey: .source)) ?? .provider
    }

    /// Whether anything at all is known.
    public var isEmpty: Bool {
        resolution == nil && range == nil && videoCodec == nil && audioCodec == nil && channels == nil && channelLayout == nil && !atmos
    }

    /// The sound's layout: the provider's ("4.1"), else its channels' (`layout(_:)`: "5.1" for six); nil when unknown.
    public var soundLayout: String? { channelLayout ?? channels.map(Self.layout) }

    /// "SD", "720p", "1080p", "4K" from the picture's size; nil when unknown (`resolution(width:height:)`).
    public var resolution: String? { Self.resolution(width: width, height: height) }

    /// "Dolby Vision", "HDR10+", "HDR10", "HLG"; nil for SDR or unknown.
    public var rangeLabel: String? { Self.rangeLabel(range) }

    /// The sound as the facts line shows it: "Atmos", "5.1 AAC", "2.0 AC-3", "AAC"; nil when unknown.
    public var audioLabel: String? {
        if atmos { return Self.atmosWord }
        let text = [soundLayout, audioCodec].compactMap { $0 }.joined(separator: " ")
        return text.isEmpty ? nil : text
    }

    /// The sound as a chip says it: "Atmos", "5.1", else its codec; nil when unknown.
    public var chipAudio: String? {
        if atmos { return Self.atmosWord }
        return soundLayout ?? audioCodec
    }

    /// These specs where they are known, `other`'s where they are not; this one's source. Atmos stays when either says
    /// it: a player that reads no Atmos (AVFoundation, Media3 on TrueHD) does not take the provider's away. The provider's
    /// layout only where it agrees with the channels known.
    public func orElse(_ other: StreamSpecs?) -> StreamSpecs {
        guard let other else { return self }
        var merged = self
        merged.width = width ?? other.width
        merged.height = height ?? other.height
        merged.range = range ?? other.range
        merged.videoCodec = videoCodec ?? other.videoCodec
        merged.audioCodec = audioCodec ?? other.audioCodec
        merged.channels = channels ?? other.channels
        merged.channelLayout = channelLayout ?? other.channelLayout.flatMap { layout in
            channels == nil || Self.channels(ofLayout: layout) == channels ? layout : nil
        }
        merged.atmos = atmos || other.atmos
        merged.bitrate = bitrate ?? other.bitrate
        return merged
    }

    /// "1920×802 · 1080p · SDR · H.264 · 5.1 AAC · 4521 kbit/s": every known field, for the title data panel.
    public func summary() -> String {
        var size: String?
        if let width, let height { size = "\(width)×\(height)" } else if let height { size = "\(height)p" }
        let sound = [soundLayout, audioCodec].compactMap { $0 }.joined(separator: " ")
        let parts: [String?] = [
            size,
            resolution,
            range.map { $0 == .sdr ? "SDR" : (Self.rangeLabel($0) ?? "") },
            videoCodec,
            sound.isEmpty ? nil : sound,
            atmos ? Self.atmosWord : nil,
            bitrate.map { "\($0) kbit/s" },
        ]
        let text = parts.compactMap { $0 }.joined(separator: " · ")
        return text.isEmpty ? "–" : text
    }

    /// A block of the provider's ffprobe answer (`info.video` or `info.audio`): its scalar fields by name, in lower case
    /// (`fields`, numbers as text), and the types of its `side_data_list` in lower case (`sideData`: "dovi configuration
    /// record", "hdr dynamic metadata smpte2094-40 (hdr10+)"). Free text (a stream's title) is never read.
    public struct ProbeBlock: Hashable, Sendable {
        public var fields: [String: String]
        public var sideData: [String]

        public init(fields: [String: String], sideData: [String] = []) {
            self.fields = fields
            self.sideData = sideData
        }
    }

    /// The words a screen reader says for specs, in the viewer's language (the app's strings; English by default).
    public struct Words: Sendable {
        /// "5.1 surround sound" for "5.1".
        public var surround: @Sendable (String) -> String
        public var stereo: String
        public var mono: String
        public var atmos: String

        public init(
            surround: @escaping @Sendable (String) -> String = { "\($0) surround sound" },
            stereo: String = "Stereo sound",
            mono: String = "Mono sound",
            atmos: String = "Dolby Atmos sound"
        ) {
            self.surround = surround
            self.stereo = stereo
            self.mono = mono
            self.atmos = atmos
        }
    }

    public static let atmosWord = "Atmos"

    /// The class of a picture's size: 4K, 1080p, 720p or SD. A film in scope is wide and short (1920×802 is 1080p,
    /// 3840×1600 4K), an anamorphic one narrow and tall (1440×1080 is 1080p): either side reaching a class's is enough.
    /// Nil when neither is known (zero is unknown).
    public static func resolution(width: Int?, height: Int?) -> String? {
        let w = max(width ?? 0, 0)
        let h = max(height ?? 0, 0)
        if w == 0 && h == 0 { return nil }
        if w >= 3200 || h >= 1800 { return "4K" }
        if w >= 1700 || h >= 900 { return "1080p" }
        if w >= 1200 || h >= 650 { return "720p" }
        return "SD"
    }

    public static func rangeLabel(_ range: PictureRange?) -> String? {
        switch range {
        case .dolbyVision: return "Dolby Vision"
        case .hdr10Plus: return "HDR10+"
        case .hdr10: return "HDR10"
        case .hlg: return "HLG"
        case .sdr, nil: return nil
        }
    }

    /// "5.1" for six channels, "2.0" for two, "7.1" for eight; "N ch" past them.
    public static func layout(_ channels: Int) -> String {
        switch channels {
        case 1: return "1.0"
        case 2: return "2.0"
        case 3: return "3.0"
        case 4: return "4.0"
        case 5: return "5.0"
        case 6: return "5.1"
        case 7: return "6.1"
        case 8: return "7.1"
        default: return "\(channels) ch"
        }
    }

    private static func lowered(_ raw: String?) -> String? {
        guard let name = raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(), !name.isEmpty else { return nil }
        return name
    }

    /// A codec's conventional name from ffprobe's `codec_name`, a FourCC or a MIME type's subtype; nil when unknown.
    public static func videoCodec(_ raw: String?) -> String? {
        lowered(raw).flatMap { videoCodecs[$0] }
    }

    /// The same for sound ("aac", "ac3", "eac3", "mp4a", "ec-3"…).
    public static func audioCodec(_ raw: String?) -> String? {
        guard let name = lowered(raw) else { return nil }
        if name.hasPrefix("pcm_") || name.hasPrefix("lpcm") { return "PCM" }
        return audioCodecs[name]
    }

    private static let videoCodecs: [String: String] = [
        "h264": "H.264", "avc": "H.264", "avc1": "H.264", "avc3": "H.264", "x264": "H.264",
        "hevc": "HEVC", "h265": "HEVC", "hvc1": "HEVC", "hev1": "HEVC", "x265": "HEVC",
        "dvh1": "HEVC", "dvhe": "HEVC",
        "av1": "AV1", "av01": "AV1", "dav1": "AV1",
        "vp9": "VP9", "vp09": "VP9", "x-vnd.on2.vp9": "VP9", "vp8": "VP8", "x-vnd.on2.vp8": "VP8",
        "mpeg2video": "MPEG-2", "mpeg2": "MPEG-2", "mp2v": "MPEG-2",
        "mpeg4": "MPEG-4", "mp4v": "MPEG-4", "mp4v-es": "MPEG-4", "xvid": "MPEG-4", "divx": "MPEG-4",
        "vc1": "VC-1", "wvc1": "VC-1",
    ]

    private static let audioCodecs: [String: String] = [
        "aac": "AAC", "mp4a": "AAC", "aac_latm": "AAC", "mp4a-latm": "AAC",
        "ac3": "AC-3", "ac-3": "AC-3",
        "eac3": "E-AC-3", "ec-3": "E-AC-3", "ec3": "E-AC-3", "eac3-joc": "E-AC-3",
        "ac4": "AC-4", "ac-4": "AC-4",
        "dts": "DTS", "dca": "DTS", "vnd.dts": "DTS", "dtsc": "DTS",
        "vnd.dts.hd": "DTS-HD", "dtsh": "DTS-HD", "dtsl": "DTS-HD",
        "truehd": "TrueHD", "true-hd": "TrueHD", "mlp": "TrueHD", "mlpa": "TrueHD",
        "mp3": "MP3", "mpeg": "MP3", ".mp3": "MP3", "mp2": "MP2", "mpeg-l2": "MP2",
        "opus": "Opus", "flac": "FLAC", "vorbis": "Vorbis", "alac": "ALAC", "raw": "PCM", "lpcm": "PCM",
    ]

    /// FourCCs of Dolby Vision pictures (a track's codecs, a sample description's type).
    static let dolbyVisionCodecs: Set<String> = ["dvh1", "dvhe", "dav1", "dvav", "dva1"]

    /// Whether a FourCC ("dvh1", "hvc1") is one of a Dolby Vision picture.
    public static func isDolbyVision(fourCC: String?) -> Bool {
        lowered(fourCC).map { dolbyVisionCodecs.contains($0) } ?? false
    }

    /// The provider's layout as the apps say it: "5.1(side)" is "5.1", "4.1" and "3.0" stay, "stereo" is "2.0", "mono"
    /// "1.0", "quad" "4.0"; nil when it is none of those.
    public static func layout(of raw: String?) -> String? {
        guard let layout = lowered(raw) else { return nil }
        switch layout {
        case "mono": return "1.0"
        case "stereo", "downmix": return "2.0"
        case "quad": return "4.0"
        case "hexagonal": return "6.0"
        case "octagonal": return "8.0"
        default: break
        }
        let main = String(layout.prefix { $0 != "(" }).trimmingCharacters(in: .whitespaces)
        guard main.filter({ $0 == "." }).count == 1, main.allSatisfy({ $0 == "." || ($0.isASCII && $0.isNumber) }) else { return nil }
        return channels(ofLayout: main) == nil ? nil : main
    }

    /// The sound the player measured: the track marked default (`defaults`), else the one of the most channels, the first
    /// of them; -1 for none. Not the viewer's choice of the moment: a stream's sound is what it offers first.
    public static func measuredAudio(defaults: [Bool], channels: [Int?]) -> Int {
        if let marked = defaults.firstIndex(of: true) { return marked }
        var best = -1
        for i in channels.indices where best < 0 || (channels[i] ?? 0) > (channels[best] ?? 0) { best = i }
        return best
    }

    /// "5.1(side)" is 6, "stereo" 2, "7.1" 8, "mono" 1; nil when it says no number.
    public static func channels(ofLayout raw: String?) -> Int? {
        guard let layout = lowered(raw) else { return nil }
        switch layout {
        case "mono": return 1
        case "stereo", "downmix": return 2
        case "quad": return 4
        case "hexagonal": return 6
        case "octagonal": return 8
        default: break
        }
        // "5.1", "7.1(wide)", "6.1(back)": the digits before and after the dot.
        let main = layout.prefix { $0 != "(" }
        guard let dot = main.firstIndex(of: "."), dot > main.startIndex,
              let front = Int(main[main.startIndex..<dot]) else { return nil }
        let after = main[main.index(after: dot)...].prefix { $0.isASCII && $0.isNumber }
        guard let low = Int(after) else { return nil }
        let total = front + low
        return (1...32).contains(total) ? total : nil
    }

    /// A whole number written as text ("1920", "1920.0"); nil for anything else, zero or less.
    static func positive(_ raw: String?) -> Int? {
        guard let text = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
        if let int = Int(text) { return int > 0 ? int : nil }
        guard let number = Double(text), number.isFinite, number >= 1, number < Double(Int32.max) else { return nil }
        return Int(number)
    }

    /// Transfer functions of SDR pictures, as ffprobe names them (`color_transfer`).
    private static let sdrTransfers: Set<String> = [
        "bt709", "bt470m", "bt470bg", "smpte170m", "smpte240m", "bt601", "iec61966-2-1", "iec61966-2-4", "gamma22",
        "gamma28", "linear", "bt1361e", "bt2020-10", "bt2020-12",
    ]

    /// Specs from the provider's ffprobe answer: `video` (`info.video`), `audio` (`info.audio`), `bitrate`
    /// (`info.bitrate`, kbit/s). A field missing, empty, zero or unknown stays unknown; nil when nothing is known. Dolby
    /// Vision from its configuration record or its FourCC; HDR10+ only from SMPTE ST 2094 metadata named in the block
    /// ("SMPTE2094-40"); HDR10 and HLG from `color_transfer` (never from a 10-bit profile alone).
    public static func fromProbe(video: ProbeBlock?, audio: ProbeBlock?, bitrate: String?) -> StreamSpecs? {
        let v = video?.fields ?? [:]
        let a = audio?.fields ?? [:]
        let tag = v["codec_tag_string"]?.lowercased()
        var range: PictureRange?
        if let video {
            if tag.map(dolbyVisionCodecs.contains) == true || (v["profile"]?.lowercased() ?? "").contains("dolby vision")
                || video.sideData.contains(where: { $0.contains("dovi") || $0.contains("dolby vision") }) {
                range = .dolbyVision
            } else if video.sideData.contains(where: { $0.contains("smpte2094") || $0.contains("hdr10+") }) {
                range = .hdr10Plus
            } else {
                switch v["color_transfer"]?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
                case "smpte2084": range = .hdr10
                case "arib-std-b67": range = .hlg
                case let transfer? where sdrTransfers.contains(transfer): range = .sdr
                default: range = nil
                }
            }
        }
        let audioProfile = a["profile"]?.lowercased() ?? ""
        var codec = audioCodec(a["codec_name"]) ?? audioCodec(a["codec_tag_string"])
        if codec == "DTS", audioProfile.contains("dts-hd") { codec = "DTS-HD" }
        let channelCount = positive(a["channels"]).flatMap { $0 <= 32 ? $0 : nil } ?? channels(ofLayout: a["channel_layout"])
        let specs = StreamSpecs(
            width: positive(v["width"]) ?? positive(v["coded_width"]),
            height: positive(v["height"]) ?? positive(v["coded_height"]),
            range: range,
            videoCodec: videoCodec(v["codec_name"]) ?? videoCodec(tag),
            audioCodec: codec,
            channels: channelCount,
            channelLayout: layout(of: a["channel_layout"]),
            atmos: audioProfile.contains("atmos") || audioProfile.contains("joc"),
            bitrate: positive(bitrate),
            source: .provider
        )
        return specs.isEmpty && specs.bitrate == nil ? nil : specs
    }

    /// The facts line's badges of a version ("4K", "Dolby Vision", "HEVC", "5.1 AAC"), in that order: from its `specs`
    /// where they know, else from the tags of its name (`nameTags`: "4K", "1080p", "HD", "SD", "Dolby Vision", "HDR",
    /// "HEVC"); its sound only from its specs (a name rarely says it). Nothing unknown is shown.
    public static func badges(_ specs: StreamSpecs?, nameTags: [VersionTag]) -> [String] {
        let quality: String? = specs?.resolution ?? (
            nameTags.contains(.uhd) ? "4K" : nameTags.contains(.fhd) ? "1080p" : nameTags.contains(.hd) ? "HD" : nameTags.contains(.sd) ? "SD" : nil
        )
        let rangeWord: String?
        if let specs, specs.range != nil {
            rangeWord = specs.rangeLabel
        } else {
            rangeWord = nameTags.contains(.dolbyVision) ? "Dolby Vision" : nameTags.contains(.hdr) ? "HDR" : nil
        }
        let codec = specs?.videoCodec ?? (nameTags.contains(.hevc) ? "HEVC" : nil)
        return [quality, rangeWord, codec, specs?.audioLabel].compactMap { $0 }
    }

    /// What a screen reader says of `badges`' specs: "4K, Dolby Vision, HEVC, 5.1 surround sound, AAC".
    public static func description(_ specs: StreamSpecs?, nameTags: [VersionTag], words: Words = Words()) -> String {
        var picture = badges(specs, nameTags: nameTags)
        if specs?.audioLabel != nil { picture.removeLast() }
        var sound: [String] = []
        if let specs {
            if specs.atmos {
                sound.append(words.atmos)
            } else if let layout = specs.soundLayout {
                switch layout {
                case "1.0": sound.append(words.mono)
                case "2.0": sound.append(words.stereo)
                default: sound.append(words.surround(layout))
                }
            }
            if let codec = specs.audioCodec { sound.append(codec) }
        }
        return (picture + sound).joined(separator: ", ")
    }
}
