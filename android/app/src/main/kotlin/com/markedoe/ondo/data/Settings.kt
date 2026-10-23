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
    /** When it was added or last edited, for sync; 0 for playlists saved before sync (then [addedAt]). */
    val updatedAt: Long = 0,
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
    /** Playlists whose "show only your languages?" suggestion was answered or dismissed. */
    val dismissedTagSuggestions: Set<String> = emptySet(),
    /** Newer awards and festival selections downloaded once a week (see [AwardsDownload]). */
    val updateAwards: Boolean = true,
    val awardsCheckedAt: Long = 0,
    /** Seconds the player gathers before it shows a live channel, and a movie or an episode; 0 is automatic (see `PlaybackBuffer`). */
    val liveBuffer: Int = 0,
    val vodBuffer: Int = 0,
    /** Movies finished (see [WatchEntry.isFinished]) left out where the viewer browses movies; search and My List keep them. */
    val hideWatchedMovies: Boolean = false,
    /** Sync with Ondo on other devices of the local network (docs/SYNC.md); off until the viewer turns it on. */
    val syncOnLocalNetwork: Boolean = false,
    /** This installation's ID for sync, made once. */
    val syncDeviceId: String = "",
)
