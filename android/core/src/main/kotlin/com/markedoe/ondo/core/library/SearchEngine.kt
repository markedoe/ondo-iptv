package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Series

/** Matches of a search, per content type, best first. */
data class SearchResults(
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
) {
    val isEmpty: Boolean
        get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()

    val totalCount: Int
        get() = channels.size + movies.size + series.size
}

/**
 * Accent / case insensitive, word-prefix search over a playlist. Names are normalised once, at construction;
 * build one engine per loaded playlist and reuse it for every query.
 */
class SearchEngine(content: PlaylistContent) {
    private class Indexed<T>(val key: String, val item: T, val categoryId: String)

    private val channels = content.channels.map { Indexed(TextNormalizer.normalize(it.name), it, it.categoryId) }
    private val movies = content.movies.map { Indexed(TextNormalizer.normalize(it.name), it, it.categoryId) }
    private val series = content.series.map { Indexed(TextNormalizer.normalize(it.name), it, it.categoryId) }

    /**
     * Items whose name contains every word of `query`. Exact names come first, then names starting with the
     * query, then the others (each group in playlist order).
     */
    fun search(query: String, limitPerType: Int = 150, excludedCategoryIds: Set<String> = emptySet()): SearchResults {
        val normalized = TextNormalizer.normalize(query)
        if (normalized.isEmpty()) return SearchResults()
        val tokens = TextNormalizer.tokens(normalized)

        fun <T> run(list: List<Indexed<T>>): List<T> {
            val exact = ArrayList<T>()
            val prefix = ArrayList<T>()
            val other = ArrayList<T>()
            for (entry in list) {
                if (entry.categoryId in excludedCategoryIds || !TextNormalizer.matches(entry.key, tokens)) continue
                when {
                    entry.key == normalized -> exact.add(entry.item)
                    entry.key.startsWith(normalized) -> prefix.add(entry.item)
                    else -> other.add(entry.item)
                }
                if (exact.size + prefix.size >= limitPerType) break
            }
            return (exact + prefix + other).take(limitPerType)
        }

        return SearchResults(channels = run(channels), movies = run(movies), series = run(series))
    }
}
