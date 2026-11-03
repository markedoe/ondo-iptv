package com.markedoe.ondo.data

import com.markedoe.ondo.core.library.FilmVersions
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.ui.model.ShowcaseItem
import java.util.Collections
import java.util.IdentityHashMap

/**
 * [items] with each film once ([FilmVersions.distinct]): the first of its versions stands for it; series stay as they
 * are. Unchanged when nothing is grouped.
 */
fun FilmVersions.distinctItems(items: List<ShowcaseItem>): List<ShowcaseItem> {
    if (!isGrouped || items.size < 2) return items
    val movies = items.mapNotNull { (it as? ShowcaseItem.MovieItem)?.movie }
    val kept = distinct(movies)
    if (kept.size == movies.size) return items
    val shown: MutableSet<Movie> = Collections.newSetFromMap(IdentityHashMap())
    shown.addAll(kept)
    return items.filter { it !is ShowcaseItem.MovieItem || it.movie in shown }
}
