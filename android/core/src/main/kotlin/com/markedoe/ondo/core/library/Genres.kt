package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.HtmlEntities
import com.markedoe.ondo.core.TextNormalizer

/**
 * Genres in any language, as one genre each (docs/SYNC.md, "Selections"): a provider's "Aventure", "Abenteuer" and
 * "Adventure" are the genre `adventure`. The genres of TMDb are the canonical ones, each keyed by its English name
 * [TextNormalizer.normalize]d (`adventure`, `science fiction`, `tv movie`), so that a device of an earlier version,
 * which keys a genre by its name, matches the same key on English names. [TABLE] holds them with the names they have
 * in English, French, Spanish, Portuguese, German, Italian, Dutch, Chinese, Arabic and Hindi (`fixtures/genres`, the
 * same file and rules as the Swift `Genres`).
 *
 * A genre field is split on `,` `/` `|` `&` `;` after its HTML references are decoded ("Sci-Fi &amp; Fantasy" →
 * Science Fiction, Fantasy); a name joining genres ("Action-Adventure", "Acción y Aventura") gives each of them when
 * each is known. A genre not in the table keeps its own key: its name normalised, each word's plural "s" left out
 * ("Érotiques" and "Erotique" → `erotique`).
 */
object Genres {
    /** A line per genre (or a few): its English name, then the names that mean it. Same text as `fixtures/genres/table.txt`. */
    const val TABLE = """
Action|action|actions|accion|acao|azione|actie|actionfilm|action film|动作|أكشن|اكشن|حركة|एक्शन
Adventure|adventure|adventures|aventure|aventures|aventura|aventuras|abenteuer|abenteuerfilm|avventura|avventure|avontuur|冒险|مغامرة|مغامرات|साहसिक
Adventure|एडवेंचर
Animation|animation|animations|animation adulte|adult animation|animacion|animacao|animazione|animatie|animatiefilm|animationsfilm|zeichentrick
Animation|dessin anime|dessins animes|cartoon|cartoons|动画|رسوم متحركة|انيميشن|एनिमेशन
Comedy|comedy|comedies|comedie|comedia|comedias|komodie|commedia|komedie|humour|humor|喜剧|كوميديا|كوميدي|कॉमेडी|हास्य
Crime|crime|crimes|crimen|policier|policiers|polar|polars|policial|krimi|kriminalfilm|crimine|poliziesco|misdaad|犯罪|جريمة|अपराध
Documentary|documentary|documentaries|documentaire|documentaires|documentaire serie|documentaires series|documental|documentales|documentario
Documentary|documentarios|documentari|dokumentarfilm|dokumentation|dokumentarserie|doku|docu|docuseries|docu serie|纪录|纪录片|وثائقي|وثائقية|वृत्तचित्र
Documentary|डॉक्यूमेंट्री
Drama|drama|dramas|drame|drames|dramma|剧情|دراما|ड्रामा|नाटक
Family|family|familial|familiale|famille|divertissement familial|film familial|familia|familiar|familie|familienfilm|famiglia|familiefilm|家庭|عائلي
Family|عائلة|पारिवारिक
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

    /** The longest genre name kept: a field without separators in a long run of text is no genre. */
    const val MAX_LENGTH = 200

    /** The canonical genres' keys, in the order of [TABLE]. */
    val canonical: List<String>

    private val canonicalSet: Set<String>
    private val synonyms: Map<String, String>

    private val qualifiers = setOf("serie", "series", "film", "films", "movie", "movies", "tv", "show", "shows")
    private val conjunctions = setOf("and", "et", "y", "e", "und", "en", "ed")

    init {
        val keys = ArrayList<String>()
        val names = HashMap<String, String>()
        for (line in TABLE.lines()) {
            val parts = line.split('|')
            if (parts.size < 2) continue
            val key = TextNormalizer.normalize(parts[0])
            // A genre with many names goes on over several lines.
            if (key !in keys) keys += key
            names[key] = key
            for (name in parts.drop(1)) {
                val normalized = TextNormalizer.normalize(name)
                if (normalized.isNotEmpty()) names.putIfAbsent(normalized, key)
            }
        }
        canonical = keys
        canonicalSet = keys.toHashSet()
        synonyms = names
    }

    /** Whether [key] is one of TMDb's genres (its name shows in the viewer's language). */
    fun isCanonical(key: String): Boolean = key in canonicalSet

    /** The names of a genre field ("Drama, Thriller", "Action &amp; Adventure"), decoded and trimmed, each once. */
    fun split(field: String?): List<String> {
        if (field.isNullOrBlank()) return emptyList()
        return HtmlEntities.decode(field).split(',', '/', '|', '&', ';').map { it.trim() }.filter { it.length in 1..MAX_LENGTH }.distinct()
    }

    /** The genre keys of a genre field, each once, in their order. */
    fun keys(field: String?): List<String> {
        val result = LinkedHashSet<String>()
        for (name in split(field)) result += keysOfName(name)
        return result.toList()
    }

    /** The keys of one genre's name: its canonical genre, the genres it joins, or its own key. */
    private fun keysOfName(name: String): List<String> {
        val normalized = withoutReference(TextNormalizer.normalize(name))
        // A reference's name alone ("amp; Fantastique" split on its ";") is no genre.
        if (normalized.isEmpty() || normalized in references) return emptyList()
        lookup(normalized)?.let { return listOf(it) }
        var words = normalized.split(' ')
        while (words.size > 1 && words.last() in qualifiers) words = words.dropLast(1)
        lookup(words.joinToString(" "))?.let { return listOf(it) }
        if (words.any { it in conjunctions }) {
            val groups = ArrayList<String>()
            var current = ArrayList<String>()
            for (word in words) {
                if (word in conjunctions) {
                    if (current.isNotEmpty()) groups += current.joinToString(" ")
                    current = ArrayList()
                } else {
                    current += word
                }
            }
            if (current.isNotEmpty()) groups += current.joinToString(" ")
            val found = groups.map { lookup(it) }
            if (groups.size >= 2 && found.all { it != null }) return found.filterNotNull().distinct()
        }
        if (words.size >= 2) {
            val found = words.map { lookup(it) }
            if (found.all { it != null }) return found.filterNotNull().distinct()
        }
        return listOf(folded(normalized))
    }

    /**
     * Without the name of a reference left at its start: an earlier version split "Action &amp; Adventure" on its `&`,
     * keeping "amp; Adventure" (`amp adventure`).
     */
    private fun withoutReference(normalized: String): String {
        val space = normalized.indexOf(' ')
        return if (space > 0 && normalized.substring(0, space) in references) normalized.substring(space + 1) else normalized
    }

    private val references = setOf("amp", "quot", "apos", "lt", "gt", "nbsp", "39")

    private fun lookup(normalized: String): String? = synonyms[normalized] ?: synonyms[folded(normalized)]

    /** Each word without its plural "s" (a word of more than three letters, not ending in "ss"). */
    private fun folded(normalized: String): String = normalized.split(' ').joinToString(" ") { word ->
        if (word.length > 3 && word.endsWith('s') && !word.endsWith("ss")) word.dropLast(1) else word
    }

    /**
     * A selection's genre tag as this version keys it: `genre:aventure` (saved by an earlier version) is
     * `genre:adventure`; `genre:action adventure` is both genres. Other tags as they are.
     */
    fun canonicalTags(tag: String): List<String> {
        if (!tag.startsWith(Selections.GENRE)) return listOf(tag)
        val keys = keysOfName(tag.substring(Selections.GENRE.length))
        return if (keys.isEmpty()) listOf(tag) else keys.map { Selections.GENRE + it }
    }

    /** Tags as this version keys them ([canonicalTags]), each once, in their order. */
    fun canonicalTags(tags: List<String>): List<String> = tags.flatMapTo(LinkedHashSet()) { canonicalTags(it) }.toList()

    /** The genre keys a genre tag needs (all of them), or null for another tag. */
    internal fun required(tag: String): List<String>? {
        if (!tag.startsWith(Selections.GENRE)) return null
        val key = tag.substring(Selections.GENRE.length)
        if (key in canonicalSet) return listOf(key)
        return keysOfName(key)
    }
}
