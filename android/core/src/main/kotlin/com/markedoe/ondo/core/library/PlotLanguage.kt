package com.markedoe.ondo.core.library

/**
 * The language a provider's description (plot) of a title is written in, told by its most common little words ("el",
 * "não", "the") or by its script (Arabic, Devanagari, Chinese), for hidden languages: a title of a category with no
 * language tag ("Netflix") whose description is in a hidden language is hidden too ([CategoryTags.isHiddenTitle]).
 * Only the provider's description counts: TMDb's comes in the app's language. [TABLE] holds the words and the category
 * tags of each language (`fixtures/plot-language`, the same file and rules as the Swift `PlotLanguage`); a word of two
 * languages would tell neither, so each word belongs to one.
 *
 * Made for a whole catalogue (100k titles): one pass over the first [SCANNED] characters, each word folded and packed
 * into a number as it is read (no string per word), looked up in a table of numbers.
 */
object PlotLanguage {
    /** A line per language and kind: its code, `tags` or `words`, then the items. Same text as `fixtures/plot-language/table.txt`. */
    const val TABLE = """
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

    /** Characters (code points) of a description read: its start tells its language. */
    const val SCANNED = 400

    /** Words a description needs, and words of one language, before its language is told. */
    private const val MIN_WORDS = 8
    private const val MIN_HITS = 3

    /** The longest word of the table: seven letters of 9 bits each fit in a Long. */
    private const val MAX_WORD = 7

    /** The languages, in the table's order. */
    val languages: List<String>

    /** The category tags that stand for each language ("es": ES, ESP, LAT…). */
    val tags: Map<String, Set<String>>
    private val languageOfTag: Map<String, String>

    /** Open addressing: a packed word ([key]) and its language's index plus one; 0 is an empty slot. */
    private val slots: LongArray
    private val owners: IntArray
    private val mask: Int

    init {
        val order = ArrayList<String>()
        val tagged = LinkedHashMap<String, MutableSet<String>>()
        val words = ArrayList<Pair<String, Int>>()
        for (line in TABLE.lines()) {
            val parts = line.trim().split('|')
            if (parts.size < 3) continue
            val language = parts[0]
            if (language !in order) order += language
            when (parts[1]) {
                "tags" -> tagged.getOrPut(language) { LinkedHashSet() } += parts.drop(2)
                "words" -> parts.drop(2).forEach { words += it to order.indexOf(language) }
            }
        }
        languages = order
        tags = tagged
        languageOfTag = tagged.flatMap { (language, list) -> list.map { it to language } }.toMap()
        var capacity = 16
        while (capacity < words.size * 4) capacity *= 2
        slots = LongArray(capacity)
        owners = IntArray(capacity)
        mask = capacity - 1
        for ((word, language) in words) {
            val key = key(word)
            require(key > 0) { "not a word the scan reads: $word" }
            var slot = slot(key)
            while (slots[slot] != 0L && slots[slot] != key) slot = (slot + 1) and mask
            require(slots[slot] == 0L) { "a word of two languages: $word" }
            slots[slot] = key
            owners[slot] = language + 1
        }
    }

    /** Whether a category tag stands for a language of the table ("LAT", "VF"), not for a label ("VIP", "HBO"). */
    fun isLanguageTag(tag: String): Boolean = tag in languageOfTag

    /** The languages one of whose tags is hidden ("LAT" hidden: "es"); empty when none is. */
    fun hiddenLanguages(hiddenTags: Set<String>): Set<String> {
        if (hiddenTags.isEmpty()) return emptySet()
        return hiddenTags.mapNotNullTo(LinkedHashSet()) { languageOfTag[it] }
    }

    /**
     * The language of a description ("es"), or null when it is too short or not clear: at least [MIN_WORDS] words, at
     * least [MIN_HITS] words of one language and twice as many as of any other; or mostly Arabic, Devanagari or Chinese
     * letters (Japanese kana leave it unknown).
     */
    fun detect(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val hits = IntArray(languages.size)
        var words = 0
        var key = 0L
        var length = 0
        var latin = 0
        var arabic = 0
        var devanagari = 0
        var han = 0
        var kana = 0
        var index = 0
        var scanned = 0
        while (index < text.length && scanned < SCANNED) {
            val point = text.codePointAt(index)
            index += Character.charCount(point)
            scanned++
            if (isLatin(point)) {
                latin++
                val folded = fold(point)
                key = if (key >= 0 && length < MAX_WORD && folded < 0x200) (key shl 9) or folded.toLong() else -1
                length++
                continue
            }
            if (length > 0) {
                words++
                count(key, hits)
                key = 0
                length = 0
            }
            when (point) {
                in 0x600..0x6FF, in 0x750..0x77F, in 0x8A0..0x8FF -> arabic++
                in 0x900..0x97F -> devanagari++
                in 0x3040..0x30FF -> kana++
                in 0x3400..0x4DBF, in 0x4E00..0x9FFF -> han++
            }
        }
        // A word cut by the limit is not read.
        if (length > 0 && index >= text.length) {
            words++
            count(key, hits)
        }
        if (arabic >= 20 && arabic > latin) return "ar"
        if (devanagari >= 20 && devanagari > latin) return "hi"
        if (han >= 10 && kana == 0 && han > latin) return "zh"
        if (words < MIN_WORDS) return null
        var best = 0
        for (language in hits.indices) if (hits[language] > hits[best]) best = language
        var second = 0
        for (language in hits.indices) if (language != best && hits[language] > second) second = hits[language]
        val top = hits[best]
        if (top < MIN_HITS || top < 2 * second) return null
        return languages[best]
    }

    private fun count(key: Long, hits: IntArray) {
        if (key <= 0) return
        var slot = slot(key)
        while (true) {
            val found = slots[slot]
            if (found == 0L) return
            if (found == key) {
                hits[owners[slot] - 1]++
                return
            }
            slot = (slot + 1) and mask
        }
    }

    private fun slot(key: Long): Int = ((key * -0x61c8864680b583ebL) ushr 40).toInt() and mask

    /** A word packed as the scan packs it: 9 bits per folded letter; -1 when the scan cannot read it. */
    internal fun key(word: String): Long {
        var key = 0L
        var length = 0
        var index = 0
        while (index < word.length) {
            val point = word.codePointAt(index)
            index += Character.charCount(point)
            val folded = fold(point)
            if (!isLatin(point) || folded >= 0x200 || length >= MAX_WORD) return -1
            key = (key shl 9) or folded.toLong()
            length++
        }
        return if (length == 0) -1 else key
    }

    /** Latin letters: ASCII, Latin-1 and Latin Extended-A and B (not × and ÷). */
    internal fun isLatin(point: Int): Boolean =
        point in 0x41..0x5A || point in 0x61..0x7A || (point in 0xC0..0x24F && point != 0xD7 && point != 0xF7)

    /** Lower case, the same on every platform: ASCII, Latin-1 and Latin Extended-A by their code points. */
    internal fun fold(point: Int): Int = when {
        point in 0x41..0x5A -> point + 32
        point in 0xC0..0xDE && point != 0xD7 -> point + 32
        point in 0x100..0x137 && point % 2 == 0 -> point + 1
        point in 0x139..0x148 && point % 2 == 1 -> point + 1
        point in 0x14A..0x177 && point % 2 == 0 -> point + 1
        point in 0x179..0x17E && point % 2 == 1 -> point + 1
        else -> point
    }
}
