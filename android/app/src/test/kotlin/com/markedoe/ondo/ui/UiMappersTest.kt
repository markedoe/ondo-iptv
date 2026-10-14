package com.markedoe.ondo.ui

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.model.ShowcaseItem
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiMappersTest {
    private val labels = Labels("Movie", "Series", Locale.US)
    private val adultShown = AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, showAdultCategories = true)

    @Test
    fun adultCategoriesAreHiddenUnlessShown() {
        val hidden = Fixtures.state()
        assertEquals(setOf(Fixtures.adultMovies.id), UiMappers.hiddenCategoryIds(hidden))
        assertFalse(UiMappers.home(hidden, labels, Fixtures.NOW).newMovies.any { it.title == "Late Show" })
        assertFalse(UiMappers.vod(hidden, series = false, title = "").categories.contains(Fixtures.adultMovies))
        assertTrue(UiMappers.grid(hidden, series = false, categoryId = null, myList = false, filter = "late").isEmpty())

        val shown = Fixtures.state(settings = adultShown)
        assertTrue(UiMappers.hiddenCategoryIds(shown).isEmpty())
        assertTrue(UiMappers.home(shown, labels, Fixtures.NOW).newMovies.any { it.title == "Late Show" })
        assertTrue(UiMappers.vod(shown, series = false, title = "").categories.contains(Fixtures.adultMovies))
    }

    @Test
    fun homeShowsTheCarouselShelvesAndChannels() {
        val home = UiMappers.home(Fixtures.state(), labels, Fixtures.NOW)
        assertEquals("Home", home.playlistName)
        assertEquals(5, home.hero.size)
        assertEquals("Movie · 2025 · ★ 7.8", home.hero.first { it.item.title == "Northern Lights" }.meta)
        assertEquals("Series · 2025 · Drama · ★ 8.2", home.hero.first { it.item.title == "Harbor Lights" }.meta)
        assertTrue(home.hasVod)
        assertEquals(
            "new movies by release year",
            listOf("Northern Lights", "The Last Signal", "Glass City", "Midnight Orchard"),
            home.newMovies.map { it.title },
        )
        assertEquals("new series in their own row", listOf("Harbor Lights", "The Archive"), home.newSeries.map { it.title })
        assertEquals("without favourites the first channels are shown", 4, home.channels.size)
        assertFalse(home.channelsAreFavorites)
    }

    @Test
    fun ratingsUseTheUsersDecimalSeparator() {
        val hero = UiMappers.home(Fixtures.state(), Labels("Film", "Série", Locale.FRANCE), Fixtures.NOW).hero
        assertEquals("Film · 2025 · ★ 7,8", hero.first { it.item.title == "Northern Lights" }.meta)
    }

    @Test
    fun favouriteChannelsComeFirstOnHome() {
        val library = LibraryState().toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.CHANNEL, "c3", "Stadium One", addedAt = 1))
        val home = UiMappers.home(Fixtures.state(library), labels, Fixtures.NOW)
        assertTrue(home.channelsAreFavorites)
        assertEquals(listOf("Stadium One"), home.channels.map { it.channel.name })
    }

    @Test
    fun vodTabHasOneRowPerCategoryAndItsContinueWatching() {
        val entry = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.glassCity.id, title = "Glass City", lastWatchedAt = 1)
        val library = LibraryState().recordStart(entry).updateProgress(entry.key, 600.0, 6_000.0, now = 2)
        val movies = UiMappers.vod(Fixtures.state(library), series = false, title = "Movies")
        assertEquals(listOf("Drama", "Science Fiction", "Thriller"), movies.rows.map { it.category.name })
        assertEquals(listOf("Northern Lights", "Summer in Lisbon", "The Long Road"), movies.rows.first().items.map { it.title })
        assertEquals(listOf("Glass City"), movies.continueWatching.map { it.title })
        assertEquals(0.1f, movies.continueWatching.first().progress, 0.001f)

        val series = UiMappers.vod(Fixtures.state(library), series = true, title = "Series")
        assertTrue("a movie is not resumed in the Series tab", series.continueWatching.isEmpty())
        assertEquals(listOf("Drama", "Mystery"), series.rows.map { it.category.name })
    }

    @Test
    fun gridFiltersIgnoreCaseAndAccents() {
        val state = Fixtures.state()
        assertEquals(listOf("Summer in Lisbon"), UiMappers.grid(state, false, null, false, "LISBÔN").map { it.title })
        assertEquals(3, UiMappers.grid(state, false, Fixtures.drama.id, false, "").size)
        assertEquals(listOf("The Archive", "Blue Horizon"), UiMappers.grid(state, true, Fixtures.seriesMystery.id, false, "").map { it.title })
    }

    @Test
    fun searchMatchesTheStartOfEachWord() {
        val state = Fixtures.state()
        val index = UiMappers.SearchIndex(state.content, UiMappers.hiddenCategoryIds(state))
        fun titles(query: String) = UiMappers.search(index, query, state, Fixtures.NOW).let { result ->
            (result.movies + result.series).map { it.title } + result.channels.map { it.channel.name }
        }
        assertEquals(listOf("The Last Signal"), titles("last sig"))
        assertEquals(listOf("Northern Lights", "Harbor Lights"), titles("lights"))
        assertEquals(listOf("Stadium One"), titles("stad"))
        assertTrue("words must start a word", titles("ights").isEmpty())
        assertTrue("hidden categories are not searched", titles("late show").isEmpty())
        assertTrue(titles("  ").isEmpty())
    }

    @Test
    fun seriesDetailPointsAtTheNextEpisode() {
        val series = Fixtures.harborLights
        val first = series.episodes[0]
        val entry = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.SERIES, series.id, first.id, title = series.name, lastWatchedAt = 1)
        val library = LibraryState().recordStart(entry).updateProgress(entry.key, 2_690.0, 2_700.0, now = 2)
        val detail = UiMappers.seriesDetail(Fixtures.state(library), series, selectedSeason = null, error = null)
        assertEquals("S01E02", detail.next?.code)
        assertEquals(1, detail.selectedSeason)
        assertTrue(detail.episodeProgress.getValue(first.id).finished)
        assertEquals(2, detail.seasons.size)
        assertEquals(2, UiMappers.seriesDetail(Fixtures.state(library), series, selectedSeason = 2, error = null).selectedSeason)
    }

    @Test
    fun movieDetailKnowsMyListAndSimilarTitles() {
        val movie = Fixtures.northernLights
        val library = LibraryState().toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, movie.id, movie.name, addedAt = 1))
        val detail = UiMappers.movieDetail(Fixtures.state(library), movie)
        assertTrue(detail.inMyList)
        assertEquals(listOf("Summer in Lisbon", "The Long Road"), detail.similar.map { it.title })
        assertEquals(null, detail.progress)
    }

    @Test
    fun liveFiltersByCategoryAndFavourites() {
        val library = LibraryState().toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.CHANNEL, "c2", "World Report", addedAt = 1))
        val state = Fixtures.state(library)
        assertEquals(4, UiMappers.live(state, null, false, Fixtures.NOW).channels.size)
        assertEquals(listOf("Stadium One", "Cycling TV"), UiMappers.live(state, Fixtures.sports.id, false, Fixtures.NOW).channels.map { it.channel.name })
        assertEquals(listOf("World Report"), UiMappers.live(state, null, true, Fixtures.NOW).channels.map { it.channel.name })
        assertEquals(setOf("c2"), UiMappers.live(state, null, false, Fixtures.NOW).favoriteIds)
    }

    @Test
    fun settingsListPlaylistsWithTheirKind() {
        val settings = UiMappers.settings(Fixtures.state(), "1.0.0") { if (it) "Xtream" else "M3U" }
        assertEquals("M3U · example.com", settings.playlists.single().detail)
        assertTrue(settings.playlists.single().active)
        assertEquals("1.0.0", settings.version)
    }

    @Test
    fun myListItemsAreOpenable() {
        val library = LibraryState().toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.SERIES, Fixtures.theArchive.id, "The Archive", addedAt = 1))
        val home = UiMappers.home(Fixtures.state(library), labels, Fixtures.NOW)
        assertEquals(listOf(ShowcaseItem.SeriesItem(Fixtures.theArchive)), home.myList)
        assertTrue(home.hero.first { it.item == ShowcaseItem.SeriesItem(Fixtures.theArchive) }.inMyList)
    }

    @Test
    fun releaseDateSortPutsTheNewestFirstAndUndatedLast() {
        fun movie(name: String, year: Int?, addedAt: Long) =
            ShowcaseItem.MovieItem(Movie(id = name, name = name, categoryId = "c", streamUrl = "http://example.com/$name.mp4", year = year, addedAt = addedAt))
        val items = listOf(
            movie("Quiet Water", 2019, 100),
            movie("Harbor Lights", null, 500),
            movie("Glass City", 2024, 100),
            movie("Northern Lights", 2024, 200),
            movie("Bright Field", 2024, 100),
        )
        assertEquals(
            listOf("Northern Lights", "Bright Field", "Glass City", "Quiet Water", "Harbor Lights"),
            UiMappers.sorted(items, PosterSort.RELEASE).map { it.title },
        )
        assertEquals("playlist order is kept", items, UiMappers.sorted(items, PosterSort.PLAYLIST))
        assertEquals("Bright Field", UiMappers.sorted(items, PosterSort.NAME).first().title)
    }
}
