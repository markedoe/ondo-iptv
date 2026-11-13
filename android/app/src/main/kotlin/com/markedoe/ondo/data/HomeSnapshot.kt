package com.markedoe.ondo.data

import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import kotlinx.serialization.Serializable

/**
 * Home as it was last drawn for a playlist with its catalogue and its carousel: the featured titles and the first titles
 * of each row, nothing more. The next start shows it at once, while the saved catalogue is read and Home is worked out
 * anew (`ui/HomeSnapshots.kt`), only for the same playlist, profile and everything else Home is made of ([fingerprint]).
 * It is kept as `home-<playlist id>.json` next to the catalogue, and goes with the playlist.
 */
@Serializable
data class HomeSnapshot(
    val version: Int = VERSION,
    val playlistId: String,
    val profileId: String,
    /** What else Home was made of: settings, hidden categories, languages and words, row order and selections, the app's language. */
    val fingerprint: String,
    /** When the catalogue it was made from was downloaded. */
    val fetchedAt: Long,
    /** The device's date it was made on ("2026-11-13"): the carousel and the selections' rows are a new pick each day. */
    val day: String = "",
    val playlistName: String,
    val hero: List<Hero> = emptyList(),
    val continueWatching: List<Resume> = emptyList(),
    val myList: List<Title> = emptyList(),
    val newMovies: List<Title> = emptyList(),
    val newSeries: List<Title> = emptyList(),
    val trending: List<Title> = emptyList(),
    val hasVod: Boolean = false,
    val order: List<String>? = null,
    val selections: List<Selection> = emptyList(),
    /** The channels' row, without what is on (the guide is read later). */
    val channels: List<Channel> = emptyList(),
    val channelsAreFavorites: Boolean = false,
    /**
     * TMDb's score of its titles, by poster key, formatted in the app's language ("7.8"): their badges show at once at
     * the next start, before what the device kept is read. Absent from a snapshot kept before: none then.
     */
    val scores: Map<String, String> = emptyMap(),
) {
    /**
     * A movie or a series of a row; in the carousel and My List, the ids My List may hold it by ([ids]: each version of
     * a film), checked against the profile's My List when shown.
     */
    @Serializable
    data class Title(val movie: Movie? = null, val series: Series? = null, val ids: List<String> = emptyList())

    /** A page of the carousel. */
    @Serializable
    data class Hero(
        val title: Title,
        val meta: String,
        val inMyList: Boolean,
        val resumable: Boolean,
        val backdropUrl: String? = null,
        val plot: String? = null,
    )

    /**
     * A card to continue watching, and the entry of the history it came from ([entryKey], resumable or not): shown only
     * while the profile's history still has that entry as its title's latest.
     */
    @Serializable
    data class Resume(
        val key: String,
        val title: String,
        val subtitle: String? = null,
        val imageUrl: String? = null,
        val progress: Float,
        val minutesLeft: Int,
        val isSeries: Boolean = false,
        val hasPage: Boolean = false,
        val showcase: Title? = null,
        val upNext: Boolean = false,
        val entryKey: String,
        val entryResumable: Boolean,
    )

    /** A selection's row, under its row key. */
    @Serializable
    data class Selection(
        val key: String,
        val id: String,
        val name: String,
        val tags: List<String>,
        val genreNames: Map<String, String> = emptyMap(),
        val items: List<Title>,
    )

    companion object {
        /** Another layout of Home's state: a snapshot of an older one is not shown. */
        const val VERSION = 1
    }
}
