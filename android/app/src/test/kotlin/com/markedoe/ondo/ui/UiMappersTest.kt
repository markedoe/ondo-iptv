package com.markedoe.ondo.ui

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.epg.EpgIndex
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.library.TitleMetadata
import com.markedoe.ondo.core.library.TmdbGenre
import com.markedoe.ondo.core.library.TmdbItem
import com.markedoe.ondo.core.library.TmdbRating
import com.markedoe.ondo.core.library.TrendingMatch
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.core.sync.SyncHidden
import com.markedoe.ondo.core.sync.SyncMerge
import com.markedoe.ondo.core.sync.SyncSnapshot
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.data.Reminder
import com.markedoe.ondo.data.SyncData
import com.markedoe.ondo.data.SyncKeys
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.model.PersonRow
import com.markedoe.ondo.ui.model.PosterFacts
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.BrowseKind
import com.markedoe.ondo.ui.screens.CollectionChoice
import com.markedoe.ondo.ui.screens.CollectionRow
import com.markedoe.ondo.ui.screens.SearchFilters
import com.markedoe.ondo.ui.screens.SearchUiState
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UiMappersTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

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

    /**
     * Trending titles (TMDb) of the playlist: a row of their own, in TMDb's order, without a hidden category or, when
     * Settings hides them, a finished movie. The carousel goes by release date: an old film TMDb says is trending stays
     * out of it.
     */
    @Test
    fun trendingTitlesShowInTheirRowAndTheCarousel() {
        fun movie(movie: Movie, id: Int) = TrendingMatch.MovieMatch(movie, TmdbItem(id, TmdbItem.MediaType.MOVIE, movie.name, year = movie.year))
        val trending = listOf(
            movie(Fixtures.longRoad, 1),
            TrendingMatch.SeriesMatch(Fixtures.blueHorizon, TmdbItem(2, TmdbItem.MediaType.TV, "Blue Horizon", year = 2023)),
            movie(Fixtures.adultMovie, 3),
            movie(Fixtures.summerInLisbon, 4),
        )
        val finished = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.summerInLisbon.id, title = "Summer in Lisbon", lastWatchedAt = 1)
        val library = LibraryState().recordStart(finished).updateProgress(finished.key, 5_900.0, 6_000.0, now = 2)
        val home = UiMappers.home(Fixtures.state(library).copy(trending = trending), labels, Fixtures.NOW, Fixtures.kept())
        assertEquals(listOf("The Long Road", "Blue Horizon", "Summer in Lisbon"), home.trending.map { it.title })
        // Without TMDb votes, the newest releases by date: Blue Horizon for its new season, not for trending.
        assertEquals(
            listOf("Harbor Lights", "The Last Signal", "Northern Lights", "Blue Horizon", "Glass City", "Silent Harbor"),
            home.hero.map { it.item.title },
        )
        // With votes, the recent releases with enough of them, by weighted rating; ten votes at 10 stay near the prior.
        val voted = Fixtures.state(library).copy(
            trending = trending,
            tmdbRatings = mapOf(
                "m-1" to TmdbRating(7.3, 12_345),
                "s-s1" to TmdbRating(7.9, 800),
                "m-4" to TmdbRating(10.0, 10),
            ),
        )
        assertEquals(
            listOf("Harbor Lights", "Northern Lights", "The Last Signal"),
            UiMappers.home(voted, labels, Fixtures.NOW, Fixtures.kept()).hero.map { it.item.title },
        )

        val hidingWatched = Fixtures.state(library, settings = AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, hideWatchedMovies = true)).copy(trending = trending)
        assertEquals(listOf("The Long Road", "Blue Horizon"), UiMappers.home(hidingWatched, labels, Fixtures.NOW).trending.map { it.title })
        // Without a token, nothing from TMDb.
        assertTrue(UiMappers.home(Fixtures.state(), labels, Fixtures.NOW).trending.isEmpty())
    }

    /** A title's wide picture: the provider's unless it did not load, else TMDb's, else the trending one's. */
    @Test
    fun theBackdropIsTheProvidersElseTmdbsElseTheTrendingOne() {
        val movie = Fixtures.longRoad
        val item = ShowcaseItem.MovieItem(movie)
        val provider = "http://example.com/backdrop/road.jpg"
        val found = "https://image.tmdb.org/t/p/w1280/found.jpg"
        val trending = TrendingMatch.MovieMatch(movie, TmdbItem(1, TmdbItem.MediaType.MOVIE, movie.name, backdropPath = "/trending.jpg"))
        val base = Fixtures.state().copy(movieDetails = mapOf(movie.id to MediaDetails(title = movie.name, backdropUrl = provider)), trending = listOf(trending))
        assertEquals(provider, UiMappers.backdrop(base, item))
        val dead = base.copy(deadBackdrops = setOf(provider))
        assertEquals("https://image.tmdb.org/t/p/w1280/trending.jpg", UiMappers.backdrop(dead, item))
        assertEquals(found, UiMappers.backdrop(dead.copy(tmdbBackdrops = mapOf(item.key to found)), item))
        assertEquals(found, UiMappers.movieDetail(dead.copy(tmdbBackdrops = mapOf(item.key to found)), movie).backdropUrl)
        assertNull(UiMappers.backdrop(dead.copy(trending = emptyList()), item))
    }

    /** Search: the people TMDb knows by the words typed follow the playlist's own, each name once, for that query only. */
    @Test
    fun tmdbPeopleFollowThePlaylistsOwnInSearch() {
        val local = SearchUiState(query = "ann", people = listOf("Ann Example"))
        val merged = UiMappers.withTmdbPeople(local, "ann", listOf("ANN EXAMPLE", "Anna Sample", ""))
        assertEquals(listOf("Ann Example", "Anna Sample"), merged.people)
        // An answer for an older query, or a filtered search, adds nobody.
        assertEquals(local, UiMappers.withTmdbPeople(local, "an", listOf("Anna Sample")))
        val filtered = local.copy(filters = SearchFilters(fromYear = 2020))
        assertEquals(filtered, UiMappers.withTmdbPeople(filtered, "ann", listOf("Anna Sample")))
    }

    @Test
    fun actorsAndDirectorsAreKeptInMyListAndCollectionsWhateverThePlaylist() {
        val ann = Favorite.person("Ann Example", "https://image.example.com/ann.jpg", now = 1)
        val bo = Favorite.person("Bo Sample", null, now = 2)
        val library = LibraryState().toggleFavorite(ann).toggleFavorite(bo)
            .creatingCollection("c1", "Weekend", now = 1)
            .togglingInCollection("c1", Favorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.glassCity.id, "Glass City", addedAt = 1), now = 2)
            .togglingInCollection("c1", bo, now = 3)
        val state = Fixtures.state().copy(library = library)
        val myList = UiMappers.myList(state, Fixtures.NOW)
        assertEquals("latest first", listOf(PersonRow("Bo Sample"), PersonRow("Ann Example", "https://image.example.com/ann.jpg")), myList.people)
        assertEquals(listOf(CollectionRow("c1", "Weekend", 2)), myList.collections)
        assertEquals(listOf(PersonRow("Bo Sample")), UiMappers.collection(state, "c1", Fixtures.NOW)!!.people)

        val page = UiMappers.person(state, "Ann Example")
        assertTrue(page.inMyList)
        assertEquals("https://image.example.com/ann.jpg", page.photoUrl)
        assertEquals(listOf(CollectionChoice("c1", "Weekend", contains = false)), page.collections)
        assertTrue(UiMappers.person(state, "Bo Sample").collections.single().contains)
        assertFalse(UiMappers.person(state, "Cy Nobody").inMyList)

        // Another playlist: the same people.
        val other = state.copy(settings = state.settings.copy(activePlaylistId = "another"))
        assertEquals(2, UiMappers.myList(other, Fixtures.NOW).people.size)
    }

    /**
     * A person's page: the pages already opened, and with TMDb's credits all the titles of the playlist TMDb credits
     * them with, without a hidden category or a finished movie Settings hides.
     */
    @Test
    fun aPersonsPageAddsTheTitlesTmdbCreditsThemWith() {
        fun movie(movie: Movie) = TrendingMatch.MovieMatch(movie, TmdbItem(1, TmdbItem.MediaType.MOVIE, movie.name))
        val online = listOf(movie(Fixtures.glassCity), movie(Fixtures.adultMovie), TrendingMatch.SeriesMatch(Fixtures.theArchive, TmdbItem(2, TmdbItem.MediaType.TV, "The Archive")))
        val opened = mapOf(Fixtures.northernLights.id to MediaDetails(title = "Northern Lights", cast = "Ann Example, Bo Sample"))
        val state = Fixtures.state().copy(movieDetails = opened)
        val known = UiMappers.person(state, "Ann Example")
        assertEquals(listOf("Northern Lights"), known.movies.map { it.title })
        assertFalse(known.searchedOnline)
        assertFalse(known.hasTmdbToken)

        val found = UiMappers.person(state, "Ann Example", online)
        assertEquals(setOf("Northern Lights", "Glass City"), found.movies.map { it.title }.toSet())
        assertEquals(listOf("The Archive"), found.series.map { it.title })
        assertTrue(found.searchedOnline)
        // TMDb answered, but nothing of the playlist: still searched.
        assertTrue(UiMappers.person(state, "Ann Example", emptyList()).searchedOnline)
    }

    @Test
    fun homeShowsTheCarouselShelvesAndChannels() {
        val home = UiMappers.home(Fixtures.state(), labels, Fixtures.NOW, Fixtures.kept())
        assertEquals("Home", home.playlistName)
        assertEquals(6, home.hero.size)
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
        val hero = UiMappers.home(Fixtures.state(), Labels("Film", "Série", Locale.FRANCE), Fixtures.NOW, Fixtures.kept()).hero
        assertEquals("Film · 2025 · ★ 7,8", hero.first { it.item.title == "Northern Lights" }.meta)
    }

    /** Both ratings side by side: the provider's, then TMDb's with its number of votes when TMDb gave them. */
    @Test
    fun aRatingShowsItsVotesWhenTmdbGaveThem() {
        // Every recent release rated on the device, so that the carousel holds them all.
        val kept = Fixtures.kept().mapValues { (_, title) -> title.copy(voteAverage = 7.0, voteCount = 100) }
        val key = UiMappers.home(Fixtures.state(), labels, Fixtures.NOW, kept).hero.first { it.item.title == "Northern Lights" }.item.key
        val state = Fixtures.state().copy(tmdbRatings = mapOf(key to TmdbRating(7.346, 12345)))
        assertEquals("Movie · 2025 · ★ 7.8 · TMDb 7.3 (12,345)", UiMappers.home(state, labels, Fixtures.NOW, kept).hero.first { it.item.title == "Northern Lights" }.meta)
        assertEquals("Series · 2025 · Drama · ★ 8.2", UiMappers.home(state, labels, Fixtures.NOW, kept).hero.first { it.item.title == "Harbor Lights" }.meta)
        val french = UiMappers.home(state, Labels("Film", "Série", Locale.FRANCE), Fixtures.NOW, kept).hero.first { it.item.title == "Northern Lights" }.meta
        assertTrue(french, french.matches(Regex("Film · 2025 · ★ 7,8 · TMDb 7,3 \\(12[\\s\\u00A0\\u202F]345\\)")))
        assertNull(UiMappers.ratingFact(null, null, Locale.US))
        assertNull(UiMappers.ratingFact(0.0, null, Locale.US))
        assertEquals("★ 6.0", UiMappers.ratingFact(6.0, null, Locale.US))
        // TMDb's alone when the provider gives none, or the same.
        assertEquals("TMDb 6.0 (42)", UiMappers.ratingFact(null, TmdbRating(6.04, 42), Locale.US))
        assertEquals("TMDb 6.0 (42)", UiMappers.ratingFact(6.0, TmdbRating(6.04, 42), Locale.US))
        // A trending title carries TMDb's votes from the trending answer.
        val movie = Fixtures.longRoad
        val trending = TrendingMatch.MovieMatch(movie, TmdbItem(1, TmdbItem.MediaType.MOVIE, movie.name, voteAverage = 6.84, voteCount = 980))
        val withTrending = Fixtures.state().copy(trending = listOf(trending))
        assertEquals(TmdbRating(6.84, 980), UiMappers.tmdbRating(withTrending, ShowcaseItem.MovieItem(movie)))
        assertEquals(TmdbRating(6.84, 980), UiMappers.movieDetail(withTrending, movie).votes)
        assertNull(UiMappers.tmdbRating(Fixtures.state(), ShowcaseItem.MovieItem(movie)))
    }

    @Test
    fun favouriteChannelsComeFirstOnHome() {
        val library = LibraryState().toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.CHANNEL, "c3", "Stadium One", addedAt = 1))
        val home = UiMappers.home(Fixtures.state(library), labels, Fixtures.NOW)
        assertTrue(home.channelsAreFavorites)
        assertEquals(listOf("Stadium One"), home.channels.map { it.channel.name })
    }

    @Test
    fun homeTellsEpisodesFromMoviesInProgress() {
        val movie = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.glassCity.id, title = "Glass City", lastWatchedAt = 1)
        val episode = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.SERIES, Fixtures.harborLights.id, "e1", title = "Harbor Lights", lastWatchedAt = 3)
        val library = LibraryState()
            .recordStart(movie).updateProgress(movie.key, 600.0, 6_000.0, now = 2)
            .recordStart(episode).updateProgress(episode.key, 300.0, 3_000.0, now = 4)
        val resume = UiMappers.home(Fixtures.state(library), labels, Fixtures.NOW).continueWatching
        assertEquals(setOf("Glass City" to false, "Harbor Lights" to true), resume.map { it.title to it.isSeries }.toSet())
        // What a TV's header shows of each card: the movie, and the episode's series.
        assertEquals(
            setOf(ShowcaseItem.MovieItem(Fixtures.glassCity), ShowcaseItem.SeriesItem(Fixtures.harborLights)),
            resume.map { it.showcase }.toSet(),
        )
    }

    /**
     * A series whose episode was watched to the end goes on in Continue watching with the next one (the next season's
     * first after a season's last), from its start, a card that cannot be taken away; with the next one started
     * earlier, its own progress; after the last episode, the series leaves. Its seasons not fetched yet: the series'
     * card plays the next one.
     */
    @Test
    fun continueWatchingGoesOnWithTheNextEpisodeOfAFinishedOne() {
        val series = Fixtures.harborLights
        // The first season's last episode, and the next season's first.
        val first = series.episodes.single { it.season == 1 && it.number == 3 }
        val second = series.episodes.single { it.season == 2 && it.number == 1 }
        fun key(id: String) = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.SERIES, series.id, id, title = series.name, lastWatchedAt = 0).key
        val finished = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.SERIES, series.id, first.id, title = series.name, subtitle = "${first.code} · ${first.title}", lastWatchedAt = 1)
        val library = LibraryState().recordStart(finished).updateProgress(finished.key, 2_700.0, 2_700.0, now = 2)

        val card = UiMappers.home(Fixtures.state(library), labels, Fixtures.NOW).continueWatching.single()
        assertEquals(key(second.id), card.key)
        assertEquals("${second.code} · ${second.title}", card.subtitle)
        assertTrue(card.upNext)
        assertEquals(0f, card.progress, 0f)
        assertEquals(listOf(key(second.id)), UiMappers.vod(Fixtures.state(library), series = true, title = "").continueWatching.map { it.key })

        // Its seasons not fetched yet: the series, under the finished episode's key, not named as if to resume it.
        val unknown = UiMappers.home(Fixtures.state(library).copy(seriesDetails = emptyMap()), labels, Fixtures.NOW).continueWatching.single()
        assertEquals(finished.key, unknown.key)
        assertTrue(unknown.upNext)
        assertNull(unknown.subtitle)
        assertEquals(0f, unknown.progress, 0f)

        // The next one was started before: its own progress.
        val started = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.SERIES, series.id, second.id, title = series.name, lastWatchedAt = 0)
        val earlier = LibraryState().recordStart(started).updateProgress(started.key, 900.0, 2_700.0, now = 1)
            .recordStart(finished.copy(lastWatchedAt = 2)).updateProgress(finished.key, 2_700.0, 2_700.0, now = 3)
        val resumed = UiMappers.home(Fixtures.state(earlier), labels, Fixtures.NOW).continueWatching.single()
        assertEquals(key(second.id), resumed.key)
        assertFalse(resumed.upNext)
        assertEquals(1f / 3, resumed.progress, 0.001f)

        // The last episode watched: the series leaves.
        val last = series.episodes.maxWith(compareBy({ it.season }, { it.number }))
        val ended = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.SERIES, series.id, last.id, title = series.name, lastWatchedAt = 1)
        val done = LibraryState().recordStart(ended).updateProgress(ended.key, 2_700.0, 2_700.0, now = 2)
        assertTrue(UiMappers.home(Fixtures.state(done), labels, Fixtures.NOW).continueWatching.isEmpty())
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
    fun finishedMoviesLeaveTheListsWhenSettingsHidesThemButStayInSearchAndMyList() {
        val finished = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.glassCity.id, title = "Glass City", lastWatchedAt = 1)
        val library = LibraryState()
            .recordStart(finished).updateProgress(finished.key, 5_900.0, 6_000.0, now = 2)
            .toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.glassCity.id, "Glass City", addedAt = 3))
        fun titles(state: com.markedoe.ondo.data.OndoState): Map<String, List<String>> {
            val home = UiMappers.home(state, labels, Fixtures.NOW, Fixtures.kept())
            val index = UiMappers.SearchIndex(state.content, UiMappers.hiddenCategoryIds(state))
            return mapOf(
                "grid" to UiMappers.grid(state, false, null, false, "").map { it.title },
                "rows" to UiMappers.vod(state, series = false, title = "").rows.flatMap { row -> row.items.map { it.title } },
                "new" to home.newMovies.map { it.title },
                "hero" to home.hero.map { it.item.title },
                "similar" to UiMappers.movieDetail(state, Fixtures.lastSignal).similar.map { it.title },
                "year" to UiMappers.browse(state, BrowseKind.YEAR, "2024").movies.map { it.title },
                "search" to UiMappers.search(index, "glass", state, Fixtures.NOW).movies.map { it.title },
                "myList" to home.myList.map { it.title },
            )
        }
        val shown = titles(Fixtures.state(library))
        shown.forEach { (list, items) -> assertTrue("$list shows it by default", "Glass City" in items) }
        val hidden = titles(Fixtures.state(library, settings = AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, hideWatchedMovies = true)))
        listOf("grid", "rows", "new", "hero", "similar", "year").forEach { assertFalse("$it leaves it out", "Glass City" in hidden.getValue(it)) }
        listOf("search", "myList").forEach { assertTrue("$it keeps it", "Glass City" in hidden.getValue(it)) }
        // Other movies stay.
        assertTrue("Northern Lights" in hidden.getValue("grid"))
    }

    /** A genre's page in each order, the titles found beyond the pages opened sorted with the others. */
    @Test
    fun aBrowsePageSortsByRatingAndByReleaseDate() {
        val state = Fixtures.state().copy(
            movieDetails = mapOf(
                Fixtures.northernLights.id to MediaDetails(title = Fixtures.northernLights.name, genre = "Drama"),
                Fixtures.summerInLisbon.id to MediaDetails(title = Fixtures.summerInLisbon.name, genre = "Drama, Romance"),
            ),
        )
        fun found(movie: Movie) = TrendingMatch.MovieMatch(movie, TmdbItem(1, TmdbItem.MediaType.MOVIE, movie.name))
        val elsewhere = listOf(Fixtures.glassCity, Fixtures.lastSignal, Fixtures.longRoad).map(::found)
        fun titles(sort: PosterSort) = UiMappers.browse(state, BrowseKind.GENRE, "Drama", Locale.US, elsewhere, sort).movies.map { it.title }

        // The page's own order: by name.
        assertEquals(listOf("Glass City", "Northern Lights", "Summer in Lisbon", "The Last Signal", "The Long Road"), titles(PosterSort.PLAYLIST))
        assertEquals(listOf("The Last Signal", "Northern Lights", "Summer in Lisbon", "Glass City", "The Long Road"), titles(PosterSort.RATING))
        // Newest year first; within 2025, the latest added first.
        assertEquals(listOf("Northern Lights", "The Last Signal", "Glass City", "Summer in Lisbon", "The Long Road"), titles(PosterSort.RELEASE))
        // A year's page too.
        assertEquals(
            listOf("The Last Signal", "Northern Lights"),
            UiMappers.browse(state, BrowseKind.YEAR, "2025", sort = PosterSort.RATING).movies.map { it.title },
        )
    }

    @Test
    fun gridFiltersIgnoreCaseAndAccents() {
        val state = Fixtures.state()
        assertEquals(listOf("Summer in Lisbon"), UiMappers.grid(state, false, null, false, "LISBÔN").map { it.title })
        assertEquals(3, UiMappers.grid(state, false, Fixtures.drama.id, false, "").size)
        assertEquals(listOf("The Archive", "Blue Horizon"), UiMappers.grid(state, true, Fixtures.seriesMystery.id, false, "").map { it.title })
    }

    @Test
    fun searchMatchesWordsAnywhereInTheTitles() {
        val state = Fixtures.state()
        val index = UiMappers.SearchIndex(state.content, UiMappers.hiddenCategoryIds(state))
        fun titles(query: String) = UiMappers.search(index, query, state, Fixtures.NOW).let { result ->
            (result.movies + result.series).map { it.title } + result.channels.map { it.channel.name }
        }
        assertEquals(listOf("The Last Signal"), titles("last sig"))
        assertEquals(listOf("Northern Lights", "Harbor Lights"), titles("lights"))
        assertEquals(listOf("Stadium One"), titles("stad"))
        assertEquals("inside a word too (as on Apple devices)", listOf("Northern Lights", "Harbor Lights"), titles("ights"))
        assertTrue("every word typed must be found", titles("lights zzz").isEmpty())
        assertTrue("hidden categories are not searched", titles("late show").isEmpty())
        assertTrue(titles("  ").isEmpty())
    }

    @Test
    fun searchFiltersByReleaseYearsAndAwards() {
        // "Glass City" (2024) won at Cannes and was nominated for an Oscar; "Silent Harbor" (2023) only competed.
        val catalogue = AwardsCatalogue.parse(
            """
            {"awards":[{"id":"palme-dor","group":"cannes","names":{"en":"Palme d'Or"}},
                       {"id":"oscar-best-picture","group":"oscars","names":{"en":"Best Picture"}},
                       {"id":"golden-bear","group":"berlin","names":{"en":"Golden Bear"}}],
             "films":[{"titles":["Glass City"],"year":2024,"awards":[["palme-dor",2024,1],["oscar-best-picture",2025,0]]},
                      {"titles":["Silent Harbor"],"year":2023,"awards":[["palme-dor",2023,0]]},
                      {"titles":["Late Show"],"year":2025,"awards":[["golden-bear",2025,1]]}]}
            """.trimIndent(),
        )
        val state = Fixtures.state().copy(awards = catalogue)
        val index = UiMappers.SearchIndex(state.content, UiMappers.hiddenCategoryIds(state))
        fun titles(query: String, filters: SearchFilters) = UiMappers.search(index, query, state, Fixtures.NOW, filters).let { result ->
            (result.movies + result.series).map { it.title } + result.channels.map { it.channel.name }
        }
        // An empty query lists what was released in those years, movies then series, newest first.
        assertEquals(listOf("Glass City", "Midnight Orchard", "The Archive"), titles("", SearchFilters(fromYear = 2024, toYear = 2024)))
        assertEquals(listOf("Northern Lights", "The Last Signal", "Harbor Lights"), titles("", SearchFilters(fromYear = 2025)))
        assertEquals(listOf("The Long Road"), titles("", SearchFilters(toYear = 2021)))
        assertEquals(2023..2025, SearchFilters(fromYear = 2025, toYear = 2023).years)
        assertEquals(listOf("Northern Lights", "Harbor Lights"), titles("lights", SearchFilters(fromYear = 2025)))
        assertTrue("channels have no release year", titles("stad", SearchFilters(fromYear = 2000)).isEmpty())
        // Awards: movies only, every selection or the wins only.
        assertEquals(listOf("Glass City", "Silent Harbor"), titles("", SearchFilters(awardGroup = "cannes")))
        assertEquals(listOf("Glass City"), titles("", SearchFilters(awardGroup = "cannes", winnersOnly = true)))
        assertTrue(titles("", SearchFilters(awardGroup = "oscars", winnersOnly = true)).isEmpty())
        assertEquals(listOf("Silent Harbor"), titles("", SearchFilters(fromYear = 2023, toYear = 2023, awardGroup = "cannes")))
        // The choices: the playlist's years and the festivals of its visible movies (the hidden "Late Show" won in Berlin).
        val choices = UiMappers.search(index, "", state, Fixtures.NOW)
        assertEquals(listOf(2025, 2024, 2023, 2022, 2021), choices.years)
        assertEquals(listOf("cannes", "oscars"), choices.awardGroups)
        assertTrue("no filter, no query: nothing listed", choices.movies.isEmpty())
    }

    @Test
    fun aPersonListsTheOpenedPagesThatNameThem() {
        val state = Fixtures.state().copy(
            movieDetails = mapOf(
                Fixtures.glassCity.id to MediaDetails(title = "Glass City", cast = "Ada Vance, Tom Reyes (voice)", director = "Lena Moss"),
                Fixtures.northernLights.id to MediaDetails(title = "Northern Lights", director = "Ada Vance"),
                Fixtures.adultMovie.id to MediaDetails(title = "Late Show", cast = "Ada Vance"),
            ),
            seriesDetails = mapOf(
                Fixtures.theArchive.id to SeriesDetails(MediaDetails(title = "The Archive", cast = "Élodie Marçeau, ada vance"), seasons = emptyList()),
            ),
        )
        val works = UiMappers.person(state, "Ada Vance")
        assertEquals("newest first, hidden categories left out", listOf("Northern Lights", "Glass City"), works.movies.map { it.title })
        assertEquals(listOf("The Archive"), works.series.map { it.title })
        assertEquals(listOf("The Archive"), UiMappers.person(state, "elodie marceau").series.map { it.title })
        assertTrue("a whole name, not a part of one", UiMappers.person(state, "Tom").movies.isEmpty())
    }

    /** Search lists the actors and directors whose name matches: series lists name their cast, opened pages too. */
    @Test
    fun searchFindsActorsAndDirectorsByName() {
        val content = Fixtures.content.copy(
            series = Fixtures.content.series.map { if (it.id == Fixtures.harborLights.id) it.copy(cast = "Mara Quinn, Ada Vance", director = "Sol Ortega") else it },
        )
        val state = Fixtures.state(content = content).copy(
            movieDetails = mapOf(Fixtures.glassCity.id to MediaDetails(title = "Glass City", cast = "Ada Vance (Mara), Tom Reyes")),
        )
        val index = UiMappers.SearchIndex(state.content, UiMappers.hiddenCategoryIds(state))
        assertEquals("once, however often it is named", listOf("Ada Vance"), UiMappers.search(index, "vance", state, Fixtures.NOW).people)
        assertEquals(listOf("Sol Ortega"), UiMappers.search(index, "ORTE", state, Fixtures.NOW).people)
        assertTrue("three letters at least", UiMappers.search(index, "so", state, Fixtures.NOW).people.isEmpty())
        // A series is found on the person's page through its list entry, without opening it.
        assertEquals(listOf("Harbor Lights"), UiMappers.person(state, "Mara Quinn").series.map { it.title })
    }

    /**
     * A language hidden in Settings leaves Search as it leaves the pages: its movies, series and channels, and the people
     * named only by its titles (a page opened before the language was hidden).
     */
    @Test
    fun searchLeavesOutWhatALanguageHiddenInSettingsHides() {
        val arMovies = MediaCategory("m-ar", "|AR| Films", ContentType.MOVIE)
        val arKids = MediaCategory("m-ar-kids", "|AR| Kids", ContentType.MOVIE)
        val frMovies = MediaCategory("m-fr", "|FR| Films", ContentType.MOVIE)
        val frKids = MediaCategory("m-fr-kids", "|FR| Kids", ContentType.MOVIE)
        val arSeries = MediaCategory("s-ar", "|AR| Series", ContentType.SERIES)
        val arNews = MediaCategory("l-ar", "|AR| News", ContentType.LIVE)
        fun movie(id: String, name: String, category: MediaCategory) = Movie(id, name, categoryId = category.id, streamUrl = "http://example.com/movie/$id.mp4", year = 2020)
        val content = Fixtures.content.copy(
            movies = Fixtures.content.movies + listOf(movie("ar1", "Desert Star", arMovies), movie("ar2", "Desert Kids", arKids), movie("fr1", "Desert Bleu", frMovies), movie("fr2", "Petit Desert", frKids)),
            series = Fixtures.content.series + Series(id = "sar", name = "Desert Nights", categoryId = arSeries.id, cast = "Omar Haddad"),
            channels = Fixtures.content.channels + Channel(id = "car", name = "Desert News", categoryId = arNews.id, streamUrl = "http://example.com/live/car.m3u8"),
            movieCategories = Fixtures.content.movieCategories + listOf(arMovies, arKids, frMovies, frKids),
            seriesCategories = Fixtures.content.seriesCategories + arSeries,
            liveCategories = Fixtures.content.liveCategories + arNews,
        )
        val shown = Fixtures.state(content = content).copy(movieDetails = mapOf("ar1" to MediaDetails(title = "Desert Star", cast = "Layla Haddad")))
        val hidden = shown.copy(library = shown.library.settingTagsHidden(listOf("AR"), hidden = true))
        fun search(state: OndoState, query: String) =
            UiMappers.search(UiMappers.SearchIndex(state.content, UiMappers.hiddenCategoryIds(state)), query, state, Fixtures.NOW)

        val before = search(shown, "desert")
        assertEquals(listOf("Desert Star", "Desert Kids", "Desert Bleu", "Petit Desert"), before.movies.map { it.title })
        assertEquals(listOf("Desert Nights"), before.series.map { it.title })
        assertEquals(listOf("Desert News"), before.channels.map { it.channel.name })
        assertEquals(listOf("Layla Haddad", "Omar Haddad"), search(shown, "haddad").people)

        val after = search(hidden, "desert")
        assertEquals("the French ones only", listOf("Desert Bleu", "Petit Desert"), after.movies.map { it.title })
        assertTrue(after.series.isEmpty())
        assertTrue(after.channels.isEmpty())
        assertTrue("nobody named only by hidden titles", search(hidden, "haddad").people.isEmpty())
        // The same with filters: an empty query then lists the whole playlist.
        val filtered = UiMappers.search(UiMappers.SearchIndex(hidden.content, UiMappers.hiddenCategoryIds(hidden)), "", hidden, Fixtures.NOW, SearchFilters(fromYear = 2020, toYear = 2020))
        assertEquals(listOf("Desert Bleu", "Petit Desert"), filtered.movies.map { it.title })
    }

    /** A hidden language's catalogue: every name carries [MARKER], so a list showing any of it is caught. */
    private object HiddenLanguage {
        const val MARKER = "Saudade"
        val films = MediaCategory("m-pt", "|PT| Films", ContentType.MOVIE)
        val kids = MediaCategory("m-pt-kids", "|PT| Infantil", ContentType.MOVIE)
        val series = MediaCategory("s-pt", "|PT| Séries", ContentType.SERIES)
        val live = MediaCategory("l-pt", "|PT| Canais", ContentType.LIVE)
        val categories = listOf(films, kids, series, live)
        val movie = Movie("pt1", "|PT| $MARKER do Mar", categoryId = films.id, streamUrl = "http://example.com/movie/pt1.mp4", year = 2024, rating = 9.9, addedAt = Fixtures.NOW / 1000)
        val started = Movie("pt2", "|PT| $MARKER em Casa", categoryId = kids.id, streamUrl = "http://example.com/movie/pt2.mp4", year = 2024, rating = 9.8, addedAt = Fixtures.NOW / 1000)
        val show = Series(id = "spt", name = "|PT| $MARKER Nocturna", categoryId = series.id, year = 2024, rating = 9.9, genre = "Drama", cast = "Rui $MARKER", addedAt = Fixtures.NOW / 1000)
        val channel = Channel(id = "cpt", name = "|PT| $MARKER TV", categoryId = live.id, streamUrl = "http://example.com/live/cpt.m3u8")

        /** In visible categories of no language, titles whose own name carries the language: each holds [BY_NAME]. */
        const val BY_NAME = "Carreira"
        val concerts = MediaCategory("m-concerts", "Concerts", ContentType.MOVIE)
        val classics = MediaCategory("s-classics", "Clássicos", ContentType.SERIES)
        val radio = MediaCategory("l-radio", "Rádio", ContentType.LIVE)
        val concert = Movie("ct1", "|PT| Tony $BY_NAME – Ao Vivo em Lisboa", categoryId = concerts.id, streamUrl = "http://example.com/movie/ct1.mp4", year = 2024, addedAt = Fixtures.NOW / 1000)
        val classic = Series(id = "sct", name = "PT - $BY_NAME Antiga", categoryId = classics.id, year = 2024, addedAt = Fixtures.NOW / 1000)
        val station = Channel(id = "cct", name = "[PT] $BY_NAME Rádio", categoryId = radio.id, streamUrl = "http://example.com/live/cct.m3u8")

        /** Names that start like a tag but carry none: they stay whatever language is hidden. */
        val notTagged = listOf(
            Movie("nt1", "Portugal: The Man – Live", categoryId = concerts.id, streamUrl = "http://example.com/movie/nt1.mp4", year = 2024),
            Movie("nt2", "PTSD Stories", categoryId = concerts.id, streamUrl = "http://example.com/movie/nt2.mp4", year = 2024),
        )

        val content = Fixtures.content.copy(
            movies = Fixtures.content.movies + listOf(movie, started, concert) + notTagged,
            series = Fixtures.content.series + show + classic,
            channels = Fixtures.content.channels + channel + station,
            movieCategories = Fixtures.content.movieCategories + listOf(films, kids, concerts),
            seriesCategories = Fixtures.content.seriesCategories + listOf(series, classics),
            liveCategories = Fixtures.content.liveCategories + listOf(live, radio),
        )
    }

    /**
     * Regression guard for hidden categories, on every list of titles at once. A language hidden in Settings (as a
     * language, or its categories one by one, on this device or on another one and applied by sync) leaves none of its
     * titles, channels or people in:
     *  - Home: carousel, new movies, new series, trending, channels;
     *  - Movies and Series: rows and grids (every category);
     *  - Search: movies, series, channels and people, with and without filters;
     *  - pages reached from a title: person, genre, year, award, similar titles (movie and series);
     *  - Live TV and the guide;
     *  - My List.
     * The one exception, the current product rule: Continue watching keeps a title the viewer already started.
     * A new screen that lists titles is added to [surfaces] here.
     */
    @Test
    fun noListShowsTheTitlesOfAHiddenLanguage() {
        val marker = HiddenLanguage.MARKER
        val started = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, HiddenLanguage.started.id, title = HiddenLanguage.started.name, lastWatchedAt = 1)
        val library = LibraryState()
            .recordStart(started).updateProgress(started.key, 600.0, 6_000.0, now = 2)
            .toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, HiddenLanguage.movie.id, HiddenLanguage.movie.name, addedAt = 3))
        val base = Fixtures.state(library, content = HiddenLanguage.content).copy(
            // The hidden titles share a genre, a year and an award with visible ones, are named by TMDb among a visible
            // movie's similar titles, and name an actor only they name.
            movieDetails = mapOf(
                HiddenLanguage.movie.id to MediaDetails(title = HiddenLanguage.movie.name, genre = "Drama", cast = "Ana $marker"),
                Fixtures.northernLights.id to MediaDetails(title = Fixtures.northernLights.name, genre = "Drama"),
            ),
            tmdbRelated = mapOf(
                ShowcaseItem.MovieItem(Fixtures.glassCity).key to listOf(TrendingMatch.MovieMatch(HiddenLanguage.movie, TmdbItem(1, TmdbItem.MediaType.MOVIE, HiddenLanguage.movie.name))),
                ShowcaseItem.SeriesItem(Fixtures.harborLights).key to listOf(TrendingMatch.SeriesMatch(HiddenLanguage.show, TmdbItem(2, TmdbItem.MediaType.TV, HiddenLanguage.show.name))),
            ),
        )
        /** Every list of titles, by its name: what each shows, as text (the titles and names in it). */
        fun surfaces(state: OndoState): Map<String, String> {
            // Every title released lately, so that the carousel would feature the hidden ones.
            val home = UiMappers.home(state, labels, Fixtures.NOW, Fixtures.released(state.content))
            val index = UiMappers.SearchIndex(state.content, UiMappers.hiddenCategoryIds(state), state.library.hiddenTags)
            // What a search found, without the words typed.
            fun found(search: SearchUiState) = listOf(search.movies, search.series, search.channels.map { it.channel.name }, search.people).toString()
            val categories = state.content.movieCategories.map { it.id }
            val seriesCategories = state.content.seriesCategories.map { it.id }
            return mapOf(
                "home" to home.copy(continueWatching = emptyList(), tagSuggestion = null).toString(),
                "movies rows" to UiMappers.vod(state, series = false, title = "").copy(continueWatching = emptyList()).toString(),
                "series rows" to UiMappers.vod(state, series = true, title = "").copy(continueWatching = emptyList()).toString(),
                "movie grids" to (categories.map { UiMappers.grid(state, false, it, false, "") } + listOf(UiMappers.grid(state, false, null, false, ""))).toString(),
                "series grids" to (seriesCategories.map { UiMappers.grid(state, true, it, false, "") } + listOf(UiMappers.grid(state, true, null, false, ""))).toString(),
                "my list" to UiMappers.myList(state, Fixtures.NOW).toString(),
                "search" to listOf("a", "o", marker.lowercase(), "do mar", "tv", "carreira", "tony").joinToString { found(UiMappers.search(index, it, state, Fixtures.NOW)) },
                "filtered search" to found(UiMappers.search(index, "", state, Fixtures.NOW, SearchFilters(fromYear = 2024, toYear = 2024))),
                "person" to listOf("Ana $marker", "Rui $marker").map { UiMappers.person(state, it).let { page -> page.movies + page.series } }.toString(),
                "genre" to UiMappers.browse(state, BrowseKind.GENRE, "Drama", Locale.ENGLISH).toString(),
                "year" to UiMappers.browse(state, BrowseKind.YEAR, "2024", Locale.ENGLISH).toString(),
                "similar movies" to UiMappers.movieDetail(state, Fixtures.glassCity).similar.toString(),
                "similar series" to UiMappers.seriesDetail(state, Fixtures.harborLights, null, null).similar.toString(),
                "live" to UiMappers.live(state, null, false, Fixtures.NOW).toString(),
                "guide" to UiMappers.guide(state, null, false, Fixtures.NOW).toString(),
            )
        }
        // Shown: every surface that can list them does, so the check below proves something.
        val shown = surfaces(base)
        listOf("movies rows", "series rows", "movie grids", "series grids", "my list", "search", "filtered search", "person", "genre", "year", "similar movies", "similar series", "live", "guide")
            .forEach { assertTrue("$it lists the language while it is shown", marker in shown.getValue(it)) }

        // The titles named with the language in visible categories of no language, shown while the language is.
        val byName = HiddenLanguage.BY_NAME
        listOf("movies rows", "series rows", "movie grids", "series grids", "search", "filtered search", "year", "live", "guide")
            .forEach { assertTrue("$it lists the titles named with the language while it is shown", byName in shown.getValue(it)) }

        val byLanguage = base.copy(library = base.library.settingTagsHidden(listOf("PT"), hidden = true))
        val oneByOne = base.copy(library = base.library.settingCategoriesHidden(Fixtures.PLAYLIST_ID, HiddenLanguage.categories.map { it.id }, hidden = true))
        // A hidden language leaves the titles whose own name carries it too ("|PT| …", "PT - …", "[PT] …"), whatever
        // their category; names that only look like a tag ("Portugal: …", "PTSD …") stay.
        surfaces(byLanguage).forEach { (surface, text) -> assertFalse("hidden as a language, $surface still shows a title named with it: $text", byName in text) }
        val left = surfaces(byLanguage)
        for (name in HiddenLanguage.notTagged.map { it.name }) {
            listOf("movies rows", "movie grids", "year").forEach { assertTrue("$name stays in $it", name in left.getValue(it)) }
        }
        // Categories hidden one by one hide those categories only: the language itself is not hidden.
        assertTrue("one by one, a title of a visible category stays", byName in surfaces(oneByOne).getValue("movies rows"))
        // Hidden on another device in this profile, and applied by sync (docs/SYNC.md, "Hidden categories").
        fun synced(state: OndoState, received: SyncSnapshot): OndoState =
            state.copy(library = SyncData.apply(state, SyncMerge.merge(SyncData.snapshot(state, "tv", null, Fixtures.NOW), received)).library)
        val syncedLanguage = synced(base, SyncSnapshot(device = "phone", hiddenCategories = listOf(SyncHidden(SyncHidden.TAG, "PT", 5))))
        val syncedOneByOne = synced(
            base, SyncSnapshot(device = "phone", hiddenCategories = HiddenLanguage.categories.map { SyncHidden.category(Fixtures.PLAYLIST_ID, it.id, 5) }),
        )
        surfaces(syncedLanguage).forEach { (surface, text) -> assertFalse("hidden as a language by sync, $surface still shows a title named with it: $text", byName in text) }
        val states = listOf(
            "as a language" to byLanguage, "one by one" to oneByOne,
            "as a language on another device" to syncedLanguage, "one by one on another device" to syncedOneByOne,
        )
        for ((how, state) in states) {
            surfaces(state).forEach { (surface, text) -> assertFalse("hidden $how, $surface still shows it: $text", marker in text) }
            // The exception: Continue watching keeps the title already started; My List does not keep the saved one.
            val home = UiMappers.home(state, labels, Fixtures.NOW)
            assertEquals("hidden $how", listOf(HiddenLanguage.started.name), home.continueWatching.filter { marker in it.title }.map { it.title })
            assertTrue("hidden $how, My List leaves it out", home.myList.none { marker in it.title })
        }
        // Shown again on another device after it was hidden here: every list shows it again.
        val hiddenHere = base.copy(library = base.library.settingTagsHidden(listOf("PT"), hidden = true, now = 5))
        val shownAgain = synced(hiddenHere, SyncSnapshot(device = "phone", deleted = mapOf(SyncKeys.hiddenTag("PT") to 10)))
        assertTrue(shownAgain.library.hiddenTags.isEmpty())
        val again = surfaces(shownAgain)
        listOf("movies rows", "series rows", "movie grids", "series grids", "my list", "search", "filtered search", "person", "genre", "year", "similar movies", "similar series", "live", "guide")
            .forEach { assertTrue("$it lists the language once shown again on another device", marker in again.getValue(it)) }
    }

    @Test
    fun aCollectionShowsItsItemsOfThePlaylistInTheOrderAdded() {
        fun favorite(kind: ItemKind, id: String, playlistId: String = Fixtures.PLAYLIST_ID) = Favorite(playlistId, kind, id, id, addedAt = 1)
        var library = LibraryState().creatingCollection("c1", "Weekend", now = 1)
        for (item in listOf(
            favorite(ItemKind.MOVIE, Fixtures.silentHarbor.id),
            favorite(ItemKind.MOVIE, Fixtures.glassCity.id),
            favorite(ItemKind.MOVIE, Fixtures.adultMovie.id),
            favorite(ItemKind.SERIES, Fixtures.theArchive.id),
            favorite(ItemKind.MOVIE, Fixtures.northernLights.id, playlistId = "another"),
        )) library = library.togglingInCollection("c1", item, now = 2)
        val state = Fixtures.state().copy(library = library)
        val collection = UiMappers.collection(state, "c1", Fixtures.NOW)!!
        assertEquals("added order; hidden categories and other playlists left out", listOf("Silent Harbor", "Glass City"), collection.movies.map { it.title })
        assertEquals(listOf("The Archive"), collection.series.map { it.title })
        assertEquals("counts what the page shows", listOf(CollectionRow("c1", "Weekend", 3)), UiMappers.myList(state, Fixtures.NOW).collections)
        assertEquals(listOf(CollectionChoice("c1", "Weekend", contains = true)), UiMappers.movieDetail(state, Fixtures.glassCity).collections)
        assertEquals(listOf(CollectionChoice("c1", "Weekend", contains = false)), UiMappers.movieDetail(state, Fixtures.lastSignal).collections)
        assertNull("deleted", UiMappers.collection(state.copy(library = library.deletingCollection("c1")), "c1", Fixtures.NOW))
    }

    @Test
    fun theGuideShowsTheProgrammesOfTheLiveChoiceAndItsReminders() {
        val now = Fixtures.NOW
        val morning = EpgProgram("c1.test", now - 30 * 60_000, now + 30 * 60_000, "Morning News")
        val evening = EpgProgram("c1.test", now + 3 * 3_600_000, now + 4 * 3_600_000, "Evening News")
        val tomorrow = EpgProgram("c1.test", now + 20 * 3_600_000, now + 21 * 3_600_000, "Tomorrow")
        val reminder = Reminder(Fixtures.PLAYLIST_ID, "c1", "Ondo News", "Evening News", evening.start, evening.end)
        val state = Fixtures.state().copy(guide = EpgIndex(listOf(morning, evening, tomorrow)), library = LibraryState().togglingReminder(reminder, now))
        val guide = UiMappers.guide(state, categoryId = Fixtures.news.id, favoritesOnly = false, now = now)
        assertEquals(listOf("Ondo News", "World Report"), guide.rows.map { it.channel.name })
        assertEquals("two hours back, twelve ahead", listOf("Morning News", "Evening News"), guide.rows.first().programs.map { it.title })
        assertTrue(guide.rows[1].programs.isEmpty())
        assertTrue(guide.start <= now - 2 * 3_600_000 && guide.start % (30 * 60_000) == 0L)
        assertEquals(guide.start + 14 * 3_600_000, guide.end)
        assertEquals(setOf("c1|${evening.start}"), guide.reminders)
        assertTrue(guide.hasGuide)
        assertFalse(UiMappers.guide(state.copy(guide = null), null, false, now).hasGuide)
        assertEquals(4, UiMappers.guide(state, null, false, now).total)
    }

    /** A row of Live TV: the programme on now, and the one after it (in a gap, the next one only). */
    @Test
    fun aChannelRowShowsTheProgrammeOnNowAndTheNextOne() {
        val now = Fixtures.NOW
        val morning = EpgProgram("c1.test", now - 30 * 60_000, now + 30 * 60_000, "Morning News")
        val noon = EpgProgram("c1.test", now + 30 * 60_000, now + 90 * 60_000, "Noon News")
        val evening = EpgProgram("c1.test", now + 3 * 3_600_000, now + 4 * 3_600_000, "Evening News")
        val state = Fixtures.state().copy(guide = EpgIndex(listOf(morning, noon, evening)))
        fun row(at: Long) = UiMappers.live(state, Fixtures.news.id, false, at).channels.first { it.channel.id == "c1" }
        assertEquals("Morning News", row(now).nowTitle)
        assertEquals("Noon News", row(now).nextTitle)
        assertNull(row(now + 2 * 3_600_000).nowTitle)
        assertEquals("Evening News", row(now + 2 * 3_600_000).nextTitle)
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
        // The playlist in use says when it was last updated (Settings shows "Updated … ago" under it).
        val fetched = Fixtures.state().let { it.copy(content = it.content.copy(fetchedAt = Fixtures.NOW - 60_000)) }
        assertEquals(Fixtures.NOW - 60_000, UiMappers.settings(fetched, "1.0.0") { "M3U" }.playlists.single().updatedAt)
    }

    @Test
    fun myListItemsAreOpenable() {
        val library = LibraryState().toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.SERIES, Fixtures.harborLights.id, "Harbor Lights", addedAt = 1))
        val home = UiMappers.home(Fixtures.state(library), labels, Fixtures.NOW, Fixtures.kept())
        assertEquals(listOf(ShowcaseItem.SeriesItem(Fixtures.harborLights)), home.myList)
        assertTrue(home.hero.first { it.item == ShowcaseItem.SeriesItem(Fixtures.harborLights) }.inMyList)
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

    @Test
    fun detailPagesFindTheirTitleByIdOncePerCatalogue() {
        val state = Fixtures.state()
        assertEquals(Fixtures.glassCity, UiMappers.movie(state, Fixtures.glassCity.id))
        assertEquals(Fixtures.theArchive, UiMappers.series(state, Fixtures.theArchive.id))
        assertNull(UiMappers.movie(state, "missing"))
        assertSame("built once per catalogue", UiMappers.index(state.content), UiMappers.index(state.content))
        // An id listed twice finds the first, as a search through the list did.
        val twice = state.content.copy(movies = state.content.movies + Fixtures.glassCity.copy(name = "Glass City (copy)"))
        assertEquals("Glass City", UiMappers.index(twice).movies.getValue(Fixtures.glassCity.id).name)
        assertNotSame(UiMappers.index(state.content), UiMappers.index(twice))
    }

    @Test
    fun searchKeepsItsIndexWhileTheCatalogueAndHiddenCategoriesStay() {
        val state = Fixtures.state()
        val hidden = UiMappers.hiddenCategoryIds(state)
        val index = UiMappers.SearchIndex.of(state.content, hidden)
        assertSame("coming back to Search", index, UiMappers.SearchIndex.of(state.content, HashSet(hidden)))
        assertNotSame("a category hidden", index, UiMappers.SearchIndex.of(state.content, hidden + Fixtures.drama.id))
        assertNotSame("a new catalogue", index, UiMappers.SearchIndex.of(state.content.copy(fetchedAt = 1), hidden))
    }

    /** A word is found inside the titles too ("dust" finds "Stardust"); those that start with it come first. */
    @Test
    fun searchFindsAWordInsideTitlesAndPutsThoseStartingWithItFirst() {
        val state = Fixtures.state()
        val titles = listOf("Stardust", "Into the Dust", "Dust Devil", "Dust")
        val dusty = titles.mapIndexed { index, title -> Fixtures.glassCity.copy(id = "dust-$index", name = title) }
        val content = state.content.copy(movies = state.content.movies + dusty)
        val found = UiMappers.search(UiMappers.SearchIndex(content, UiMappers.hiddenCategoryIds(state)), "dust", state.copy(content = content), Fixtures.NOW)
        assertEquals(listOf("Dust", "Dust Devil", "Into the Dust", "Stardust"), found.movies.map { it.title })
    }

    /** A title or a channel named after a hidden language is not found, even in a category of no language. */
    @Test
    fun searchLeavesOutTheTitlesAndChannelsOfAHiddenLanguage() {
        val state = Fixtures.state()
        val german = Fixtures.glassCity.copy(id = "de-1", name = "|DE| Glass City")
        val content = state.content.copy(movies = state.content.movies + german)
        val hidden = UiMappers.hiddenCategoryIds(state)
        val shown = UiMappers.SearchIndex(content, hidden).movies.map { it.second.id }
        assertTrue("no language hidden: found", "de-1" in shown)
        val withoutGerman = UiMappers.SearchIndex(content, hidden, setOf("DE")).movies.map { it.second.id }
        assertFalse("German hidden: not found", "de-1" in withoutGerman)
        assertTrue("the others stay", Fixtures.glassCity.id in withoutGerman)
        assertNotSame("a language hidden", UiMappers.SearchIndex.of(content, hidden), UiMappers.SearchIndex.of(content, hidden, setOf("DE")))
    }

    /** The cast of a series named after a hidden language is not found either: the series itself is not. */
    @Test
    fun searchLeavesOutThePeopleOfASeriesOfAHiddenLanguage() {
        val state = Fixtures.state()
        val german = Fixtures.harborLights.copy(id = "de-s", name = "|DE| Hafenlichter", cast = "Hannelore Quist")
        val content = state.content.copy(series = state.content.series + german)
        val shown = state.copy(content = content)
        val hidden = UiMappers.hiddenCategoryIds(state)
        assertEquals(listOf("Hannelore Quist"), UiMappers.people(UiMappers.SearchIndex(content, hidden), shown, "quist"))
        assertEquals(emptyList<String>(), UiMappers.people(UiMappers.SearchIndex(content, hidden, setOf("DE")), shown, "quist"))
    }

    @Test
    fun aSearchReplacedByANewerOneStops() {
        val state = Fixtures.state()
        val index = UiMappers.SearchIndex.of(state.content, UiMappers.hiddenCategoryIds(state))
        var checks = 0
        assertThrows(CancellationException::class.java) {
            UiMappers.search(index, "signal", state, Fixtures.NOW, checkCancelled = { if (++checks > 2) throw CancellationException("newer query") })
        }
        assertEquals(listOf("The Last Signal"), UiMappers.search(index, "signal", state, Fixtures.NOW).movies.map { it.title })
    }

    @Test
    fun homeSaysWhenTheSavedCatalogueIsStillRead() {
        val state = Fixtures.state(content = PlaylistContent.EMPTY).copy(isRestoring = true)
        assertTrue(UiMappers.home(state, labels, Fixtures.NOW).isRestoring)
        assertFalse(UiMappers.home(Fixtures.state(), labels, Fixtures.NOW).isRestoring)
    }

    /** A category's posters on a TV: TMDb's rating first, else the provider's; TMDb's genres first; two at most. */
    @Test
    fun postersShowARatingWithStarsAndTwoGenres() {
        val northern = ShowcaseItem.MovieItem(Fixtures.northernLights)
        val glass = ShowcaseItem.MovieItem(Fixtures.glassCity)
        val silent = ShowcaseItem.MovieItem(Fixtures.silentHarbor)
        val blue = ShowcaseItem.SeriesItem(Fixtures.blueHorizon)
        val unrated = ShowcaseItem.MovieItem(Fixtures.northernLights.copy(id = "u", rating = null))
        val state = Fixtures.state().copy(tmdbRatings = mapOf(northern.key to TmdbRating(7.4, 1_200)))
        val kept = mapOf(
            glass.key to TitleMetadata(
                "movie:glass city:2024", "movie", voteAverage = 6.2, voteCount = 80, fetchedAt = 1,
                genres = listOf(TmdbGenre(878, "Science Fiction"), TmdbGenre(18, "Drama"), TmdbGenre(53, "Thriller")),
            ),
            // Kept without votes: the provider's rating shows.
            silent.key to TitleMetadata("movie:silent harbor:2023", "movie", voteAverage = 8.0, fetchedAt = 1, genres = listOf(TmdbGenre(53, "Thriller"))),
        )
        val facts = UiMappers.posterFacts(state, listOf(northern, glass, silent, blue, unrated), kept, Locale.US)
        assertEquals(PosterFacts("7.4", 7, emptyList()), facts[northern.key])
        assertEquals(PosterFacts("6.2", 6, listOf("Science Fiction", "Drama")), facts[glass.key])
        assertEquals(PosterFacts("6.9", 7, listOf("Thriller")), facts[silent.key])
        // A series' genres come with the catalogue.
        assertEquals(PosterFacts("7.2", 7, listOf("Mystery", "Science Fiction")), facts[blue.key])
        assertEquals(PosterFacts(null, 0, emptyList()), facts[unrated.key])
        // The viewer's way of writing numbers.
        assertEquals("7,4", UiMappers.posterFacts(state, listOf(northern), emptyMap(), Locale.FRANCE)[northern.key]?.rating)
    }

    @Test
    fun aRatingOutOfTenIsHalfStarsOutOfFive() {
        assertEquals(0, UiMappers.halfStars(0.0))
        assertEquals(7, UiMappers.halfStars(7.4))
        assertEquals(8, UiMappers.halfStars(7.5))
        assertEquals(9, UiMappers.halfStars(8.6))
        assertEquals(10, UiMappers.halfStars(12.0))
    }
}
