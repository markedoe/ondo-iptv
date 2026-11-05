package com.markedoe.ondo.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import com.markedoe.ondo.R
import kotlinx.serialization.Serializable

/** Type-safe navigation destinations. */
object Routes {
    @Serializable data object Home
    @Serializable data object Movies
    @Serializable data object Series
    @Serializable data object Live
    @Serializable data object Search
    @Serializable data object MyList
    @Serializable data class MovieDetail(val id: String)
    @Serializable data class SeriesDetail(val id: String)

    /** The movies and series of an actor or director (a name tapped on a detail page). */
    @Serializable data class Person(val name: String)

    /** The titles sharing an award, a festival, a year or a genre (`BrowseKind` name, value). */
    @Serializable data class Browse(val kind: String, val value: String)

    @Serializable data object Guide
    @Serializable data object Reminders

    /** A collection of My List. */
    @Serializable data class Collection(val id: String)

    /**
     * Poster grid: every movie or series, one category, My List (of one type, or both with [anyType]), or a row of titles
     * Ondo picks ([source], a [GridSource] name: every title of the row, in the row's own order).
     */
    @Serializable data class Grid(
        val series: Boolean,
        val categoryId: String? = null,
        val myList: Boolean = false,
        val anyType: Boolean = false,
        val source: String? = null,
        /** A selection's titles (its ID), as its row in [selectionTab] (`home`, `movie`, `series`) picks them today. */
        val selection: String? = null,
        val selectionTab: String? = null,
    )
    @Serializable data object Settings

    /** Which categories are shown, opened on one type (`ContentType.rawValue`). */
    @Serializable data class Categories(val type: String = "movie")
    @Serializable data object Help

    /** Settings → Row order: the order of the rows of the Movies and Series tabs. */
    @Serializable data object RowOrder

    /** A selection made of tags (Settings → Row order → New selection): [id] null makes one, shown in [tab] at first. */
    @Serializable data class SelectionEdit(val id: String? = null, val tab: String = "movie")
    /** The profile switcher (the top bar's avatar), and a profile's page: [id] null adds one (docs/PROFILES.md). */
    @Serializable data object Profiles
    @Serializable data class ProfileEdit(val id: String? = null)
    @Serializable data object AddPlaylist

    /** The privacy policy or the support page inside the app, on a TV (no browser there: Google's TV-WB). */
    @Serializable data class Web(val url: String)
}

/**
 * The rows whose titles Ondo picks rather than the provider's categories, as a grid of their own ([Routes.Grid.source]):
 * See all at the end of the row shows all of them, in the row's order ([UiMappers.rowTitles]).
 */
enum class GridSource {
    /** New movies or series: released this year or last year, the newest first (`Showcase.newMovies`). */
    NEW,

    /** Award winners, films or series: the most recent award first ([MovieAwards.winners], [MovieAwards.seriesWinners]). */
    AWARDS,
    ;

    companion object {
        /** The source a route names ([Routes.Grid.source]); null for none, or one this version does not know. */
        fun of(name: String?): GridSource? = entries.firstOrNull { it.name == name }
    }
}

/** The tabs: bottom bar on phones, rail on tablets, Chromebooks and TVs. Search is the magnifying glass of each screen. */
enum class TopLevel(val route: Any, @StringRes val label: Int, val icon: ImageVector, val tag: String) {
    HOME(Routes.Home, R.string.home, Icons.Rounded.Home, "tabHome"),
    MOVIES(Routes.Movies, R.string.movies, Icons.Rounded.Movie, "tabMovies"),
    SERIES(Routes.Series, R.string.series, Icons.Rounded.VideoLibrary, "tabSeries"),
    LIVE(Routes.Live, R.string.live_tv, Icons.Rounded.LiveTv, "tabLive"),
    MY_LIST(Routes.MyList, R.string.my_list, Icons.Rounded.Bookmarks, "tabMyList"),
}
