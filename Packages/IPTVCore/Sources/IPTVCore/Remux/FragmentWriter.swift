import Foundation

/// A track as written to fragmented MP4 (CMAF): what the system player needs to decode it.
public struct FragmentTrack: Sendable, Equatable {
    public enum Codec: Sendable, Equatable {
        /// H.264 with its avcC record.
        case avc(Data)
        /// HEVC with its hvcC record, and Dolby Vision's configuration (dvcC or dvvC) when the stream carries it.
        case hevc(Data, dolbyVision: Data?)
        /// AAC with its AudioSpecificConfig.
        case aac(Data)
        /// AC-3 with its dac3 payload.
        case ac3(Data)
        /// E-AC-3 with its dec3 payload.
        case eac3(Data)

        public var isVideo: Bool {
            switch self {
            case .avc, .hevc: return true
            case .aac, .ac3, .eac3: return false
            }
        }
    }

    public var id: UInt32
    public var codec: Codec
    /// Ticks per second of the track's times.
    public var timescale: UInt32
    public var width = 0
    public var height = 0
    public var channels = 2
    public var sampleRate = 48_000
    public var colour: MatroskaTrack.Colour?
    /// ISO 639-2 code ("eng"), "und" when unknown.
    public var language = "und"

    public init(id: UInt32, codec: Codec, timescale: UInt32) {
        self.id = id
        self.codec = codec
        self.timescale = timescale
    }
}

/// A sample of a fragment: its bytes and times in the track's timescale.
public struct FragmentSample: Sendable, Equatable {
    public var data: Data
    public var duration: UInt32
    /// Presentation time minus decoding time; negative values are allowed (version 1 track runs).
    public var compositionOffset: Int32
    public var isSync: Bool

    public init(data: Data, duration: UInt32, compositionOffset: Int32 = 0, isSync: Bool) {
        self.data = data
        self.duration = duration
        self.compositionOffset = compositionOffset
        self.isSync = isSync
    }
}

/// Writes fragmented MP4 for HLS: the initialization segment (ftyp, moov) and media segments (moof, mdat).
public enum FragmentWriter {
    /// ftyp and moov: the tracks and how to decode them, with no samples.
    public static func initSegment(tracks: [FragmentTrack]) -> Data {
        var writer = BoxWriter()
        writer.box("ftyp") { box in
            box.fourCC("iso6")
            box.u32(0)
            for brand in ["iso6", "cmfc", "isom", "mp41"] { box.fourCC(brand) }
        }
        writer.box("moov") { moov in
            moov.fullBox("mvhd") { box in
                box.u32(0)
                box.u32(0)
                box.u32(1_000)
                box.u32(0)
                box.u32(0x0001_0000)
                box.u16(0x0100)
                box.zeros(10)
                box.matrix()
                box.zeros(24)
                box.u32((tracks.map(\.id).max() ?? 0) + 1)
            }
            for track in tracks { moov.trak(track) }
            moov.box("mvex") { mvex in
                for track in tracks {
                    mvex.fullBox("trex") { box in
                        box.u32(track.id)
                        box.u32(1)
                        box.u32(0)
                        box.u32(0)
                        box.u32(0)
                    }
                }
            }
        }
        return writer.data
    }

    /// A moof and its mdat: each track's samples from `baseDecodeTime`, in the order given.
    public static func mediaSegment(sequence: UInt32, runs: [(track: FragmentTrack, baseDecodeTime: UInt64, samples: [FragmentSample])]) -> Data {
        // The moof first with zero data offsets, then the offsets once its size is known.
        func moof(offsets: [UInt32]) -> Data {
            var writer = BoxWriter()
            writer.box("moof") { moof in
                moof.fullBox("mfhd") { $0.u32(sequence) }
                for (index, run) in runs.enumerated() {
                    moof.box("traf") { traf in
                        // default-base-is-moof: data offsets count from the start of this moof.
                        traf.fullBox("tfhd", flags: 0x02_0000) { $0.u32(run.track.id) }
                        traf.fullBox("tfdt", version: 1) { $0.u64(run.baseDecodeTime) }
                        // Data offset, and per sample: duration, size, flags, composition offset.
                        traf.fullBox("trun", version: 1, flags: 0x0000_0001 | 0x0000_0100 | 0x0000_0200 | 0x0000_0400 | 0x0000_0800) { trun in
                            trun.u32(UInt32(run.samples.count))
                            trun.u32(offsets[index])
                            for sample in run.samples {
                                trun.u32(sample.duration)
                                trun.u32(UInt32(sample.data.count))
                                // Sync: depends on no other sample. Otherwise: depends on others, not a sync sample.
                                trun.u32(sample.isSync ? 0x0200_0000 : 0x0101_0000)
                                trun.u32(UInt32(bitPattern: sample.compositionOffset))
                            }
                        }
                    }
                }
            }
            return writer.data
        }
        let size = moof(offsets: runs.map { _ in 0 }).count
        var offsets: [UInt32] = []
        var position = size + 8
        for run in runs {
            offsets.append(UInt32(position))
            position += run.samples.reduce(0) { $0 + $1.data.count }
        }
        var segment = moof(offsets: offsets)
        var mdat = BoxWriter()
        mdat.box("mdat") { box in
            for run in runs {
                for sample in run.samples { box.bytes(sample.data) }
            }
        }
        segment.append(mdat.data)
        return segment
    }

    /// The RFC 6381 codec name for an HLS playlist ("avc1.640028", "mp4a.40.2"…).
    public static func codecName(_ codec: FragmentTrack.Codec) -> String {
        func hex(_ byte: UInt8) -> String { String(format: "%02X", byte) }
        switch codec {
        case .avc(let config):
            let bytes = [UInt8](config)
            guard bytes.count >= 4 else { return "avc1" }
            return "avc1.\(hex(bytes[1]))\(hex(bytes[2]))\(hex(bytes[3]))"
        case .hevc(let config, let dolbyVision):
            if let dolbyVision, let name = dolbyVisionName(dolbyVision) { return name }
            return hevcName(config)
        case .aac(let config):
            let objectType = config.first.map { $0 >> 3 } ?? 2
            return "mp4a.40.\(objectType == 0 ? 2 : objectType)"
        case .ac3:
            return "ac-3"
        case .eac3:
            return "ec-3"
        }
    }

    /// "hvc1.2.4.L153.B0": profile space and profile, compatibility flags (bits reversed), tier and level, constraints.
    static func hevcName(_ config: Data) -> String {
        let bytes = [UInt8](config)
        guard bytes.count >= 13 else { return "hvc1" }
        let space = ["", "A", "B", "C"][Int(bytes[1] >> 6)]
        let tier = bytes[1] & 0x20 != 0 ? "H" : "L"
        let profile = bytes[1] & 0x1F
        let flags = UInt32(bytes[2]) << 24 | UInt32(bytes[3]) << 16 | UInt32(bytes[4]) << 8 | UInt32(bytes[5])
        var reversed: UInt32 = 0
        for bit in 0..<32 where flags & (1 << bit) != 0 { reversed |= 1 << (31 - bit) }
        var constraints = Array(bytes[6..<12])
        while let last = constraints.last, last == 0 { constraints.removeLast() }
        let constraintText = constraints.map { ".\(String($0, radix: 16, uppercase: true))" }.joined()
        return "hvc1.\(space)\(profile).\(String(reversed, radix: 16, uppercase: true)).\(tier)\(bytes[12])\(constraintText)"
    }

    /// "dvh1.08.06": Dolby Vision's profile and level, from its configuration record.
    static func dolbyVisionName(_ config: Data) -> String? {
        let bytes = [UInt8](config)
        guard bytes.count >= 4 else { return nil }
        let profile = bytes[2] >> 1
        let level = (bytes[2] & 0x01) << 5 | bytes[3] >> 3
        return String(format: "dvh1.%02d.%02d", profile, level)
    }
}

/// Writes ISO base media boxes: big-endian fields, and box sizes filled in when the box closes.
struct BoxWriter {
    private(set) var data = Data()

    mutating func box(_ type: String, _ body: (inout BoxWriter) -> Void) {
        let start = data.count
        u32(0)
        fourCC(type)
        body(&self)
        let size = UInt32(data.count - start)
        data.replaceSubrange(start..<start + 4, with: [UInt8(size >> 24), UInt8(size >> 16 & 0xFF), UInt8(size >> 8 & 0xFF), UInt8(size & 0xFF)])
    }

    mutating func fullBox(_ type: String, version: UInt8 = 0, flags: UInt32 = 0, _ body: (inout BoxWriter) -> Void) {
        box(type) { box in
            box.u8(version)
            box.u24(flags)
            body(&box)
        }
    }

    mutating func fourCC(_ code: String) {
        let bytes = Array(code.utf8)
        precondition(bytes.count == 4, "a box type has four characters")
        data.append(contentsOf: bytes)
    }

    mutating func u8(_ value: UInt8) { data.append(value) }
    mutating func u16(_ value: UInt16) { data.append(contentsOf: [UInt8(value >> 8), UInt8(value & 0xFF)]) }
    mutating func u24(_ value: UInt32) { data.append(contentsOf: [UInt8(value >> 16 & 0xFF), UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)]) }
    mutating func u32(_ value: UInt32) { data.append(contentsOf: [UInt8(value >> 24), UInt8(value >> 16 & 0xFF), UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)]) }
    mutating func u64(_ value: UInt64) {
        u32(UInt32(value >> 32))
        u32(UInt32(value & 0xFFFF_FFFF))
    }
    mutating func bytes(_ value: Data) { data.append(value) }
    mutating func zeros(_ count: Int) { data.append(contentsOf: [UInt8](repeating: 0, count: count)) }

    /// The unity transformation matrix of mvhd and tkhd.
    mutating func matrix() {
        for value: UInt32 in [0x0001_0000, 0, 0, 0, 0x0001_0000, 0, 0, 0, 0x4000_0000] { u32(value) }
    }

    /// A trak for a track with no samples: its header, timescale, handler and sample description.
    mutating func trak(_ track: FragmentTrack) {
        let video = track.codec.isVideo
        box("trak") { trak in
            trak.fullBox("tkhd", flags: 0x000003) { box in
                box.u32(0)
                box.u32(0)
                box.u32(track.id)
                box.u32(0)
                box.u32(0)
                box.zeros(8)
                box.u16(0)
                box.u16(video ? 0 : 1)
                box.u16(video ? 0 : 0x0100)
                box.u16(0)
                box.matrix()
                box.u32(UInt32(track.width) << 16)
                box.u32(UInt32(track.height) << 16)
            }
            trak.box("mdia") { mdia in
                mdia.fullBox("mdhd") { box in
                    box.u32(0)
                    box.u32(0)
                    box.u32(track.timescale)
                    box.u32(0)
                    box.u16(Self.packedLanguage(track.language))
                    box.u16(0)
                }
                mdia.fullBox("hdlr") { box in
                    box.u32(0)
                    box.fourCC(video ? "vide" : "soun")
                    box.zeros(12)
                    box.bytes(Data((video ? "VideoHandler" : "SoundHandler").utf8))
                    box.u8(0)
                }
                mdia.box("minf") { minf in
                    if video {
                        minf.fullBox("vmhd", flags: 1) { $0.zeros(8) }
                    } else {
                        minf.fullBox("smhd") { $0.zeros(4) }
                    }
                    minf.box("dinf") { dinf in
                        dinf.fullBox("dref") { dref in
                            dref.u32(1)
                            dref.fullBox("url ", flags: 1) { _ in }
                        }
                    }
                    minf.box("stbl") { stbl in
                        stbl.fullBox("stsd") { stsd in
                            stsd.u32(1)
                            stsd.sampleEntry(track)
                        }
                        stbl.fullBox("stts") { $0.u32(0) }
                        stbl.fullBox("stsc") { $0.u32(0) }
                        stbl.fullBox("stsz") { box in
                            box.u32(0)
                            box.u32(0)
                        }
                        stbl.fullBox("stco") { $0.u32(0) }
                    }
                }
            }
        }
    }

    mutating func sampleEntry(_ track: FragmentTrack) {
        switch track.codec {
        case .avc(let config):
            visualEntry("avc1", track) { $0.box("avcC") { $0.bytes(config) } }
        case .hevc(let config, let dolbyVision):
            visualEntry(dolbyVision == nil ? "hvc1" : "dvh1", track) { entry in
                entry.box("hvcC") { $0.bytes(config) }
                if let dolbyVision {
                    // Profiles up to 7 are described in dvcC, later ones in dvvC.
                    entry.box(dolbyVision.count >= 3 && dolbyVision[dolbyVision.startIndex + 2] >> 1 > 7 ? "dvvC" : "dvcC") { $0.bytes(dolbyVision) }
                }
            }
        case .aac(let config):
            audioEntry("mp4a", track) { $0.esds(config) }
        case .ac3(let config):
            audioEntry("ac-3", track) { $0.box("dac3") { $0.bytes(config) } }
        case .eac3(let config):
            audioEntry("ec-3", track) { $0.box("dec3") { $0.bytes(config) } }
        }
    }

    private mutating func visualEntry(_ type: String, _ track: FragmentTrack, _ config: (inout BoxWriter) -> Void) {
        box(type) { entry in
            entry.zeros(6)
            entry.u16(1)
            entry.zeros(16)
            entry.u16(UInt16(clamping: track.width))
            entry.u16(UInt16(clamping: track.height))
            entry.u32(0x0048_0000)
            entry.u32(0x0048_0000)
            entry.u32(0)
            entry.u16(1)
            entry.zeros(32)
            entry.u16(0x0018)
            entry.u16(0xFFFF)
            config(&entry)
            if let colour = track.colour { entry.colourBoxes(colour) }
        }
    }

    private mutating func audioEntry(_ type: String, _ track: FragmentTrack, _ config: (inout BoxWriter) -> Void) {
        box(type) { entry in
            entry.zeros(6)
            entry.u16(1)
            entry.zeros(8)
            entry.u16(UInt16(clamping: track.channels))
            entry.u16(16)
            entry.u16(0)
            entry.u16(0)
            entry.u32(UInt32(clamping: min(track.sampleRate, 65_535)) << 16)
            config(&entry)
        }
    }

    /// colr (nclx), and mdcv and clli for HDR10, from Matroska's colour description.
    private mutating func colourBoxes(_ colour: MatroskaTrack.Colour) {
        if let primaries = colour.primaries, let transfer = colour.transfer, let matrix = colour.matrix {
            box("colr") { box in
                box.fourCC("nclx")
                box.u16(UInt16(clamping: primaries))
                box.u16(UInt16(clamping: transfer))
                box.u16(UInt16(clamping: matrix))
                box.u8(colour.range == 2 ? 0x80 : 0)
            }
        }
        if let volume = colour.colourVolume, volume.primaries.count == 6, volume.whitePoint.count == 2 {
            box("mdcv") { box in
                // Chromaticities in units of 0.00002, in the order G, B, R; luminance in units of 0.0001 cd/m².
                func chroma(_ value: Double) -> UInt16 { UInt16(clamping: Int((value / 0.000_02).rounded())) }
                for index in [2, 4, 0] {
                    box.u16(chroma(volume.primaries[index]))
                    box.u16(chroma(volume.primaries[index + 1]))
                }
                box.u16(chroma(volume.whitePoint[0]))
                box.u16(chroma(volume.whitePoint[1]))
                box.u32(UInt32(clamping: Int((volume.maxLuminance * 10_000).rounded())))
                box.u32(UInt32(clamping: Int((volume.minLuminance * 10_000).rounded())))
            }
        }
        if colour.maxCLL != nil || colour.maxFALL != nil {
            box("clli") { box in
                box.u16(UInt16(clamping: colour.maxCLL ?? 0))
                box.u16(UInt16(clamping: colour.maxFALL ?? 0))
            }
        }
    }

    /// esds: the MPEG-4 descriptors around an AudioSpecificConfig.
    private mutating func esds(_ config: Data) {
        fullBox("esds") { box in
            func descriptor(_ tag: UInt8, _ body: Data) -> Data {
                var bytes = Data([tag])
                let length = body.count
                bytes.append(contentsOf: [UInt8(0x80 | length >> 21 & 0x7F), UInt8(0x80 | length >> 14 & 0x7F), UInt8(0x80 | length >> 7 & 0x7F), UInt8(length & 0x7F)])
                return bytes + body
            }
            var decoderConfig = Data([0x40, 0x15, 0, 0, 0])
            decoderConfig.append(contentsOf: [UInt8](repeating: 0, count: 8))
            decoderConfig.append(descriptor(0x05, config))
            var elementary = Data([0, 0, 0])
            elementary.append(descriptor(0x04, decoderConfig))
            elementary.append(descriptor(0x06, Data([0x02])))
            box.bytes(descriptor(0x03, elementary))
        }
    }

    /// ISO 639-2/T in mdhd: three letters as 5-bit values.
    static func packedLanguage(_ code: String) -> UInt16 {
        let letters = Array(code.lowercased().utf8)
        guard letters.count == 3, letters.allSatisfy({ (97...122).contains($0) }) else { return packedLanguage("und") }
        return letters.reduce(0) { $0 << 5 | UInt16($1 - 0x60) }
    }
}
