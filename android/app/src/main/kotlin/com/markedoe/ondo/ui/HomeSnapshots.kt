package com.markedoe.ondo.ui

import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.data.HomeSnapshot
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.HomeUiState
import java.security.MessageDigest

/**
 * Home kept for the next start ([HomeSnapshot]): made from a Home worked out with its catalogue and its carousel, shown
 * at a cold start while the catalogue is read, only when it is the same playlist, the same profile and nothing else Home
 * is made of changed since ([fingerprint]): another playlist or profile, hidden categories or languages, the rows'
 * order, the profile's lists (My List, what is watched), a setting or the app's language leave it unused.
 */
object HomeSnapshots {
    fun of(home: HomeUiState, state: OndoState, labels: Labels): HomeSnapshot? {
        val playlistId = state.activePlaylistId ?: return null
        return HomeSnapshot(
            playlistId = playlistId,
            profileId = state.activeProfile.id,
            fingerprint = fingerprint(state, labels),
            fetchedAt = state.content.fetchedAt,
            playlistName = home.playlistName,
            hero = home.hero.map { HomeSnapshot.Hero(title(it.item), it.meta, it.inMyList, it.resumable, it.backdropUrl, it.plot) },
            continueWatching = home.continueWatching.map {
                HomeSnapshot.Resume(it.key, it.title, it.subtitle, it.imageUrl, it.progress, it.minutesLeft, it.isSeries, it.hasPage, it.showcase?.let(::title), it.upNext)
            },
            myList = home.myList.map(::title),
            newMovies = home.newMovies.map(::title),
            newSeries = home.newSeries.map(::title),
            trending = home.trending.map(::title),
            hasVod = home.hasVod,
            order = home.order,
            selections = home.selections.map { (key, row) -> HomeSnapshot.Selection(key, row.id, row.name, row.tags, row.genreNames, row.items.map(::title)) },
            channels = home.channels.map { it.channel },
            channelsAreFavorites = home.channelsAreFavorites,
        )
    }

    /** Whether [snapshot] is Home as [state] would show it, its catalogue aside. */
    fun matches(snapshot: HomeSnapshot, state: OndoState, labels: Labels): Boolean =
        snapshot.version == HomeSnapshot.VERSION &&
            snapshot.playlistId == state.activePlaylistId &&
            snapshot.profileId == state.activeProfile.id &&
            snapshot.fingerprint == fingerprint(state, labels)

    /** Home drawn from [snapshot], with [state]'s own playlist name, profile and progress flags. */
    fun home(snapshot: HomeSnapshot, state: OndoState, now: Long): HomeUiState = HomeUiState(
        playlistName = state.activePlaylist?.name ?: snapshot.playlistName,
        updatedAt = snapshot.fetchedAt.takeIf { it > 0 },
        now = now,
        hero = snapshot.hero.mapNotNull { page -> item(page.title)?.let { HeroPage(it, page.meta, page.inMyList, page.resumable, page.backdropUrl, page.plot) } },
        continueWatching = snapshot.continueWatching.map {
            ResumeItem(it.key, it.title, it.subtitle, it.imageUrl, it.progress, it.minutesLeft, it.isSeries, it.hasPage, it.showcase?.let(::item), it.upNext)
        },
        myList = snapshot.myList.mapNotNull(::item),
        newMovies = snapshot.newMovies.mapNotNull(::item),
        newSeries = snapshot.newSeries.mapNotNull(::item),
        trending = snapshot.trending.mapNotNull(::item),
        hasVod = snapshot.hasVod,
        order = snapshot.order,
        selections = snapshot.selections.associate { row -> row.key to SelectionRow(row.id, row.name, row.tags, row.genreNames, row.items.mapNotNull(::item)) },
        channels = snapshot.channels.map { ChannelRow(it, null, null, null) },
        channelsAreFavorites = snapshot.channelsAreFavorites,
        isLoading = state.isLoading,
        isRestoring = state.isRestoring,
        profile = state.activeProfile.takeIf { state.profiles.isNotEmpty() },
    )

    /**
     * What Home is made of besides its catalogue, the time and what the device kept of TMDb, as one value: the playlist,
     * the profile and its lists of this playlist (My List, what is watched and where), hidden categories, languages and
     * words, the rows' order and selections, the settings Home reads, and the app's words and language.
     */
    fun fingerprint(state: OndoState, labels: Labels): String {
        val playlistId = state.activePlaylistId.orEmpty()
        val settings = state.settings
        val library = state.library
        val profile = state.activeProfile
        val text = buildString {
            append(HomeSnapshot.VERSION).append('\n')
            append(playlistId).append('\n')
            append(profile.id).append(' ').append(profile.isKids).append('\n')
            append(settings.showAdultCategories).append(' ').append(settings.hideWatchedMovies).append(' ').append(settings.groupVersions).append('\n')
            settings.chosenVersions.sorted().joinTo(this, ",").append('\n')
            library.hiddenCategories.sorted().joinTo(this, ",").append('\n')
            library.hiddenTags.sorted().joinTo(this, ",").append('\n')
            library.hiddenWords.sorted().joinTo(this, ",").append('\n')
            append(library.rowOrder(RowOrder.HOME)).append('\n')
            append(library.selections).append('\n')
            library.favorites.filter { it.playlistId == playlistId }.joinTo(this, ",") { "${it.kind}|${it.itemId}" }.append('\n')
            library.history.filter { it.playlistId == playlistId }.joinTo(this, ",") { "${it.key}@${it.position}/${it.duration}/${it.lastWatchedAt}" }.append('\n')
            append(labels.locale.toLanguageTag()).append('|').append(labels.movie).append('|').append(labels.series).append('|')
            append(labels.newEpisode).append('|').append(labels.dayMonth)
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun title(item: ShowcaseItem): HomeSnapshot.Title = when (item) {
        is ShowcaseItem.MovieItem -> HomeSnapshot.Title(movie = item.movie)
        is ShowcaseItem.SeriesItem -> HomeSnapshot.Title(series = item.series)
    }

    private fun item(title: HomeSnapshot.Title): ShowcaseItem? =
        title.movie?.let { ShowcaseItem.MovieItem(it) } ?: title.series?.let { ShowcaseItem.SeriesItem(it) }
}
