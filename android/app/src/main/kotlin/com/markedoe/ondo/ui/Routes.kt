package com.markedoe.ondo.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
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
    @Serializable data class MovieDetail(val id: String)
    @Serializable data class SeriesDetail(val id: String)

    /** Poster grid: every movie or series, one category, or My List (of one type, or both with [anyType]). */
    @Serializable data class Grid(val series: Boolean, val categoryId: String? = null, val myList: Boolean = false, val anyType: Boolean = false)
    @Serializable data object Settings

    /** Which categories are shown, opened on one type (`ContentType.rawValue`). */
    @Serializable data class Categories(val type: String = "movie")
    @Serializable data object AddPlaylist
}

/** The tabs: bottom bar on phones, rail on tablets and Chromebooks. */
enum class TopLevel(val route: Any, @StringRes val label: Int, val icon: ImageVector, val tag: String) {
    HOME(Routes.Home, R.string.home, Icons.Rounded.Home, "tabHome"),
    MOVIES(Routes.Movies, R.string.movies, Icons.Rounded.Movie, "tabMovies"),
    SERIES(Routes.Series, R.string.series, Icons.Rounded.VideoLibrary, "tabSeries"),
    LIVE(Routes.Live, R.string.live_tv, Icons.Rounded.LiveTv, "tabLive"),
    SEARCH(Routes.Search, R.string.search, Icons.Rounded.Search, "tabSearch"),
}
