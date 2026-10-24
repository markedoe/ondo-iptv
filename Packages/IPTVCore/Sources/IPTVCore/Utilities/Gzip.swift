import Foundation
import CZlib

public enum GzipError: Error, Equatable, LocalizedError {
    case initFailed(Int32)
    case inflateFailed(Int32)
    case outputTooLarge(Int)

    public var errorDescription: String? {
        switch self {
        case .initFailed, .inflateFailed: return "The file is damaged: it could not be decompressed."
        case .outputTooLarge(let limit): return "The file is too large once decompressed (over \(limit / 1_048_576) MB)."
        }
    }
}

extension Data {
    /// Largest decompressed size accepted: more than any real guide, and a ceiling for files that inflate
    /// without end (a few megabytes of gzip can expand to gigabytes). Low enough for an iPhone to parse what
    /// it decompressed without being stopped for using too much memory.
    public static let maxGunzippedSize = 320 << 20

    /// gzip magic number 1f 8b.
    public var isGzipped: Bool {
        count >= 2 && self[startIndex] == 0x1f && self[startIndex + 1] == 0x8b
    }

    /// Inflates gzip data (it starts with 1f 8b). Anything else, zlib streams included, is returned unchanged.
    public func gunzippedIfNeeded(limit: Int = Data.maxGunzippedSize) throws -> Data {
        isGzipped ? try gunzipped(limit: limit) : self
    }

    /// Inflates gzip or zlib data (the header says which), throwing past `limit` bytes.
    public func gunzipped(limit: Int = Data.maxGunzippedSize) throws -> Data {
        guard !isEmpty else { return self }
        let inflater = try GzipInflater(limit: limit)
        var output = Data()
        output.reserveCapacity(Swift.min(count * 4, 64 * 1024 * 1024))
        try withUnsafeBytes { input in
            try inflater.feed(input) { output.append($0) }
        }
        try inflater.finish()
        return output
    }
}

/// zlib inflation fed a piece at a time, so a file is inflated without being read whole: `Data.gunzipped` gives it all
/// the data at once, `decompress(_:to:limit:)` a file's pieces. Concatenated gzip members (some EPG providers do this)
/// are inflated one after the other; padding or junk after a complete member ends the file.
final class GzipInflater {
    private var stream = z_stream()
    private var buffer = [UInt8](repeating: 0, count: 256 * 1024)
    private let limit: Int
    /// Bytes inflated so far.
    private var produced = 0
    private var completedMember = false
    /// A member ended with the input given so far: more input starts the next one.
    private var atMemberEnd = false
    /// What follows the last complete member is not gzip: the rest of the input is ignored.
    private var ended = false

    init(limit: Int) throws {
        // 15 = max window, +32 = auto-detect gzip / zlib headers.
        let status = czlib_inflate_init(&stream, 15 + 32)
        guard status == Z_OK else { throw GzipError.initFailed(status) }
        self.limit = limit
    }

    deinit { inflateEnd(&stream) }

    /// Inflates the next piece of the input, handing what it gives to `output` (throwing past the limit).
    func feed(_ input: UnsafeRawBufferPointer, output: (UnsafeBufferPointer<UInt8>) throws -> Void) throws {
        guard !ended, !input.isEmpty, let base = input.bindMemory(to: Bytef.self).baseAddress else { return }
        if atMemberEnd {
            inflateReset(&stream)
            atMemberEnd = false
        }
        stream.next_in = UnsafeMutablePointer(mutating: base)
        stream.avail_in = uInt(input.count)
        var status: Int32 = Z_OK
        repeat {
            let inflated: Int = try buffer.withUnsafeMutableBufferPointer { out in
                stream.next_out = out.baseAddress
                stream.avail_out = uInt(out.count)
                status = inflate(&stream, Z_NO_FLUSH)
                let inflated = out.count - Int(stream.avail_out)
                if status == Z_STREAM_END {
                    completedMember = true
                    // Concatenated gzip members: restart on the next one.
                    if stream.avail_in > 0 {
                        inflateReset(&stream)
                        status = Z_OK
                    } else {
                        atMemberEnd = true
                    }
                    return inflated
                }
                guard status == Z_OK || status == Z_BUF_ERROR else {
                    // Padding or junk after a complete member: what was inflated is the file.
                    if completedMember {
                        ended = true
                        return inflated
                    }
                    throw GzipError.inflateFailed(status)
                }
                return inflated
            }
            guard produced + inflated <= limit else { throw GzipError.outputTooLarge(limit) }
            produced += inflated
            if inflated != 0 { try buffer.withUnsafeBufferPointer { try output(UnsafeBufferPointer(rebasing: $0[0..<inflated])) } }
            // This piece is used up.
            if status == Z_BUF_ERROR && inflated == 0 { break }
        } while status != Z_STREAM_END && !ended
    }

    /// After the last piece: a stream cut short keeps what could be inflated (a partial guide is better than none),
    /// unless that is nothing.
    func finish() throws {
        if !atMemberEnd, !ended, produced == 0 { throw GzipError.inflateFailed(Z_DATA_ERROR) }
    }

    /// Inflates a gzip or zlib file into `destination` (created, or replaced) a piece at a time, throwing past `limit`
    /// inflated bytes or when the calling task is cancelled. On failure `destination` is removed.
    static func decompress(_ file: URL, to destination: URL, limit: Int = Data.maxGunzippedSize) throws {
        let fileManager = FileManager.default
        do {
            try? fileManager.removeItem(at: destination)
            let input = try FileHandle(forReadingFrom: file)
            defer { try? input.close() }
            guard fileManager.createFile(atPath: destination.path, contents: nil) else {
                throw CocoaError(.fileWriteUnknown, userInfo: [NSURLErrorKey: destination])
            }
            let output = try FileHandle(forWritingTo: destination)
            defer { try? output.close() }
            let inflater = try GzipInflater(limit: limit)
            while let piece = try input.read(upToCount: 256 * 1024), !piece.isEmpty {
                try Task.checkCancellation()
                try piece.withUnsafeBytes { bytes in
                    try inflater.feed(bytes) { try output.write(contentsOf: Data(buffer: $0)) }
                }
            }
            try inflater.finish()
        } catch {
            try? fileManager.removeItem(at: destination)
            throw error
        }
    }

    /// Whether the file starts like gzip (1f 8b).
    static func isGzipFile(_ file: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: file) else { return false }
        defer { try? handle.close() }
        return ((try? handle.read(upToCount: 2)) ?? Data()).isGzipped
    }
}
