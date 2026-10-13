import Foundation
import CZlib

public enum GzipError: Error, Equatable {
    case initFailed(Int32)
    case inflateFailed(Int32)
}

extension Data {
    /// gzip magic number 1f 8b.
    public var isGzipped: Bool {
        count >= 2 && self[startIndex] == 0x1f && self[startIndex + 1] == 0x8b
    }

    /// Inflates gzip or zlib data. Returns `self` unchanged when it is not compressed.
    public func gunzippedIfNeeded() throws -> Data {
        isGzipped ? try gunzipped() : self
    }

    public func gunzipped() throws -> Data {
        guard !isEmpty else { return self }
        var stream = z_stream()
        // 15 = max window, +32 = auto-detect gzip / zlib headers.
        let initStatus = czlib_inflate_init(&stream, 15 + 32)
        guard initStatus == Z_OK else { throw GzipError.initFailed(initStatus) }
        defer { inflateEnd(&stream) }

        var output = Data()
        output.reserveCapacity(Swift.min(count * 6, 512 * 1024 * 1024))
        let chunkSize = 256 * 1024
        var buffer = [UInt8](repeating: 0, count: chunkSize)

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
                    // Concatenated gzip members (some EPG providers do this): restart on the next one.
                    if status == Z_STREAM_END && stream.avail_in > 0 {
                        let produced = chunkSize - Int(stream.avail_out)
                        inflateReset(&stream)
                        status = Z_OK
                        return produced
                    }
                    guard status == Z_OK || status == Z_STREAM_END || status == Z_BUF_ERROR else {
                        throw GzipError.inflateFailed(status)
                    }
                    return chunkSize - Int(stream.avail_out)
                }
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
