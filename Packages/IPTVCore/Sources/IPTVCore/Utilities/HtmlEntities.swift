import Foundation

/// The HTML character references some providers leave in their text ("Action &amp; Adventure", "Com&eacute;die",
/// "L&#39;Odyssée"): `&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`, `&nbsp;`, the accented Latin letters (`&eacute;`…),
/// a few marks of punctuation, and numeric ones (`&#39;`, `&#x27;`). Text escaped twice (`&amp;amp;`) is decoded again,
/// up to `passes` times. A reference this does not know stays as it is. The same rules as the Kotlin `HtmlEntities`.
public enum HtmlEntities {
    private static let passes = 3

    /// The longest name looked at after an `&`.
    private static let longest = 10

    private static let named: [String: String] = {
        var table: [String: String] = [
            "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ",
            "szlig": "ß", "aelig": "æ", "AElig": "Æ", "oelig": "œ", "OElig": "Œ", "oslash": "ø", "Oslash": "Ø",
            "hellip": "…", "lsquo": "‘", "rsquo": "’", "ldquo": "“", "rdquo": "”", "laquo": "«", "raquo": "»",
            "ndash": "–", "mdash": "—",
        ]
        // Accented letters: a letter and its mark, composed.
        let marks: [(String, String)] = [
            ("acute", "\u{0301}"), ("grave", "\u{0300}"), ("circ", "\u{0302}"), ("uml", "\u{0308}"),
            ("tilde", "\u{0303}"), ("cedil", "\u{0327}"), ("ring", "\u{030A}"),
        ]
        for letter in "aeiouycnAEIOUYCN" {
            for (name, mark) in marks {
                let composed: String = (String(letter) + mark).precomposedStringWithCanonicalMapping
                if composed.utf16.count == 1 { table[String(letter) + name] = composed }
            }
        }
        return table
    }()

    /// `text` with its character references decoded.
    public static func decode(_ text: String) -> String {
        var result = text
        for _ in 0..<passes {
            guard result.contains("&") else { return result }
            let next = decodeOnce(result)
            if next == result { return result }
            result = next
        }
        return result
    }

    private static func decodeOnce(_ text: String) -> String {
        let units: [UInt16] = Array(text.utf16)
        var out: [UInt16] = []
        out.reserveCapacity(units.count)
        let amp: UInt16 = 0x26
        let semicolon: UInt16 = 0x3B
        var index = 0
        while index < units.count {
            let unit = units[index]
            if unit != amp {
                out.append(unit)
                index += 1
                continue
            }
            var end = -1
            var probe = index + 1
            while probe < units.count {
                if units[probe] == semicolon { end = probe; break }
                probe += 1
            }
            var reference: String?
            if end > index + 1 && end - index - 1 <= longest {
                let name = String(decoding: units[(index + 1)..<end], as: UTF16.self)
                reference = resolve(name)
            }
            if let reference {
                out.append(contentsOf: Array(reference.utf16))
                index = end + 1
            } else {
                out.append(unit)
                index += 1
            }
        }
        return String(decoding: out, as: UTF16.self)
    }

    private static func resolve(_ name: String) -> String? {
        guard name.hasPrefix("#") else { return named[name] }
        let body = name.dropFirst()
        let hex = body.hasPrefix("x") || body.hasPrefix("X")
        let digits = hex ? String(body.dropFirst()) : String(body)
        guard !digits.isEmpty, digits.count <= 7, let value = Int(digits, radix: hex ? 16 : 10) else { return nil }
        // No control character, no surrogate, nothing past Unicode.
        if value < 0x20 && value != 0x09 && value != 0x0A && value != 0x0D { return nil }
        if (0xD800...0xDFFF).contains(value) || value > 0x10FFFF || value == 0x7F { return nil }
        guard let scalar = Unicode.Scalar(UInt32(value)) else { return nil }
        return String(Character(scalar))
    }
}
