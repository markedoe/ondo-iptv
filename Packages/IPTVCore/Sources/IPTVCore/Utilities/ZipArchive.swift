import Foundation
import CZlib

public enum ZipError: Error, Equatable, LocalizedError {
    case notAnArchive
    case damaged
    case unsupportedEntry(String)
    case entryTooLarge(Int)

    public var errorDescription: String? {
        switch self {
        case .notAnArchive: return "The file is not a ZIP archive."
        case .damaged: return "The ZIP archive is damaged."
        case .unsupportedEntry(let name): return "“\(name)” is stored in a way this app cannot read."
        case .entryTooLarge(let limit): return "A file in the archive is too large once decompressed (over \(limit / 1_048_576) MB)."
        }
    }
}

/// Reads the files of a ZIP archive (subtitle sites send their files zipped). Stored and deflated entries,
/// no encryption, no ZIP64: what subtitle archives use. Every entry is capped when inflated, as an archive
/// from the internet can claim any size.
public struct ZipArchive: Sendable {
    public struct Entry: Hashable, Sendable {
        public let name: String
        let method: UInt16
        let flags: UInt16
        let compressedSize: Int
        let uncompressedSize: Int
        let localHeaderOffset: Int
    }

    /// Largest file read from an archive: subtitles are a few hundred kilobytes.
    public static let maxEntrySize = 16 * 1_048_576

    public let entries: [Entry]
    private let data: Data

    public init(_ data: Data) throws {
        self.data = data
        let bytes = [UInt8](data)
        guard bytes.count >= 22 else { throw ZipError.notAnArchive }
        // End of central directory: its signature, searched back over the longest possible comment.
        var end = -1
        var position = bytes.count - 22
        let earliest = max(0, bytes.count - 22 - 65_535)
        while position >= earliest {
            if Self.uint32(bytes, position) == 0x0605_4B50 {
                end = position
                break
            }
            position -= 1
        }
        guard end >= 0 else { throw ZipError.notAnArchive }
        let count = Int(Self.uint16(bytes, end + 10))
        var offset = Int(Self.uint32(bytes, end + 16))
        var entries: [Entry] = []
        for _ in 0..<count {
            guard offset + 46 <= bytes.count, Self.uint32(bytes, offset) == 0x0201_4B50 else { throw ZipError.damaged }
            let nameLength = Int(Self.uint16(bytes, offset + 28))
            let extraLength = Int(Self.uint16(bytes, offset + 30))
            let commentLength = Int(Self.uint16(bytes, offset + 32))
            guard offset + 46 + nameLength <= bytes.count else { throw ZipError.damaged }
            let nameBytes = bytes[(offset + 46)..<(offset + 46 + nameLength)]
            let name = String(decoding: nameBytes, as: UTF8.self)
            entries.append(Entry(
                name: name,
                method: Self.uint16(bytes, offset + 10),
                flags: Self.uint16(bytes, offset + 8),
                compressedSize: Int(Self.uint32(bytes, offset + 20)),
                uncompressedSize: Int(Self.uint32(bytes, offset + 24)),
                localHeaderOffset: Int(Self.uint32(bytes, offset + 42))
            ))
            offset += 46 + nameLength + extraLength + commentLength
        }
        self.entries = entries
    }

    /// The files, without folders and the metadata macOS adds (`__MACOSX/`).
    public var files: [Entry] {
        entries.filter { !$0.name.hasSuffix("/") && !$0.name.hasPrefix("__MACOSX/") }
    }

    public func contents(of entry: Entry, limit: Int = ZipArchive.maxEntrySize) throws -> Data {
        guard entry.flags & 1 == 0 else { throw ZipError.unsupportedEntry(entry.name) }  // encrypted
        guard entry.uncompressedSize <= limit else { throw ZipError.entryTooLarge(limit) }
        let bytes = [UInt8](data)
        let header = entry.localHeaderOffset
        guard header + 30 <= bytes.count, Self.uint32(bytes, header) == 0x0403_4B50 else { throw ZipError.damaged }
        let start = header + 30 + Int(Self.uint16(bytes, header + 26)) + Int(Self.uint16(bytes, header + 28))
        guard start + entry.compressedSize <= bytes.count else { throw ZipError.damaged }
        let compressed = Data(bytes[start..<(start + entry.compressedSize)])
        switch entry.method {
        case 0:
            return compressed
        case 8:
            return try Self.inflateRaw(compressed, limit: limit)
        default:
            throw ZipError.unsupportedEntry(entry.name)
        }
    }

    /// Raw deflate (no zlib or gzip header), stopping at `limit` bytes.
    static func inflateRaw(_ input: Data, limit: Int) throws -> Data {
        guard !input.isEmpty else { return Data() }
        var stream = z_stream()
        guard czlib_inflate_init(&stream, -15) == Z_OK else { throw ZipError.damaged }
        defer { inflateEnd(&stream) }
        var output = Data()
        let chunkSize = 64 * 1024
        var buffer = [UInt8](repeating: 0, count: chunkSize)
        try input.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            guard let base = raw.bindMemory(to: Bytef.self).baseAddress else { return }
            stream.next_in = UnsafeMutablePointer(mutating: base)
            stream.avail_in = uInt(raw.count)
            var status: Int32 = Z_OK
            repeat {
                let produced: Int = buffer.withUnsafeMutableBufferPointer { out in
                    stream.next_out = out.baseAddress
                    stream.avail_out = uInt(chunkSize)
                    status = inflate(&stream, Z_NO_FLUSH)
                    return chunkSize - Int(stream.avail_out)
                }
                guard status == Z_OK || status == Z_STREAM_END || (status == Z_BUF_ERROR && produced > 0) else {
                    throw ZipError.damaged
                }
                guard output.count + produced <= limit else { throw ZipError.entryTooLarge(limit) }
                output.append(buffer, count: produced)
                if status == Z_BUF_ERROR { break }
            } while status != Z_STREAM_END
        }
        return output
    }

    private static func uint16(_ bytes: [UInt8], _ offset: Int) -> UInt16 {
        guard offset + 2 <= bytes.count else { return 0 }
        return UInt16(bytes[offset]) | UInt16(bytes[offset + 1]) << 8
    }

    private static func uint32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        guard offset + 4 <= bytes.count else { return 0 }
        return UInt32(bytes[offset]) | UInt32(bytes[offset + 1]) << 8 | UInt32(bytes[offset + 2]) << 16 | UInt32(bytes[offset + 3]) << 24
    }
}
