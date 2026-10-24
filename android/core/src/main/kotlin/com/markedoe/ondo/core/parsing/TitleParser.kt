package com.markedoe.ondo.core.parsing

import java.util.regex.Pattern

/** Extracts episode numbering and release years from provider titles. */
object TitleParser {
    /** Season / episode found in a title. */
    data class EpisodeInfo(
        val seriesName: String,
        val season: Int,
        val episode: Int,
        val episodeTitle: String,
    )

    /** A title without its trailing year. */
    data class TitleAndYear(val name: String, val year: Int?)

    // "Name S01E02", "Name S01 E02", "Name - S1.E2 - Title", "Name 1x02", "Name Saison 2 Épisode 3".
    // Linear in the title's length: the look-behind lets the name end only where a run of separators starts
    // (where the leftmost match starts anyway), and the rest of the title, line breaks included, always matches.
    // Otherwise each position inside a long run, or each marker before a line break, scanned the rest again.
    private val patterns: List<Pattern> = listOf(
        """^(.*?)(?<![\s._\-|:])[\s._\-|:]*\bS(\d{1,2})[\s._\-]*E(\d{1,4})\b(?s:(.*?))$""",
        """^(.*?)(?<![\s._\-|:])[\s._\-|:]*\b(\d{1,2})x(\d{1,3})\b(?s:(.*?))$""",
        """^(.*?)(?<![\s._\-|:])[\s._\-|:]*\b(?:Saison|Season|Temporada|Staffel)\s*(\d{1,2})[\s._\-,]*""" +
            """(?:[EÉ]pisode|Episode|Ep\.?|Folge|Capitulo)\s*(\d{1,4})\b(?s:(.*?))$""",
    ).map { Pattern.compile(it, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE) }

    private val seasonWords = listOf("season", "saison", "temporada", "staffel")
    private const val REST_SEPARATORS = " -–|:._"
    private const val NAME_SEPARATORS = " -–|:._(["

    fun parseEpisode(title: String): EpisodeInfo? {
        // Cheap pre-check before running regexes (hot path on big playlists).
        if (!mayContainEpisodeMarker(title)) return null
        for (pattern in patterns) {
            val match = pattern.matcher(title)
            if (!match.find()) continue
            val season = match.group(2)?.toIntOrNull() ?: continue
            val episode = match.group(3)?.toIntOrNull() ?: continue
            val name = cleanSeriesName(match.group(1).orEmpty())
            if (name.isEmpty()) continue
            // Strip leading separators only: "... - Cat's in the Bag..." keeps its ellipsis.
            val rest = match.group(4).orEmpty().trimStart { it in REST_SEPARATORS }.trim()
            // An English name of the core's own: the app shows it in its language (PlaceholderNames).
            val episodeTitle = rest.ifEmpty { "Episode $episode" }
            return EpisodeInfo(name, season, episode, episodeTitle)
        }
        return null
    }

    /** True when the title has `S<digit>`, `<digit>x<digit>` or a season word. */
    internal fun mayContainEpisodeMarker(title: String): Boolean {
        fun isDigit(c: Char) = c in '0'..'9'
        for (i in title.indices) {
            val lower = title[i].code or 0x20 // ASCII lowercase
            if (lower == 's'.code && i + 1 < title.length && isDigit(title[i + 1])) return true
            if (lower == 'x'.code && i > 0 && i + 1 < title.length && isDigit(title[i - 1]) && isDigit(title[i + 1])) return true
        }
        val lowerTitle = title.lowercase()
        return seasonWords.any { lowerTitle.contains(it) }
    }

    private fun cleanSeriesName(name: String): String = name.trim { it in NAME_SEPARATORS }.trim()

    private val yearPattern: Pattern = Pattern.compile("""^(.*?)(?<![\s\-_.])[\s\-_.]*[(\[]?((?:19|20)\d{2})[)\]]?\s*$""")

    /** `"Lighthouse (2010)"` -> (`"Lighthouse"`, 2010). */
    fun splitYear(title: String): TitleAndYear {
        if (title.length < 4 || title.none { it in '0'..'9' }) return TitleAndYear(title, null)
        val match = yearPattern.matcher(title)
        if (!match.find()) return TitleAndYear(title, null)
        val year = match.group(2)?.toIntOrNull() ?: return TitleAndYear(title, null)
        val name = match.group(1).orEmpty().trim()
        return if (name.isEmpty()) TitleAndYear(title, year) else TitleAndYear(name, year)
    }
}
