package com.markedoe.ondo.ui

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.epg.EpgIndex
import com.markedoe.ondo.core.library.AwardsCatalogue
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
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.data.Reminder
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.model.PersonRow
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

    /**
     * Trending titles (TMDb) of the playlist: a row of their own and the carousel after what is being watched, in TMDb's
     * order, without a hidden category or, when Settings hides them, a finished movie.
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
        val home = UiMappers.home(Fixtures.state(library).copy(trending = trending), labels, Fixtures.NOW)
        assertEquals(listOf("The Long Road", "Blue Horizon", "Summer in Lisbon"), home.trending.map { it.title })
        // The best rated additions of the week come first; trending titles fill the carousel after them.
        assertEquals(
            listOf("Harbor Lights", "The Last Signal", "Northern Lights", "The Archive", "Summer in Lisbon", "Blue Horizon", "Glass City", "The Long Road", "Silent Harbor", "Midnight Orchard"),
            home.hero.map { it.item.title },
        )
        val week = Fixtures.NOW + 14 * 86_400_000L
        assertEquals("The Long Road", UiMappers.home(Fixtures.state(library).copy(trending = trending), labels, week).hero.first().item.title)

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
        val home = UiMappers.home(Fixtures.state(), labels, Fixtures.NOW)
        assertEquals("Home", home.playlistName)
        assertEquals(10, home.hero.size)
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

    /** Both ratings side by side: the provider's, then TMDb's with its number of votes when TMDb gave them. */
    @Test
    fun aRatingShowsItsVotesWhenTmdbGaveThem() {
        val key = UiMappers.home(Fixtures.state(), labels, Fixtures.NOW).hero.first { it.item.title == "Northern Lights" }.item.key
        val state = Fixtures.state().copy(tmdbRatings = mapOf(key to TmdbRating(7.346, 12345)))
        assertEquals("Movie · 2025 · ★ 7.8 · TMDb 7.3 (12,345)", UiMappers.home(state, labels, Fixtures.NOW).hero.first { it.item.title == "Northern Lights" }.meta)
        assertEquals("Series · 2025 · Drama · ★ 8.2", UiMappers.home(state, labels, Fixtures.NOW).hero.first { it.item.title == "Harbor Lights" }.meta)
        val french = UiMappers.home(state, Labels("Film", "Série", Locale.FRANCE), Fixtures.NOW).hero.first { it.item.title == "Northern Lights" }.meta
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
            val home = UiMappers.home(state, labels, Fixtures.NOW)
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
}
