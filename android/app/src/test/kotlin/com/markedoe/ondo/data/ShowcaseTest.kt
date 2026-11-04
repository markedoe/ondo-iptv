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
    ) = Showcase(content, library, playlistId, hidden, votes = votes)

    /**
     * What TMDb's voters said, by poster key. Added within four days (the last signal exactly four days ago): Northern
     * Lights 7.3 from 12,345 (7.29 weighted), Glass City 10 from 10 (not enough votes), Silent Harbor 8.0 from 50 (6.8),
     * The Last Signal 8.0 from 4,000 (7.93), Harbor Lights and Blue Horizon 7.9 from 800 (7.62 each), The Archive no
     * votes known. Added earlier, so out of the window whatever the votes: Summer in Lisbon (5 days), The Long Road
     * (7 days). Late Show is in the hidden adult category.
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
    fun theCarouselShowsTheBestRatedRecentAdditionsWeightedByVotes() {
        val adultHidden = setOf(Fixtures.adultMovies.id)
        val showcase = showcase(hidden = adultHidden, votes = votes)
        assertEquals(
            "added in the last four days with at least 50 votes, by weighted rating, then latest added: ten from ten votes, " +
                "unknown votes, older titles and hidden categories are out",
            listOf("The Last Signal", "Harbor Lights", "Blue Horizon", "Northern Lights", "Silent Harbor"),
            showcase.bestRecent(Fixtures.NOW).map { it.title },
        )
        // The other sources fill the carousel after them (new releases here), each title once.
        assertEquals(
            listOf("The Last Signal", "Harbor Lights", "Blue Horizon", "Northern Lights", "Silent Harbor", "The Archive", "Glass City", "Midnight Orchard"),
            showcase.heroItems(Fixtures.NOW).map { it.title },
        )
        assertEquals(2, showcase.heroItems(Fixtures.NOW, limit = 2).size)
        // A fortnight on, nothing was added in the last four days: new releases alone.
        val later = Fixtures.NOW + 14 * 86_400_000L
        assertTrue(showcase.bestRecent(later).isEmpty())
        assertEquals(
            listOf("Northern Lights", "Harbor Lights", "The Last Signal", "The Archive", "Glass City", "Midnight Orchard"),
            showcase.heroItems(later).map { it.title },
        )
    }

    @Test
    fun aTenFromTenVotesAddedYesterdayIsNotInTheCarouselsBestRecent() {
        val tenVotes = mapOf("m-1" to TmdbRating(10.0, 10), "m-4" to TmdbRating(7.0, 500))
        val pick = showcase(hidden = setOf(Fixtures.adultMovies.id), votes = tenVotes).bestRecent(Fixtures.NOW).map { it.title }
        assertFalse("Northern Lights" in pick)
        assertEquals(listOf("The Last Signal"), pick)
    }

    @Test
    fun aTitleAddedFiveDaysAgoIsNotInTheCarouselsBestRecent() {
        val pick = showcase(hidden = setOf(Fixtures.adultMovies.id), votes = mapOf("m-5" to TmdbRating(9.5, 50_000), "m-3" to TmdbRating(7.0, 500))).bestRecent(Fixtures.NOW).map { it.title }
        assertFalse("Summer in Lisbon" in pick)
        assertEquals(listOf("Silent Harbor"), pick)
    }

    /** A catalogue of old titles with posters, in a hidden category too, one without a poster. */
    private fun oldCatalogue(): PlaylistContent {
        fun art(id: String) = "http://example.com/poster/$id.jpg"
        return Fixtures.content.copy(
            movies = Fixtures.content.movies.map { it.copy(year = 2010, posterUrl = if (it.id == Fixtures.summerInLisbon.id) null else art(it.id)) },
            series = Fixtures.content.series.map { it.copy(year = 2010, coverUrl = art(it.id)) },
        )
    }

    @Test
    fun aCatalogueWithoutTmdbVotesOrRecentReleasesStillShowsACarousel() {
        val showcase = showcase(content = oldCatalogue(), hidden = setOf(Fixtures.adultMovies.id))
        assertTrue(showcase.bestRecent(Fixtures.NOW).isEmpty())
        assertTrue(showcase.newReleases(Fixtures.NOW).isEmpty())
        val hero = showcase.heroItems(Fixtures.NOW).map { it.title }
        assertEquals("the latest added with artwork first, then by title", listOf("Harbor Lights", "Northern Lights"), hero.take(2))
        assertFalse("a hidden category stays out", "Late Show" in hero)
        assertFalse("no artwork, no place", "Summer in Lisbon" in hero)
        assertTrue(hero.size > 2)
    }

    @Test
    fun withoutAnAddedDateTheLatestWithArtworkFollowThePlaylistOrder() {
        val content = oldCatalogue().let { it.copy(movies = it.movies.map { m -> m.copy(addedAt = null) }, series = emptyList()) }
        val hero = showcase(content = content, hidden = setOf(Fixtures.adultMovies.id)).heroItems(Fixtures.NOW).map { it.title }
        assertEquals(listOf("Northern Lights", "Glass City", "Silent Harbor"), hero.take(3))
    }

    @Test
    fun aTenFromTenVotesTitleIsStillNotRankedFirstByTheBestRecentSource() {
        val ranked = mapOf("m-1" to TmdbRating(10.0, 10), "m-4" to TmdbRating(7.0, 500))
        val showcase = showcase(hidden = setOf(Fixtures.adultMovies.id), votes = ranked)
        assertEquals("The Last Signal", showcase.bestRecent(Fixtures.NOW).first().title)
        assertEquals("The Last Signal", showcase.heroItems(Fixtures.NOW).first().title)
    }

    @Test
    fun theBestRecentWindowIsFourDays() {
        val showcase = showcase(hidden = setOf(Fixtures.adultMovies.id), votes = votes)
        assertEquals(4L, RECENT_DAYS)
        val day = 86_400_000L
        assertTrue("exactly four days is in", "The Last Signal" in showcase.bestRecent(Fixtures.NOW).map { it.title })
        assertFalse("a day later it is out", "The Last Signal" in showcase.bestRecent(Fixtures.NOW + day).map { it.title })
        assertEquals(listOf("Harbor Lights", "Blue Horizon", "Northern Lights", "Silent Harbor").sorted(), showcase.bestRecent(Fixtures.NOW + day).map { it.title }.sorted())
        // Added five and seven days ago: out whatever their votes.
        assertTrue(showcase.bestRecent(Fixtures.NOW).none { it.title == "Summer in Lisbon" || it.title == "The Long Road" })
    }

    @Test
    fun withoutTmdbVotesTheBestRecentSourceIsEmptyAndTheCarouselFallsBack() {
        val none = showcase(hidden = setOf(Fixtures.adultMovies.id))
        assertTrue(none.bestRecent(Fixtures.NOW).isEmpty())
        assertEquals(
            "new releases, alternating movies and series",
            listOf("Northern Lights", "Harbor Lights", "The Last Signal", "The Archive", "Glass City", "Midnight Orchard"),
            none.heroItems(Fixtures.NOW).map { it.title },
        )
        // The provider's own rating is not a vote count: it does not qualify a title.
        // Votes known for some titles only: the others stay out of the source, not out of the carousel.
        val some = showcase(hidden = setOf(Fixtures.adultMovies.id), votes = mapOf("m-4" to TmdbRating(8.0, 4_000)))
        assertEquals(listOf("The Last Signal"), some.bestRecent(Fixtures.NOW).map { it.title })
        assertTrue("The Archive" in some.heroItems(Fixtures.NOW).map { it.title })
    }

    @Test
    fun theTitlesPreparedAheadAreTheNewAndBestRecentOnes() {
        val titles = showcase(hidden = setOf(Fixtures.adultMovies.id), votes = votes).aheadTitles(Fixtures.NOW, limit = 50)
        assertEquals("each once", titles.size, titles.distinctBy { it.key }.size)
        assertTrue("the best recent title first", titles.first().title == "The Last Signal")
        assertFalse("hidden categories left out", titles.any { it.title == "Late Show" })
        assertEquals(3, showcase().aheadTitles(Fixtures.NOW, limit = 3).size)
        // Titles added in the window are asked for first even when their votes are not known yet.
        val unknown = showcase(hidden = setOf(Fixtures.adultMovies.id)).aheadTitles(Fixtures.NOW, limit = 50).map { it.title }
        assertTrue("Silent Harbor" in unknown)
        assertFalse("Summer in Lisbon" in unknown)
    }

    @Test
    fun theSameFilmTwiceIsInTheCarouselOnce() {
        val copy = Fixtures.lastSignal.copy(id = "4-fr", streamUrl = "http://example.com/movie/4-fr.mp4")
        val content = Fixtures.content.copy(movies = Fixtures.content.movies + copy)
        val copyVotes = votes + ("m-4-fr" to TmdbRating(8.0, 4_000))
        val titles = showcase(content = content, hidden = setOf(Fixtures.adultMovies.id), votes = copyVotes).bestRecent(Fixtures.NOW).map { it.title }
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
