package com.markedoe.ondo.ui

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.epg.EpgIndex
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.data.Reminder
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.CollectionChoice
import com.markedoe.ondo.ui.screens.CollectionRow
import com.markedoe.ondo.ui.screens.SearchFilters
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
