package com.markedoe.ondo.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.library.Selections
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.core.sync.SyncSelection
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.RowOrderActions
import com.markedoe.ondo.ui.screens.RowOrderScreen
import com.markedoe.ondo.ui.screens.SelectionActions
import com.markedoe.ondo.ui.screens.SelectionScreen
import com.markedoe.ondo.ui.screens.SelectionUiState
import com.markedoe.ondo.ui.screens.SelectionsActions
import com.markedoe.ondo.ui.screens.SelectionsScreen
import com.markedoe.ondo.ui.screens.SelectionsUiState
import com.markedoe.ondo.ui.screens.SettingsActions
import com.markedoe.ondo.ui.screens.SettingsScreen
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvSelectionScreen
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Selections (docs/SYNC.md, "Selections"): their rows pick each tab's titles of their tags, as every row leaves out
 * what is hidden and what a Kids profile never sees, a new pick every day with the titles watched last; made from
 * Settings → Row order with chips, on a phone and with a TV's remote.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class SelectionAppTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val dramaOfTheTwenties = SyncSelection(
        "s1", "Recent dramas", listOf(Selections.decade(2020), Selections.genre("Drama")!!), SyncSelection.ALL, listOf(RowOrder.HOME, RowOrder.MOVIES, RowOrder.SERIES), 1,
    )

    /** The catalogue with Northern Lights' page opened (its genre known: Drama) and [selections] made. */
    private fun state(vararg selections: SyncSelection, library: LibraryState = LibraryState()): OndoState =
        Fixtures.state(library.copy(selections = selections.toList())).copy(
            movieDetails = mapOf(Fixtures.northernLights.id to MediaDetails(title = Fixtures.northernLights.name, genre = "Drama")),
        )

    private fun titles(state: OndoState, tab: String, now: Long = Fixtures.NOW) =
        SelectionRows.rows(state, tab, now, emptyMap(), Int.MAX_VALUE).values.single().items.map { it.title }

    @Test
    fun eachTabShowsItsOwnTitlesOfTheTagsAndHomeMixesThem() {
        val state = state(dramaOfTheTwenties)
        // All tags: a drama of the 2020s. Movies: the film whose genre is known; Series: the drama series; Home: both.
        assertEquals(listOf("Northern Lights"), titles(state, RowOrder.MOVIES))
        assertEquals(listOf("Harbor Lights"), titles(state, RowOrder.SERIES))
        assertEquals(setOf("Northern Lights", "Harbor Lights"), titles(state, RowOrder.HOME).toSet())
        // Any tag: every title of the 2020s, or a drama.
        val any = state(dramaOfTheTwenties.copy(match = SyncSelection.ANY))
        assertEquals(
            Fixtures.content.movies.filter { it.categoryId != Fixtures.adultMovies.id }.map { it.name }.toSet(),
            titles(any, RowOrder.MOVIES).toSet(),
        )
        // The row is in the tab's order of rows, and each tab's state carries it.
        val vod = UiMappers.vod(state, series = false, title = "", now = Fixtures.NOW)
        assertTrue(Selections.rowKey("s1") in vod.shownRows)
        assertEquals(listOf("Northern Lights"), vod.selections.getValue(Selections.rowKey("s1")).items.map { it.title })
        assertTrue(Selections.rowKey("s1") in UiMappers.home(state, Labels(), Fixtures.NOW).shownRows)
        // Not shown in a tab it is not for.
        val moviesOnly = state(dramaOfTheTwenties.copy(tabs = listOf(RowOrder.MOVIES)))
        assertFalse(Selections.rowKey("s1") in UiMappers.vod(moviesOnly, series = true, title = "", now = Fixtures.NOW).shownRows)
        assertFalse(Selections.rowKey("s1") in UiMappers.home(moviesOnly, Labels(), Fixtures.NOW).shownRows)
    }

    @Test
    fun aRowLeavesOutWhatIsHiddenAndWhatAKidsProfileNeverSees() {
        val twenties = SyncSelection("s2", "", listOf(Selections.decade(2020)), SyncSelection.ANY, listOf(RowOrder.MOVIES), 1)
        // The adult category shown in Settings: its film is in the row of the main profile.
        val shown = state(twenties).let { it.copy(settings = it.settings.copy(showAdultCategories = true)) }
        assertTrue("Late Show" in titles(shown, RowOrder.MOVIES))
        // A Kids profile never sees it, whatever the setting.
        val kids = Profile(id = "k", name = "Kids", isKids = true)
        val kidsState = shown.copy(profiles = listOf(Profile(id = Profile.DEFAULT_ID, name = "Me"), kids), settings = shown.settings.copy(activeProfileId = kids.id))
        assertTrue(kidsState.activeProfile.isKids)
        assertFalse("Late Show" in titles(kidsState, RowOrder.MOVIES))
        // A hidden category leaves its titles out too.
        val hidden = state(twenties, library = LibraryState().settingCategoriesHidden(Fixtures.PLAYLIST_ID, listOf(Fixtures.drama.id), hidden = true))
        assertTrue(titles(hidden, RowOrder.MOVIES).none { it in setOf("Northern Lights", "Summer in Lisbon", "The Long Road") })
    }

    @Test
    fun theRowIsANewPickEveryDayWithTheTitlesWatchedLast() {
        val twenties = SyncSelection("s3", "", listOf(Selections.decade(2020)), SyncSelection.ANY, listOf(RowOrder.MOVIES), 1)
        val state = state(twenties)
        val today = titles(state, RowOrder.MOVIES)
        // The same all day, another order on other days.
        assertEquals(today, titles(state, RowOrder.MOVIES, Fixtures.NOW + 60_000))
        val days = (1..7).map { titles(state, RowOrder.MOVIES, Fixtures.NOW + it * 86_400_000L) }
        assertTrue(days.any { it != today })
        assertEquals(today.toSet(), days.first().toSet())
        // A film watched to the end goes last.
        val first = Fixtures.content.movies.first { it.name == today.first() }
        val seen = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, first.id, title = first.name, position = 5_990.0, duration = 6_000.0, lastWatchedAt = 1)
        val watched = state(twenties, library = LibraryState(history = listOf(seen)))
        assertEquals(first.name, titles(watched, RowOrder.MOVIES).last())
        assertNotEquals(first.name, titles(watched, RowOrder.MOVIES).first())
        // The day is the device's own date.
        assertEquals("2025-10-09", SelectionRows.day(Fixtures.NOW, ZoneId.of("UTC")))
    }

    @Test
    fun seeAllOpensEveryTitleOfTheRowInItsOrder() {
        val twenties = SyncSelection("s4", "", listOf(Selections.decade(2020)), SyncSelection.ANY, listOf(RowOrder.HOME), 1)
        val state = state(twenties)
        val row = UiMappers.home(state, Labels(), Fixtures.NOW).selections.getValue(Selections.rowKey("s4")).items
        val grid = UiMappers.grid(state, Routes.Grid(series = false, anyType = true, selection = "s4", selectionTab = RowOrder.HOME), now = Fixtures.NOW)
        assertEquals(row.map(ShowcaseItem::key), grid.take(row.size).map(ShowcaseItem::key))
        assertTrue(grid.any { it is ShowcaseItem.SeriesItem } && grid.any { it is ShowcaseItem.MovieItem })
    }

    /** On a phone: New selection under Movies, chips chosen, saved; the Movies tab then shows its row. */
    @Test
    fun aPhoneMakesASelectionAndTheMoviesTabShowsItsRow() {
        var opened: String? = null
        var saved: SyncSelection? = null
        // Row order, then the selection's page, then the Movies tab.
        var page by mutableStateOf(0)
        val base = state()
        val ui = SelectionUiState(null, "", emptyList(), false, listOf(RowOrder.MOVIES), SelectionRows.choices(base, emptyMap()))
        compose.setContent {
            OndoTheme {
                when (page) {
                    0 -> RowOrderScreen(UiMappers.rowOrder(base), RowOrderActions(onNewSelection = { opened = it; page = 1 }))
                    1 -> SelectionScreen(ui, SelectionActions(onSave = { name, tags, all, tabs ->
                        saved = SyncSelection("new", name, tags, if (all) SyncSelection.ALL else SyncSelection.ANY, tabs, 1)
                        page = 2
                    }))
                    else -> VodScreen(UiMappers.vod(state(saved!!), series = false, title = "Movies", now = Fixtures.NOW), VodActions())
                }
            }
        }
        compose.onNodeWithTag("rowOrderList").performScrollToNode(hasTestTag("newSelection-movies"))
        compose.onNodeWithTag("newSelection-movies").performClick()
        compose.waitForIdle()
        assertEquals(RowOrder.MOVIES, opened)
        // The tags of the catalogue, by group: its genres (Drama), decades, the styles of its categories.
        for (tag in listOf("genre:drama", "decade:2020")) {
            compose.onNodeWithTag("selectionList").performScrollToNode(hasTestTag("tag-$tag"))
            compose.onNodeWithTag("tag-$tag").performClick()
        }
        compose.onNodeWithTag("selectionList").performScrollToNode(hasTestTag("selectionAll"))
        compose.onNodeWithTag("selectionAll").performClick()
        compose.onNodeWithTag("saveSelection").performClick()
        compose.waitForIdle()
        val selection = requireNotNull(saved)
        assertEquals(listOf("genre:drama", "decade:2020"), selection.tags)
        assertEquals(SyncSelection.ALL, selection.match)
        assertEquals(listOf(RowOrder.MOVIES), selection.tabs)
        // No name typed: none saved, and the row is named after its tags, in the viewer's language.
        assertEquals("", selection.name)
        compose.onNodeWithTag("vod").performScrollToNode(hasTestTag("shelfSelection-new"))
        assertTrue(compose.onAllNodesWithTag("shelfSelection-new").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Drama · 2020s").fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * Home and the tabs (what the device kept, read as the catalogue arrived) and a selection's grid (read when it opens)
     * each find their titles' tags worked out once: going from one to the other does not go through the catalogue again.
     */
    @Test
    fun theTagsOfTheCatalogueAreWorkedOutOnceForEachReading() {
        val state = state()
        val home = mapOf("m-1" to Fixtures.released(Fixtures.content).getValue("m-1"))
        val grid = HashMap(home)
        val first = SelectionRows.tagged(state, home)
        val other = SelectionRows.tagged(state, grid)
        assertTrue(first === SelectionRows.tagged(state, home))
        assertTrue(other === SelectionRows.tagged(state, grid))
        assertFalse(first === other)
    }

    /**
     * A tag of the selection the playlist no longer offers (its titles hidden, or of another playlist) still shows among
     * the chips, chosen, so that it can be taken out.
     */
    @Test
    fun aTagNoLongerOfferedCanStillBeTakenOut() {
        val choices = SelectionRows.choices(state(), emptyMap())
        assertFalse(Selections.decade(1950) in choices.decades.map(Selections::decade))
        val ui = SelectionUiState("s9", "Old", listOf(Selections.decade(1950), Selections.decade(2020)), false, listOf(RowOrder.MOVIES), choices)
        assertTrue(ui.groups.flatMap { it.second }.contains(Selections.decade(1950)))
        var saved: List<String>? = null
        compose.setContent { OndoTheme { SelectionScreen(ui, SelectionActions(onSave = { _, tags, _, _ -> saved = tags })) } }
        compose.onNodeWithTag("selectionList").performScrollToNode(hasTestTag("tag-decade:1950"))
        compose.onNodeWithTag("tag-decade:1950").performClick()
        compose.onNodeWithTag("saveSelection").performClick()
        compose.runOnIdle { assertEquals(listOf(Selections.decade(2020)), saved) }
    }

    /** On a TV, the remote reaches every chip: OK chooses one, and Save saves the selection. */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun theRemoteChoosesTagsOnATelevision() {
        var saved: List<String>? = null
        val ui = SelectionUiState(null, "", emptyList(), false, listOf(RowOrder.HOME), SelectionRows.choices(state(), emptyMap()))
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { TelevisionFrame { TvSelectionScreen(ui, SelectionActions(onSave = { _, tags, _, _ -> saved = tags })) } }
        }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        fun press(key: Key) {
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("selection").performScrollToNode(hasTestTag("tag-genre:drama"))
        compose.onNodeWithTag("tag-genre:drama").requestFocus()
        press(Key.DirectionCenter)
        // Right goes on along the chips of the group.
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        compose.onNodeWithTag("selection").performScrollToNode(hasTestTag("saveSelection"))
        compose.onNodeWithTag("saveSelection").requestFocus()
        press(Key.DirectionCenter)
        // Drama, then the genre after it: Mystery.
        assertEquals(listOf("genre:drama", "genre:mystery"), saved)
    }

    /**
     * The bugs of the genres: the same genre in each language was a tag of its own (Aventure, Abenteuer, Adventure), and
     * "Action &amp; Adventure" gave the tag "amp; Adventure". Now one genre, named in the viewer's language, matching
     * the titles of every language, and the tags an earlier version saved still match.
     */
    @Test
    fun aGenreInEveryLanguageIsOneTagNamedInTheViewersLanguage() {
        val films = Fixtures.content.movies.filter { it.categoryId != Fixtures.adultMovies.id }.take(3)
        val genres = listOf("Aventure, Drame", "Abenteuer", "Action &amp; Adventure")
        val state = Fixtures.state().copy(movieDetails = films.zip(genres).associate { (film, genre) -> film.id to MediaDetails(title = film.name, genre = genre) })
        val choices = SelectionRows.choices(state, emptyMap())
        val tags = choices.genres.map { it.first }
        assertEquals(1, tags.count { it == "genre:adventure" })
        assertTrue("genre:action" in tags && "genre:drama" in tags)
        assertTrue(tags.none { it in setOf("genre:aventure", "genre:abenteuer", "genre:drame", "genre:amp adventure", "genre:action adventure") })
        // A tag saved by an earlier version from the French name matches the three films.
        val old = SyncSelection("s5", "", listOf("genre:aventure"), SyncSelection.ANY, listOf(RowOrder.MOVIES), 1)
        val oldState = state.copy(library = state.library.copy(selections = listOf(old)))
        assertEquals(films.map { it.name }.toSet(), titles(oldState, RowOrder.MOVIES).toSet())
        // The page shows it as this version's tag, named in English here.
        val ui = SelectionUiState("s5", "", com.markedoe.ondo.core.library.Genres.canonicalTags(old.tags), false, old.tabs, choices)
        compose.setContent { OndoTheme { SelectionScreen(ui, SelectionActions()) } }
        compose.onNodeWithTag("selectionList").performScrollToNode(hasTestTag("tag-genre:adventure"))
        assertTrue(compose.onAllNodesWithText("Adventure").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("amp; Adventure").fetchSemanticsNodes().isEmpty())
    }

    /**
     * On a phone: Settings → My selections lists the profile's selections; a selection opens its page, its bin deletes
     * it, New selection makes one. A Kids profile has no such row, as no Row order.
     */
    @Test
    fun aPhoneOpensMySelectionsFromSettings() {
        var page by mutableStateOf(0)
        var edited: Pair<String, String>? = null
        var deleted: String? = null
        var made = 0
        val state = state(dramaOfTheTwenties)
        val settings = UiMappers.settings(state, "1.0.0") { if (it) "Xtream Codes" else "M3U playlist" }
        compose.setContent {
            OndoTheme {
                when (page) {
                    0 -> SettingsScreen(settings, SettingsActions(onSelections = { page = 1 }))
                    else -> SelectionsScreen(
                        SelectionsUiState(state.library.selections),
                        SelectionsActions(onNew = { made++ }, onEdit = { id, tab -> edited = id to tab }, onDelete = { deleted = it }),
                    )
                }
            }
        }
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("selectionsRow"))
        compose.onNodeWithTag("selectionsRow").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("selections").assertExists()
        // Its name, its tags and its tabs.
        compose.onNodeWithText("Recent dramas").assertExists()
        compose.onNodeWithText("All of: 2020s, Drama").assertExists()
        compose.onNodeWithText("Home · Movies · Series").assertExists()
        compose.onNodeWithTag("selection-s1").performClick()
        assertEquals("s1" to RowOrder.HOME, edited)
        compose.onNodeWithTag("deleteSelection-s1").performClick()
        assertEquals("s1", deleted)
        compose.onNodeWithTag("newSelection").performClick()
        assertEquals(1, made)
    }

    @Test
    fun aKidsProfileHasNoSelectionsRowInSettings() {
        val kids = Profile(id = "k", name = "Kids", isKids = true)
        val base = state(dramaOfTheTwenties)
        val kidsState = base.copy(profiles = listOf(Profile(id = Profile.DEFAULT_ID, name = "Me"), kids), settings = base.settings.copy(activeProfileId = kids.id))
        val settings = UiMappers.settings(kidsState, "1.0.0") { if (it) "Xtream Codes" else "M3U playlist" }
        assertFalse(settings.canChangeRowOrder)
        compose.setContent { OndoTheme { SettingsScreen(settings, SettingsActions()) } }
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("categoriesRow"))
        assertTrue(compose.onAllNodesWithTag("selectionsRow").fetchSemanticsNodes().isEmpty())
    }
}
