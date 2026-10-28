package com.markedoe.ondo.data

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.library.CatalogueMatcher
import com.markedoe.ondo.core.library.People
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.parsing.TitleParser
import kotlin.math.abs

/** How "More like this" orders the titles of a category (see [Showcase.similarMovies]). Pure functions, unit tested. */
internal object Similarity {
    /** A title as compared: its name and year, what its page says ([details], when known), and its rating. */
    class Title(val name: String, val year: Int?, val details: MediaDetails?, val rating: Double? = null)

    /** Points for each genre, the director, each actor shared (three of each at most: a long cast is no closer). */
    private const val GENRE = 3
    private const val DIRECTOR = 3
    private const val ACTOR = 1
    private const val MOST_SHARED = 3

    /** Release years five apart make one era: within it, the best rated come first. */
    private const val ERA_YEARS = 5

    /**
     * The [limit] [candidates] closest to [of], best first: the most genres, director and actors shared, then the
     * closest era, then the best rated, then the given order. Never [of] itself or another copy of it, and each film
     * once (the first of its copies, by that order); [id] tells the same entry listed twice.
     */
    fun <T> rank(of: Title, candidates: List<Pair<Title, T>>, limit: Int, id: (T) -> String): List<T> {
        if (limit <= 0 || candidates.isEmpty()) return emptyList()
        val genres = genres(of.details?.genre)
        val directors = people(of.details?.director)
        val cast = people(of.details?.cast)
        val year = yearOf(of)

        class Scored(val index: Int, val title: Title, val item: T, val score: Int, val era: Int) {
            val key: String by lazy { CatalogueMatcher.filmKey(title.name) }
        }

        fun score(title: Title): Int {
            val details = title.details ?: return 0
            val sharedGenres = if (genres.isEmpty()) 0 else genres(details.genre).count { it in genres }
            val sameDirector = directors.isNotEmpty() && people(details.director).any { it in directors }
            val sharedCast = if (cast.isEmpty()) 0 else people(details.cast).count { it in cast }
            return GENRE * minOf(sharedGenres, MOST_SHARED) + (if (sameDirector) DIRECTOR else 0) + ACTOR * minOf(sharedCast, MOST_SHARED)
        }

        fun era(title: Title): Int {
            val other = yearOf(title)
            // A year unknown on either side: after every known one.
            if (year == null || other == null) return Int.MAX_VALUE
            return (abs(year.toLong() - other.toLong()) / ERA_YEARS).coerceAtMost(Int.MAX_VALUE - 1L).toInt()
        }

        val ranked = candidates.mapIndexed { index, (title, item) -> Scored(index, title, item, score(title), era(title)) }
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenBy { it.era }
                    .thenByDescending { it.title.rating ?: 0.0 }
                    .thenBy { it.index },
            )
        val ownKey = CatalogueMatcher.filmKey(of.name)
        fun sameFilm(a: String, aTitle: Title, b: String, bTitle: Title) =
            a.isNotEmpty() && a == b && CatalogueMatcher.isSameFilm(aTitle.name, aTitle.year, bTitle.name, bTitle.year)
        val ids = HashSet<String>()
        val kept = ArrayList<Scored>()
        for (candidate in ranked) {
            if (kept.size >= limit) break
            if (!ids.add(id(candidate.item))) continue
            if (sameFilm(ownKey, of, candidate.key, candidate.title)) continue
            if (kept.any { sameFilm(it.key, it.title, candidate.key, candidate.title) }) continue
            kept += candidate
        }
        return kept.map { it.item }
    }

    private fun yearOf(title: Title): Int? = title.year ?: TitleParser.splitYear(title.name).year

    /** The genres of a genre field ("Drama, Thriller", "Action / Adventure"), normalised. */
    private fun genres(field: String?): Set<String> =
        field.orEmpty().split(',', '/', '|', '&').mapNotNullTo(HashSet()) { TextNormalizer.normalize(it).takeIf(String::isNotEmpty) }

    private fun people(field: String?): Set<String> = People.names(field).mapTo(HashSet()) { TextNormalizer.normalize(it) }
}
