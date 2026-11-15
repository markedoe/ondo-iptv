package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.PortableRegex
import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.model.DolbyVisionFallback
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PictureRange
import com.markedoe.ondo.core.model.SpecSource
import com.markedoe.ondo.core.model.StreamSpecs
import com.markedoe.ondo.core.playback.MediaFormat
import java.util.BitSet

/**
 * What the screen can show, for the version that plays best: high dynamic range (HDR10, HDR10+ or HLG), Dolby Vision (the
 * screen's, not only a decoder's), 4K (its largest mode, whatever the mode of the moment).
 */
data class PictureSupport(val hdr: Boolean = false, val dolbyVision: Boolean = false, val uhd: Boolean = false)

/**
 * What a version's picture asks of this screen and does not get, for the title data panel ([FilmVersions.screenNotes]):
 * the reason a version does not play first on it.
 */
enum class ScreenNote {
    /** Dolby Vision without the screen for it, its base layer (HDR10, SDR, HLG) shown instead: profile 8, 9. */
    DOLBY_VISION_BASE_LAYER,

    /** Dolby Vision without the screen for it, no base layer it shows right (profile 5): wrong colours, never first. */
    DOLBY_VISION_WRONG_COLOURS,

    /** Dolby Vision without the screen for it, profile unknown (a name saying "DV" only): taken as wrong colours. */
    DOLBY_VISION_UNKNOWN,

    /** HDR without the screen for it. */
    HDR_UNSUPPORTED,

    /** 4K above the screen's largest mode. */
    UHD_UNSUPPORTED,
}

/** Why a film's card plays the version it plays ([FilmVersions.defaultChoice]). */
enum class DefaultReason {
    /** The film has one version. */
    ONLY,
    /** The viewer is watching it. */
    IN_PROGRESS,
    /** The viewer chose it last on this device. */
    CHOSEN,
    /** It suits the screen best (4K on a 4K screen, no Dolby Vision where the screen cannot show it). */
    BEST_FOR_SCREEN,
    /** Nothing sets one apart: the first in the catalogue. */
    FIRST,
}

/** What a version's tag says about it, in the order a version's chip names them (docs/VERSIONS.md). */
enum class VersionAspect {
    QUALITY, RANGE, THREE_D, EDITION, CODEC, LANGUAGE, SUBTITLES,
    /** A copy filmed or leaked before the film's release (CAM, TS, TC, SCR, R5, WORKPRINT): named last, played last. */
    SOURCE,
}

/**
 * A tag of a version, read from its name and its category's name. Its chip says it in words ([VersionWords]): "DV" is
 * "Dolby Vision", "VOSTFR" "Original, French subtitles". In the order of [VersionAspect], the best quality first.
 */
enum class VersionTag(val aspect: VersionAspect) {
    UHD(VersionAspect.QUALITY), FHD(VersionAspect.QUALITY), HD(VersionAspect.QUALITY), SD(VersionAspect.QUALITY),
    DOLBY_VISION(VersionAspect.RANGE), HDR(VersionAspect.RANGE),
    THREE_D(VersionAspect.THREE_D), IMAX(VersionAspect.EDITION), HEVC(VersionAspect.CODEC),
    MULTI(VersionAspect.LANGUAGE), VFF(VersionAspect.LANGUAGE), VFQ(VersionAspect.LANGUAGE), VFI(VersionAspect.LANGUAGE),
    VF(VersionAspect.LANGUAGE), VO(VersionAspect.LANGUAGE), ENGLISH(VersionAspect.LANGUAGE), SPANISH(VersionAspect.LANGUAGE),
    GERMAN(VersionAspect.LANGUAGE), ITALIAN(VersionAspect.LANGUAGE), PORTUGUESE(VersionAspect.LANGUAGE),
    ARABIC(VersionAspect.LANGUAGE),
    VOSTFR(VersionAspect.SUBTITLES), VOST(VersionAspect.SUBTITLES),
    CAM(VersionAspect.SOURCE), TELESYNC(VersionAspect.SOURCE), TELECINE(VersionAspect.SOURCE), SCREENER(VersionAspect.SOURCE),
    R5(VersionAspect.SOURCE), WORKPRINT(VersionAspect.SOURCE),
    ;

    /** Said with the other picture tags without a dot ("4K Dolby Vision"); a language or subtitles apart ("4K · English"). */
    val isPicture: Boolean get() = aspect < VersionAspect.LANGUAGE
}

/**
 * The words of a version's chip in the viewer's language (the app's strings; English by default). The names of
 * formats ("4K", "Dolby Vision", "HDR", "3D", "IMAX", "HEVC") are the same in every language.
 */
data class VersionWords(
    /** A version without a tag next to versions with one: the provider's plain stream. */
    val standard: String = "Standard",
    /** Versions nothing tells apart, numbered: "Version 1", "Version 2" (`%1$d` is the number). */
    val numbered: String = "Version %1\$d",
    val fullHd: String = "Full HD",
    val hd: String = "HD",
    val sd: String = "SD",
    val multilingual: String = "Multilingual",
    val french: String = "French",
    val frenchFrance: String = "French (France)",
    val frenchQuebec: String = "French (Quebec)",
    val frenchInternational: String = "French (international)",
    val original: String = "Original language",
    val english: String = "English",
    val spanish: String = "Spanish",
    val german: String = "German",
    val italian: String = "Italian",
    val portuguese: String = "Portuguese",
    val arabic: String = "Arabic",
    val frenchSubtitles: String = "Original, French subtitles",
    val subtitled: String = "Original, subtitled",
    /** A copy filmed or leaked before the film's release: "Cinema copy (CAM)" (`%1$s` is the provider's marker). */
    val cinemaCopy: String = "Cinema copy (%1\$s)",
) {
    fun word(tag: VersionTag): String = when (tag) {
        VersionTag.UHD -> "4K"
        VersionTag.FHD -> fullHd
        VersionTag.HD -> hd
        VersionTag.SD -> sd
        VersionTag.DOLBY_VISION -> "Dolby Vision"
        VersionTag.HDR -> "HDR"
        VersionTag.THREE_D -> "3D"
        VersionTag.IMAX -> "IMAX"
        VersionTag.HEVC -> "HEVC"
        VersionTag.MULTI -> multilingual
        VersionTag.VFF -> frenchFrance
        VersionTag.VFQ -> frenchQuebec
        VersionTag.VFI -> frenchInternational
        VersionTag.VF -> french
        VersionTag.VO -> original
        VersionTag.ENGLISH -> english
        VersionTag.SPANISH -> spanish
        VersionTag.GERMAN -> german
        VersionTag.ITALIAN -> italian
        VersionTag.PORTUGUESE -> portuguese
        VersionTag.ARABIC -> arabic
        VersionTag.VOSTFR -> frenchSubtitles
        VersionTag.VOST -> subtitled
        VersionTag.CAM -> copy("CAM")
        VersionTag.TELESYNC -> copy("TS")
        VersionTag.TELECINE -> copy("TC")
        VersionTag.SCREENER -> copy("SCR")
        VersionTag.R5 -> copy("R5")
        VersionTag.WORKPRINT -> copy("Workprint")
    }

    private fun copy(marker: String): String =
        runCatching { String.format(java.util.Locale.ROOT, cinemaCopy, marker) }.getOrElse { "$cinemaCopy $marker" }

    /** "Version 2". */
    fun number(n: Int): String = runCatching { String.format(java.util.Locale.ROOT, numbered, n) }.getOrElse { "$numbered $n" }
}

/**
 * One chip of a film's page ([FilmVersions.choices]): versions that play the same thing (the same chip tags, the same
 * container, the same name once normalised: a panel filing one stream under two categories) are one choice.
 */
data class VersionChoice(
    /** What its chip says. */
    val label: String,
    /** Its versions, in catalogue order. */
    val members: List<Movie>,
    /** The member it plays ([FilmVersions.preferred]): the one in progress, else the one chosen last, else the first. */
    val preferred: Movie,
) {
    /** Its first member's id: the same whichever member plays (the chip's key, focus and test tag). */
    val id: String get() = members.first().id

    /** [preferred], then the others in catalogue order: the streams tried in turn when one fails to start. */
    val playOrder: List<Movie> get() = listOf(preferred) + members.filter { it.id != preferred.id }

    operator fun contains(id: String): Boolean = members.any { it.id == id }
}

/**
 * The same film's versions in one playlist (4K, Dolby Vision, HD, VOSTFR…), grouped so that lists show each film once
 * and its page offers the versions. Movies only. A derivation of the catalogue, built once per load off the main thread;
 * favourites, history and progress stay per stream. The same rules and tests as Apple's `FilmVersions` (IPTVCore);
 * docs/VERSIONS.md. When in doubt, streams are not grouped:
 *
 * - **A, the provider's TMDb id** ([Movie.tmdbId], a positive number only): streams of the same id are grouped when their
 *   titles ([titleKey]) agree ([titlesAgree]); one whose title does not stays apart (counted in
 *   [Diagnostics.idTitleMismatches]). Streams of two different ids are never grouped, whatever their titles.
 * - **B, no id on one side or both**: the same title ([titleKey], exactly) and the same year ([Movie.year], present on
 *   both, no tolerance). A stream without an id joins a group with an id only when one id has that title and year: when
 *   two do, it stays with the streams without an id ([Diagnostics.ambiguousTitles]).
 *
 * Every stream is in exactly one group, in catalogue order; the groups are in the order of their first stream.
 */
class FilmVersions private constructor(
    private val movies: List<Movie>,
    /** Stream index -> its group; null when nothing is grouped (each stream alone). */
    private val groupOf: IntArray?,
    /** Group -> where its streams start in [members]; one more entry than groups. */
    private val starts: IntArray,
    /** Stream indices, group after group. */
    private val members: IntArray,
    /** Open addressing: stream id -> its index + 1 (0 is empty); the first stream of an id a panel lists twice. */
    private val table: IntArray,
    val diagnostics: Diagnostics,
) {
    /** Counts of a grouping, for tests and the diagnostic report. */
    data class Diagnostics(
        val streams: Int = 0,
        val groups: Int = 0,
        /** Groups of two streams or more. */
        val groupsWithVersions: Int = 0,
        /** Streams grouped with another by rule A (beyond the first of each group). */
        val groupedById: Int = 0,
        /** Streams grouped by rule B. */
        val groupedByTitleAndYear: Int = 0,
        /** Streams kept apart from others of their TMDb id because the titles differ. */
        val idTitleMismatches: Int = 0,
        /** Streams without an id whose title and year two different ids have: not grouped with either. */
        val ambiguousTitles: Int = 0,
        /** Of [idTitleMismatches], the streams whose title starts like another of their id's, kept apart for want of years. */
        val prefixWithoutYears: Int = 0,
    )

    /** Whether versions are grouped at all ("Group versions" on, a catalogue with movies). */
    val isGrouped: Boolean get() = groupOf != null

    val groupCount: Int get() = if (groupOf == null) movies.size else starts.size - 1

    /**
     * Each stream's group, in the catalogue's order (null when nothing is grouped): what [restored] makes these
     * versions from again for the same catalogue, without reading a title.
     */
    fun groups(): IntArray? = groupOf?.copyOf()

    /** Each row of the catalogue with this stream id (a panel can list one stream twice, in two categories). */
    private inline fun forEachIndexOf(id: String, action: (Int) -> Unit) {
        if (table.isEmpty()) return
        val mask = table.size - 1
        var slot = mix(id.hashCode()) and mask
        while (true) {
            val entry = table[slot]
            if (entry == 0) return
            if (movies[entry - 1].id == id) action(entry - 1)
            slot = (slot + 1) and mask
        }
    }

    /** The first row with this id, or -1. */
    private fun indexOf(id: String): Int {
        var first = Int.MAX_VALUE
        forEachIndexOf(id) { if (it < first) first = it }
        return if (first == Int.MAX_VALUE) -1 else first
    }

    /** [movie]'s own row: the same object, else an equal one, else the first row of its id; -1 when not listed. */
    private fun indexOf(movie: Movie): Int {
        var same = -1
        var equal = Int.MAX_VALUE
        var first = Int.MAX_VALUE
        forEachIndexOf(movie.id) { index ->
            val row = movies[index]
            if (row === movie) same = index
            if (row == movie && index < equal) equal = index
            if (index < first) first = index
        }
        return when {
            same >= 0 -> same
            equal != Int.MAX_VALUE -> equal
            first != Int.MAX_VALUE -> first
            else -> -1
        }
    }

    /** The group of a stream (its first row), or -1 when the catalogue does not list it. */
    fun groupOf(id: String): Int {
        val index = indexOf(id)
        return if (index < 0) -1 else groupOf?.get(index) ?: index
    }

    /** The group of [movie]'s own row (a stream listed twice can be in two groups), or -1. */
    fun groupOf(movie: Movie): Int {
        val index = indexOf(movie)
        return if (index < 0) -1 else groupOf?.get(index) ?: index
    }

    /** The streams of a group, in catalogue order. */
    fun versions(group: Int): List<Movie> {
        if (groupOf == null) return movies.getOrNull(group)?.let { listOf(it) }.orEmpty()
        if (group !in 0 until groupCount) return emptyList()
        return (starts[group] until starts[group + 1]).map { movies[members[it]] }
    }

    /** The versions of [movie]'s film, [movie] among them, in catalogue order; [movie] alone when it has none. */
    fun versions(movie: Movie): List<Movie> {
        val group = groupOf(movie)
        if (groupOf == null || group < 0) return listOf(movie)
        return versions(group)
    }

    /** Whether [movie]'s film has another version. */
    fun hasVersions(movie: Movie): Boolean {
        val group = groupOf ?: return false
        val index = indexOf(movie)
        if (index < 0) return false
        val g = group[index]
        return starts[g + 1] - starts[g] > 1
    }

    /**
     * [list] with each film once: the first of its versions in [list] stands for it (a category's list keeps the first
     * of that category, so a film is in every category one of its versions is in). Unchanged when nothing is grouped.
     */
    fun distinct(list: List<Movie>): List<Movie> {
        if (groupOf == null || list.size < 2) return list
        val seen = BitSet(groupCount)
        val result = ArrayList<Movie>(list.size)
        for (movie in list) {
            val group = groupOf(movie)
            if (group < 0) {
                result.add(movie)
            } else if (!seen[group]) {
                seen.set(group)
                result.add(movie)
            }
        }
        return if (result.size == list.size) list else result
    }

    /** [ids] with the other versions of their films: a film is watched, or in My List, when one of its versions is. */
    fun expand(ids: Set<String>): Set<String> {
        if (groupOf == null || ids.isEmpty()) return ids
        val result = HashSet<String>(ids)
        val groups = groupOf ?: return ids
        for (id in ids) {
            forEachIndexOf(id) { index ->
                val group = groups[index]
                for (k in starts[group] until starts[group + 1]) result.add(movies[members[k]].id)
            }
        }
        return if (result.size == ids.size) ids else result
    }

    companion object {
        /** Each stream alone: "Group versions" off, exactly as before. */
        fun none(movies: List<Movie> = emptyList()): FilmVersions =
            if (movies.isEmpty()) NONE else FilmVersions(movies, null, IntArray(1), IntArray(0), IntArray(0), Diagnostics(movies.size, movies.size))

        /** Nothing grouped, one instance (caches keyed on the versions see the same one). */
        val NONE: FilmVersions by lazy { FilmVersions(emptyList(), null, IntArray(1), IntArray(0), IntArray(0), Diagnostics()) }

        /** At most this many groups of one TMDb id compared with a stream: a panel giving one id to everything stays linear. */
        private const val MAX_CLUSTERS_PER_ID = 16

        /**
         * A provider's TMDb id when it is one: digits only (spaces around them allowed), above zero. "0", "", "abc",
         * "-3" and "12.5" are none.
         */
        fun tmdbId(raw: String?): Int? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty() || text.length > 10 || !text.all { it in '0'..'9' }) return null
            return text.toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
        }

        private fun validId(movie: Movie): Int = movie.tmdbId?.takeIf { it > 0 } ?: 0

        /** The title the rules compare: the provider's tags left out ([CatalogueMatcher.displayTitle]), case and accents too. */
        fun titleKey(name: String): String = TextNormalizer.normalize(CatalogueMatcher.displayTitle(name))

        /** What rule A makes of two titles of one TMDb id. */
        enum class Agreement {
            /** One film. */
            SAME,
            /** Another film (another year, another number, another title). */
            DIFFERENT,
            /** One title is the other's first words; years would decide, and one is missing: kept apart. */
            NEEDS_YEARS,
        }

        /**
         * Rule A's test of two [titleKey]s of one TMDb id with their years. Two years given and different: another film.
         * A number of one that the other lacks (2, IV, deux, two…): another film. Then the same key, or the same words
         * ("the matrix" and "matrix", short words such as "the" left out): one film. The words of one the first words of
         * the other ("le transport" and "le transport heritage"): one film only with the same year given on both, and
         * no word of a sequel among the others ("part", "chapter", "episode"…). Anything else: another film.
         */
        fun agreement(a: String, aYear: Int?, b: String, bYear: Int?): Agreement {
            if (!groupable(a) || !groupable(b)) return Agreement.DIFFERENT
            if (aYear != null && bYear != null && aYear != bYear) return Agreement.DIFFERENT
            val first = a.split(' ')
            val second = b.split(' ')
            if (first.filter { isNumber(it) }.sorted() != second.filter { isNumber(it) }.sorted()) return Agreement.DIFFERENT
            if (a == b || contentWords(a) == contentWords(b)) return Agreement.SAME
            val (short, long) = if (first.size <= second.size) first to second else second to first
            if (long.size > short.size && long.subList(0, short.size) == short) {
                if (long.drop(short.size).any { it in SEQUEL_WORDS }) return Agreement.DIFFERENT
                return if (aYear != null && bYear != null) Agreement.SAME else Agreement.NEEDS_YEARS
            }
            return Agreement.DIFFERENT
        }

        /** [agreement] is [Agreement.SAME]. */
        fun titlesAgree(a: String, aYear: Int?, b: String, bYear: Int?): Boolean = agreement(a, aYear, b, bYear) == Agreement.SAME

        /** Roman numerals of sequels (II to XX; "I" is a word too often). */
        private val ROMAN = setOf(
            "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x", "xi", "xii", "xiii", "xiv", "xv", "xvi", "xvii", "xviii",
            "xix", "xx",
        )

        /** Numbers written as words in the apps' Latin-script languages (English, French, Spanish, Portuguese). */
        private val NUMBER_WORDS = setOf(
            "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "deux", "trois", "quatre", "cinq", "sept", "huit", "neuf", "dix",
            "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
            "dois", "duas", "quatro", "sete", "oito", "nove", "dez",
        )

        /** Words of a sequel or a part, in those languages (accents already gone). */
        private val SEQUEL_WORDS = setOf(
            "part", "partie", "parte", "chapter", "chapitre", "capitulo", "episode", "episodio", "vol", "volume", "volumen",
            "tome", "tomo", "saga", "returns", "retour", "regreso", "volta",
        )

        /** Chinese numerals (simplified), a sequel's number written as a word of its own ("流浪地球 二"). */
        private const val CJK_NUMERALS = "〇零一二三四五六七八九十百千两"

        /**
         * A number in a title key: decimal digits of any script (2, ٢, २), Chinese numerals, a roman numeral, a number
         * word. The same characters on both platforms (Apple's `FilmVersions.isNumber`).
         */
        internal fun isNumber(word: String): Boolean = isNumeral(word) || word in ROMAN || word in NUMBER_WORDS

        /** Written in figures: decimal digits of any script, or Chinese numerals. */
        private fun isNumeral(word: String): Boolean =
            word.isNotEmpty() && (word.all { it.isDigit() } || word.all { it in CJK_NUMERALS })

        /**
         * Whether a title key can stand for a film: not empty, and not made of the providers' tags only ("4k", "multi",
         * "fr 4k"): such names say nothing of the film.
         */
        fun groupable(key: String): Boolean = key.isNotEmpty() && !key.split(' ').all(CatalogueMatcher::isTagWord)

        /** Short words of many languages that say nothing of a film ("the", "le", "de"): "The Matrix" and "The Godfather" share none. */
        private val STOP_WORDS = setOf(
            "the", "a", "an", "of", "and", "le", "la", "les", "l", "un", "une", "des", "de", "du", "d", "et", "el", "los",
            "las", "y", "il", "lo", "gli", "der", "die", "das", "und", "o", "os", "as", "e",
        )

        /** A title key's distinct words without [STOP_WORDS] (all of them when nothing else is left). */
        private fun contentWords(key: String): Set<String> {
            val all = key.split(' ').toHashSet()
            return all.filterTo(HashSet()) { it !in STOP_WORDS }.ifEmpty { all }
        }

        val LATEST_YEAR = java.time.Year.now().value + 1

        /**
         * A stream's title key and year: a year at the end of its name ("Dune 2021", "Dune - 2021") is its year, not a
         * number of the title, when it is the stream's own year, or the stream has none and it is one (1900 to next year).
         * A title that is a number only stays one ("1917", "2001").
         */
        fun keyAndYear(name: String, year: Int?, latest: Int = LATEST_YEAR): Pair<String, Int?> {
            val key = titleKey(name)
            val cut = key.lastIndexOf(' ')
            if (cut < 0) return key to year
            val last = key.substring(cut + 1)
            if (last.length != 4 || !last.all { it in '0'..'9' }) return key to year
            val number = last.toInt()
            return when {
                year == number -> key.substring(0, cut) to year
                year == null && number in 1900..latest -> key.substring(0, cut) to number
                else -> key to year
            }
        }

        /** Rule B's titles: [groupable], and more than a number ("FR - 2021" is "2021"). */
        private fun groupableByTitle(key: String): Boolean = groupable(key) && !key.split(' ').all { isNumeral(it) }

        /**
         * The versions of [movies] (one playlist's). [grouped] false ("Group versions" off): each stream alone, the
         * catalogue exactly as it is.
         */
        fun of(movies: List<Movie>, grouped: Boolean = true): FilmVersions {
            if (!grouped || movies.isEmpty()) return none(movies)
            val n = movies.size
            // Titles kept only for the streams compared again later (a cluster's first stream, a title's first stream).
            val keys = arrayOfNulls<String>(n)
            // Each stream's year: its own, or the one its name ends with ("Dune 2021"); -1 until read, 0 none.
            val years = IntArray(n) { -1 }
            fun read(i: Int): String {
                val (k, year) = keyAndYear(movies[i].name, movies[i].year)
                years[i] = year ?: 0
                return k
            }
            fun key(i: Int): String = keys[i] ?: read(i).also { keys[i] = it }
            fun year(i: Int): Int? {
                if (years[i] < 0) read(i)
                return years[i].takeIf { it != 0 }
            }
            // A stream's cluster is the index of its cluster's first stream (an anchor).
            val cluster = IntArray(n) { it }
            // Rule A: per TMDb id, its clusters' anchors (a list through `nextAnchor`, its last in `lastAnchor`).
            val firstAnchor = IntIntTable(n)
            val lastAnchor = IntIntTable(n)
            val nextAnchor = IntArray(n) { -1 }
            // A cluster's year: its first stream's, else the first one a stream joining it gave (0: none yet). Compared
            // with every stream joining, so one cluster never holds two years.
            val clusterYear = IntArray(n)
            var groupedById = 0
            var mismatches = 0
            var withoutYears = 0
            for (i in 0 until n) {
                val id = validId(movies[i])
                if (id == 0) continue
                clusterYear[i] = year(i) ?: 0
                var anchor = firstAnchor[id]
                if (anchor < 0) {
                    firstAnchor[id] = i
                    lastAnchor[id] = i
                    continue
                }
                var compared = 0
                var joined = false
                var needsYears = false
                while (anchor >= 0 && compared < MAX_CLUSTERS_PER_ID) {
                    when (agreement(key(i), year(i), key(anchor), clusterYear[anchor].takeIf { it != 0 })) {
                        Agreement.SAME -> {
                            cluster[i] = anchor
                            joined = true
                            if (clusterYear[anchor] == 0) clusterYear[anchor] = year(i) ?: 0
                        }
                        Agreement.NEEDS_YEARS -> needsYears = true
                        Agreement.DIFFERENT -> Unit
                    }
                    if (joined) break
                    compared++
                    anchor = nextAnchor[anchor]
                }
                if (joined) {
                    groupedById++
                } else {
                    mismatches++
                    if (needsYears) withoutYears++
                    nextAnchor[lastAnchor[id]] = i
                    lastAnchor[id] = i
                }
            }
            // Rule B: by title and year. First the clusters with an id, then the streams without one join them.
            val titles = TitleYearTable(n)
            for (i in 0 until n) {
                val movie = movies[i]
                val id = validId(movie)
                if (id == 0) continue
                val k = keys[i] ?: read(i)
                val year = year(i) ?: continue
                if (!groupableByTitle(k)) continue
                val slot = titles.find(k, year, ::key)
                if (slot < 0) {
                    keys[i] = k
                    titles.insert(k, year, i).also { titles.setIdCluster(it, cluster[i], id) }
                } else {
                    titles.addId(slot, cluster[i], id)
                }
            }
            var groupedByTitle = 0
            var ambiguous = 0
            for (i in 0 until n) {
                val movie = movies[i]
                if (validId(movie) != 0) continue
                val k = read(i)
                val year = year(i) ?: continue
                if (!groupableByTitle(k)) continue
                var slot = titles.find(k, year, ::key)
                if (slot >= 0 && titles.isAmbiguous(slot)) ambiguous++
                val target = when {
                    slot < 0 -> -1
                    titles.idCluster(slot) >= 0 && !titles.isAmbiguous(slot) -> titles.idCluster(slot)
                    else -> titles.noIdCluster(slot)
                }
                if (target >= 0) {
                    cluster[i] = target
                    groupedByTitle++
                } else {
                    if (slot < 0) {
                        keys[i] = k
                        slot = titles.insert(k, year, i)
                    }
                    titles.setNoIdCluster(slot, i)
                }
            }
            // Groups numbered in the order of their first stream; streams in catalogue order within each.
            val number = IntArray(n) { -1 }
            val groupOf = IntArray(n)
            var groups = 0
            for (i in 0 until n) {
                val c = cluster[i]
                if (number[c] < 0) number[c] = groups++
                groupOf[i] = number[c]
            }
            return assembled(movies, groupOf, groups, Diagnostics(n, groups, 0, groupedById, groupedByTitle, mismatches, ambiguous, withoutYears))
        }

        /**
         * The versions of [movies] from each stream's group ([groupOf], [groups] of them, numbered in the order of their
         * first stream); [diagnostics] with its count of groups of two streams or more filled in.
         */
        private fun assembled(movies: List<Movie>, groupOf: IntArray, groups: Int, diagnostics: Diagnostics): FilmVersions {
            val n = movies.size
            val starts = IntArray(groups + 1)
            for (i in 0 until n) starts[groupOf[i] + 1]++
            for (g in 0 until groups) starts[g + 1] += starts[g]
            val fill = starts.copyOf(groups)
            val members = IntArray(n)
            for (i in 0 until n) members[fill[groupOf[i]]++] = i
            var withVersions = 0
            for (g in 0 until groups) if (starts[g + 1] - starts[g] > 1) withVersions++
            return FilmVersions(movies, groupOf, starts, members, idTable(movies), diagnostics.copy(groupsWithVersions = withVersions))
        }

        /**
         * Changes with the rules above: a grouping kept by an app with other rules ([groups]) is not [restored], it is
         * made again.
         */
        const val RULES = 1

        /**
         * The versions of [movies] as [groups] has them ([FilmVersions.groups] of an earlier grouping of the same movies
         * in the same order, kept between two starts of the app): no title is read, a large catalogue's grouping takes
         * a moment. Null when [groups] is not a grouping of these movies (another count, groups not numbered in the
         * order of their first stream): the caller groups them again ([of]). Only the counts of streams and groups of
         * [Diagnostics] are known then.
         */
        fun restored(movies: List<Movie>, groups: IntArray): FilmVersions? {
            val n = movies.size
            if (n == 0 || groups.size != n) return null
            var count = 0
            for (group in groups) {
                if (group < 0 || group > count) return null
                if (group == count) count++
            }
            return assembled(movies, groups.copyOf(), count, Diagnostics(n, count))
        }

        private fun idTable(movies: List<Movie>): IntArray {
            val table = IntArray(capacity(movies.size))
            val mask = table.size - 1
            // Every row, a stream listed twice too: each row keeps its own group.
            movies.forEachIndexed { index, movie ->
                var slot = mix(movie.id.hashCode()) and mask
                while (table[slot] != 0) slot = (slot + 1) and mask
                table[slot] = index + 1
            }
            return table
        }

        /** A power of two at least twice [count]. */
        internal fun capacity(count: Int): Int = Integer.highestOneBit(maxOf(4, count) * 2 - 1) shl 1

        internal fun mix(hash: Int): Int {
            val h = hash * -0x61c88647
            return h xor (h ushr 16)
        }

        // -- Badges and the version shown first.

        private val TAG_WORDS: Map<String, VersionTag> = mapOf(
            "4k" to VersionTag.UHD, "uhd" to VersionTag.UHD, "2160p" to VersionTag.UHD,
            "fhd" to VersionTag.FHD, "1080p" to VersionTag.FHD, "hd" to VersionTag.HD, "720p" to VersionTag.HD,
            "sd" to VersionTag.SD, "480p" to VersionTag.SD, "576p" to VersionTag.SD,
            "dv" to VersionTag.DOLBY_VISION, "dovi" to VersionTag.DOLBY_VISION,
            "hdr" to VersionTag.HDR, "hdr10" to VersionTag.HDR, "hdr10plus" to VersionTag.HDR,
            "3d" to VersionTag.THREE_D, "sbs" to VersionTag.THREE_D, "hsbs" to VersionTag.THREE_D, "imax" to VersionTag.IMAX,
            "hevc" to VersionTag.HEVC, "h265" to VersionTag.HEVC, "x265" to VersionTag.HEVC,
            "multi" to VersionTag.MULTI, "dual" to VersionTag.MULTI,
            "truefrench" to VersionTag.VFF, "vff" to VersionTag.VFF, "vfq" to VersionTag.VFQ, "vfi" to VersionTag.VFI,
            "vf" to VersionTag.VF, "french" to VersionTag.VF, "vo" to VersionTag.VO, "english" to VersionTag.ENGLISH,
            "spanish" to VersionTag.SPANISH, "espanol" to VersionTag.SPANISH, "castellano" to VersionTag.SPANISH,
            "german" to VersionTag.GERMAN, "deutsch" to VersionTag.GERMAN, "italian" to VersionTag.ITALIAN,
            "italiano" to VersionTag.ITALIAN, "portuguese" to VersionTag.PORTUGUESE, "portugues" to VersionTag.PORTUGUESE,
            "arabic" to VersionTag.ARABIC,
            "vostfr" to VersionTag.VOSTFR, "stfr" to VersionTag.VOSTFR, "subfr" to VersionTag.VOSTFR, "frsub" to VersionTag.VOSTFR,
            "vost" to VersionTag.VOST, "vosta" to VersionTag.VOST, "vosten" to VersionTag.VOST, "subbed" to VersionTag.VOST,
        )

        /** A copy filmed or leaked before the film's release, in any letter case ("HDCAM", "Telesync", "DVDScr"). */
        private val SOURCE_WORDS: Map<String, VersionTag> = mapOf(
            "hdcam" to VersionTag.CAM, "hdts" to VersionTag.TELESYNC, "telesync" to VersionTag.TELESYNC,
            "telecine" to VersionTag.TELECINE, "dvdscr" to VersionTag.SCREENER, "workprint" to VersionTag.WORKPRINT,
        )

        /** The same, also words or letters of titles ("Cam", "Ts", "Screener"): a tag only in capitals ("CAM", "TS"). */
        private val SOURCE_CAPS: Map<String, VersionTag> = mapOf(
            "cam" to VersionTag.CAM, "ts" to VersionTag.TELESYNC, "tc" to VersionTag.TELECINE, "scr" to VersionTag.SCREENER,
            "screener" to VersionTag.SCREENER, "r5" to VersionTag.R5,
        )

        /** A provider's word of a copy filmed or leaked before release ([SOURCE_WORDS], [SOURCE_CAPS]), or null. */
        internal fun sourceTag(word: String): VersionTag? {
            val lower = word.lowercase()
            SOURCE_WORDS[lower]?.let { return it }
            return SOURCE_CAPS[lower]?.takeIf { word == word.uppercase() }
        }

        /** [text]'s words as written, split at every character that is no letter or digit (no regular expression). */
        private fun writtenWords(text: String): List<String> {
            val words = ArrayList<String>()
            val word = StringBuilder()
            for (c in text) {
                if (c.isLetterOrDigit()) {
                    word.append(c)
                } else if (word.isNotEmpty()) {
                    words.add(word.toString())
                    word.setLength(0)
                }
            }
            if (word.isNotEmpty()) words.add(word.toString())
            return words
        }

        /**
         * A language's code: a tag only in capitals, at either end of a name or a category ("FR - Sandstorm",
         * "EN | Movies", "Sandstorm (DE)"), where "It" and "De" are words of titles.
         */
        private val EDGE_CODES: Map<String, VersionTag> = mapOf(
            "fr" to VersionTag.VF, "en" to VersionTag.ENGLISH, "eng" to VersionTag.ENGLISH, "es" to VersionTag.SPANISH,
            "esp" to VersionTag.SPANISH, "de" to VersionTag.GERMAN, "ger" to VersionTag.GERMAN, "it" to VersionTag.ITALIAN,
            "ita" to VersionTag.ITALIAN, "pt" to VersionTag.PORTUGUESE, "br" to VersionTag.PORTUGUESE, "ar" to VersionTag.ARABIC,
        )

        /** Words of a provider's tag in two words ("Dolby Vision", "H 265", "ST FR"): never a word of a version's own. */
        private val TAG_PARTS = setOf("dolby", "vision", "h", "265", "st", "sub", "fr")

        /** Unicode whitespace, in a class ([PortableRegex]: the same on Android and in the unit tests). */
        private const val SPACE = PortableRegex.SPACE

        /** Unicode whitespace (a no-break space too), as Apple's `CharacterSet.whitespacesAndNewlines`. */
        private val NAME_SEPARATORS = Regex("""[$SPACE|\[\](){}:·_,/]+""")

        /**
         * Tag words that are also ordinary words of titles ("The English Orchard", "French Lantern", "Dual Harbor"): in a
         * version's name, tags only outside the title its versions share ([labelTags] of a film's versions).
         */
        private val TITLE_LIKE = setOf(
            "english", "french", "spanish", "espanol", "castellano", "german", "deutsch", "italian", "italiano", "portuguese",
            "portugues", "arabic", "dual", "vo", "sd",
        )

        /**
         * A language's code before a name, set apart by a provider's delimiter: "FR - …", "|EN| …", "[IT] …", "DE: …";
         * not a title's first word ("IT WAITS BELOW").
         */
        private val PREFIX_CODE = Regex("""^[$SPACE]*[\[(|]?[$SPACE]*([A-Z]{2,3})[$SPACE]*[\])|:\-–·]""")

        /** A language's code after a name, set apart the same way: "… (DE)", "… - EN", "… |FR|". */
        private val SUFFIX_CODE = Regex("""[\[(|\-–:·][$SPACE]*([A-Z]{2,3})[$SPACE]*[\])|]?[$SPACE]*$""")

        /** The tags of a version, from its name and its category's name, in [VersionTag]'s order; 4K leaves HD out. */
        fun tags(name: String, categoryName: String?): List<VersionTag> = tags(name, categoryName, emptySet())

        /** [tags], the words of [titleWords] that are also title words ([TITLE_LIKE]) not read as tags in the name. */
        private fun tags(name: String, categoryName: String?, titleWords: Set<String>): List<VersionTag> {
            val found = java.util.EnumSet.noneOf(VersionTag::class.java)
            for ((which, text) in listOf(name, categoryName).withIndex()) {
                if (text == null) continue
                val words = TextNormalizer.normalize(text).split(' ')
                for ((index, word) in words.withIndex()) {
                    if (which == 0 && word in titleWords && word in TITLE_LIKE) continue
                    TAG_WORDS[word]?.let(found::add)
                    val next = words.getOrNull(index + 1)
                    if (word == "dolby" && next == "vision") found.add(VersionTag.DOLBY_VISION)
                    if (word == "h" && next == "265") found.add(VersionTag.HEVC)
                    // French subtitles in two words: "ST FR", "SUB FR", "FR SUB".
                    if ((word == "st" || word == "sub") && next == "fr" || word == "fr" && next == "sub") found.add(VersionTag.VOSTFR)
                }
                // A copy filmed or leaked before release, in the name or the category ("Cinema CAM"), as written.
                for (word in writtenWords(text)) sourceTag(word)?.let(found::add)
            }
            return tidy(found)
        }

        /** One quality (the best) and one French; subtitles mean the original language, not a dub. */
        private fun tidy(found: java.util.EnumSet<VersionTag>): List<VersionTag> {
            if (VersionTag.UHD in found) found.removeAll(listOf(VersionTag.FHD, VersionTag.HD, VersionTag.SD))
            if (VersionTag.FHD in found) found.removeAll(listOf(VersionTag.HD, VersionTag.SD))
            if (VersionTag.HD in found) found.remove(VersionTag.SD)
            if (VersionTag.VOSTFR in found) found.remove(VersionTag.VOST)
            if (VersionTag.VOSTFR in found || VersionTag.VOST in found) found.removeAll(listOf(VersionTag.VO, VersionTag.VF))
            if (VersionTag.VFF in found || VersionTag.VFQ in found || VersionTag.VFI in found) found.remove(VersionTag.VF)
            return found.toList()
        }

        /**
         * [tags], with a language's code in capitals at either end of the name or the category, set apart by a provider's
         * delimiter ([PREFIX_CODE], [SUFFIX_CODE], [EDGE_CODES]): a chip's tags. [titleWords]: see [tags].
         */
        fun labelTags(name: String, categoryName: String?, titleWords: Set<String> = emptySet()): List<VersionTag> {
            val found = java.util.EnumSet.noneOf(VersionTag::class.java)
            found.addAll(tags(name, categoryName, titleWords))
            for (text in listOfNotNull(name, categoryName)) {
                for (pattern in listOf(PREFIX_CODE, SUFFIX_CODE)) {
                    pattern.find(text)?.groupValues?.get(1)?.let { EDGE_CODES[it.lowercase()] }?.let(found::add)
                }
            }
            return tidy(found)
        }

        /**
         * The chip tags of a film's [versions], in their order: a title word that is also a tag ("English", "French",
         * "Dual") counts only outside the words every version's name shares ("The English Orchard" and "The English
         * Orchard MULTI" differ by Multilingual only).
         */
        fun labelTags(versions: List<Movie>, categoryName: (String) -> String?): List<List<VersionTag>> {
            val shared: Set<String> = if (versions.size < 2) emptySet() else
                versions.map { TextNormalizer.normalize(it.name).split(' ').toHashSet() }.reduce { a, b -> a.apply { retainAll(b) } }
            return versions.map { labelTags(it.name, categoryName(it.categoryId), shared) }
        }

        /** A copy filmed or leaked before the film's release (CAM, TS, TC, SCR, R5, WORKPRINT). */
        fun isEarlyCopy(tags: List<VersionTag>): Boolean = tags.any { it.aspect == VersionAspect.SOURCE }

        /** Apostrophes as providers write them: "Director's", "Director’s" and "Directors" are one name. */
        private const val APOSTROPHES = "'’‘ʼ`´"

        /**
         * A version's name as [choices] compares it: case, accents, apostrophes and punctuation left out, and the words
         * its chip tags come from (the provider's prefix "|FR|", "4K", "Dolby Vision") and years. The words the chips
         * have no name for ("DUB") and those of an edition ("Extended Cut") stay: they tell versions apart.
         */
        fun nameKey(name: String): String {
            // A copy's marker ("CAM") is its tag, not a word of its name.
            val written = writtenWords(name.filterNot { it in APOSTROPHES }).filter { sourceTag(it) == null }.joinToString(" ")
            val words = TextNormalizer.normalize(written).split(' ').filter { it.isNotEmpty() }
            // A language's code at an end only where a provider's delimiter sets it apart, as [labelTags] reads it.
            val prefix = PREFIX_CODE.containsMatchIn(name)
            val suffix = SUFFIX_CODE.containsMatchIn(name)
            val kept = ArrayList<String>(words.size)
            var i = 0
            while (i < words.size) {
                val word = words[i]
                val next = words.getOrNull(i + 1)
                val pair = word == "dolby" && next == "vision" || word == "h" && next == "265" ||
                    (word == "st" || word == "sub") && next == "fr" || word == "fr" && next == "sub"
                when {
                    pair -> i += 2
                    word in TAG_WORDS || isYear(word) || (i == 0 && prefix || i == words.size - 1 && suffix) && word in EDGE_CODES -> i++
                    else -> { kept.add(word); i++ }
                }
            }
            return kept.joinToString(" ")
        }

        /**
         * The member of a choice that plays: the one in progress ([inProgress], most recent first), else the one the viewer
         * chose last ([chosen], most recent first), else the first in catalogue order. The rules of [defaultChoice].
         */
        fun preferred(members: List<Movie>, inProgress: List<String>, chosen: List<String>): Movie {
            inProgress.firstNotNullOfOrNull { id -> members.firstOrNull { it.id == id } }?.let { return it }
            chosen.firstNotNullOfOrNull { id -> members.firstOrNull { it.id == id } }?.let { return it }
            return members.first()
        }

        /**
         * A film's chips: its [versions] (catalogue order) with the same chip tags ([labelTags]), the same container
         * ([container]) and the same [nameKey] are one choice, labelled as one version ("Standard", "4K Dolby Vision"),
         * in the order of its first member; a category's name never sets versions apart. Each plays its [preferred]
         * member ([inProgress], [chosen]); the others are its fallbacks ([VersionChoice.playOrder]). One choice alone
         * reads "Standard". Empty without versions.
         *
         * [specs] gives what is known of a stream's picture and sound ([StreamSpecs]: measured by the player, else the
         * provider's): a chip says them ("1080p · 5.1", "4K Dolby Vision · Atmos"), and versions the rules above make one
         * stay apart when their known specs differ ([specKey]); a version whose specs are unknown stays with the first of
         * them. Without specs, the chips are the same as before.
         */
        fun choices(
            versions: List<Movie>,
            categoryName: (String) -> String?,
            words: VersionWords = VersionWords(),
            inProgress: List<String> = emptyList(),
            chosen: List<String> = emptyList(),
            specs: (String) -> StreamSpecs? = { null },
        ): List<VersionChoice> {
            if (versions.isEmpty()) return emptyList()
            val tags = labelTags(versions, categoryName)
            val known = versions.map { movie -> specs(movie.id)?.takeUnless { it.isEmpty } }
            val groups = LinkedHashMap<Triple<List<VersionTag>, String?, String>, MutableList<Int>>()
            for (i in versions.indices) groups.getOrPut(Triple(tags[i], container(versions[i]), nameKey(versions[i].name))) { ArrayList() }.add(i)
            // Versions whose known specs differ (4K measured, 1080p measured) are two chips, whatever their names.
            val split = groups.values.flatMap { group -> splitBySpecs(group, known) }.sortedBy { it.first() }
            // A proper copy's chips before those of a copy filmed or leaked before release, each in catalogue order.
            val members = split.sortedBy { group -> isEarlyCopy(tags[group.first()]) }
            // A chip's specs: those of its first member that has some.
            val chipSpecs = members.map { group -> group.firstNotNullOfOrNull { known[it] } }
            val texts = if (members.size < 2) listOf(words.standard) else labels(members.map { versions[it.first()] }, members.map { tags[it.first()] }, chipSpecs, words)
            return members.mapIndexed { k, group ->
                val movies = group.map { versions[it] }
                VersionChoice(texts[k], movies, preferred(movies, inProgress, chosen))
            }
        }

        /**
         * What a film's [versions] say on their chips, in their order, in [words]. Each names what sets it apart from the
         * others, in [VersionAspect]'s order ("4K Dolby Vision", "HD · Original, French subtitles"): a tag they all share
         * is left out. Two that would read the same add the words of their names the others lack ("Extended Cut"); a
         * version without a tag next to tagged ones is "Standard". Labels still the same add, in turn, their container
         * ("Standard · MKV", "Standard · MP4"), then the provider's words the chips have no word for ("DUB"); labels still
         * the same are numbered ("4K 1", "4K 2"; "Version 1", "Version 2"): no two versions read the same. A category's
         * name is never one of those words (only its tags count). [categoryName] names a stream's category. The page's
         * chips are [choices]: versions that play the same thing are one.
         */
        fun labels(
            versions: List<Movie>,
            categoryName: (String) -> String?,
            words: VersionWords = VersionWords(),
            specs: (String) -> StreamSpecs? = { null },
        ): List<String> =
            labels(versions, labelTags(versions, categoryName), versions.map { movie -> specs(movie.id)?.takeUnless { it.isEmpty } }, words)

        /** Where a chip's sound goes: after its picture's words, before its language. */
        private val SOUND_SLOT = VersionAspect.CODEC.ordinal * 2 + 1

        /** The last place a chip's word can take. */
        private val LAST_SLOT = VersionAspect.entries.last().ordinal * 2

        private fun slotOf(aspect: VersionAspect): Int = aspect.ordinal * 2

        /**
         * A chip's words, place by place ([VersionAspect]'s order, its sound after its picture): its specs' where they are
         * known ("1080p" for the tag "4K" of a stream measured 1080p, nothing for "HDR" in the name of one measured SDR),
         * its tags' elsewhere. Its video codec only sets apart chips that read the same ([labels]).
         */
        private fun chipParts(tags: List<VersionTag>, specs: StreamSpecs?, words: VersionWords): List<Pair<Int, String>> {
            val resolution = specs?.resolution
            val parts = ArrayList<Pair<Int, String>>()
            for (slot in 0..LAST_SLOT) {
                for (tag in tags) {
                    if (slotOf(tag.aspect) != slot) continue
                    val known = when (tag.aspect) {
                        VersionAspect.QUALITY -> resolution != null
                        VersionAspect.RANGE -> specs?.range != null
                        VersionAspect.CODEC -> specs?.videoCodec != null
                        else -> false
                    }
                    if (!known) parts.add(slot to words.word(tag))
                }
                when (slot) {
                    slotOf(VersionAspect.QUALITY) -> resolution?.let { parts.add(slot to it) }
                    slotOf(VersionAspect.RANGE) -> specs?.rangeLabel?.let { parts.add(slot to it) }
                    SOUND_SLOT -> specs?.chipAudio?.let { parts.add(slot to it) }
                }
            }
            return parts
        }

        /** Picture words with a space between them, the others after them with a dot: "4K Dolby Vision · Atmos · English". */
        private fun joined(parts: List<Pair<Int, String>>): String {
            val picture = parts.filter { it.first < SOUND_SLOT }.joinToString(" ") { it.second }
            return (listOf(picture).filter { it.isNotEmpty() } + parts.filter { it.first >= SOUND_SLOT }.map { it.second }).joinToString(" · ")
        }

        /** What a chip says of its specs alone ("1080p · 5.1"), when it would say nothing else; null when none are known. */
        private fun specText(specs: StreamSpecs?): String? {
            specs ?: return null
            return joined(listOfNotNull(specs.resolution?.let { 0 to it }, specs.rangeLabel?.let { slotOf(VersionAspect.RANGE) to it }, specs.chipAudio?.let { SOUND_SLOT to it }))
                .ifEmpty { null }
        }

        /**
         * What [choices] compares of two versions' specs: their picture's class, range and codec, their sound. Null when
         * none is known.
         */
        internal fun specKey(specs: StreamSpecs?): List<String?>? =
            specs?.takeUnless { it.isEmpty }?.let { listOf(it.resolution, it.rangeLabel, it.videoCodec, it.audioLabel) }

        /**
         * [group]'s versions (catalogue order) apart by their known specs ([specKey]), in the order of each one's first
         * version; the versions whose specs are unknown stay with the first. One group when the known ones agree.
         */
        private fun splitBySpecs(group: List<Int>, known: List<StreamSpecs?>): List<List<Int>> {
            val keys = group.mapNotNull { specKey(known[it]) }.distinct()
            if (keys.size < 2) return listOf(group)
            val parts = keys.map { ArrayList<Int>() }
            for (i in group) {
                val key = specKey(known[i])
                parts[if (key == null) 0 else keys.indexOf(key)].add(i)
            }
            return parts.map { part -> part.sorted() }
        }

        private fun labels(versions: List<Movie>, tags: List<List<VersionTag>>, specs: List<StreamSpecs?>, words: VersionWords): List<String> {
            val parts = tags.indices.map { chipParts(tags[it], specs[it], words) }
            // The places the versions differ in: a word all of them have, or none, tells none apart.
            val differing = (0..LAST_SLOT).filter { slot -> parts.map { list -> list.filter { it.first == slot }.map { it.second } }.distinct().size > 1 }.toSet()
            val texts = parts.map { list -> joined(list.filter { it.first in differing }) }.toMutableList()
            // Versions that would read the same: the words of their names the others lack.
            val extras = extraWords(versions)
            for (same in texts.indices.groupBy { texts[it] }.values) {
                if (same.size < 2 || same.map { extras[it] }.distinct().size < 2) continue
                for (i in same) if (extras[i].isNotEmpty()) texts[i] = listOf(texts[i], extras[i]).filter { it.isNotEmpty() }.joinToString(" · ")
            }
            val anyTagged = texts.any { it.isNotEmpty() }
            // "Standard" only for a version nothing is known of: one with specs says them ("1080p · 5.1").
            fun plain(i: Int): String = specText(specs[i]) ?: words.standard
            val labels = texts.indices.mapTo(ArrayList(texts.size)) { i ->
                when {
                    texts[i].isNotEmpty() -> texts[i]
                    else -> specText(specs[i]) ?: if (anyTagged) words.standard else ""
                }
            }
            // Still the same: what else sets them apart, in this order: their video codec, their sound's codec ("HEVC",
            // "AAC"), their container ("MKV", "MP4"), then the provider's words the chips have no name for ("DUB"). Never
            // their categories' names: viewers see no category on a film's page, and a panel files one stream under several.
            setApart(labels) { same -> same.map { specs[it]?.videoCodec.orEmpty() } }
            setApart(labels) { same -> same.map { specs[it]?.audioCodec.orEmpty() } }
            setApart(labels) { same -> same.map { container(versions[it]).orEmpty() } }
            setApart(labels) { same -> differingWords(same.map { versions[it].name }, tagsOnly = true) }
            if (labels.any { it.isNotEmpty() }) for (i in labels.indices) if (labels[i].isEmpty()) labels[i] = plain(i)
            // Still the same: numbered, each of them.
            val counts = labels.groupingBy { it }.eachCount()
            val seen = HashMap<String, Int>()
            val numbered = labels.map { label ->
                if (counts.getValue(label) < 2) return@map label.ifEmpty { words.number(1) }
                val n = (seen[label] ?: 0) + 1
                seen[label] = n
                if (label.isEmpty()) words.number(n) else "$label $n"
            }
            // A number that made another version's own label ("4K 2" next to two "4K"): their positions tell them apart.
            return if (numbered.distinct().size == numbered.size) numbered else numbered.mapIndexed { i, label -> "$label (${i + 1})" }
        }

        /**
         * Labels that read the same get what [values] gives each of them (in the order of the group's indices), when
         * those differ: "Standard · MKV" next to "Standard · MP4".
         */
        private fun setApart(labels: MutableList<String>, values: (List<Int>) -> List<String>) {
            for (same in labels.indices.groupBy { labels[it] }.values) {
                if (same.size < 2) continue
                val found = values(same)
                if (found.distinct().size < 2) continue
                same.forEachIndexed { k, i -> if (found[k].isNotEmpty()) labels[i] = listOf(labels[i], found[k]).filter { it.isNotEmpty() }.joinToString(" · ") }
            }
        }

        /**
         * The words of each text that are not in every other one, years left out. The providers' tags are left out too,
         * or, with [tagsOnly], they are the only words kept: those of a name the chips have no word for ("DUB", "TR").
         */
        private fun differingWords(texts: List<String>, tagsOnly: Boolean = false): List<String> {
            val split = texts.map { text -> text.split(NAME_SEPARATORS).map { it.trim('-', '.', '*', '+', '"') }.filter { it.isNotEmpty() } }
            val common = split.map { words -> words.flatMapTo(HashSet()) { TextNormalizer.normalize(it).split(' ') } }
                .reduceOrNull { a, b -> a.apply { retainAll(b) } } ?: emptySet()
            return split.map { words ->
                words.filter { word ->
                    val parts = TextNormalizer.normalize(word).split(' ').filter { it.isNotEmpty() }
                    parts.isNotEmpty() && !parts.all { it in common || isYear(it) || (!tagsOnly && isNoise(it)) } &&
                        (!tagsOnly || parts.all { it in common || isNoise(it) })
                }.joinToString(" ")
            }
        }

        /** A version's container in capitals ("MKV", "MP4", "HLS"): the provider's extension, else its address's. */
        fun container(movie: Movie): String? =
            movie.containerExtension?.let(MediaFormat::ofExtension) ?: MediaFormat.label(movie.streamUrl)

        private fun isYear(word: String): Boolean = word.length == 4 && word.all { it in '0'..'9' } && word.toInt() in 1900..2099

        /** The words of each version's name that are not in every other one's, tags and years left out ("Extended Cut"). */
        private fun extraWords(versions: List<Movie>): List<String> {
            val split = versions.map { movie -> movie.name.split(NAME_SEPARATORS).map { it.trim('-', '.', '*', '+', '"') }.filter { it.isNotEmpty() } }
            val common = split.map { words -> words.flatMapTo(HashSet()) { TextNormalizer.normalize(it).split(' ') } }
                .reduceOrNull { a, b -> a.apply { retainAll(b) } } ?: emptySet()
            return split.map { words ->
                words.filter { word ->
                    val parts = TextNormalizer.normalize(word).split(' ').filter { it.isNotEmpty() }
                    parts.isNotEmpty() && !parts.all { it in common || isNoise(it) }
                }.joinToString(" ")
            }
        }

        /** A provider's tag, a part of one, or a year: never a word that tells a version apart. */
        private fun isNoise(word: String): Boolean =
            word in TAG_WORDS || word in EDGE_CODES || word in TAG_PARTS || word in SOURCE_WORDS || word in SOURCE_CAPS ||
                CatalogueMatcher.isTagWord(word) || isYear(word)

        /** The names of a playlist's movie categories by id, for [labels]. */
        fun categoryNames(categories: List<MediaCategory>): (String) -> String? {
            val names = HashMap<String, String>(categories.size * 2)
            for (category in categories) names.putIfAbsent(category.id, category.name)
            return names::get
        }

        /**
         * How well a version suits the screen: 4K on a 4K screen, HD; Dolby Vision or HDR only where the screen shows
         * it. Its [specs] (measured, else the provider's) before the tags of its name where they know. A Dolby Vision
         * stream on a screen without it: its base layer where its profile has one the screen shows (8, 9: HDR10 on an
         * HDR screen), else below the others (profile 5, or unknown: wrong colours). A copy filmed or leaked before
         * release ([isEarlyCopy]) below every other.
         */
        fun score(tags: List<VersionTag>, support: PictureSupport, specs: StreamSpecs? = null): Int {
            var score = if (isEarlyCopy(tags)) -1_000 else 0
            val fallback = dolbyVisionFallback(tags, specs)
            if (fallback != DolbyVisionFallback.NONE) {
                score += when {
                    support.dolbyVision -> 4
                    fallback != DolbyVisionFallback.BASE_LAYER -> -40
                    // Profile 8.2: an SDR base layer.
                    specs?.dolbyVisionCompatibility == 2 -> 0
                    else -> if (support.hdr) 2 else -20
                }
            }
            val known = specs?.takeUnless { it.isEmpty }
            val range = known?.range
            val hdr = if (range != null) range in HDR_RANGES else VersionTag.HDR in tags
            if (hdr) score += if (support.hdr) 2 else -20
            val resolution = known?.resolution
            val uhd = if (resolution != null) resolution == "4K" else VersionTag.UHD in tags
            if (uhd) score += if (support.uhd) 8 else 1
            val hd = if (resolution != null) resolution == "1080p" || resolution == "720p" else VersionTag.HD in tags || VersionTag.FHD in tags
            if (hd) score += 4
            return score
        }

        private val HDR_RANGES = setOf(PictureRange.HDR10, PictureRange.HDR10_PLUS, PictureRange.HLG)

        /**
         * What a screen without Dolby Vision shows of a version: from its [specs] where they know its range, else from its
         * name (a "DV" tag says no profile: [DolbyVisionFallback.UNKNOWN]). A name saying "DV" over the provider's
         * specs that show no Dolby Vision stays [DolbyVisionFallback.UNKNOWN]: a panel's ffprobe can read only the HDR10
         * base layer of a profile 5 file (no configuration record); only what the player measured overrules the name.
         */
        fun dolbyVisionFallback(tags: List<VersionTag>, specs: StreamSpecs?): DolbyVisionFallback {
            val named = VersionTag.DOLBY_VISION in tags
            if (specs != null && (specs.range != null || specs.dolbyVisionProfile != null)) {
                val fallback = specs.dolbyVisionFallback
                if (fallback == DolbyVisionFallback.NONE && named && specs.source != SpecSource.MEASURED) return DolbyVisionFallback.UNKNOWN
                return fallback
            }
            return if (named) DolbyVisionFallback.UNKNOWN else DolbyVisionFallback.NONE
        }

        /** What a version's picture asks of this screen and does not get ([ScreenNote]); empty when the screen shows it all. */
        fun screenNotes(tags: List<VersionTag>, support: PictureSupport, specs: StreamSpecs? = null): List<ScreenNote> {
            val notes = ArrayList<ScreenNote>()
            if (!support.dolbyVision) {
                when (dolbyVisionFallback(tags, specs)) {
                    DolbyVisionFallback.BASE_LAYER -> notes.add(ScreenNote.DOLBY_VISION_BASE_LAYER)
                    DolbyVisionFallback.WRONG_COLOURS -> notes.add(ScreenNote.DOLBY_VISION_WRONG_COLOURS)
                    DolbyVisionFallback.UNKNOWN -> notes.add(ScreenNote.DOLBY_VISION_UNKNOWN)
                    DolbyVisionFallback.NONE -> Unit
                }
            }
            val known = specs?.takeUnless { it.isEmpty }
            val range = known?.range
            val hdr = if (range != null) range in HDR_RANGES else VersionTag.HDR in tags
            if (hdr && !support.hdr) notes.add(ScreenNote.HDR_UNSUPPORTED)
            val resolution = known?.resolution
            val uhd = if (resolution != null) resolution == "4K" else VersionTag.UHD in tags
            if (uhd && !support.uhd) notes.add(ScreenNote.UHD_UNSUPPORTED)
            return notes
        }

        /**
         * The version a film's card plays and its page selects first: the one in progress ([inProgress]); else the one
         * the viewer chose last ([chosen], most recent first); else the one that suits the screen best ([score]); else
         * the first.
         */
        fun defaultVersion(
            versions: List<Movie>,
            inProgress: String?,
            chosen: List<String>,
            support: PictureSupport,
            categoryName: (String) -> String?,
            specs: (String) -> StreamSpecs? = { null },
        ): Movie? = defaultChoice(versions, inProgress, chosen, support, categoryName, specs)?.first

        /** [defaultVersion] and why it is that one; null without versions. [specs]: what is known of a stream, by id. */
        fun defaultChoice(
            versions: List<Movie>,
            inProgress: String?,
            chosen: List<String>,
            support: PictureSupport,
            categoryName: (String) -> String?,
            specs: (String) -> StreamSpecs? = { null },
        ): Pair<Movie, DefaultReason>? {
            if (versions.size < 2) return versions.firstOrNull()?.let { it to DefaultReason.ONLY }
            inProgress?.let { id -> versions.firstOrNull { it.id == id }?.let { return it to DefaultReason.IN_PROGRESS } }
            if (chosen.isNotEmpty()) {
                val ids = versions.mapTo(HashSet()) { it.id }
                chosen.firstOrNull { it in ids }?.let { id -> return versions.first { it.id == id } to DefaultReason.CHOSEN }
            }
            var best = versions.first()
            var bestScore = Int.MIN_VALUE
            val scores = HashSet<Int>()
            for (movie in versions) {
                val score = score(tags(movie.name, categoryName(movie.categoryId)), support, specs(movie.id))
                scores.add(score)
                if (score > bestScore) {
                    best = movie
                    bestScore = score
                }
            }
            return best to if (scores.size > 1) DefaultReason.BEST_FOR_SCREEN else DefaultReason.FIRST
        }
    }

    /** Open addressing, Int -> Int (a TMDb id -> its first cluster's anchor); -1 when absent. */
    private class IntIntTable(count: Int) {
        private val keys = IntArray(capacity(count))
        private val values = IntArray(keys.size)
        private val mask = keys.size - 1

        private fun slot(key: Int): Int {
            var slot = mix(key) and mask
            while (keys[slot] != 0 && keys[slot] != key) slot = (slot + 1) and mask
            return slot
        }

        operator fun get(key: Int): Int {
            val slot = slot(key)
            return if (keys[slot] == key) values[slot] else -1
        }

        operator fun set(key: Int, value: Int) {
            val slot = slot(key)
            keys[slot] = key
            values[slot] = value
        }
    }

    /**
     * Open addressing by title and year: each entry keeps a stream of that title ([representative], to tell titles
     * apart past their hash), the first cluster with an id and its id, whether another id has it too, and the cluster of
     * the streams without an id.
     */
    private class TitleYearTable(count: Int) {
        private val hashes = LongArray(capacity(count))
        private val representative = IntArray(hashes.size) { -1 }
        private val years = IntArray(hashes.size)
        private val idClusters = IntArray(hashes.size) { -1 }
        private val ids = IntArray(hashes.size)
        private val ambiguous = BitSet(hashes.size)
        private val noIdClusters = IntArray(hashes.size) { -1 }
        private val mask = hashes.size - 1

        private fun hash(key: String, year: Int): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in key) h = (h xor c.code.toLong()) * 0x100000001b3L
            return (h xor year.toLong()) * 0x100000001b3L
        }

        /** The entry of [key] and [year], or -1. */
        fun find(key: String, year: Int, keyOf: (Int) -> String): Int {
            val h = hash(key, year)
            var slot = (h xor (h ushr 32)).toInt().let(::mix) and mask
            while (representative[slot] >= 0) {
                if (hashes[slot] == h && years[slot] == year && keyOf(representative[slot]) == key) return slot
                slot = (slot + 1) and mask
            }
            return -1
        }

        fun insert(key: String, year: Int, stream: Int): Int {
            val h = hash(key, year)
            var slot = (h xor (h ushr 32)).toInt().let(::mix) and mask
            while (representative[slot] >= 0) slot = (slot + 1) and mask
            hashes[slot] = h
            years[slot] = year
            representative[slot] = stream
            return slot
        }

        /** The first cluster with an id of this entry. */
        fun setIdCluster(slot: Int, cluster: Int, id: Int) {
            idClusters[slot] = cluster
            ids[slot] = id
        }

        /** Another cluster with an id has this title and year: ambiguous when its id differs. */
        fun addId(slot: Int, cluster: Int, id: Int) {
            if (idClusters[slot] < 0) setIdCluster(slot, cluster, id) else if (ids[slot] != id) ambiguous.set(slot)
        }

        fun setNoIdCluster(slot: Int, cluster: Int) {
            if (noIdClusters[slot] < 0) noIdClusters[slot] = cluster
        }

        fun idCluster(slot: Int) = idClusters[slot]
        fun noIdCluster(slot: Int) = noIdClusters[slot]
        fun isAmbiguous(slot: Int) = ambiguous[slot]
    }
}
