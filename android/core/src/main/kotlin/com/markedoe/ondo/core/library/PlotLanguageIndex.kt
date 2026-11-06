package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.model.PlaylistContent

/**
 * The movies and series of a catalogue by the language of their provider's description ([PlotLanguage.detect]), for
 * hidden languages: those of categories with no language tag ([CategoryTags.readsPlots]) only, and never English
 * ([PlotLanguage.neverHidden]). Built once per catalogue, off the main thread, and only once a hidden language needs it;
 * hiding or showing a language then only gathers sets ([hidden]). Same rules as the Swift `PlotLanguageIndex`.
 */
class PlotLanguageIndex(content: PlaylistContent) {
    val movies: Map<String, Set<String>>
    val series: Map<String, Set<String>>

    init {
        val readsPlots = HashSet<String>()
        for (category in content.movieCategories) if (CategoryTags.readsPlots(CategoryTags.tagOf(category.name))) readsPlots += category.id
        for (category in content.seriesCategories) if (CategoryTags.readsPlots(CategoryTags.tagOf(category.name))) readsPlots += category.id
        val movies = HashMap<String, MutableSet<String>>()
        for (movie in content.movies) {
            if (movie.plot == null || movie.categoryId !in readsPlots) continue
            val language = PlotLanguage.detect(movie.plot) ?: continue
            if (language !in PlotLanguage.neverHidden) movies.getOrPut(language) { HashSet() } += movie.id
        }
        val series = HashMap<String, MutableSet<String>>()
        for (show in content.series) {
            if (show.plot == null || show.categoryId !in readsPlots) continue
            val language = PlotLanguage.detect(show.plot) ?: continue
            if (language !in PlotLanguage.neverHidden) series.getOrPut(language) { HashSet() } += show.id
        }
        this.movies = movies
        this.series = series
    }

    /** The movies and the series described in one of these languages ([PlotLanguage.hiddenLanguages]). */
    fun hidden(languages: Set<String>): Pair<Set<String>, Set<String>> {
        val movieIds = HashSet<String>()
        val seriesIds = HashSet<String>()
        for (language in languages) {
            movies[language]?.let { movieIds += it }
            series[language]?.let { seriesIds += it }
        }
        return movieIds to seriesIds
    }
}
