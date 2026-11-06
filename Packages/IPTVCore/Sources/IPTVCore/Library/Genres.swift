import Foundation

/// Genres in any language, as one genre each (docs/SYNC.md, "Selections"): a provider's "Aventure", "Abenteuer" and
/// "Adventure" are the genre `adventure`. The genres of TMDb are the canonical ones, each keyed by its English name
/// `TextNormalizer.normalize`d (`adventure`, `science fiction`, `tv movie`), so that a device of an earlier version,
/// which keys a genre by its name, matches the same key on English names. `table` holds them with the names they have
/// in English, French, Spanish, Portuguese, German, Italian, Dutch, Chinese, Arabic and Hindi (`fixtures/genres`, the
/// same file and rules as the Kotlin `Genres`).
///
/// A genre field is split on `,` `/` `|` `&` `;` after its HTML references are decoded ("Sci-Fi &amp; Fantasy" →
/// Science Fiction, Fantasy); a name joining genres ("Action-Adventure", "Acción y Aventura") gives each of them when
/// each is known. A genre not in the table keeps its own key: its name normalised, each word's plural "s" left out
/// ("Érotiques" and "Erotique" → `erotique`).
public enum Genres {
    /// One line per genre: its English name, then the names that mean it. Same text as `fixtures/genres/table.txt`.
    public static let table: String = """
Action|action|actions|accion|acao|azione|actie|actionfilm|action film|动作|أكشن|اكشن|حركة|एक्शन
Adventure|adventure|adventures|aventure|aventures|aventura|aventuras|abenteuer|abenteuerfilm|avventura|avventure|avontuur|冒险|مغامرة|مغامرات|साहसिक|एडवेंचर
Animation|animation|animations|animation adulte|adult animation|animacion|animacao|animazione|animatie|animatiefilm|animationsfilm|zeichentrick|dessin anime|dessins animes|cartoon|cartoons|动画|رسوم متحركة|انيميشن|एनिमेशन
Comedy|comedy|comedies|comedie|comedia|comedias|komodie|commedia|komedie|humour|humor|喜剧|كوميديا|كوميدي|कॉमेडी|हास्य
Crime|crime|crimes|crimen|policier|policiers|polar|polars|policial|krimi|kriminalfilm|crimine|poliziesco|misdaad|犯罪|جريمة|अपराध
Documentary|documentary|documentaries|documentaire|documentaires|documentaire serie|documentaires series|documental|documentales|documentario|documentarios|documentari|dokumentarfilm|dokumentation|dokumentarserie|doku|docu|docuseries|docu serie|纪录|纪录片|وثائقي|وثائقية|वृत्तचित्र|डॉक्यूमेंट्री
Drama|drama|dramas|drame|drames|dramma|剧情|دراما|ड्रामा|नाटक
Family|family|familial|familiale|famille|divertissement familial|film familial|familia|familiar|familie|familienfilm|famiglia|familiefilm|家庭|عائلي|عائلة|पारिवारिक
Fantasy|fantasy|fantastique|fantastic|fantasia|fantasie|fantastico|fantastisch|奇幻|فانتازيا|خيالي|फ़ैंटेसी|फंतासी
History|history|historical|histoire|historique|historia|historico|historisch|historienfilm|storia|storico|geschiedenis|历史|تاريخ|تاريخي|इतिहास|ऐतिहासिक
Horror|horror|horreur|epouvante|epouvante horreur|terror|terreur|orrore|恐怖|رعب|हॉरर|डरावनी
Music|music|musique|musical|musicals|comedie musicale|musica|musik|musikfilm|musicale|muziek|音乐|موسيقى|موسيقي|موسيقية|संगीत
Mystery|mystery|mysteries|mystere|misterio|mistero|mysterie|悬疑|غموض|रहस्य
Romance|romance|romances|romantic|romantique|romantico|romantica|romantik|liebesfilm|romantiek|sentimentale|爱情|رومانسي|رومانسية|रोमांस
Science Fiction|science fiction|sciencefiction|sci fi|scifi|sf|ciencia ficcion|ficcao cientifica|fantascienza|科幻|خيال علمي|साइंस फ़िक्शन|विज्ञान कथा
Thriller|thriller|thrillers|suspense|suspenso|惊悚|إثارة|اثارة|ثريلر|थ्रिलर|रोमांचक
War|war|guerre|guerra|belica|belico|krieg|kriegsfilm|oorlog|战争|حرب|حربي|युद्ध
Western|western|westerns|oeste|faroeste|西部|غربي|ويسترن|वेस्टर्न
Kids|kids|kid|children|enfants|enfant|jeunesse|infantil|ninos|criancas|kinder|bambini|kinderen|儿童|أطفال|बच्चे
News|news|actualites|actualite|informations|noticias|nachrichten|notizie|nieuws|新闻|أخبار|اخبار|समाचार
Reality|reality|reality tv|reality show|telerealite|realite|realidad|realityshow|真人秀|واقع|تلفزيون الواقع|रियलिटी
Soap|soap|soaps|soap opera|feuilleton|telenovela|novela|seifenoper|肥皂剧|صابون|सोप ओपेरा
Talk|talk|talk show|talkshow|entrevistas|脱口秀|حواري|टॉक शो
Politics|politics|political|politique|politica|politico|politik|politiek|政治|سياسة|سياسي|राजनीति
TV Movie|tv movie|tv film|telefilm|telefilms|pelicula de tv|filme para tv|fernsehfilm|film tv|televisiefilm|电视电影|فيلم تلفزيوني|टीवी फ़िल्म
"""

    /// The longest genre name kept: a field without separators in a long run of text is no genre.
    public static let maxLength = 200

    private struct Index {
        var canonical: [String] = []
        var canonicalSet: Set<String> = []
        var synonyms: [String: String] = [:]
    }

    private static let index: Index = {
        var built = Index()
        for line in table.split(separator: "\n") {
            let parts: [Substring] = line.split(separator: "|", omittingEmptySubsequences: false)
            guard parts.count >= 2 else { continue }
            let key: String = TextNormalizer.normalize(String(parts[0]))
            built.canonical.append(key)
            built.canonicalSet.insert(key)
            built.synonyms[key] = key
            for name in parts.dropFirst() {
                let normalized: String = TextNormalizer.normalize(String(name))
                if !normalized.isEmpty && built.synonyms[normalized] == nil { built.synonyms[normalized] = key }
            }
        }
        return built
    }()

    /// The canonical genres' keys, in the order of `table`.
    public static var canonical: [String] { index.canonical }

    private static let qualifiers: Set<String> = ["serie", "series", "film", "films", "movie", "movies", "tv", "show", "shows"]
    private static let conjunctions: Set<String> = ["and", "et", "y", "e", "und", "en", "ed"]

    /// Whether `key` is one of TMDb's genres (its name shows in the viewer's language).
    public static func isCanonical(_ key: String) -> Bool { index.canonicalSet.contains(key) }

    /// The names of a genre field ("Drama, Thriller", "Action &amp; Adventure"), decoded and trimmed, each once.
    public static func split(_ field: String?) -> [String] {
        guard let field, !field.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
        let decoded: String = HtmlEntities.decode(field)
        var seen = Set<String>()
        var names: [String] = []
        for part in decoded.split(omittingEmptySubsequences: false, whereSeparator: { ",/|&;".contains($0) }) {
            let name: String = part.trimmingCharacters(in: .whitespacesAndNewlines)
            let length = name.utf16.count
            if length >= 1 && length <= maxLength && seen.insert(name).inserted { names.append(name) }
        }
        return names
    }

    /// The genre keys of a genre field, each once, in their order.
    public static func keys(_ field: String?) -> [String] {
        var seen = Set<String>()
        var result: [String] = []
        for name in split(field) {
            for key in keys(ofName: name) where seen.insert(key).inserted { result.append(key) }
        }
        return result
    }

    /// The keys of one genre's name: its canonical genre, the genres it joins, or its own key.
    static func keys(ofName name: String) -> [String] {
        let normalized: String = withoutReference(TextNormalizer.normalize(name))
        // A reference's name alone ("amp; Fantastique" split on its ";") is no genre.
        if normalized.isEmpty || references.contains(normalized) { return [] }
        if let key = lookup(normalized) { return [key] }
        var words: [String] = normalized.split(separator: " ").map { String($0) }
        while words.count > 1, let last = words.last, qualifiers.contains(last) { words.removeLast() }
        if let key = lookup(words.joined(separator: " ")) { return [key] }
        if words.contains(where: { conjunctions.contains($0) }) {
            var groups: [String] = []
            var current: [String] = []
            for word in words {
                if conjunctions.contains(word) {
                    if !current.isEmpty { groups.append(current.joined(separator: " ")) }
                    current = []
                } else {
                    current.append(word)
                }
            }
            if !current.isEmpty { groups.append(current.joined(separator: " ")) }
            if groups.count >= 2, let found = allKnown(groups) { return found }
        }
        if words.count >= 2, let found = allKnown(words) { return found }
        return [folded(normalized)]
    }

    /// The keys of each of `names`, each once; nil when one of them is no known genre.
    private static func allKnown(_ names: [String]) -> [String]? {
        var result: [String] = []
        for name in names {
            guard let key = lookup(name) else { return nil }
            if !result.contains(key) { result.append(key) }
        }
        return result
    }

    private static let references: Set<String> = ["amp", "quot", "apos", "lt", "gt", "nbsp", "39"]

    /// Without the name of a reference left at its start: an earlier version split "Action &amp; Adventure" on its `&`,
    /// keeping "amp; Adventure" (`amp adventure`).
    private static func withoutReference(_ normalized: String) -> String {
        guard let space = normalized.firstIndex(of: " "), space > normalized.startIndex else { return normalized }
        guard references.contains(String(normalized[..<space])) else { return normalized }
        return String(normalized[normalized.index(after: space)...])
    }

    private static func lookup(_ normalized: String) -> String? {
        index.synonyms[normalized] ?? index.synonyms[folded(normalized)]
    }

    /// Each word without its plural "s" (a word of more than three letters, not ending in "ss").
    private static func folded(_ normalized: String) -> String {
        var words: [String] = []
        for word in normalized.split(separator: " ", omittingEmptySubsequences: false) {
            if word.utf16.count > 3 && word.hasSuffix("s") && !word.hasSuffix("ss") {
                words.append(String(word.dropLast()))
            } else {
                words.append(String(word))
            }
        }
        return words.joined(separator: " ")
    }

    /// A selection's genre tag as this version keys it: `genre:aventure` (saved by an earlier version) is
    /// `genre:adventure`; `genre:action adventure` is both genres. Other tags as they are.
    public static func canonicalTags(_ tag: String) -> [String] {
        guard tag.hasPrefix(Selections.genrePrefix) else { return [tag] }
        let keys = keys(ofName: String(tag.dropFirst(Selections.genrePrefix.count)))
        return keys.isEmpty ? [tag] : keys.map { Selections.genrePrefix + $0 }
    }

    /// Tags as this version keys them (`canonicalTags(_:)`), each once, in their order.
    public static func canonicalTags(_ tags: [String]) -> [String] {
        var seen = Set<String>()
        var result: [String] = []
        for tag in tags {
            for canonical in canonicalTags(tag) where seen.insert(canonical).inserted { result.append(canonical) }
        }
        return result
    }

    /// The genre keys a genre tag needs (all of them), or nil for another tag.
    static func required(_ tag: String) -> [String]? {
        guard tag.hasPrefix(Selections.genrePrefix) else { return nil }
        let key = String(tag.dropFirst(Selections.genrePrefix.count))
        if index.canonicalSet.contains(key) { return [key] }
        return keys(ofName: key)
    }
}
