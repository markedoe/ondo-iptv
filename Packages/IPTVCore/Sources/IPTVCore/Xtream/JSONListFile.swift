import Foundation

/// The elements of a JSON array saved in a file, decoded one at a time, with `LossyArray`'s rules: an element that does
/// not decode as `T` is dropped, not the list. Decoded whole, a large panel's list of films was held whole, took more
/// than its size again to decode, and its decoded elements stayed beside the films made from them (measured on Linux:
/// 47 MB of JSON for 100,000 films took 159 MB beyond its data, for 128 MB of films).
enum JSONListFile {
    /// Deeper nesting than an Xtream element has is left to the whole decoding, whose limit counts the list's level.
    private static let maximumDepth = 32

    /// The elements that decode as `T`, each passed through `transform` (`nil` drops it) as soon as it is read.
    /// `nil` when the file is anything but a plain, well-formed array of JSON values: an object keyed by index, a byte
    /// order mark, a list cut short, a damaged element, a trailing comma... The caller then decodes it whole, as it
    /// always was, so such answers are refused or accepted exactly as before.
    static func elements<T: Decodable, U>(_ type: T.Type, in file: URL, pieceSize: Int = 1 << 16, _ transform: (T) -> U?) throws -> [U]? {
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        let decoder = JSONDecoder()
        var splitter = Splitter()
        var results: [U] = []
        while let piece = try handle.read(upToCount: pieceSize), !piece.isEmpty {
            try Task.checkCancellation()
            let regular = piece.withUnsafeBytes { bytes in
                splitter.split(bytes) { element in
                    // A key the decoder cannot read would stop the app (`JSONKeys`): such an element is not a `T`.
                    if JSONKeys.areReadable(element), let value = try? decoder.decode(T.self, from: element) {
                        if let result = transform(value) { results.append(result) }
                        return true
                    }
                    // Not a `T`: dropped. Not JSON at all: the whole decoding decides.
                    return (try? decoder.decode(AnyJSONValue.self, from: element)) != nil
                }
            }
            guard regular else { return nil }
        }
        return splitter.ended ? results : nil
    }

    /// Splits an array into its elements' bytes, a piece of the file at a time.
    private struct Splitter {
        private(set) var ended = false
        private var started = false
        private var element: [UInt8] = []
        private var depth = 0
        private var inString = false
        private var escaped = false
        private var elementsRead = 0

        private static func isWhitespace(_ byte: UInt8) -> Bool {
            byte == 0x20 || byte == 0x0A || byte == 0x0D || byte == 0x09
        }

        /// `false` when the bytes are not those of a plain array, or `element` refused one.
        mutating func split(_ bytes: UnsafeRawBufferPointer, element decode: (Data) -> Bool) -> Bool {
            var segment = 0
            for index in bytes.indices {
                let byte = bytes[index]
                if !started || ended {
                    guard Self.isWhitespace(byte) || (!started && byte == UInt8(ascii: "[")) else { return false }
                    if byte == UInt8(ascii: "[") {
                        started = true
                        segment = index + 1
                    }
                } else if byte == 0 {
                    // UTF-16 or UTF-32, or a NUL: the whole decoding decides.
                    return false
                } else if inString {
                    if escaped {
                        escaped = false
                    } else if byte == UInt8(ascii: "\\") {
                        escaped = true
                    } else if byte == UInt8(ascii: "\"") {
                        inString = false
                    }
                } else {
                    switch byte {
                    case UInt8(ascii: "\""):
                        inString = true
                    case UInt8(ascii: "{"), UInt8(ascii: "["):
                        depth += 1
                        guard depth <= JSONListFile.maximumDepth else { return false }
                    case UInt8(ascii: "}"):
                        guard depth > 0 else { return false }
                        depth -= 1
                    case UInt8(ascii: "]") where depth > 0:
                        depth -= 1
                    case UInt8(ascii: ","), UInt8(ascii: "]"):
                        guard depth == 0 else { break }
                        element.append(contentsOf: UnsafeRawBufferPointer(rebasing: bytes[segment..<index]))
                        guard finishElement(last: byte == UInt8(ascii: "]"), decode: decode) else { return false }
                        segment = index + 1
                    default:
                        break
                    }
                }
            }
            if started && !ended && segment < bytes.count {
                element.append(contentsOf: UnsafeRawBufferPointer(rebasing: bytes[segment...]))
            }
            return true
        }

        private mutating func finishElement(last: Bool, decode: (Data) -> Bool) -> Bool {
            defer { element.removeAll(keepingCapacity: true) }
            if last { ended = true }
            guard let first = element.firstIndex(where: { !Self.isWhitespace($0) }),
                  let end = element.lastIndex(where: { !Self.isWhitespace($0) }) else {
                // `[]` is an empty list; `[,`, `,,` and `,]` are left to the whole decoding.
                return last && elementsRead == 0
            }
            elementsRead += 1
            return decode(Data(element[first...end]))
        }
    }
}

/// Any JSON value, read for nothing: decoding it only checks that an element is JSON.
struct AnyJSONValue: Decodable {
    init(from decoder: Decoder) throws {}
}
