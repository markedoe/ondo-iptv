package com.markedoe.ondo.data

import com.markedoe.ondo.ui.theme.Appearance
import kotlinx.serialization.Serializable

/** A playlist as the user configured it (credentials stay in the app's private storage). */
@Serializable
data class SavedPlaylist(
    val id: String,
    val name: String,
    /** M3U link, or the Xtream server address. */
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val epgUrl: String? = null,
    val addedAt: Long = 0,
) {
    val isXtream: Boolean get() = username != null && password != null
}

/** Order of the movie and series grids. */
@Serializable
enum class PosterSort { PLAYLIST, NAME, RECENT, RELEASE, RATING }

@Serializable
data class AppSettings(
    val appearance: Appearance = Appearance.DARK,
    val activePlaylistId: String? = null,
    /** Categories that look adult (see `AdultContentDetector`) are hidden unless this is on. */
    val showAdultCategories: Boolean = false,
    val posterSort: PosterSort = PosterSort.PLAYLIST,
)
