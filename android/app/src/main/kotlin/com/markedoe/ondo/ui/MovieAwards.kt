package com.markedoe.ondo.ui

import androidx.tracing.trace
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.library.FilmVersions
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.data.OndoState
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** An award of a movie or a series, ready to show: won, in competition (festivals) or nominated. */
data class AwardRow(val name: String, val year: Int, val won: Boolean, val festival: Boolean, val awardId: String = "")

/**
 * Awards and festival selections of the catalogue's movies, and the awards of its series (bundled data, matched on the
 * device; a series only among awarded series, a movie only among awarded films).
 */
object MovieAwards {
    /** Festivals, where taking part is being "in competition"; elsewhere a film is "nominated". */
    private val festivals = setOf("cannes", "venice", "berlin", "san-sebastian", "locarno")

    @Volatile private var cached: Triple<PlaylistContent, AwardsCatalogue, Map<String, List<AwardsCatalogue.Entry>>>? = null
    @Volatile private var cachedSeries: Triple<PlaylistContent, AwardsCatalogue, Map<String, List<AwardsCatalogue.Entry>>>? = null
    private val lock = Any()

    /** How many times the catalogue's titles were matched with the awards (each takes a moment on a large catalogue). */
    private val matchCount = AtomicInteger()
    internal val matches: Int get() = matchCount.get()

    /** By movie id; matched once per catalogue. */
    fun of(state: OndoState): Map<String, List<AwardsCatalogue.Entry>> = of(state.content, state.awards)

    /** By series id; matched once per catalogue. */
    fun ofSeries(state: OndoState): Map<String, List<AwardsCatalogue.Entry>> = ofSeries(state.content, state.awards)

    /**
     * Matched once per catalogue and awards file: the view model does it in the background as a catalogue arrives
     * ([prepare]); a screen asking meanwhile waits for that one match instead of starting another.
     */
    fun of(content: PlaylistContent, awards: AwardsCatalogue): Map<String, List<AwardsCatalogue.Entry>> {
        cached?.let { (known, file, found) -> if (known === content && file === awards) return found }
        synchronized(lock) {
            cached?.let { (known, file, found) -> if (known === content && file === awards) return found }
            matchCount.incrementAndGet()
            return trace("Ondo.awards.movies") { awards.match(content.movies) }.also { cached = Triple(content, awards, it) }
        }
    }

    fun ofSeries(content: PlaylistContent, awards: AwardsCatalogue): Map<String, List<AwardsCatalogue.Entry>> {
        cachedSeries?.let { (known, file, found) -> if (known === content && file === awards) return found }
        synchronized(lock) {
            cachedSeries?.let { (known, file, found) -> if (known === content && file === awards) return found }
            matchCount.incrementAndGet()
            return trace("Ondo.awards.series") { awards.matchSeries(content.series) }.also { cachedSeries = Triple(content, awards, it) }
        }
    }

    /** Matches the movies and the series of a catalogue that just arrived, so that the Movies and Series tabs open at once. */
    fun prepare(content: PlaylistContent, awards: AwardsCatalogue) {
        if (content.movies.isNotEmpty()) of(content, awards)
        if (content.series.isNotEmpty()) ofSeries(content, awards)
    }

    /** Whether the awards of this catalogue's movies and series are matched already. */
    internal fun isPrepared(content: PlaylistContent, awards: AwardsCatalogue): Boolean =
        cached.let { it != null && it.first === content && it.second === awards } &&
            cachedSeries.let { it != null && it.first === content && it.second === awards }

    internal fun forget() {
        cached = null
        cachedSeries = null
    }

    fun rows(state: OndoState, movie: Movie, locale: Locale): List<AwardRow> = rows(state, of(state)[movie.id].orEmpty(), locale)

    fun rows(state: OndoState, series: Series, locale: Locale): List<AwardRow> = rows(state, ofSeries(state)[series.id].orEmpty(), locale)

    private fun rows(state: OndoState, entries: List<AwardsCatalogue.Entry>, locale: Locale): List<AwardRow> {
        val language = if (locale.language == "zh") "zh-Hans" else locale.language
        // The awards file can list a title twice for one award and year (won, and nominated): once, the win first.
        return entries.distinctBy { it.awardId to it.year }.mapNotNull { entry ->
            val award = state.awards.award(entry.awardId) ?: return@mapNotNull null
            AwardRow(award.name(language), entry.year, entry.won, award.group in festivals, award.id)
        }
    }

    /** Movies that won an award, the most recent award first. */
    fun winners(
        state: OndoState,
        hidden: Set<String>,
        limit: Int,
        leftOut: Set<String> = emptySet(),
        films: FilmVersions = FilmVersions.none(),
        alsoLeftOut: Set<String> = emptySet(),
    ): List<Movie> {
        val awarded = of(state)
        val byId = awarded(state.content.movies, awarded) { it.id }
        val sorted = awarded.mapNotNull { (id, entries) ->
            val win = entries.firstOrNull { it.won } ?: return@mapNotNull null
            val movie = byId[id]?.takeIf { it.categoryId !in hidden && it.id !in leftOut && it.id !in alsoLeftOut } ?: return@mapNotNull null
            movie to win.year
        }.sortedWith(compareByDescending<Pair<Movie, Int>> { it.second }.thenBy { it.first.name }).map { it.first }
        // Each film once, whatever its versions.
        return films.distinct(sorted).take(limit)
    }

    /** Series that won an award, the most recent award first. */
    fun seriesWinners(state: OndoState, hidden: Set<String>, limit: Int, leftOut: Set<String> = emptySet()): List<Series> {
        val awarded = ofSeries(state)
        val byId = awarded(state.content.series, awarded) { it.id }
        return awarded.mapNotNull { (id, entries) ->
            val win = entries.firstOrNull { it.won } ?: return@mapNotNull null
            val series = byId[id]?.takeIf { it.categoryId !in hidden && it.id !in leftOut } ?: return@mapNotNull null
            series to win.year
        }.sortedWith(compareByDescending<Pair<Series, Int>> { it.second }.thenBy { it.first.name }).map { it.first }.take(limit)
    }

    /**
     * The titles of [list] that have awards, by id; the last of an id listed twice, as `associateBy` keeps it: a map of the
     * awarded titles only, not of the whole catalogue at every redraw.
     */
    private fun <T> awarded(list: List<T>, awards: Map<String, List<AwardsCatalogue.Entry>>, id: (T) -> String): Map<String, T> {
        if (awards.isEmpty()) return emptyMap()
        val found = HashMap<String, T>(awards.size * 2)
        for (item in list) {
            val key = id(item)
            if (key in awards) found[key] = item
        }
        return found
    }
}
