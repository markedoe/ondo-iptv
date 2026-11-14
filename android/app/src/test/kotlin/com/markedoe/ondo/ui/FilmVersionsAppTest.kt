package com.markedoe.ondo.ui

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.PictureSupport
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.screens.BrowseKind
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A film the playlist lists several times (4K Dolby Vision, 4K, HD, VOSTFR) shows once in every list, in every
 * category one of its versions is in, with its versions on its page; its state comes from its versions; "Group
 * versions" off is the playlist as it is.
 */
class FilmVersionsAppTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val labels = Labels("Movie", "Series", Locale.US)
    private val uhdFilms = MediaCategory("m-4k", "4K | Films", ContentType.MOVIE)

    private fun version(id: String, name: String, category: MediaCategory, tmdb: Int?) =
        Movie(id, name, categoryId = category.id, streamUrl = "http://example.com/movie/$id.mkv", year = 2024, tmdbId = tmdb, addedAt = Fixtures.NOW / 1000)

    private val dv = version("g-dv", "Glass City 4K DV", uhdFilms, 77)
    private val uhd = version("g-4k", "Glass City 4K", uhdFilms, 77)
    private val vostfr = version("g-vostfr", "Glass City VOSTFR", Fixtures.sciFi, null)
    private val hd = Fixtures.glassCity.copy(tmdbId = 77)

    private val content = Fixtures.content.copy(
        movies = Fixtures.content.movies.map { if (it.id == hd.id) hd else it } + listOf(dv, uhd, vostfr),
        movieCategories = Fixtures.content.movieCategories + uhdFilms,
    )

    private fun state(library: LibraryState = LibraryState(), grouped: Boolean = true, hideWatched: Boolean = false, chosen: List<String> = emptyList()) =
        Fixtures.state(library, content, AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, groupVersions = grouped, hideWatchedMovies = hideWatched, chosenVersions = chosen))

    private val glass = setOf(hd.id, dv.id, uhd.id, vostfr.id)

    private fun count(items: List<String>) = items.count { it in glass }

    private fun lists(state: OndoState): Map<String, List<String>> {
        val vod = UiMappers.vod(state, series = false, title = "")
        val index = UiMappers.SearchIndex(state.content, UiMappers.hiddenCategoryIds(state), films = UiMappers.films(state))
        return mapOf(
            "grid" to UiMappers.grid(state, false, null, false, "").map { (it as com.markedoe.ondo.ui.model.ShowcaseItem.MovieItem).movie.id },
            "sci-fi row" to vod.rows.firstOrNull { it.category.id == Fixtures.sciFi.id }?.items.orEmpty().map { it.key.removePrefix("m-") },
            "4K row" to vod.rows.firstOrNull { it.category.id == uhdFilms.id }?.items.orEmpty().map { it.key.removePrefix("m-") },
            "year" to UiMappers.browse(state, BrowseKind.YEAR, "2024").movies.map { it.key.removePrefix("m-") },
            "search" to UiMappers.search(index, "glass", state, Fixtures.NOW).movies.map { it.key.removePrefix("m-") },
            "new" to UiMappers.home(state, labels, Fixtures.NOW).newMovies.map { it.key.removePrefix("m-") },
        )
    }

    @After
    fun forget() {
        UiMappers.forgetCatalogue()
        UiMappers.pictureSupport = PictureSupport()
    }

    @Test
    fun aFilmShowsOnceInEveryListAndInEachCategoryOfItsVersions() {
        val grouped = lists(state())
        grouped.forEach { (list, ids) -> assertEquals("$list: the film once", 1, count(ids)) }
        assertEquals("the sci-fi row keeps the first version it lists", listOf(hd.id), grouped.getValue("sci-fi row").filter { it in glass })
        assertEquals("the 4K row too", listOf(dv.id), grouped.getValue("4K row").filter { it in glass })
        assertEquals("every other film stays", Fixtures.content.movies.size - 2, UiMappers.grid(state(), false, null, false, "").size - 1)
    }

    /** The store screenshot's search built its index without the versions and showed Sintel three times. */
    @Test
    fun searchAsTheAppBuildsItShowsAFilmOnce() {
        val found = UiMappers.search(UiMappers.SearchIndex.of(state()), "glass", state(), Fixtures.NOW).movies.map { it.key.removePrefix("m-") }
        assertEquals(1, count(found))
        val off = UiMappers.search(UiMappers.SearchIndex.of(state(grouped = false)), "glass", state(grouped = false), Fixtures.NOW).movies.map { it.key.removePrefix("m-") }
        assertEquals(4, count(off))
    }

    /** The screens asking at once when a catalogue arrives get one grouping, built once (a TV holds one in memory). */
    @Test
    fun everyScreenGetsTheSameGrouping() {
        val fresh = content.copy(fetchedAt = 42)
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map { pool.submit<com.markedoe.ondo.core.library.FilmVersions> { start.await(); UiMappers.films(fresh) } }
            start.countDown()
            val first = results.first().get()
            results.forEach { assertSame(first, it.get()) }
        } finally {
            pool.shutdown()
        }
    }

    /**
     * A series' Play (a tap, on the main thread) finds its episode without grouping the catalogue's films: it never waits
     * for a large catalogue's grouping being built.
     */
    @Test
    fun aSeriesPlaysWithoutWaitingForTheGrouping() {
        val fresh = Fixtures.state(content = content.copy(fetchedAt = 7))
        val series = Fixtures.harborLights
        val next = UiMappers.nextEpisode(fresh, series, fresh.seriesDetails.getValue(series.id))
        assertEquals(series.episodes.first().id, next?.first?.id)
        assertSame("nothing grouped on the way", com.markedoe.ondo.core.library.FilmVersions.NONE, UiMappers.filmsIfReady(fresh))
    }

    /** A version whose title differs from its film's card is found by its own words, and by its own year. */
    @Test
    fun aVersionIsFoundByItsOwnWords() {
        val heritage = version("g-heritage", "Glass City : Héritage", Fixtures.sciFi, 77).copy(year = 2024)
        val state = Fixtures.state(content = content.copy(movies = content.movies + heritage))
        assertEquals("grouped with the film", UiMappers.films(state).groupOf(hd), UiMappers.films(state).groupOf(heritage))
        val index = UiMappers.SearchIndex.of(state)
        assertEquals(listOf(heritage.id), UiMappers.search(index, "heritage", state, Fixtures.NOW).movies.map { it.key.removePrefix("m-") })
        val byYear = UiMappers.search(index, "glass", state, Fixtures.NOW, com.markedoe.ondo.ui.screens.SearchFilters(fromYear = 2024, toYear = 2024))
        assertEquals("one card for the film", 1, byYear.movies.count { it.key.removePrefix("m-") in glass + heritage.id })
    }

    @Test
    fun groupVersionsOffIsThePlaylistAsItIs() {
        val off = lists(state(grouped = false))
        assertEquals(4, count(off.getValue("grid")))
        assertEquals(2, count(off.getValue("sci-fi row")))
        assertEquals(2, count(off.getValue("4K row")))
        assertEquals(4, count(off.getValue("search")))
        assertTrue(UiMappers.movieDetail(state(grouped = false), hd).versions.isEmpty())
    }

    @Test
    fun aFilmIsWatchedWhenOneOfItsVersionsIsFinished() {
        val finished = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, vostfr.id, title = vostfr.name, lastWatchedAt = 1)
        val library = LibraryState().recordStart(finished).updateProgress(finished.key, 5_900.0, 6_000.0, now = 2)
        val hidden = lists(state(library, hideWatched = true))
        listOf("grid", "sci-fi row", "4K row", "year", "new").forEach { list -> assertEquals("$list leaves every version out", 0, count(hidden.getValue(list))) }
        assertEquals("search keeps it", 1, count(hidden.getValue("search")))
    }

    @Test
    fun aFilmIsInMyListWhenOneOfItsVersionsIs() {
        val library = LibraryState().toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, uhd.id, uhd.name, addedAt = 3))
        val state = state(library)
        UiMappers.films(state)
        val keys = UiMappers.myListKeys(state)
        assertTrue(glass.all { "m-$it" in keys })
        assertTrue(UiMappers.isFavorite(state, com.markedoe.ondo.ui.model.ShowcaseItem.MovieItem(hd)))
        assertEquals(listOf(uhd.id), UiMappers.myList(state, Fixtures.NOW).movies.map { it.key.removePrefix("m-") }.filter { it in glass })
    }

    @Test
    fun aFilmsPageShowsItsVersionsAndSelectsTheDefaultOne() {
        val page = UiMappers.movieDetail(state(), hd)
        assertEquals(listOf(hd.id, dv.id, uhd.id, vostfr.id), page.versions.map { it.id })
        assertEquals(listOf("Standard", "4K Dolby Vision", "4K", "Original, French subtitles"), page.versions.map { it.label })
        assertEquals("an HD screen: 4K, not Dolby Vision", uhd, page.version)
        UiMappers.pictureSupport = PictureSupport(hdr = true, dolbyVision = true, uhd = true)
        assertEquals("a Dolby Vision screen", dv, UiMappers.movieDetail(state(), hd).version)
        assertEquals("the one chosen last", vostfr, UiMappers.movieDetail(state(chosen = listOf(vostfr.id)), hd).version)
        val watching = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, hd.id, title = hd.name, lastWatchedAt = 1)
        val library = LibraryState().recordStart(watching).updateProgress(watching.key, 600.0, 6_000.0, now = 2)
        val resumed = UiMappers.movieDetail(state(library, chosen = listOf(vostfr.id)), dv)
        assertEquals("the one in progress first", hd, resumed.version)
        assertTrue(resumed.progress != null)
        assertEquals("picked on the page", vostfr, UiMappers.movieDetail(state(library), hd, selected = vostfr.id).version)
    }

    @Test
    fun continueWatchingSaysWhichVersionIsInProgress() {
        val watching = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, dv.id, title = dv.name, lastWatchedAt = 1)
        val library = LibraryState().recordStart(watching).updateProgress(watching.key, 600.0, 6_000.0, now = 2)
        val state = state(library)
        assertEquals("4K Dolby Vision", UiMappers.resume(library.progress(watching.key)!!, state).subtitle)
        assertFalse(UiMappers.resume(library.progress(watching.key)!!, state(library, grouped = false)).subtitle == "4K Dolby Vision")
    }

    /** The version in progress without a tag of its own (the plain stream) is named too, as its page names it. */
    @Test
    fun continueWatchingNamesAPlainVersionToo() {
        val watching = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, hd.id, title = hd.name, lastWatchedAt = 1)
        val library = LibraryState().recordStart(watching).updateProgress(watching.key, 600.0, 6_000.0, now = 2)
        assertEquals("Standard", UiMappers.resume(library.progress(watching.key)!!, state(library)).subtitle)
    }

    /** A panel listing a stream twice (the same id): one version, one badge (the page's rows need one key each). */
    @Test
    fun aStreamListedTwiceIsOneVersion() {
        val twice = Fixtures.state(content = content.copy(movies = content.movies + dv))
        assertEquals(4, UiMappers.movieDetail(twice, hd).versions.size)
    }

    @Test
    fun aVersionInAHiddenCategoryIsNoBadge() {
        val adult = version("g-adult", "Glass City MULTI", Fixtures.adultMovies, 77)
        val withAdult = Fixtures.state(content = content.copy(movies = content.movies + adult))
        assertFalse(UiMappers.movieDetail(withAdult, hd).versions.any { it.id == adult.id })
    }
}
