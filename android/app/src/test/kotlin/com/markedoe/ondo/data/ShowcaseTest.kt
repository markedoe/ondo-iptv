package com.markedoe.ondo.data

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.ui.model.ShowcaseItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same rules as the iPhone app's `ShowcaseTests`: carousel, next episode, More like this, My List. */
class ShowcaseTest {
    private val playlistId = Fixtures.PLAYLIST_ID

    private fun LibraryState.watched(kind: ItemKind, itemId: String, episodeId: String? = null, fraction: Double, duration: Double, at: Long): LibraryState {
        val entry = WatchEntry(playlistId, kind, itemId, episodeId, title = itemId, lastWatchedAt = at)
        return recordStart(entry).updateProgress(entry.key, duration * fraction, duration, now = at)
    }

    private fun showcase(library: LibraryState = LibraryState(), content: PlaylistContent = Fixtures.content, hidden: Set<String> = emptySet()) =
        Showcase(content, library, playlistId, hidden)

    @Test
    fun nextEpisodeFollowsWhatWasWatched() {
        val series = Fixtures.harborLights
        val details = Fixtures.details(series)
        fun next(library: LibraryState) = showcase(library).nextEpisode(series, details)?.first?.code
        val e = series.episodes

        assertEquals("nothing watched: the first episode", "S01E01", next(LibraryState()))
        var library = LibraryState().watched(ItemKind.SERIES, series.id, e[1].id, 0.3, 2_700.0, at = 1)
        assertEquals("an episode in progress is resumed", "S01E02", next(library))
        library = library.watched(ItemKind.SERIES, series.id, e[2].id, 0.99, 2_700.0, at = 2)
        assertEquals("after the last episode of a season comes the next season", "S02E01", next(library))
        library = library.watched(ItemKind.SERIES, series.id, e.last().id, 0.99, 2_700.0, at = 3)
        assertEquals("everything watched: the last episode stays", "S02E03", next(library))
        assertNull("episodes keep their own progress", showcase(library).progress(series, e[0]))
    }

    @Test
    fun heroPutsContinueWatchingFirstWithoutDuplicates() {
        val road = Fixtures.longRoad
        val library = LibraryState().watched(ItemKind.MOVIE, road.id, fraction = 0.2, duration = 6_000.0, at = 1)
        val adultHidden = setOf(Fixtures.adultMovies.id)
        val hero = showcase(library, hidden = adultHidden).heroItems(Fixtures.NOW, limit = 5)
        assertEquals(
            "what is being watched comes first, then new releases (movies and series), never twice",
            listOf("The Long Road", "Northern Lights", "Harbor Lights", "The Last Signal", "The Archive"),
            hero.map { it.title },
        )
        assertEquals(2, showcase(library, hidden = adultHidden).heroItems(Fixtures.NOW, limit = 2).size)
    }

    @Test
    fun newReleasesGoByReleaseYearAndMixMoviesAndSeries() {
        val hidden = setOf(Fixtures.adultMovies.id)
        assertEquals(
            "2024 and 2025 releases, newest first then latest added, movies and series alternating; not older or hidden ones",
            listOf("Northern Lights", "Harbor Lights", "The Last Signal", "The Archive", "Glass City", "Midnight Orchard"),
            showcase(hidden = hidden).newReleases(Fixtures.NOW).map { it.title },
        )
        assertEquals(listOf("Northern Lights", "Harbor Lights"), showcase(hidden = hidden).newReleases(Fixtures.NOW, limit = 2).map { it.title })
        val threeYearsLater = Fixtures.NOW + 3 * 366 * 86_400_000L
        assertTrue("the same titles are no longer new three years later", showcase().newReleases(threeYearsLater).isEmpty())
        val undated = Fixtures.content.copy(movies = Fixtures.content.movies.map { it.copy(year = null) }, series = emptyList())
        assertTrue("titles without a known year never appear", showcase(content = undated).newReleases(Fixtures.NOW).isEmpty())
    }

    @Test
    fun newestTitlesAreFeaturedFirst() {
        val featured = showcase().featuredMovies()
        assertEquals(Fixtures.adultMovie, featured.first())
        assertEquals(Fixtures.northernLights, showcase(hidden = setOf(Fixtures.adultMovies.id)).featuredMovies().first())
    }

    @Test
    fun liveOnlyPlaylistHasNoCarousel() {
        val live = Fixtures.content.copy(movies = emptyList(), series = emptyList())
        assertTrue(showcase(content = live).heroItems(Fixtures.NOW).isEmpty())
        assertTrue(showcase(content = live).newReleases(Fixtures.NOW).isEmpty())
        assertTrue(showcase(content = live).featuredMovies().isEmpty())
    }

    @Test
    fun similarTitlesStayInTheCategoryAndSkipHiddenOnes() {
        val northern = Fixtures.northernLights
        assertEquals(listOf("Summer in Lisbon", "The Long Road"), showcase().similarMovies(northern).map { it.name })
        assertTrue(showcase(hidden = setOf(Fixtures.drama.id)).similarMovies(northern).isEmpty())
        assertEquals(listOf("Blue Horizon"), showcase().similarSeries(Fixtures.theArchive).map { it.name })
    }

    @Test
    fun myListShowsTheLatestAdditionFirst() {
        val library = LibraryState()
            .toggleFavorite(Favorite(playlistId, ItemKind.MOVIE, Fixtures.glassCity.id, "Glass City", addedAt = 1))
            .toggleFavorite(Favorite(playlistId, ItemKind.SERIES, Fixtures.harborLights.id, "Harbor Lights", addedAt = 2))
            .toggleFavorite(Favorite(playlistId, ItemKind.CHANNEL, "c1", "Ondo News", addedAt = 3))
            .toggleFavorite(Favorite("other", ItemKind.MOVIE, Fixtures.longRoad.id, "The Long Road", addedAt = 4))
        assertEquals(
            listOf(ShowcaseItem.SeriesItem(Fixtures.harborLights), ShowcaseItem.MovieItem(Fixtures.glassCity)),
            showcase(library).myList(),
        )
    }

    @Test
    fun undatedCataloguesKeepThePlaylistOrder() {
        val undated = Fixtures.content.copy(movies = Fixtures.content.movies.map { it.copy(addedAt = null) })
        val showcase = showcase(content = undated)
        assertEquals(false, showcase.moviesAreDated)
        assertEquals(undated.movies.take(3), showcase.featuredMovies(limit = 3))
    }
}
