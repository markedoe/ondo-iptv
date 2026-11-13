package com.markedoe.ondo.ui

import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.data.HomeSnapshot
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.HomeUiState
import java.security.MessageDigest

/**
 * Home kept for the next start ([HomeSnapshot]): made from a Home worked out with its catalogue and its carousel, shown
 * at a cold start while the catalogue is read, only when it is the same playlist, the same profile and nothing else Home
 * is made of changed since ([fingerprint]): another playlist or profile, hidden categories, languages or words, the
 * rows' order, a setting or the app's language leave it unused. The profile's own lists are checked as it is shown
 * ([home]): only what its history and My List still hold shows, so what was watched or changed since Home was kept
 * (in the player, on a title's page) never brings back what is gone, and nothing of another profile ever shows.
 */
object HomeSnapshots {
    fun of(home: HomeUiState, state: OndoState, labels: Labels): HomeSnapshot? {
        val playlistId = state.activePlaylistId ?: return null
        // Each card's entry of the history: the latest of its title, as Continue watching takes them.
        val entries = state.library.continueWatching(playlistId).associateBy { "${it.kind}|${it.itemId}" }
        return HomeSnapshot(
            playlistId = playlistId,
            profileId = state.activeProfile.id,
            fingerprint = fingerprint(state, labels),
            fetchedAt = state.content.fetchedAt,
            day = SelectionRows.day(home.now),
            playlistName = home.playlistName,
            hero = home.hero.map { HomeSnapshot.Hero(listed(it.item, state), it.meta, it.inMyList, it.resumable, it.backdropUrl, it.plot) },
            continueWatching = home.continueWatching.mapNotNull { card ->
                val entry = WatchEntry.ofKey(card.key)?.let { entries["${it.kind}|${it.itemId}"] } ?: return@mapNotNull null
                HomeSnapshot.Resume(
                    card.key, card.title, card.subtitle, card.imageUrl, card.progress, card.minutesLeft, card.isSeries, card.hasPage,
                    card.showcase?.let(::title), card.upNext, entryKey = entry.key, entryResumable = entry.isResumable,
                )
            },
            myList = home.myList.map { listed(it, state) },
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

    /**
     * Whether [snapshot] is Home as [state] would show it at [now], its catalogue and the profile's lists aside: made the
     * same day (the carousel and the selections' rows are a new pick each day).
     */
    fun matches(snapshot: HomeSnapshot, state: OndoState, labels: Labels, now: Long): Boolean =
        snapshot.version == HomeSnapshot.VERSION &&
            snapshot.day == SelectionRows.day(now) &&
            snapshot.playlistId == state.activePlaylistId &&
            snapshot.profileId == state.activeProfile.id &&
            snapshot.fingerprint == fingerprint(state, labels)

    /**
     * Home drawn from [snapshot], with [state]'s own playlist name, profile and progress flags, and its lists as the
     * profile's library has them now: a card to continue watching only while its entry is still its title's latest (its
     * progress as it is now), a title of My List only while My List holds it, the carousel's My List mark as it is now.
     */
    fun home(snapshot: HomeSnapshot, state: OndoState, now: Long): HomeUiState {
        val playlistId = state.activePlaylistId.orEmpty()
        val library = state.library
        val entries = if (playlistId == snapshot.playlistId) library.continueWatching(playlistId).associateBy { "${it.kind}|${it.itemId}" } else emptyMap()
        fun inMyList(title: HomeSnapshot.Title): Boolean = when {
            title.movie != null -> (title.ids + title.movie.id).any { library.isFavorite(playlistId, ItemKind.MOVIE, it) }
            title.series != null -> library.isFavorite(playlistId, ItemKind.SERIES, title.series.id)
            else -> false
        }
        return HomeUiState(
            playlistName = state.activePlaylist?.name ?: snapshot.playlistName,
            updatedAt = snapshot.fetchedAt.takeIf { it > 0 },
            now = now,
            hero = snapshot.hero.mapNotNull { page -> item(page.title)?.let { HeroPage(it, page.meta, inMyList(page.title), page.resumable, page.backdropUrl, page.plot) } },
            continueWatching = snapshot.continueWatching.mapNotNull { card ->
                val kept = WatchEntry.ofKey(card.key)?.takeIf { it.playlistId == playlistId } ?: return@mapNotNull null
                val entry = entries["${kept.kind}|${kept.itemId}"] ?: return@mapNotNull null
                if (entry.key != card.entryKey || entry.isResumable != card.entryResumable) return@mapNotNull null
                val same = entry.key == card.key && !card.upNext
                ResumeItem(
                    card.key, card.title, card.subtitle, card.imageUrl,
                    progress = if (same) entry.progress.toFloat() else card.progress,
                    minutesLeft = if (same) entry.minutesLeft else card.minutesLeft,
                    isSeries = card.isSeries, hasPage = card.hasPage, showcase = card.showcase?.let(::item), upNext = card.upNext,
                )
            },
            myList = snapshot.myList.filter(::inMyList).mapNotNull(::item),
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
    }

    /**
     * What Home is made of besides its catalogue, the time, what the device kept of TMDb and the profile's lists (checked
     * as it is shown), as one value: the playlist, the profile, hidden categories, languages and words, the rows' order
     * and selections, the settings Home reads, and the app's words and language.
     */
    fun fingerprint(state: OndoState, labels: Labels): String {
        val settings = state.settings
        val library = state.library
        val profile = state.activeProfile
        val text = buildString {
            append(HomeSnapshot.VERSION).append('\n')
            append(state.activePlaylistId.orEmpty()).append('\n')
            append(profile.id).append(' ').append(profile.isKids).append('\n')
            append(settings.showAdultCategories).append(' ').append(settings.hideWatchedMovies).append(' ').append(settings.groupVersions).append('\n')
            settings.chosenVersions.sorted().joinTo(this, ",").append('\n')
            library.hiddenCategories.sorted().joinTo(this, ",").append('\n')
            library.hiddenTags.sorted().joinTo(this, ",").append('\n')
            library.hiddenWords.sorted().joinTo(this, ",").append('\n')
            append(library.rowOrder(RowOrder.HOME)).append('\n')
            append(library.selections).append('\n')
            append(labels.locale.toLanguageTag()).append('|').append(labels.movie).append('|').append(labels.series).append('|')
            append(labels.newEpisode).append('|').append(labels.dayMonth)
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * The titles of [snapshot]'s cards to continue watching, by the title they are of ([resumeKey]): the Movies and
     * Series tabs show them in a TV's header while the catalogue is read.
     */
    fun resumeTitles(snapshot: HomeSnapshot?): Map<String, ShowcaseItem> =
        snapshot?.continueWatching.orEmpty().mapNotNull { card ->
            val entry = WatchEntry.ofKey(card.key) ?: return@mapNotNull null
            val item = card.showcase?.let(::item) ?: return@mapNotNull null
            resumeKey(entry) to item
        }.toMap()

    /** The title an entry of the history is of, its playlist's: the same in every one of its entries (its episodes). */
    fun resumeKey(entry: WatchEntry): String = "${entry.playlistId}|${entry.kind}|${entry.itemId}"

    private fun title(item: ShowcaseItem): HomeSnapshot.Title = when (item) {
        is ShowcaseItem.MovieItem -> HomeSnapshot.Title(movie = item.movie)
        is ShowcaseItem.SeriesItem -> HomeSnapshot.Title(series = item.series)
    }

    /** A title with the ids My List may hold it by: a film by any of its versions. */
    private fun listed(item: ShowcaseItem, state: OndoState): HomeSnapshot.Title = when (item) {
        is ShowcaseItem.MovieItem -> HomeSnapshot.Title(movie = item.movie, ids = UiMappers.visibleVersions(state, item.movie, UiMappers.filmsIfReady(state)).map { it.id })
        is ShowcaseItem.SeriesItem -> HomeSnapshot.Title(series = item.series, ids = listOf(item.series.id))
    }

    private fun item(title: HomeSnapshot.Title): ShowcaseItem? =
        title.movie?.let { ShowcaseItem.MovieItem(it) } ?: title.series?.let { ShowcaseItem.SeriesItem(it) }
}
