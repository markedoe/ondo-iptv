import Foundation

/// The object keys of a JSON document, checked before `JSONDecoder` reads it. Swift 6.0's `JSONDecoder` (the one of
/// Linux, and the Foundation of the systems before swift-foundation 6.1) reads an object's keys with a `try!`: a key it
/// cannot read as a string (bytes that are not UTF-8, a control character, an escape such as `\d`, half a surrogate
/// pair) stops the app instead of throwing. Any panel answer could hold one, and a cache re-read at launch would stop
/// the app at every start. Values are not checked: a value the decoder cannot read is an ordinary error, and the
/// lenient decoders drop it.
enum JSONKeys {
    /// Whether every object key in the document is one `JSONDecoder` can read: the strings followed by `:`, read in the
    /// encoding the decoder reads the document in (its BOM or its first bytes say UTF-16 or UTF-32).
    static func areReadable(_ data: Data) -> Bool {
        data.withUnsafeBytes { bytes in
            guard let (encoding, bom) = wideEncoding(bytes) else { return areReadable(utf8: bytes) }
            // The decoder converts such a document whole, without its BOM, and refuses it when that fails.
            guard var text = String(bytes: bytes[bom...], encoding: encoding) else { return true }
            return text.withUTF8 { areReadable(utf8: UnsafeRawBufferPointer($0)) }
        }
    }

    /// The encoding `JSONDecoder` reads a document in when it is not UTF-8, and the length of its BOM: the decoder's
    /// own rules (RFC 4627: the first two characters are ASCII).
    private static func wideEncoding(_ bytes: UnsafeRawBufferPointer) -> (String.Encoding, Int)? {
        func at(_ index: Int) -> Int { index < bytes.count ? Int(bytes[index]) : -1 }
        switch (at(0), at(1), at(2), at(3)) {
        case (0, 0, 0xFE, 0xFF): return (.utf32BigEndian, 4)
        case (0xFE, 0xFF, 0, 0): return (.utf32LittleEndian, 4)
        case (0xFE, 0xFF, _, _): return (.utf16BigEndian, 2)
        case (0xFF, 0xFE, _, _): return (.utf16LittleEndian, 2)
        case (0xEF, 0xBB, 0xBF, _): return nil
        case let (0, 0, 0, last) where last > 0: return (.utf32BigEndian, 0)
        case let (0, second, 0, last) where second > 0 && last > 0: return (.utf16BigEndian, 0)
        case let (first, 0, 0, 0) where first > 0: return (.utf32LittleEndian, 0)
        case let (first, 0, third, 0) where first > 0 && third > 0: return (.utf16LittleEndian, 0)
        case let (0, second, -1, -1) where second > 0: return (.utf16BigEndian, 0)
        case let (first, 0, -1, -1) where first > 0: return (.utf16LittleEndian, 0)
        default: return nil
        }
    }

    private static let quote = UInt8(ascii: "\""), backslash = UInt8(ascii: "\\"), colon = UInt8(ascii: ":")

    private static func areReadable(utf8 bytes: UnsafeRawBufferPointer) -> Bool {
        // The last string read, until a byte other than whitespace follows it: a `:` makes it a key.
        var string: Range<Int>?
        var index = 0
        while index < bytes.count {
            let byte = bytes[index]
            if byte == quote {
                var end = index + 1
                while end < bytes.count, bytes[end] != quote {
                    end += bytes[end] == backslash ? 2 : 1
                }
                // A string cut short: the decoder refuses the document.
                guard end < bytes.count else { return true }
                string = (index + 1)..<end
                index = end + 1
                continue
            }
            if byte == colon, let key = string, !isReadable(UnsafeRawBufferPointer(rebasing: bytes[key])) { return false }
            if !(byte == 0x20 || byte == 0x0A || byte == 0x0D || byte == 0x09) { string = nil }
            index += 1
        }
        return true
    }

    /// What `JSONDecoder` accepts between a string's quotes: UTF-8 without control characters, and the escapes
    /// `\" \\ \/ \b \f \n \r \t \uXXXX`, a `\u` surrogate only as a whole pair.
    static func isReadable(_ string: UnsafeRawBufferPointer) -> Bool {
        var index = 0
        let count = string.count
        func hex(at start: Int) -> UInt16? {
            guard start + 4 <= count else { return nil }
            var value: UInt16 = 0
            for offset in 0..<4 {
                let byte = string[start + offset]
                let digit: UInt8
                switch byte {
                case UInt8(ascii: "0")...UInt8(ascii: "9"): digit = byte - UInt8(ascii: "0")
                case UInt8(ascii: "a")...UInt8(ascii: "f"): digit = byte - UInt8(ascii: "a") + 10
                case UInt8(ascii: "A")...UInt8(ascii: "F"): digit = byte - UInt8(ascii: "A") + 10
                default: return nil
                }
                value = value << 4 | UInt16(digit)
            }
            return value
        }
        func continuation(_ offset: Int, _ range: ClosedRange<UInt8> = 0x80...0xBF) -> Bool {
            index + offset < count && range.contains(string[index + offset])
        }
        while index < count {
            let byte = string[index]
            switch byte {
            case backslash:
                guard index + 1 < count else { return false }
                switch string[index + 1] {
                case quote, backslash, UInt8(ascii: "/"), UInt8(ascii: "b"), UInt8(ascii: "f"), UInt8(ascii: "n"), UInt8(ascii: "r"), UInt8(ascii: "t"):
                    index += 2
                case UInt8(ascii: "u"):
                    guard let unit = hex(at: index + 2) else { return false }
                    index += 6
                    if UTF16.isLeadSurrogate(unit) {
                        guard index + 1 < count, string[index] == backslash, string[index + 1] == UInt8(ascii: "u"),
                              let trail = hex(at: index + 2), UTF16.isTrailSurrogate(trail) else { return false }
                        index += 6
                    } else if UTF16.isTrailSurrogate(unit) {
                        return false
                    }
                default:
                    return false
                }
            case 0..<0x20:
                return false
            case 0x20..<0x80:
                index += 1
            case 0xC2...0xDF:
                guard continuation(1) else { return false }
                index += 2
            case 0xE0...0xEF:
                let second: ClosedRange<UInt8> = byte == 0xE0 ? 0xA0...0xBF : byte == 0xED ? 0x80...0x9F : 0x80...0xBF
                guard continuation(1, second), continuation(2) else { return false }
                index += 3
            case 0xF0...0xF4:
                let second: ClosedRange<UInt8> = byte == 0xF0 ? 0x90...0xBF : byte == 0xF4 ? 0x80...0x8F : 0x80...0xBF
                guard continuation(1, second), continuation(2), continuation(3) else { return false }
                index += 4
            default:
                // A continuation byte alone, an overlong lead (C0, C1) or a lead past U+10FFFF.
                return false
            }
        }
        return true
    }
}

extension JSONDecoder {
    /// `decode(_:from:)`, for data from outside the app: a document with a key the decoder cannot read is refused with
    /// `DecodingError.dataCorrupted`, as an invalid document is, instead of stopping the app (`JSONKeys`).
    func decodeCheckingKeys<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        guard JSONKeys.areReadable(data) else {
            throw DecodingError.dataCorrupted(DecodingError.Context(codingPath: [], debugDescription: "An object key is not a readable string."))
        }
        return try decode(type, from: data)
    }
}
