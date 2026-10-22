import Foundation

/// AC-3 (Dolby Digital) and E-AC-3 (Dolby Digital Plus, with Dolby Atmos) as MP4 describes them: the dac3 and dec3
/// boxes, made from the first frame's bit stream information (ETSI TS 102 366, annexes E and F).
enum DolbyAudio {
    /// What the MP4 sample entry needs.
    struct Config: Equatable {
        var box: Data
        var sampleRate: Int
        var channels: Int
        /// Samples per MKV frame.
        var frameLength: Int
        /// Joint object coding: the stream carries Dolby Atmos.
        var atmos = false
    }

    static let sampleRates = [48_000, 44_100, 32_000]

    /// AC-3: the dac3 payload from a syncframe.
    static func ac3(_ frame: Data) throws -> Config {
        var bits = BitReader(frame)
        guard try bits.read(16) == 0x0B77 else { throw RemuxError("not an AC-3 frame") }
        try bits.skip(16)
        let fscod = try bits.read(2)
        let frmsizecod = try bits.read(6)
        let bsid = try bits.read(5)
        let bsmod = try bits.read(3)
        let acmod = try bits.read(3)
        guard fscod < 3, bsid <= 10 else { throw RemuxError("unsupported AC-3 stream") }
        if acmod & 1 != 0 && acmod != 1 { try bits.skip(2) }
        if acmod & 4 != 0 { try bits.skip(2) }
        if acmod == 2 { try bits.skip(2) }
        let lfeon = try bits.read(1)
        var box = BitWriter()
        box.write(fscod, 2)
        box.write(bsid, 5)
        box.write(bsmod, 3)
        box.write(acmod, 3)
        box.write(lfeon, 1)
        box.write(frmsizecod >> 1, 5)
        box.write(0, 5)
        return Config(box: box.data, sampleRate: sampleRates[Int(fscod)], channels: channels(acmod: acmod, lfe: lfeon), frameLength: 1_536)
    }

    /// One E-AC-3 syncframe's bit stream information.
    struct Substream {
        var type: UInt32
        var id: UInt32
        /// Bytes.
        var size: Int
        var fscod: UInt32
        var sampleRate: Int
        var blocks: Int
        var acmod: UInt32
        var lfeon: UInt32
        var bsid: UInt32
        var bsmod: UInt32 = 0
        var chanmap: UInt32?
        var atmos = false
        var complexity: UInt32 = 0
    }

    /// E-AC-3: the dec3 payload from an MKV frame (an independent substream and its dependent ones).
    static func eac3(_ frame: Data) throws -> Config {
        var substreams: [Substream] = []
        var offset = 0
        while offset + 6 <= frame.count {
            let substream = try eac3Substream(frame.subdata(in: offset..<frame.count))
            substreams.append(substream)
            offset += substream.size
        }
        guard let first = substreams.first, first.type != 1 else { throw RemuxError("E-AC-3 frame without an independent substream") }
        let independent = substreams.filter { $0.type != 1 }
        // Several independent substreams in one frame are other programs: only the first is described.
        let main = independent.filter { $0.id == 0 }
        let dependents = substreams.filter { $0.type == 1 }
        let samplesPerFrame = main.count * first.blocks * 256
        // The first program gives the frame's length: without it, the audio clock would have no ticks per frame.
        guard samplesPerFrame > 0 else { throw RemuxError("E-AC-3 frame without its first program") }
        let bytes = substreams.reduce(0) { $0 + $1.size }
        let dataRate = bytes * 8 * first.sampleRate / samplesPerFrame / 1_000
        var box = BitWriter()
        box.write(UInt32(min(dataRate, 8_191)), 13)
        box.write(0, 3)
        box.write(first.fscod, 2)
        box.write(first.bsid, 5)
        box.write(0, 1)
        box.write(0, 1)
        box.write(first.bsmod, 3)
        box.write(first.acmod, 3)
        box.write(first.lfeon, 1)
        box.write(0, 3)
        box.write(UInt32(dependents.count), 4)
        var channels = channels(acmod: first.acmod, lfe: first.lfeon)
        if dependents.isEmpty {
            box.write(0, 1)
        } else {
            var location: UInt32 = 0
            for dependent in dependents {
                guard let map = dependent.chanmap else { continue }
                // chanmap's bits 10 to 3 (Lc/Rc to Vhc) and 1 (LFE2), in chan_loc's order.
                location |= (map >> 2) & 0x1FE | (map >> 1) & 1
            }
            box.write(location, 9)
            channels += (0..<9).reduce(0) { total, bit in
                guard location & (1 << bit) != 0 else { return total }
                return total + ([0, 1, 5, 6].contains(bit) ? 1 : 2)
            }
        }
        if first.atmos {
            box.write(0, 7)
            box.write(1, 1)
            box.write(first.complexity, 8)
        }
        return Config(box: box.data, sampleRate: first.sampleRate, channels: channels, frameLength: samplesPerFrame, atmos: first.atmos)
    }

    // swiftlint:disable:next cyclomatic_complexity
    static func eac3Substream(_ data: Data) throws -> Substream {
        var bits = BitReader(data)
        guard try bits.read(16) == 0x0B77 else { throw RemuxError("not an E-AC-3 frame") }
        let type = try bits.read(2)
        let id = try bits.read(3)
        let size = (Int(try bits.read(11)) + 1) * 2
        let fscod = try bits.read(2)
        var blocks = 6
        var sampleRate: Int
        if fscod == 3 {
            let fscod2 = try bits.read(2)
            guard fscod2 < 3 else { throw RemuxError("bad E-AC-3 sample rate") }
            sampleRate = sampleRates[Int(fscod2)] / 2
        } else {
            blocks = [1, 2, 3, 6][Int(try bits.read(2))]
            sampleRate = sampleRates[Int(fscod)]
        }
        let acmod = try bits.read(3)
        let lfeon = try bits.read(1)
        let bsid = try bits.read(5)
        guard bsid > 10, bsid <= 16 else { throw RemuxError("not E-AC-3 (bsid \(bsid))") }
        var substream = Substream(
            type: type, id: id, size: size, fscod: fscod, sampleRate: sampleRate, blocks: blocks, acmod: acmod, lfeon: lfeon, bsid: bsid
        )
        try bits.skip(5)
        if try bits.read(1) == 1 { try bits.skip(8) }
        if acmod == 0 {
            try bits.skip(5)
            if try bits.read(1) == 1 { try bits.skip(8) }
        }
        if type == 1, try bits.read(1) == 1 { substream.chanmap = try bits.read(16) }
        // Mixing metadata.
        if try bits.read(1) == 1 {
            if acmod > 2 { try bits.skip(2) }
            if acmod & 1 != 0 && acmod > 2 { try bits.skip(6) }
            if acmod & 4 != 0 { try bits.skip(6) }
            if lfeon == 1, try bits.read(1) == 1 { try bits.skip(5) }
            if type == 0 {
                if try bits.read(1) == 1 { try bits.skip(6) }
                if acmod == 0, try bits.read(1) == 1 { try bits.skip(6) }
                if try bits.read(1) == 1 { try bits.skip(6) }
                switch try bits.read(2) {
                case 1: try bits.skip(5)
                case 2: try bits.skip(12)
                case 3: try bits.skip(8 * (Int(try bits.read(5)) + 2))
                default: break
                }
                if acmod < 2 {
                    if try bits.read(1) == 1 { try bits.skip(14) }
                    if acmod == 0, try bits.read(1) == 1 { try bits.skip(14) }
                }
                if try bits.read(1) == 1 {
                    if blocks == 1 {
                        try bits.skip(5)
                    } else {
                        for _ in 0..<blocks {
                            let blockMixing = try bits.read(1)
                            if blockMixing == 1 { try bits.skip(5) }
                        }
                    }
                }
            }
        }
        // Informational metadata: the service type (bsmod).
        if try bits.read(1) == 1 {
            substream.bsmod = try bits.read(3)
            try bits.skip(2)
            if acmod == 2 { try bits.skip(4) }
            if acmod >= 6 { try bits.skip(2) }
            if try bits.read(1) == 1 { try bits.skip(8) }
            if acmod == 0, try bits.read(1) == 1 { try bits.skip(8) }
            if fscod < 3 { try bits.skip(1) }
        }
        if type == 0 && blocks != 6 { try bits.skip(1) }
        if type == 2 {
            let blkid = try blocks == 6 ? 1 : bits.read(1)
            if blkid == 1 { try bits.skip(6) }
        }
        // Additional information: Dolby Atmos (joint object coding) is announced in its first byte.
        if try bits.read(1) == 1 {
            let length = Int(try bits.read(6)) + 1
            if length >= 1 {
                try bits.skip(7)
                substream.atmos = try bits.read(1) == 1
                if substream.atmos, length >= 2 { substream.complexity = try bits.read(8) }
            }
        }
        return substream
    }

    /// Channels of an audio coding mode, LFE included.
    static func channels(acmod: UInt32, lfe: UInt32) -> Int {
        [2, 1, 2, 3, 3, 4, 4, 5][Int(acmod & 7)] + Int(lfe)
    }
}

/// Reads bits from the most significant first.
struct BitReader {
    private let bytes: [UInt8]
    private var position = 0

    init(_ data: Data) {
        bytes = [UInt8](data)
    }

    mutating func read(_ count: Int) throws -> UInt32 {
        guard position + count <= bytes.count * 8 else { throw RemuxError("bit stream too short") }
        var value: UInt32 = 0
        for _ in 0..<count {
            let byte = bytes[position >> 3]
            value = value << 1 | UInt32(byte >> (7 - position & 7) & 1)
            position += 1
        }
        return value
    }

    mutating func skip(_ count: Int) throws {
        guard position + count <= bytes.count * 8 else { throw RemuxError("bit stream too short") }
        position += count
    }
}

/// Writes bits from the most significant first.
struct BitWriter {
    private(set) var data = Data()
    private var used = 8

    mutating func write(_ value: UInt32, _ count: Int) {
        for bit in stride(from: count - 1, through: 0, by: -1) {
            if used == 8 {
                data.append(0)
                used = 0
            }
            if value >> bit & 1 == 1 { data[data.count - 1] |= 1 << (7 - used) }
            used += 1
        }
    }
}
