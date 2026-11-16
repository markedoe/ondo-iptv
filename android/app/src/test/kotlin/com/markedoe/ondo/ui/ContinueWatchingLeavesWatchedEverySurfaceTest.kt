package com.markedoe.ondo.ui

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.model.ResumeItem
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Regression guard for Continue watching, on every surface that shows it: a title watched (as its poster's check mark
 * has it, `UiMappers.watchedKeys`) never shows there. A film leaves once one of its versions is marked watched, another
 * version left halfway included; a series leaves once every episode known is watched, whatever order they were watched
 * or marked in, and with its seasons not fetched yet (the playlist's own list); a title in progress and not watched
 * stays. The Apple apps have the same test (`ContinueWatchingLeavesWatchedEverySurfaceTests`). A new screen that shows
 * Continue watching is added to [surfaces] here.
 */
class ContinueWatchingLeavesWatchedEverySurfaceTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val labels = Labels("Movie", "Series", Locale.US)
    private val uhdFilms = MediaCategory("m-4k", "4K | Films", ContentType.MOVIE)

    private fun version(id: String, name: String, category: MediaCategory, tmdb: Int?) =
        Movie(id, name, categoryId = category.id, streamUrl = "http://example.com/movie/$id.mkv", year = 2024, tmdbId = tmdb, addedAt = Fixtures.NOW / 1000)

    // Glass City, listed four times (as `FilmVersionsAppTest`): one film.
    private val dv = version("g-dv", "Glass City 4K DV", uhdFilms, 77)
    private val uhd = version("g-4k", "Glass City 4K", uhdFilms, 77)
    private val vostfr = version("g-vostfr", "Glass City VOSTFR", Fixtures.sciFi, null)
    private val hd = Fixtures.glassCity.copy(tmdbId = 77)

    private val content = Fixtures.content.copy(
        movies = Fixtures.content.movies.map { if (it.id == hd.id) hd else it } + listOf(dv, uhd, vostfr),
        movieCategories = Fixtures.content.movieCategories + uhdFilms,
    )

    private val northernLights = Fixtures.northernLights
    private val silentHarbor = Fixtures.silentHarbor
    private val harborLights = Fixtures.harborLights
    private val archive = Fixtures.theArchive
    private val blueHorizon = Fixtures.blueHorizon

    private fun movie(movie: Movie, position: Double = 0.0, at: Long = 0) =
        WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, movie.id, title = movie.name, position = position, duration = 6_000.0, lastWatchedAt = at)

    private fun episode(series: Series, index: Int, position: Double = 0.0, at: Long = 0): WatchEntry {
        val episode = series.episodes[index]
        return WatchEntry(
            Fixtures.PLAYLIST_ID, ItemKind.SERIES, series.id, episode.id, series.name, "${episode.code} · ${episode.title}",
            position = position, duration = 2_700.0, lastWatchedAt = at,
        )
    }

    /**
     * Before: Glass City's 4K DV version, Northern Lights and Silent Harbor halfway; Harbor Lights halfway through its
     * second episode; Blue Horizon's third episode watched, then its first, its second halfway; The Archive's first
     * episode watched, its second halfway.
     */
    private val before: LibraryState = listOf(
        movie(dv, 600.0, 10),
        movie(northernLights, 600.0, 11),
        movie(silentHarbor, 600.0, 12),
        episode(harborLights, 0, 2_700.0, 13),
        episode(harborLights, 1, 600.0, 14),
        episode(blueHorizon, 2, 2_700.0, 15),
        episode(blueHorizon, 0, 2_700.0, 16),
        episode(blueHorizon, 1, 600.0, 17),
        episode(archive, 0, 2_700.0, 18),
        episode(archive, 1, 600.0, 19),
    ).fold(LibraryState()) { library, entry -> library.copy(history = listOf(entry) + library.history) }

    /** Marks a series' episodes as its page does (`OndoRepository.setEpisodesWatched`). */
    private fun LibraryState.marking(series: Series, indices: List<Int>, from: Long): LibraryState =
        markingEpisodes(indices.map { episode(series, it) }, watched = true, order = series.episodes.map { it.id }, stamps = indices.indices.map { from + it })

    /**
     * After, as the pages mark them: Glass City's page marks its HD version watched (the version it shows), Northern
     * Lights is marked watched, Harbor Lights is marked watched whole, Blue Horizon's second episode is marked watched
     * (every episode watched, the latest not the last one). The Archive and Silent Harbor stay in progress.
     */
    private val after: LibraryState = before
        .markingWatched(movie(hd), now = 20)
        .markingWatched(movie(northernLights), now = 21)
        .marking(harborLights, harborLights.episodes.indices.toList(), from = 22)
        .marking(blueHorizon, listOf(1), from = 30)

    private fun state(library: LibraryState): OndoState = Fixtures.state(library, content)

    /** The title of a card ([ResumeItem.key] is its entry's: an episode's for a series). */
    private fun titles(cards: List<ResumeItem>): Set<String> = cards.mapNotNull { WatchEntry.ofKey(it.key)?.itemId }.toSet()

    /**
     * Every surface that shows Continue watching, with the cards it draws for [state]. Home on a phone (`HomeScreen`) and
     * on a TV (`TvHomeScreen`, its movies and series rows) draw [HomeUiState.continueWatching]; the Movies and Series tabs
     * (`VodScreen`, phone and TV) theirs; Home kept for the next start (a snapshot made while the titles were in progress,
     * shown at launch before the catalogue is read) its own.
     */
    private fun surfaces(state: OndoState): Map<String, Set<String>> {
        val home = titles(UiMappers.home(state, labels, Fixtures.NOW, Fixtures.kept()).continueWatching)
        val kept = state(before).let { earlier ->
            requireNotNull(HomeSnapshots.of(UiMappers.home(earlier, labels, Fixtures.NOW, Fixtures.kept()), earlier, labels))
        }
        // At launch: the catalogue and the seasons not read yet.
        val launching = Fixtures.state(state.library, PlaylistContent.EMPTY).copy(isRestoring = true)
        return mapOf(
            "phone Home (HomeScreen)" to home,
            "TV Home (TvHomeScreen)" to home,
            "Movies tab (VodScreen)" to titles(UiMappers.vod(state, series = false, title = "").continueWatching),
            "Series tab (VodScreen)" to titles(UiMappers.vod(state, series = true, title = "").continueWatching),
            "saved Home at launch (HomeSnapshots)" to titles(HomeSnapshots.home(kept, launching, Fixtures.NOW).continueWatching),
        )
    }

    private fun tabOf(surface: String): ItemKind? = when {
        surface.startsWith("Movies") -> ItemKind.MOVIE
        surface.startsWith("Series") -> ItemKind.SERIES
        else -> null
    }

    private fun expected(surface: String, movies: Set<String>, series: Set<String>): Set<String> = when (tabOf(surface)) {
        ItemKind.MOVIE -> movies
        ItemKind.SERIES -> series
        else -> movies + series
    }

    @After
    fun forget() {
        UiMappers.forgetCatalogue()
    }

    @Test
    fun everyTitleInProgressShowsBeforeItIsMarked() {
        for ((surface, shown) in surfaces(state(before))) {
            assertEquals(
                "$surface: every title in progress",
                expected(surface, setOf(dv.id, northernLights.id, silentHarbor.id), setOf(harborLights.id, blueHorizon.id, archive.id)),
                shown,
            )
        }
    }

    /** No Continue watching shows a title watched; those in progress and not watched stay. Apple: `testNoContinueWatchingShowsATitleWatched`. */
    @Test
    fun noContinueWatchingShowsATitleWatched() {
        val watched = UiMappers.watchedKeys(state(after))
        assertTrue("Glass City is watched, every version", setOf("m-${hd.id}", "m-${dv.id}").all { it in watched })
        assertTrue("Blue Horizon is watched", "s-${blueHorizon.id}" in watched)
        for ((surface, shown) in surfaces(state(after))) {
            assertEquals("$surface: only the titles in progress and not watched", expected(surface, setOf(silentHarbor.id), setOf(archive.id)), shown)
        }
    }

    /** A series watched whole leaves, its seasons not fetched yet too: the playlist's own list of its episodes says so. */
    @Test
    fun aSeriesWatchedLeavesWithoutItsSeasons() {
        val unknown = state(after).copy(seriesDetails = emptyMap())
        assertEquals(setOf(archive.id), titles(UiMappers.home(unknown, labels, Fixtures.NOW, Fixtures.kept()).continueWatching.filter { it.isSeries }))
        assertEquals(setOf(archive.id), titles(UiMappers.vod(unknown, series = true, title = "").continueWatching))
    }

    /**
     * Marked unwatched again (every version, as its page does), the film's version left halfway comes back with its
     * resume point: marking a film watched never erased it.
     */
    @Test
    fun aFilmMarkedUnwatchedComesBackWithItsVersionInProgress() {
        val unmarked = listOf(hd, dv, uhd, vostfr).fold(after) { library, version -> library.markingUnwatched(movie(version).key, now = 40) }
        val home = UiMappers.home(state(unmarked), labels, Fixtures.NOW, Fixtures.kept()).continueWatching
        val card = home.firstOrNull { WatchEntry.ofKey(it.key)?.itemId == dv.id }
        assertTrue("Glass City is back, by its 4K DV version: $home", card != null)
        assertEquals(0.1f, card!!.progress, 0.001f)
    }
}
