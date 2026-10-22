import Foundation

/// Matroska files built in tests: elements with their size on 8 bytes, and a small film the remux accepts.
enum MatroskaBytes {
    /// Element bytes: the ID, its size on 8 bytes, the body.
    static func element(_ id: UInt32, _ body: Data) -> Data {
        var bytes = Data()
        let idLength = id > 0xFF_FFFF ? 4 : id > 0xFFFF ? 3 : id > 0xFF ? 2 : 1
        for shift in stride(from: (idLength - 1) * 8, through: 0, by: -8) { bytes.append(UInt8(truncatingIfNeeded: id >> UInt32(shift))) }
        bytes.append(0x01)
        for shift in stride(from: 48, through: 0, by: -8) { bytes.append(UInt8(truncatingIfNeeded: body.count >> shift)) }
        return bytes + body
    }

    static func element(_ id: UInt32, _ children: [Data]) -> Data {
        element(id, children.reduce(Data(), +))
    }

    static func unsigned(_ id: UInt32, _ value: UInt64) -> Data {
        element(id, Data((0..<8).reversed().map { UInt8(truncatingIfNeeded: value >> ($0 * 8)) }))
    }

    static func string(_ id: UInt32, _ value: String) -> Data {
        element(id, Data(value.utf8))
    }

    static func float(_ id: UInt32, _ value: Double) -> Data {
        unsigned(id, value.bitPattern) // An 8-byte float is read as its bits.
    }

    /// An H.264 film without audio (only the first byte of its configuration is read): a cluster at each of
    /// `clusterTimes` (ms) holding 25 frames 40 ms apart, a key frame first, and an index before the clusters.
    static func film(clusterTimes: [UInt64] = [0, 1_000, 2_000], defaultDuration: UInt64? = 40_000_000, duration: Double = 3_000) -> Data {
        let header = element(0x1A45_DFA3, [string(0x4282, "matroska")])
        let info = element(0x1549_A966, [unsigned(0x2AD7B1, 1_000_000), float(0x4489, duration)])
        var track = [
            unsigned(0xD7, 1), unsigned(0x83, 1), string(0x86, "V_MPEG4/ISO/AVC"), element(0x63A2, Data([1, 0x64, 0, 0x0B, 0xFF, 0xE1])),
            element(0xE0, [unsigned(0xB0, 160), unsigned(0xBA, 96)]),
        ]
        if let defaultDuration { track.append(unsigned(0x23E383, defaultDuration)) }
        let tracks = element(0x1654_AE6B, [element(0xAE, track)])
        let clusters = clusterTimes.map { time in
            element(0x1F43_B675, [unsigned(0xE7, time)] + (0..<25).map { index in
                let relative = UInt16(index * 40)
                let block = Data([0x81, UInt8(relative >> 8), UInt8(relative & 0xFF), index == 0 ? 0x80 : 0]) + Data(repeating: UInt8(index), count: 10)
                return element(0xA3, block)
            })
        }
        // Positions are from the start of the segment's content, and the index's size does not depend on them.
        func cues(_ positions: [UInt64]) -> Data {
            element(0x1C53_BB6B, zip(clusterTimes, positions).map { time, position in
                element(0xBB, [unsigned(0xB3, time), element(0xB7, [unsigned(0xF7, 1), unsigned(0xF1, position)])])
            })
        }
        var position = UInt64(info.count + tracks.count + cues(clusterTimes.map { _ in 0 }).count)
        var positions: [UInt64] = []
        for cluster in clusters {
            positions.append(position)
            position += UInt64(cluster.count)
        }
        return header + element(0x1853_8067, [info, tracks, cues(positions)] + clusters)
    }
}
