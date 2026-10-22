import Foundation

/// Why a file cannot be remuxed for the system player: the app plays it with VLC instead.
public struct RemuxError: Error, Equatable, Sendable, CustomStringConvertible {
    public let description: String

    public init(_ description: String) {
        self.description = description
    }
}

/// Positions and times read from a file, added and scaled without trapping: a damaged file's values that overflow 64
/// bits throw, and the app plays the file with VLC.
extension Int64 {
    func adding(_ other: Int64) throws -> Int64 {
        let (sum, overflow) = addingReportingOverflow(other)
        guard !overflow else { throw RemuxError("value out of range (\(self) + \(other))") }
        return sum
    }

    func multiplied(by other: Int64) throws -> Int64 {
        let (product, overflow) = multipliedReportingOverflow(by: other)
        guard !overflow else { throw RemuxError("value out of range (\(self) × \(other))") }
        return product
    }
}

/// Random access to a file's bytes: a local file, memory, or a provider's server through range requests.
public protocol ByteSource: Sendable {
    /// The file's size, when known.
    func size() async throws -> Int64?
    /// The bytes of `range`; fewer at the end of the file.
    func read(_ range: Range<Int64>) async throws -> Data
}

/// A file held in memory.
public struct DataByteSource: ByteSource {
    public let data: Data

    public init(_ data: Data) {
        self.data = Data(data)
    }

    public func size() async throws -> Int64? { Int64(data.count) }

    public func read(_ range: Range<Int64>) async throws -> Data {
        let lower = Int(clamping: max(range.lowerBound, 0))
        let upper = Int(clamping: min(range.upperBound, Int64(data.count)))
        return lower < upper ? data.subdata(in: lower..<upper) : Data()
    }
}

/// EBML, the binary layout of Matroska: elements made of an ID, a size and a body, nested.
enum EBML {
    /// A size of all ones: the element runs until its parent ends or another element of its level starts.
    static let unknownSize = UInt64.max

    /// An element's ID and size, found at `offset` of a buffer.
    struct Header: Equatable {
        var id: UInt32
        var size: UInt64
        var offset: Int
        var headerLength: Int

        var dataOffset: Int { offset + headerLength }
        var hasUnknownSize: Bool { size == EBML.unknownSize }
        /// Where the element ends, when its size is known.
        var end: Int? { hasUnknownSize ? nil : dataOffset + Int(clamping: size) }
    }

    /// A variable-length integer at `offset`: its value and length. An ID keeps its marker bit; a size loses it, and
    /// all ones (the reserved value) stands for an unknown size.
    static func vint(_ bytes: Data, at offset: Int, isID: Bool) -> (value: UInt64, length: Int)? {
        guard offset >= bytes.startIndex, offset < bytes.endIndex else { return nil }
        let first = bytes[offset]
        guard first != 0 else { return nil }
        let length = first.leadingZeroBitCount + 1
        guard length <= (isID ? 4 : 8), offset + length <= bytes.endIndex else { return nil }
        let mask: UInt8 = 0xFF >> length
        var value = UInt64(isID ? first : first & mask)
        var allOnes = first & mask == mask
        for index in 1..<length {
            let byte = bytes[offset + index]
            value = value << 8 | UInt64(byte)
            if byte != 0xFF { allOnes = false }
        }
        if !isID && allOnes { return (unknownSize, length) }
        return (value, length)
    }

    /// The element header at `offset`, if the buffer holds all of it.
    static func header(_ bytes: Data, at offset: Int) -> Header? {
        guard let id = vint(bytes, at: offset, isID: true),
              let size = vint(bytes, at: offset + id.length, isID: false) else { return nil }
        return Header(id: UInt32(id.value), size: size.value, offset: offset, headerLength: id.length + size.length)
    }

    /// The elements in `range` of a buffer, one level down. Throws on a child that runs past its parent.
    static func children(_ bytes: Data, in range: Range<Int>) throws -> [Header] {
        var headers: [Header] = []
        var offset = range.lowerBound
        while offset < range.upperBound {
            guard let header = header(bytes, at: offset) else { throw RemuxError("broken element at \(offset)") }
            guard let end = header.end, end <= range.upperBound else {
                throw RemuxError("element \(String(header.id, radix: 16)) runs past its parent")
            }
            headers.append(header)
            offset = end
        }
        return headers
    }

    static func unsigned(_ bytes: Data, _ header: Header) throws -> UInt64 {
        guard header.size <= 8, let end = header.end, end <= bytes.endIndex else { throw RemuxError("bad unsigned integer") }
        return bytes[header.dataOffset..<end].reduce(0) { $0 << 8 | UInt64($1) }
    }

    static func signed(_ bytes: Data, _ header: Header) throws -> Int64 {
        let value = try unsigned(bytes, header)
        guard header.size > 0, header.size < 8 else { return Int64(bitPattern: value) }
        let shift = 64 - 8 * header.size
        return Int64(bitPattern: value << shift) >> shift
    }

    static func float(_ bytes: Data, _ header: Header) throws -> Double {
        let bits = try unsigned(bytes, header)
        switch header.size {
        case 0: return 0
        case 4: return Double(Float(bitPattern: UInt32(bits)))
        case 8: return Double(bitPattern: bits)
        default: throw RemuxError("bad float")
        }
    }

    static func string(_ bytes: Data, _ header: Header) throws -> String {
        let data = try binary(bytes, header)
        return String(decoding: data.prefix { $0 != 0 }, as: UTF8.self)
    }

    static func binary(_ bytes: Data, _ header: Header) throws -> Data {
        guard let end = header.end, end <= bytes.endIndex else { throw RemuxError("element past the buffer") }
        return bytes.subdata(in: header.dataOffset..<end)
    }
}
