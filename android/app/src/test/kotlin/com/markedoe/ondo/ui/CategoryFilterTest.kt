package com.markedoe.ondo.ui

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.LibraryState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryFilterTest {
    private val names = listOf("|PT| Filmes", "|PT| Ação", "|FR| Films", "|FR| Action", "|EN| Movies", "|EN| Drama", "|DE| Filme", "|DE| Action", "Classics")
    private val categories = names.mapIndexed { index, name -> MediaCategory("m-$index", name, ContentType.MOVIE) }
    private val movies = categories.mapIndexed { index, category ->
        Movie(id = "$index", name = "Title $index", categoryId = category.id, streamUrl = "http://example.com/movie/$index.mp4", year = 2020)
    }
    private val content = Fixtures.content.copy(movieCategories = categories, movies = movies)
    private val english = Locale.ENGLISH

    @Test
    fun hidingATagHidesItsCategoriesIncludingNewOnes() {
        val library = LibraryState().settingTagsHidden(listOf("PT"), hidden = true)
        val state = Fixtures.state(library = library, content = content)
        val hidden = UiMappers.hiddenCategoryIds(state)
        assertTrue("m-0" in hidden && "m-1" in hidden)
        assertFalse("m-2" in hidden)
        assertFalse("m-8" in hidden)

        // A category the provider adds later follows the rule.
        val added = MediaCategory("m-new", "|PT| Novidades", ContentType.MOVIE)
        val refreshed = Fixtures.state(library = library, content = content.copy(movieCategories = categories + added))
        assertTrue("m-new" in UiMappers.hiddenCategoryIds(refreshed))
    }

    @Test
    fun hidingAStyleOrAWordHidesTheCategoriesNamedAfterIt() {
        val named = listOf("|FR| Manga", "Animes VOSTFR", "Dessins Animés", "Bollywood Hits", "Drama")
            .mapIndexed { index, name -> MediaCategory("s-$index", name, ContentType.MOVIE) }
        val withStyles = content.copy(movieCategories = named)
        val library = LibraryState().settingWordHidden("manga", hidden = true).settingWordHidden("bollywood", hidden = true)
        val state = Fixtures.state(library = library, content = withStyles)
        assertEquals("French cartoons are not anime", setOf("s-0", "s-1", "s-3"), UiMappers.hiddenCategoryIds(state).filter { it.startsWith("s-") }.toSet())

        val ui = CategoryFilter.categories(state, ContentType.MOVIE, filter = "", locale = english)
        assertEquals(
            "the fixture's live \"Sports\" category counts too",
            listOf(StyleRow("manga", 2, hidden = true), StyleRow("kids", 1, hidden = false), StyleRow("sport", 1, hidden = false)),
            ui.styles,
        )
        assertEquals(listOf(WordRow("bollywood", 1)), ui.words)
        assertEquals("manga", ui.categories.first { it.id == "s-0" }.hiddenWith)
        assertFalse("filtering lists categories only", CategoryFilter.categories(state, ContentType.MOVIE, filter = "manga", locale = english).showsRules)
    }

    @Test
    fun theCategoriesScreenListsTagsAndCategories() {
        val library = LibraryState().settingTagsHidden(listOf("PT"), hidden = true).settingCategoryHidden(Fixtures.PLAYLIST_ID, "m-8", hidden = true)
        val ui = CategoryFilter.categories(Fixtures.state(library = library, content = content), ContentType.MOVIE, filter = "", locale = english)
        assertEquals(listOf("EN · English", "FR · French", "DE · German", "PT · Portuguese"), ui.tags.map { it.label })
        assertTrue(ui.tags.first { it.tag == "PT" }.hidden)
        val portuguese = ui.categories.first { it.id == "m-0" }
        assertTrue(portuguese.hidden)
        assertEquals("PT", portuguese.hiddenWith)
        assertTrue(ui.categories.first { it.id == "m-8" }.hidden)
        assertNull(ui.categories.first { it.id == "m-8" }.hiddenWith)

        val filtered = CategoryFilter.categories(Fixtures.state(content = content), ContentType.MOVIE, filter = "film", locale = english)
        assertEquals(listOf("|PT| Filmes", "|FR| Films", "|DE| Filme"), filtered.categories.map { it.name })
        assertTrue(filtered.tags.isEmpty())
    }

    @Test
    fun theSuggestionKeepsTheViewersLanguagesOnce() {
        val state = Fixtures.state(content = content)
        val suggestion = CategoryFilter.suggestion(state, setOf("FR", "EN"), english)
        assertNotNull(suggestion)
        suggestion!!
        assertEquals(setOf("FR", "EN"), suggestion.kept.toSet())
        assertEquals(setOf("PT", "DE"), suggestion.hidden.toSet())

        val answered = Fixtures.state(content = content, library = LibraryState().settingTagsHidden(suggestion.hidden, hidden = true))
        assertNull(CategoryFilter.suggestion(answered, setOf("FR", "EN"), english))
        val dismissed = Fixtures.state(content = content, settings = AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, dismissedTagSuggestions = setOf(Fixtures.PLAYLIST_ID)))
        assertNull(CategoryFilter.suggestion(dismissed, setOf("FR", "EN"), english))
        assertNull("none of the viewer's languages: no guess", CategoryFilter.suggestion(state, setOf("JA"), english))
    }

    @Test
    fun tagNamesAndViewerCodes() {
        assertEquals("Arabic", CategoryFilter.name("AR", english))
        assertEquals("United Kingdom", CategoryFilter.name("UK", english))
        assertEquals("Brazil", CategoryFilter.name("BR", english))
        assertNull(CategoryFilter.name("EX-YU", english))
        // "QC" is Quebec: no country code, so the app names it.
        assertNull(CategoryFilter.name("QC", english))
        assertEquals("Québec", CategoryFilter.name("QC", english, mapOf("QC" to "Québec")))
        assertEquals(setOf("FR", "EN", "GB", "UK"), CategoryFilter.viewerCodes(listOf(Locale.FRANCE, Locale.UK)))
    }
}
