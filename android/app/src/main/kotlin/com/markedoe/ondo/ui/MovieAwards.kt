package com.markedoe.ondo.ui

import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.data.OndoState
import java.util.Locale

/** An award of a movie, ready to show: won, in competition (festivals) or nominated. */
data class AwardRow(val name: String, val year: Int, val won: Boolean, val festival: Boolean, val awardId: String = "")

/** Awards and festival selections of the catalogue's movies (bundled data, matched on the device). */
object MovieAwards {
    /** Festivals, where taking part is being "in competition"; elsewhere a film is "nominated". */
    private val festivals = setOf("cannes", "venice", "berlin", "san-sebastian", "locarno")

    @Volatile private var cached: Triple<PlaylistContent, AwardsCatalogue, Map<String, List<AwardsCatalogue.Entry>>>? = null

    /** By movie id; matched once per catalogue. */
    fun of(state: OndoState): Map<String, List<AwardsCatalogue.Entry>> {
        cached?.let { (content, awards, found) -> if (content === state.content && awards === state.awards) return found }
        return state.awards.match(state.content.movies).also { cached = Triple(state.content, state.awards, it) }
    }

    internal fun forget() {
        cached = null
    }

    fun rows(state: OndoState, movie: Movie, locale: Locale): List<AwardRow> {
        val language = if (locale.language == "zh") "zh-Hans" else locale.language
        // The awards file can list a film twice for one award and year (won, and nominated): once, the win first.
        return of(state)[movie.id].orEmpty().distinctBy { it.awardId to it.year }.mapNotNull { entry ->
            val award = state.awards.award(entry.awardId) ?: return@mapNotNull null
            AwardRow(award.name(language), entry.year, entry.won, award.group in festivals, award.id)
        }
    }

    /** Movies that won an award, the most recent award first. */
    fun winners(state: OndoState, hidden: Set<String>, limit: Int, leftOut: Set<String> = emptySet()): List<Movie> {
        val byId = state.content.movies.associateBy { it.id }
        return of(state).mapNotNull { (id, entries) ->
            val win = entries.firstOrNull { it.won } ?: return@mapNotNull null
            val movie = byId[id]?.takeIf { it.categoryId !in hidden && it.id !in leftOut } ?: return@mapNotNull null
            movie to win.year
        }.sortedWith(compareByDescending<Pair<Movie, Int>> { it.second }.thenBy { it.first.name }).take(limit).map { it.first }
    }
}
