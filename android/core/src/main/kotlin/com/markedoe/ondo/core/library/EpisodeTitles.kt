package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.core.parsing.TitleParser

/**
 * An episode's own title as people read it, the same on every screen (its series' page, the player, Up next, Continue
 * watching, search): TMDb's name for it when known, else the provider's, without the series' name, the provider's
 * tags or its code (`"|EN| Tom Clancy's Jack Ryan S03E02"` names no episode). An episode named by nothing is the
 * core's `"Episode 2"`, which the app shows in its language ([PlaceholderNames]). Same rules as the Swift `EpisodeTitles`.
 */
object EpisodeTitles {
    /** What separates a series' name, a code and a title: never a title's own end ("Cat's in the Bag..."). */
    private const val SEPARATORS = " -–—|:_,·"

    /** A title's own last marks, which [CatalogueMatcher.displayTitle] leaves out. */
    private const val END_MARKS = ".!?…"

    /**
     * "Episode 2", "Épisode 02", "Ep. 2", "E02": a number, not a title. A bare number is one only when it is the
     * episode's own ("2" for episode 2): "42" or "1984" can be a title.
     */
    private val numberOnly = Regex(
        """^((?:episode|épisode|episodio|episódio|epizod|folge|capitulo|capítulo|ep\.?|e)\s*)?0*(\d{1,4})$""",
        RegexOption.IGNORE_CASE,
    )

    private fun isNumberOnly(name: String, number: Int): Boolean {
        val match = numberOnly.matchEntire(name) ?: return false
        return match.groupValues[1].isNotEmpty() || match.groupValues[2].toIntOrNull() == number
    }

    /** The core's name of episode [number], in English ([PlaceholderNames.EPISODE]). */
    fun placeholder(number: Int): String = "${PlaceholderNames.EPISODE} $number"

    /**
     * [title] without its series' name ([seriesName]), the provider's tags around it and its code: `"Copper Valley -
     * S01E01 - Pilot"` -> `"Pilot"`, `"|EN| Tom Clancy's Jack Ryan S03E02"` -> `"Episode 2"`, `"Jack Ryan - The Big
     * Fish"` -> `"The Big Fish"`. [placeholder] of [number] when nothing of its own is left.
     */
    fun clean(title: String, seriesName: String?, number: Int): String {
        var name = title.trim()
        TitleParser.parseEpisode(name)?.let { parsed ->
            // Nothing after the code: the parser's own "Episode N".
            name = if (parsed.episodeTitle == placeholder(parsed.episode)) "" else parsed.episodeTitle
        }
        if (name.isNotEmpty()) name = withoutTags(name)
        if (name.isNotEmpty() && name.all { it in SEPARATORS || it in END_MARKS || it == '[' || it == ']' }) name = ""
        val series = seriesName?.let { TitleParser.splitYear(CatalogueMatcher.displayTitle(it)).name.trim() }.orEmpty()
        if (name.isNotEmpty() && series.isNotEmpty()) {
            if (CatalogueMatcher.key(name) == CatalogueMatcher.key(series)) {
                name = ""
            } else if (name.startsWith(series, ignoreCase = true)) {
                val rest = name.substring(series.length)
                if (rest.isEmpty() || rest.first() in SEPARATORS) name = rest.trim { it in SEPARATORS }
            }
        }
        if (isNumberOnly(name, number)) name = ""
        return name.ifEmpty { placeholder(number) }
    }

    /** [name] without the provider's tags around it ([CatalogueMatcher.displayTitle]), its own last marks kept. */
    private fun withoutTags(name: String): String {
        val cleaned = CatalogueMatcher.displayTitle(name).trim { it in SEPARATORS || it == '.' }
        val at = name.lastIndexOf(cleaned)
        val marks = if (at < 0) "" else name.substring(at + cleaned.length).takeWhile { it in END_MARKS }
        return cleaned + marks
    }

    /** A history's line of an episode, `"S03E02 · <title>"`: its code, then its title. */
    private val codeLine = Regex("""^(S\d{2,}E(\d{2,}))\s+·\s+(.*)$""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The line Continue watching shows under a series' name, `"S03E02 · <title>"`, with the episode's title as it shows
     * ([clean]; [names] for an episode named by nothing): a line kept before reads like a new one. Another text as it is.
     */
    fun cleanSubtitle(subtitle: String, seriesName: String?, names: PlaceholderNames = PlaceholderNames.ENGLISH): String {
        val match = codeLine.matchEntire(subtitle) ?: return subtitle
        val number = match.groupValues[2].toIntOrNull() ?: return subtitle
        return match.groupValues[1] + " · " + names.episodeTitle(clean(match.groupValues[3], seriesName, number), number)
    }

    /** Whether [title] is the core's name of an episode ([placeholder]): no title of its own. */
    fun isPlaceholder(title: String, number: Int): Boolean = title == placeholder(number)

    /**
     * [episode] as it shows: its title ([clean]), TMDb's name for it first ([tmdb]); its picture the provider's, else
     * TMDb's still; its plot the provider's, else TMDb's. The same instance when nothing changes.
     */
    fun merge(episode: Episode, seriesName: String?, tmdb: TmdbEpisode?): Episode {
        val own = clean(episode.title, seriesName, episode.number)
        val tmdbName = tmdb?.name?.let { clean(it, seriesName, episode.number) }?.takeIf { !isPlaceholder(it, episode.number) }
        val title = tmdbName ?: own
        val poster = episode.posterUrl?.takeIf { it.isNotBlank() } ?: tmdb?.stillUrl ?: episode.posterUrl
        val plot = episode.plot?.takeIf { it.isNotBlank() } ?: tmdb?.overview?.takeIf { it.isNotBlank() } ?: episode.plot
        if (title == episode.title && poster == episode.posterUrl && plot == episode.plot) return episode
        return episode.copy(title = title, posterUrl = poster, plot = plot)
    }

    /**
     * Every episode of [details] as it shows ([merge]), with TMDb's episodes of each season ([tmdb], by season number)
     * matched by their number in it. The same instance when nothing changes (the screens' caches go by identity).
     */
    fun merge(details: SeriesDetails, seriesName: String?, tmdb: Map<Int, List<TmdbEpisode>> = emptyMap()): SeriesDetails {
        var changed = false
        val seasons = details.seasons.map { season ->
            val known = tmdb[season.number]?.associateBy { it.number }.orEmpty()
            var seasonChanged = false
            val episodes = season.episodes.map { episode ->
                merge(episode, seriesName, known[episode.number]).also { if (it !== episode) seasonChanged = true }
            }
            if (seasonChanged) {
                changed = true
                season.copy(episodes = episodes)
            } else {
                season
            }
        }
        return if (changed) details.copy(seasons = seasons) else details
    }
}
