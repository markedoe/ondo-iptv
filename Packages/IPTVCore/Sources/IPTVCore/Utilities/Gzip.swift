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
    /// without end (a few megabytes of gzip can expand to gigabytes).
    public static let maxGunzippedSize = 1 << 30

    /// gzip magic number 1f 8b.
    public var isGzipped: Bool {
        count >= 2 && self[startIndex] == 0x1f && self[startIndex + 1] == 0x8b
    }

    /// Inflates gzip or zlib data. Returns `self` unchanged when it is not compressed.
    public func gunzippedIfNeeded(limit: Int = Data.maxGunzippedSize) throws -> Data {
        isGzipped ? try gunzipped(limit: limit) : self
    }

    public func gunzipped(limit: Int = Data.maxGunzippedSize) throws -> Data {
        guard !isEmpty else { return self }
        var stream = z_stream()
        // 15 = max window, +32 = auto-detect gzip / zlib headers.
        let initStatus = czlib_inflate_init(&stream, 15 + 32)
        guard initStatus == Z_OK else { throw GzipError.initFailed(initStatus) }
        defer { inflateEnd(&stream) }

        var output = Data()
        output.reserveCapacity(Swift.min(count * 4, 64 * 1024 * 1024))
        let chunkSize = 256 * 1024
        var buffer = [UInt8](repeating: 0, count: chunkSize)
        var completedMember = false

        try withUnsafeBytes { (input: UnsafeRawBufferPointer) in
            guard let base = input.bindMemory(to: Bytef.self).baseAddress else { return }
            stream.next_in = UnsafeMutablePointer(mutating: base)
            stream.avail_in = uInt(input.count)
            var status: Int32 = Z_OK
            repeat {
                let produced: Int = try buffer.withUnsafeMutableBufferPointer { out in
                    stream.next_out = out.baseAddress
                    stream.avail_out = uInt(chunkSize)
                    status = inflate(&stream, Z_NO_FLUSH)
                    let produced = chunkSize - Int(stream.avail_out)
                    if status == Z_STREAM_END {
                        completedMember = true
                        // Concatenated gzip members (some EPG providers do this): restart on the next one.
                        if stream.avail_in > 0 {
                            inflateReset(&stream)
                            status = Z_OK
                        }
                        return produced
                    }
                    guard status == Z_OK || status == Z_BUF_ERROR else {
                        // Padding or junk after a complete member: what was inflated is the file.
                        if completedMember {
                            status = Z_STREAM_END
                            return produced
                        }
                        throw GzipError.inflateFailed(status)
                    }
                    return produced
                }
                guard output.count + produced <= limit else { throw GzipError.outputTooLarge(limit) }
                output.append(buffer, count: produced)
                if status == Z_BUF_ERROR && produced == 0 { break }
            } while status != Z_STREAM_END
            // Truncated stream: keep what could be inflated (a partial guide is better than none).
            if status != Z_STREAM_END && output.isEmpty {
                throw GzipError.inflateFailed(Z_DATA_ERROR)
            }
        }
        return output
    }
}
