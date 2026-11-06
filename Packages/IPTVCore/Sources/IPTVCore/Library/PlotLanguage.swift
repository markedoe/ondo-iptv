import Foundation

/// The language a provider's description (plot) of a title is written in, told by its most common little words ("el",
/// "não", "the") or by its script (Arabic, Devanagari, Chinese), for hidden languages: a title of a category with no
/// language tag ("Netflix") whose description is in a hidden language is hidden too (`CategoryTags.isHiddenTitle`).
/// Only the provider's description counts: TMDb's comes in the app's language. `table` holds the words and the
/// category tags of each language (`fixtures/plot-language`, the same file and rules as the Kotlin `PlotLanguage`); a
/// word of two languages would tell neither, so each word belongs to one.
///
/// Made for a whole catalogue (100k titles): one pass over the first `scanned` scalars, each word folded and packed
/// into a number as it is read (no string per word), looked up in a table of numbers.
public enum PlotLanguage {
    /// A line per language and kind: its code, `tags` or `words`, then the items. Same text as `fixtures/plot-language/table.txt`.
    public static let table: String = """
es|tags|ES|ESP|SPA|LAT|LATINO|LATAM|MX|CAST
es|words|el|los|las|del|unos|unas|pero|muy|están|su|sus|cuando|porque|y|es|al|hasta|donde|tras
es|words|años|él|ella|ellos|sin|hacia|después|hija|hijo|nueva|nuevo|hace|tiene|había
pt|tags|PT|BR|POR|PTBR|PT-BR
pt|words|não|um|uma|vai|pai|mãe|umas|dos|também|ao|aos|em|seu|seus|suas|você|com|nos|nas|pelo|pela|mas
pt|words|muito|ele|ela|eles|até|depois|sem|são|já|foi|isso|anos|filha|filho|nova|novo|tem|uns
fr|tags|FR|FRA|VF|VFF|VFQ
fr|words|le|les|une|est|dans|pour|qui|avec|sur|ses|aux|du|au|ce|cette|mais|pas|plus|elle|ils|leur|être
fr|words|où|très|après|tout|sont|lui|ans|fille|fils
en|tags|EN|ENG|UK|US
en|words|the|and|of|to|in|is|his|her|with|that|for|on|are|who|when|their|an|by|from|this|after|they|she|he|has
en|words|be|was|but|its|into|while|years|new|life
it|tags|IT|ITA
it|words|gli|della|delle|dei|nel|nella|che|non|per|sono|anche|suo|più|dopo|questo|molto
it|words|alla|degli|è|ma|anni|figlia|figlio|nuova|nuovo|essere
de|tags|DE|GER|DEU
de|words|der|die|und|ist|nicht|mit|sich|auf|ein|eine|dem|den|für|von|zu|im|sie|er|nach|wird|aber|auch
de|words|einen|seine|ihre|jahre|tochter|sohn|neue|sein|wie|als
nl|tags|NL
nl|words|het|een|van|niet|zijn|met|voor|op|dat|maar|ook|naar|hij|zij|wordt|worden|haar|jaar|dochter|zoon|nieuwe
tr|tags|TR|TUR
tr|words|bir|ve|bu|için|ile|ama|çok|olan|olarak|gibi|daha|sonra|onun|kendi|ancak|değil|kızı|oğlu|yeni|yıl
pl|tags|PL|POL
pl|words|w|z|się|nie|jest|że|jak|ale|po|od|jego|jej|który|która|są|przez|dla|oraz|córka|syn|nowy|lat
ar|tags|AR|ARA|ARAB
hi|tags|HI|IN|HIN
zh|tags|ZH|CN|CHI
"""

    /// Scalars of a description read: its start tells its language.
    public static let scanned = 400

    /// Words a description needs, and words of one language, before its language is told.
    private static let minimumWords = 8
    private static let minimumHits = 3

    /// The longest word of the table: seven letters of 9 bits each fit in 64 bits.
    private static let longestWord = 7

    private struct Table {
        var languages: [String] = []
        var tags: [String: Set<String>] = [:]
        var languageOfTag: [String: String] = [:]
        /// A packed word (`key(_:)`) and its language's index.
        var owners: [UInt64: Int] = [:]
    }

    private static let parsed: Table = {
        var result = Table()
        for line in table.split(separator: "\n") {
            let parts = line.trimmingCharacters(in: .whitespaces).split(separator: "|", omittingEmptySubsequences: false).map(String.init)
            guard parts.count >= 3 else { continue }
            let language = parts[0]
            if !result.languages.contains(language) { result.languages.append(language) }
            let items = parts.dropFirst(2)
            switch parts[1] {
            case "tags":
                result.tags[language, default: []].formUnion(items)
                for tag in items { result.languageOfTag[tag] = language }
            case "words":
                let index = result.languages.firstIndex(of: language) ?? 0
                for word in items {
                    guard let key = key(word) else { preconditionFailure("not a word the scan reads: \(word)") }
                    precondition(result.owners[key] == nil, "a word of two languages: \(word)")
                    result.owners[key] = index
                }
            default:
                continue
            }
        }
        return result
    }()

    /// The languages, in the table's order.
    public static var languages: [String] { parsed.languages }

    /// The category tags that stand for each language ("es": ES, ESP, LAT…).
    public static var tags: [String: Set<String>] { parsed.tags }

    /// Whether a category tag stands for a language of the table ("LAT", "VF"), not for a label ("VIP", "HBO").
    public static func isLanguageTag(_ tag: String) -> Bool { parsed.languageOfTag[tag] != nil }

    /// The languages one of whose tags is hidden ("LAT" hidden: "es"); empty when none is.
    public static func hiddenLanguages(_ hiddenTags: Set<String>) -> Set<String> {
        guard !hiddenTags.isEmpty else { return [] }
        let languageOfTag = parsed.languageOfTag
        return Set(hiddenTags.compactMap { languageOfTag[$0] })
    }

    /// The language of a description ("es"), or nil when it is too short or not clear: at least `minimumWords` words,
    /// at least `minimumHits` words of one language and twice as many as of any other; or mostly Arabic, Devanagari or
    /// Chinese letters (Japanese kana leave it unknown).
    public static func detect(_ text: String?) -> String? {
        guard let text, !text.isEmpty else { return nil }
        let table = parsed
        var hits = [Int](repeating: 0, count: table.languages.count)
        var words = 0
        var key: UInt64 = 0
        var readable = true
        var length = 0
        var latin = 0
        var arabic = 0
        var devanagari = 0
        var han = 0
        var kana = 0
        var count = 0
        var cut = false

        func endWord() {
            guard length > 0 else { return }
            words += 1
            if readable, let owner = table.owners[key] { hits[owner] += 1 }
            key = 0
            readable = true
            length = 0
        }

        for scalar in text.unicodeScalars {
            if count == scanned {
                cut = true
                break
            }
            count += 1
            let point: UInt32 = scalar.value
            if isLatin(point) {
                latin += 1
                let folded: UInt32 = fold(point)
                if readable && length < longestWord && folded < 0x200 {
                    key = (key << 9) | UInt64(folded)
                } else {
                    readable = false
                }
                length += 1
                continue
            }
            endWord()
            switch point {
            case 0x600...0x6FF, 0x750...0x77F, 0x8A0...0x8FF: arabic += 1
            case 0x900...0x97F: devanagari += 1
            case 0x3040...0x30FF: kana += 1
            case 0x3400...0x4DBF, 0x4E00...0x9FFF: han += 1
            default: break
            }
        }
        // A word cut by the limit is not read.
        if !cut { endWord() }
        if arabic >= 20 && arabic > latin { return "ar" }
        if devanagari >= 20 && devanagari > latin { return "hi" }
        if han >= 10 && kana == 0 && han > latin { return "zh" }
        guard words >= minimumWords, !hits.isEmpty else { return nil }
        var best = 0
        for language in hits.indices where hits[language] > hits[best] { best = language }
        var second = 0
        for language in hits.indices where language != best && hits[language] > second { second = hits[language] }
        let top: Int = hits[best]
        guard top >= minimumHits, top >= 2 * second else { return nil }
        return table.languages[best]
    }

    /// A word packed as the scan packs it: 9 bits per folded letter; nil when the scan cannot read it.
    static func key(_ word: String) -> UInt64? {
        var key: UInt64 = 0
        var length = 0
        for scalar in word.unicodeScalars {
            let folded: UInt32 = fold(scalar.value)
            guard isLatin(scalar.value), folded < 0x200, length < longestWord else { return nil }
            key = (key << 9) | UInt64(folded)
            length += 1
        }
        return length == 0 ? nil : key
    }

    /// Latin letters: ASCII, Latin-1 and Latin Extended-A and B (not × and ÷).
    static func isLatin(_ point: UInt32) -> Bool {
        (0x41...0x5A).contains(point) || (0x61...0x7A).contains(point)
            || ((0xC0...0x24F).contains(point) && point != 0xD7 && point != 0xF7)
    }

    /// Lower case, the same on every platform: ASCII, Latin-1 and Latin Extended-A by their code points.
    static func fold(_ point: UInt32) -> UInt32 {
        switch point {
        case 0x41...0x5A: return point + 32
        case 0xC0...0xDE where point != 0xD7: return point + 32
        case 0x100...0x137 where point % 2 == 0: return point + 1
        case 0x139...0x148 where point % 2 == 1: return point + 1
        case 0x14A...0x177 where point % 2 == 0: return point + 1
        case 0x179...0x17E where point % 2 == 1: return point + 1
        default: return point
        }
    }
}
