package com.markedoe.ondo.ui

import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.xtream.XtreamClient
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.data.SavedPlaylist
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.screens.BrowseKind
import com.markedoe.ondo.ui.screens.SearchFilters
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The states the TV screens are made of, from a panel of real size ([BigPanel]) just added: every list a screen keys
 * its rows by holds each key once (the same key twice stops the app), and no mapper throws (a throw in the view
 * model's flows stops the app too).
 */
class BigPanelMappersTest {
    private val now = 1_760_000_000_000L
    private val playlistId = "a1b2c3d4-0000-4000-8000-000000000001"
    private val credentials = XtreamCredentials(BigPanel.SERVER, BigPanel.USERNAME, BigPanel.PASSWORD)

    private val content: PlaylistContent by lazy {
        runBlocking { PlaylistLoader(FakeHttp(BigPanel.answers())).load(Playlist(id = playlistId, name = "Panel", source = PlaylistSource.Xtream(credentials))) }
    }

    /** What arrives from another device with the playlist: My List, favourite channels, titles in progress, a list. */
    private fun library(content: PlaylistContent): LibraryState {
        val movies = content.movies.take(30) + content.movies.takeLast(5)
        val series = content.series.take(10)
        val channels = content.channels.take(25)
        val favorites = movies.map { Favorite(playlistId, ItemKind.MOVIE, it.id, it.name, it.posterUrl, now - 1) } +
            series.map { Favorite(playlistId, ItemKind.SERIES, it.id, it.name, it.coverUrl, now - 2) } +
            channels.map { Favorite(playlistId, ItemKind.CHANNEL, it.id, it.name, it.logoUrl, now - 3) } +
            // Titles the panel no longer lists.
            Favorite(playlistId, ItemKind.MOVIE, "x1", "Gone", null, now)
        val history = movies.take(12).mapIndexed { index, movie ->
            WatchEntry(playlistId, ItemKind.MOVIE, movie.id, title = movie.name, position = 600.0 + index, duration = 6000.0, lastWatchedAt = now - index)
        } + series.take(6).flatMapIndexed { index, show ->
            listOf(1, 2).map { episode -> WatchEntry(playlistId, ItemKind.SERIES, show.id, "e$episode", show.name, position = 300.0, duration = 2400.0, lastWatchedAt = now - 100 - index * 2 - episode) }
        } + channels.take(8).mapIndexed { index, channel ->
            WatchEntry(playlistId, ItemKind.CHANNEL, channel.id, title = channel.name, lastWatchedAt = now - 200 - index)
        }
        return LibraryState(favorites = favorites, history = history)
            .creatingCollection("l1", "Weekend", now)
            .togglingInCollection("l1", favorites.first(), now)
            .togglingInCollection("l1", favorites.last { it.kind == ItemKind.CHANNEL }, now)
    }

    private fun state(withLibrary: Boolean = true) = OndoState(
        isReady = true,
        settings = AppSettings(activePlaylistId = playlistId),
        playlists = listOf(
            SavedPlaylist("demo", "Demo", "https://example.com/demo.m3u", addedAt = 1),
            SavedPlaylist(playlistId, "Panel", BigPanel.SERVER, BigPanel.USERNAME, BigPanel.PASSWORD, addedAt = 2),
        ),
        content = content,
        library = if (withLibrary) library(content) else LibraryState(),
    )

    private fun assertUnique(list: String, keys: List<Any>) {
        val twice = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("$list: keys listed twice: ${twice.take(5)}", twice.isEmpty())
    }

    @Test
    fun theCatalogueIsOfRealSize() {
        assertTrue(content.channels.size >= BigPanel.CHANNELS)
        assertTrue(content.movies.size >= BigPanel.MOVIES)
        assertTrue(content.series.size >= BigPanel.SERIES)
    }

    @Test
    fun homeAndItsShelvesHoldEachKeyOnce() {
        for (library in listOf(true, false)) {
            val home = UiMappers.home(state(library), Labels("Movie", "Series", Locale.US), now)
            assertTrue("up to fifteen titles: ${home.hero.size}", home.hero.size in 6..15)
            assertTrue("the suggestion shows", home.tagSuggestion != null)
            assertUnique("hero", home.hero.map { it.item.key })
            assertUnique("resume", home.continueWatching.map { it.key })
            assertUnique("myList", home.myList.map { it.key })
            assertUnique("newMovies", home.newMovies.map { it.key })
            assertUnique("newSeries", home.newSeries.map { it.key })
            assertUnique("channels", home.channels.map { it.channel.id })
        }
    }

    @Test
    fun moviesSeriesLiveAndTheGuideHoldEachKeyOnce() {
        val state = state()
        for (series in listOf(false, true)) {
            val vod = UiMappers.vod(state, series, "")
            assertUnique("chips", vod.categories.map { it.id })
            assertUnique("rows", vod.rows.map { it.category.id })
            vod.rows.forEach { row -> assertUnique("row ${row.category.id}", row.items.map { it.key }) }
            assertUnique("resume", vod.continueWatching.map { it.key })
            assertUnique("myList", vod.myList.map { it.key })
            assertUnique("awards", vod.awardWinners.map { it.key })
            for (sort in PosterSort.entries) {
                assertUnique("grid", UiMappers.grid(state, series, null, false, "", sort = sort).map { it.key })
                assertUnique("grid of a category", UiMappers.grid(state, series, vod.categories.first().id, false, "", sort = sort).map { it.key })
            }
        }
        val all = UiMappers.live(state, null, false, now)
        assertUnique("live chips", all.categories.map { it.id })
        assertUnique("live", all.channels.map { it.channel.id })
        for (category in all.categories) assertUnique("live ${category.id}", UiMappers.live(state, category.id, false, now).channels.map { it.channel.id })
        assertUnique("favourite channels", UiMappers.live(state, null, true, now).channels.map { it.channel.id })
        val guide = UiMappers.guide(state, null, false, now)
        assertUnique("guide", guide.rows.map { it.channel.id })
        val myList = UiMappers.myList(state, now)
        assertUnique("my movies", myList.movies.map { it.key })
        assertUnique("my series", myList.series.map { it.key })
        assertUnique("my channels", myList.channels.map { it.channel.id })
        assertUnique("collections", myList.collections.map { it.id })
        UiMappers.collection(state, "l1", now)!!.let { list ->
            assertUnique("list movies", list.movies.map { it.key })
            assertUnique("list channels", list.channels.map { it.channel.id })
        }
    }

    @Test
    fun searchSettingsAndCategoriesHoldEachKeyOnce() {
        val state = state()
        val index = UiMappers.SearchIndex.of(state.content, UiMappers.hiddenCategoryIds(state))
        for (query in listOf("", "movie", "series 1", "أفلام", "🎬", "ana", "x", "channel 4")) {
            val results = UiMappers.search(index, query, state, now)
            assertUnique("people $query", results.people)
            assertUnique("movies $query", results.movies.map { it.key })
            assertUnique("series $query", results.series.map { it.key })
            assertUnique("channels $query", results.channels.map { it.channel.id })
        }
        val filtered = UiMappers.search(index, "", state, now, SearchFilters(fromYear = 2024))
        assertUnique("filtered", filtered.movies.map { it.key } + filtered.series.map { it.key })
        val settings = UiMappers.settings(state, "1.0") { if (it) "Xtream" else "M3U" }
        assertUnique("playlists", settings.playlists.map { it.id })
        for (type in ContentType.entries) {
            for (filter in listOf("", "fr", "أفلام")) {
                val categories = CategoryFilter.categories(state, type, filter, Locale.US)
                assertUnique("categories", categories.tags.map { "tag-" + it.tag } + categories.styles.map { "style-" + it.style } + categories.words.map { "word-" + it.word } + categories.categories.map { it.id })
            }
        }
    }

    @Test
    fun theTvPagesOfItsTitlesHoldEachKeyOnce() {
        val state = state()
        for (movie in state.content.movies.take(40) + state.content.movies.filter { it.name.isEmpty() || it.name.length > 1_000 }) {
            val page = UiMappers.movieDetail(state, movie, Locale.US)
            assertUnique("similar", page.similar.map { it.key })
            assertUnique("awards", page.awards.map { "${it.awardId}:${it.year}:${it.name}" })
            assertUnique("genres", UiMappers.genres(page.details?.genre).map { "genre:$it" })
        }
        val client = XtreamClient(credentials, FakeHttp(BigPanel.answers()))
        for (series in state.content.series.take(8)) {
            val details = runBlocking { client.seriesDetails(series.xtreamSeriesId!!) }
            val withDetails = state.copy(seriesDetails = mapOf(series.id to details))
            val page = UiMappers.seriesDetail(withDetails, series, null, null)
            assertUnique("seasons", page.seasons.map { it.number })
            page.seasons.forEach { season -> assertUnique("episodes", season.episodes.map { it.id }) }
            assertUnique("similar", page.similar.map { it.key })
            assertUnique("genres", UiMappers.genres(details.info.genre ?: series.genre).map { "genre:$it" })
            for (kind in BrowseKind.entries) UiMappers.browse(withDetails, kind, if (kind == BrowseKind.YEAR) "2025" else "Drama", Locale.US)
            UiMappers.person(withDetails, "Ana Lima")
        }
    }
}
