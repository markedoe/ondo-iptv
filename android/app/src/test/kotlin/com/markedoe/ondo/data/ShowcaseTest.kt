package com.markedoe.ondo.data

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.TmdbRating
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.ui.model.ShowcaseItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Same rules as the iPhone app's `ShowcaseTests`: carousel, next episode, More like this, My List. */
class ShowcaseTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val playlistId = Fixtures.PLAYLIST_ID

    private fun LibraryState.watched(kind: ItemKind, itemId: String, episodeId: String? = null, fraction: Double, duration: Double, at: Long): LibraryState {
        val entry = WatchEntry(playlistId, kind, itemId, episodeId, title = itemId, lastWatchedAt = at)
        return recordStart(entry).updateProgress(entry.key, duration * fraction, duration, now = at)
    }

    private fun showcase(
        library: LibraryState = LibraryState(),
        content: PlaylistContent = Fixtures.content,
        hidden: Set<String> = emptySet(),
        votes: Map<String, TmdbRating> = emptyMap(),
    ) = Showcase(content, library, playlistId, hidden, votes = votes, kept = Fixtures.kept(content))

    /**
     * What TMDb's voters said, by poster key, of the titles [Fixtures.releaseDaysAgo] dates: The Last Signal 8.0 from
     * 4,000 (7.93 weighted), Harbor Lights and Blue Horizon 7.9 from 800 (7.62 each: the newer first), Northern Lights
     * 7.3 from 12,345 (7.29), Silent Harbor 8.0 from 50 (6.8, released 20 days ago: in once the window widens), Glass
     * City 10 from 10 (6.67, just enough votes). Summer in Lisbon and The Long Road, best rated, are old films; Late
     * Show is in the hidden adult category.
     */
    private val votes = mapOf(
        "m-1" to TmdbRating(7.3, 12_345),
        "m-2" to TmdbRating(10.0, 10),
        "m-3" to TmdbRating(8.0, 50),
        "m-4" to TmdbRating(8.0, 4_000),
        "s-s1" to TmdbRating(7.9, 800),
        "s-s3" to TmdbRating(7.9, 800),
        "m-5" to TmdbRating(9.5, 50_000),
        "m-7" to TmdbRating(9.0, 50_000),
        "m-8" to TmdbRating(9.0, 9_000),
    )

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

    /** An episode listed twice (the same address, as some M3U playlists list it): after it comes the next one, not its copy. */
    @Test
    fun afterAnEpisodeListedTwiceComesTheNextOne() {
        val series = Fixtures.harborLights
        val base = Fixtures.details(series)
        val first = base.seasons[0].episodes[0]
        val copy = first.copy(id = "${first.id}-2")
        val details = base.copy(seasons = listOf(base.seasons[0].copy(episodes = listOf(first, copy) + base.seasons[0].episodes.drop(1))) + base.seasons.drop(1))
        val library = LibraryState().watched(ItemKind.SERIES, series.id, first.id, 0.99, 2_700.0, at = 1)
        assertEquals("S01E02", showcase(library).nextEpisode(series, details)?.first?.code)
        // The copy last in its season: the next season.
        val lastSeason = base.seasons[0].episodes
        val last = lastSeason.last()
        val atEnd = base.copy(seasons = listOf(base.seasons[0].copy(episodes = lastSeason + last.copy(id = "${last.id}-2"))) + base.seasons.drop(1))
        val finishedLast = LibraryState().watched(ItemKind.SERIES, series.id, last.id, 0.99, 2_700.0, at = 1)
        assertEquals("S02E01", showcase(finishedLast).nextEpisode(series, atEnd)?.first?.code)
    }

    @Test
    fun theCarouselShowsTheBestRatedRecentReleasesWeightedByVotes() {
        val showcase = showcase(hidden = setOf(Fixtures.adultMovies.id), votes = votes)
        assertEquals(
            "released in the last 7 days, then 14, 30: by weighted rating, ties to the newest; an old series with nothing new, " +
                "older titles, a release to come and hidden categories are out",
            listOf("The Last Signal", "Harbor Lights", "Blue Horizon", "Northern Lights", "Silent Harbor", "Glass City"),
            showcase.heroItems(Fixtures.NOW).map { it.title },
        )
        assertEquals(listOf("The Last Signal", "Harbor Lights"), showcase.heroItems(Fixtures.NOW, limit = 2).map { it.title })
        // The provider's dates added do not count: Summer in Lisbon and The Long Road, best rated of all, are old films.
        assertTrue(showcase.heroItems(Fixtures.NOW).none { it.title == "Summer in Lisbon" || it.title == "The Long Road" })
    }

    @Test
    fun anOldSeriesWithANewSeasonIsInAndOneWithNothingNewIsOut() {
        val showcase = showcase(hidden = setOf(Fixtures.adultMovies.id), votes = votes)
        val hero = showcase.heroItems(Fixtures.NOW).map { it.title }
        assertEquals(2023, Fixtures.blueHorizon.year)
        assertTrue("a 2023 series whose new season aired 4 days ago", "Blue Horizon" in hero)
        assertEquals(2024, Fixtures.theArchive.year)
        assertFalse("its latest episode aired over a year ago", "The Archive" in hero)
        assertFalse("released in ten days", "Midnight Orchard" in hero)
    }

    @Test
    fun withoutVotesTheNewestReleasesStandInAndWithoutRecentReleasesTheCarouselIsEmpty() {
        val none = showcase(hidden = setOf(Fixtures.adultMovies.id))
        assertEquals(
            "the newest releases by date",
            listOf("Harbor Lights", "The Last Signal", "Northern Lights", "Blue Horizon", "Glass City", "Silent Harbor"),
            none.heroItems(Fixtures.NOW).map { it.title },
        )
        // Two months on, nothing was released in the last 60 days: no carousel, rather than older titles.
        assertTrue(none.heroItems(Fixtures.NOW + 70 * 86_400_000L).isEmpty())
        // Without any release date known (no TMDb answer, no provider details), none either.
        assertTrue(Showcase(Fixtures.content, LibraryState(), playlistId).heroItems(Fixtures.NOW).isEmpty())
    }

    @Test
    fun theProvidersFullReleaseDateCountsAndAYearAloneDoesNot() {
        val kept = Fixtures.kept().mapValues { it.value.copy(releaseDate = "") }
        val provider = mapOf("m-1" to Fixtures.releasedDaysAgo(2), "m-2" to "2025")
        val showcase = Showcase(
            Fixtures.content, LibraryState(), playlistId, setOf(Fixtures.adultMovies.id), kept = kept,
            providerRelease = { provider[it.key] },
        )
        assertEquals(listOf("Northern Lights"), showcase.heroItems(Fixtures.NOW).map { it.title })
    }

    @Test
    fun aTitleWithoutArtworkOrInAHiddenCategoryIsNeverInTheCarousel() {
        val kept = Fixtures.kept().mapValues { (key, title) -> if (key == "m-4") title.copy(backdropUrl = null) else title }
        val showcase = Showcase(Fixtures.content, LibraryState(), playlistId, setOf(Fixtures.adultMovies.id), votes = votes, kept = kept)
        val hero = showcase.heroItems(Fixtures.NOW).map { it.title }
        assertFalse("no poster, no backdrop", "The Last Signal" in hero)
        assertFalse("hidden adult category, released yesterday", "Late Show" in hero)
        assertTrue("Late Show" in showcase(votes = votes).heroItems(Fixtures.NOW).map { it.title })
        // A poster is artwork enough.
        val poster = Fixtures.content.copy(movies = Fixtures.content.movies.map { if (it.id == "4") it.copy(posterUrl = "http://example.com/poster/4.jpg") else it })
        assertTrue("The Last Signal" in Showcase(poster, LibraryState(), playlistId, kept = kept).heroItems(Fixtures.NOW).map { it.title })
    }

    @Test
    fun theTitlesAskedAboutAreTheOnesThatMayHaveComeOutLately() {
        val asked = showcase(hidden = setOf(Fixtures.adultMovies.id)).releasesToAsk(Fixtures.NOW).map { it.title }
        // Released this year or last (2024-2025 at NOW), or changed by the provider in the last 60 days: the playlist adds
        // all of them within a week, so every shown title is asked, the latest changed first.
        assertEquals(listOf("Northern Lights", "Harbor Lights", "Glass City", "The Archive", "Silent Harbor", "Blue Horizon"), asked.take(6))
        assertFalse("hidden categories left out", "Late Show" in asked)
        val later = Fixtures.NOW + 400 * 86_400_000L
        assertEquals("a year on, the 2024 and older titles nobody changed are not asked", listOf("Northern Lights", "Harbor Lights", "The Last Signal"), showcase().releasesToAsk(later).map { it.title }.filter { it != "Late Show" })
        val ahead = showcase(hidden = setOf(Fixtures.adultMovies.id), votes = votes).aheadTitles(Fixtures.NOW, limit = 50)
        assertEquals("each once", ahead.size, ahead.distinctBy { it.key }.size)
        assertEquals(3, showcase().aheadTitles(Fixtures.NOW, limit = 3).size)
    }

    @Test
    fun theSameFilmTwiceIsInTheCarouselOnce() {
        val copy = Fixtures.lastSignal.copy(id = "4-fr", streamUrl = "http://example.com/movie/4-fr.mp4")
        val content = Fixtures.content.copy(movies = Fixtures.content.movies + copy)
        val copyVotes = votes + ("m-4-fr" to TmdbRating(8.0, 4_000))
        val kept = Fixtures.kept(content).let { it + ("m-4-fr" to it.getValue("m-4")) }
        val titles = Showcase(content, LibraryState(), playlistId, setOf(Fixtures.adultMovies.id), votes = copyVotes, kept = kept).heroItems(Fixtures.NOW).map { it.title }
        assertEquals(1, titles.count { it == "The Last Signal" })
    }


    @Test
    fun newMoviesAndSeriesGoByReleaseYear() {
        val showcase = showcase(hidden = setOf(Fixtures.adultMovies.id))
        assertEquals(
            "2024 and 2025 releases, newest first then latest added; not older or hidden ones",
            listOf("Northern Lights", "The Last Signal", "Glass City", "Midnight Orchard"),
            showcase.newMovies(Fixtures.NOW).map { it.title },
        )
        assertEquals("series in their own row", listOf("Harbor Lights", "The Archive"), showcase.newSeries(Fixtures.NOW).map { it.title })
        assertEquals(listOf("Northern Lights"), showcase.newMovies(Fixtures.NOW, limit = 1).map { it.title })
        assertEquals(
            "the carousel alternates movies and series",
            listOf("Northern Lights", "Harbor Lights", "The Last Signal", "The Archive", "Glass City", "Midnight Orchard"),
            showcase.newReleases(Fixtures.NOW).map { it.title },
        )
        val threeYearsLater = Fixtures.NOW + 3 * 366 * 86_400_000L
        assertTrue("the same titles are no longer new three years later", showcase().newMovies(threeYearsLater).isEmpty())
        assertTrue(showcase().newSeries(threeYearsLater).isEmpty())
        val undated = Fixtures.content.copy(movies = Fixtures.content.movies.map { it.copy(year = null) }, series = emptyList())
        assertTrue("titles without a known year never appear", showcase(content = undated).newMovies(Fixtures.NOW).isEmpty())
    }

    @Test
    fun liveOnlyPlaylistHasNoCarousel() {
        val live = Fixtures.content.copy(movies = emptyList(), series = emptyList())
        assertTrue(showcase(content = live).heroItems(Fixtures.NOW).isEmpty())
        assertTrue(showcase(content = live).newMovies(Fixtures.NOW).isEmpty())
        assertTrue(showcase(content = live).newSeries(Fixtures.NOW).isEmpty())
    }

    @Test
    fun similarTitlesStayInTheCategoryAndSkipHiddenOnes() {
        val northern = Fixtures.northernLights
        assertEquals(listOf("Summer in Lisbon", "The Long Road"), showcase().similarMovies(northern).map { it.name })
        assertTrue(showcase(hidden = setOf(Fixtures.drama.id)).similarMovies(northern).isEmpty())
        assertEquals(listOf("Blue Horizon"), showcase().similarSeries(Fixtures.theArchive).map { it.name })
    }

    /** TMDb's recommendations the playlist has come first, in TMDb's order, from any category; never the title itself. */
    @Test
    fun tmdbsRecommendationsComeFirst() {
        val northern = Fixtures.northernLights
        val similar = showcase().similarMovies(northern, related = listOf(Fixtures.lastSignal, northern, Fixtures.glassCity)).map { it.name }
        assertEquals(listOf("The Last Signal", "Glass City", "Summer in Lisbon", "The Long Road"), similar)
        assertEquals("a hidden category stays out", listOf("Summer in Lisbon", "The Long Road"),
            showcase(hidden = setOf(Fixtures.sciFi.id)).similarMovies(northern, related = listOf(Fixtures.lastSignal)).map { it.name })
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
}
