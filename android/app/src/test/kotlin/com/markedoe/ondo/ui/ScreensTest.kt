package com.markedoe.ondo.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.AddPlaylistScreen
import com.markedoe.ondo.ui.screens.CategoriesActions
import com.markedoe.ondo.ui.screens.CategoriesScreen
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.screens.HelpScreen
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeScreen
import com.markedoe.ondo.ui.screens.MovieDetailScreen
import com.markedoe.ondo.ui.screens.PlaylistForm
import com.markedoe.ondo.ui.screens.SeriesDetailScreen
import com.markedoe.ondo.ui.screens.SettingsActions
import com.markedoe.ondo.ui.screens.SettingsScreen
import com.markedoe.ondo.ui.screens.WelcomeScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The screens with fixture states: what is shown and what each control does. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()
    private val labels = Labels("Movie", "Series")

    @Test
    fun helpOpensATopicAndLinksToSupport() {
        var support = 0
        compose.setContent { OndoTheme { HelpScreen(onBack = {}, onSupport = { support++ }) } }
        compose.onNodeWithText("|PT|", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("help-categories").performClick()
        compose.onNodeWithText("|PT|", substring = true).assertExists()
        compose.onNodeWithText("Support").performScrollTo().performClick()
        assertEquals(1, support)
    }

    @Test
    fun categoriesScreenTogglesATagAndACategory() {
        val tags = mutableListOf<Pair<String, Boolean>>()
        val categories = mutableListOf<Pair<String, Boolean>>()
        val state = CategoriesUiState(
            tags = listOf(TagRow("PT", "PT · Portuguese", 2, hidden = false)),
            categories = listOf(CategoryToggle("m-1", "|PT| Filmes", 3, hidden = false, hiddenWith = null)),
        )
        compose.setContent {
            OndoTheme {
                CategoriesScreen(state, ContentType.MOVIE, filter = "", actions = CategoriesActions(
                    onTag = { tag, hidden -> tags += tag to hidden },
                    onCategory = { id, hidden -> categories += id to hidden },
                ))
            }
        }
        compose.onNodeWithTag("tag-PT").performClick()
        assertEquals(listOf("PT" to true), tags)
        compose.onNodeWithText("Hide all").performScrollTo().performClick()
        compose.onNodeWithText("|PT| Filmes").assertExists()
    }

    @Test
    fun welcomeOffersAPlaylistOrTheDemoAndSaysOndoHasNoContent() {
        var demo = 0
        var add = 0
        compose.setContent { OndoTheme { WelcomeScreen(onAddPlaylist = { add++ }, onTryDemo = { demo++ }, isLoading = false) } }
        compose.onNodeWithText("player only", substring = true).assertExists()
        compose.onNodeWithTag("demoButton").performClick()
        compose.onNodeWithTag("addPlaylist").performClick()
        assertEquals(1, demo)
        assertEquals(1, add)
    }

    @Test
    fun addPlaylistNeedsTheXtreamLoginBeforeSaving() {
        var saved: PlaylistForm? = null
        compose.setContent { OndoTheme { AddPlaylistScreen(onSave = { saved = it }, onBack = {}, isLoading = false, error = null) } }
        compose.onNodeWithTag("savePlaylist").assertIsNotEnabled()
        compose.onNodeWithTag("serverField").performTextInput("provider.example.com:8080")
        compose.onNodeWithTag("usernameField").performTextInput("me")
        compose.onNodeWithTag("passwordField").performTextInput("secret")
        compose.onNodeWithTag("savePlaylist").assertIsEnabled().performClick()
        val form = saved as PlaylistForm.Xtream
        assertEquals("provider.example.com:8080", form.server)
        assertEquals("me", form.username)
    }

    @Test
    fun homeShowsTheCarouselAndItsActionsWork() {
        val listed = mutableListOf<ShowcaseItem>()
        val played = mutableListOf<ShowcaseItem>()
        val state = UiMappers.home(Fixtures.state(), labels, Fixtures.NOW)
        compose.setContent {
            OndoTheme {
                HomeScreen(state, HomeActions(onPlay = { played += it }, onToggleMyList = { listed += it }), autoAdvance = false)
            }
        }
        compose.onNodeWithTag("hero").assertIsDisplayed()
        compose.onAllNodesWithText("Northern Lights").onFirst().assertExists()
        compose.onAllNodesWithText("Play").onFirst().performClick()
        compose.onAllNodesWithText("My List").onFirst().performClick()
        assertEquals(ShowcaseItem.MovieItem(Fixtures.northernLights), played.single())
        assertEquals(ShowcaseItem.MovieItem(Fixtures.northernLights), listed.single())
        // The channels come last, under "Live TV" (no favourite channel yet).
        compose.onNodeWithTag("home").performScrollToNode(hasText("Live TV"))
        compose.onNodeWithText("Live TV").assertIsDisplayed()
        compose.onNodeWithText("Ondo News").assertIsDisplayed()
    }

    @Test
    fun movieDetailResumesAndCanStartOver() {
        val movie = Fixtures.northernLights
        val entry = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, movie.id, title = movie.name, lastWatchedAt = 1)
        val library = LibraryState().recordStart(entry).updateProgress(entry.key, 1_800.0, 6_000.0, now = 2)
        var play = 0
        var fromStart = 0
        compose.setContent {
            OndoTheme {
                MovieDetailScreen(UiMappers.movieDetail(Fixtures.state(library), movie), DetailActions(onPlay = { play++ }, onPlayFromStart = { fromStart++ }))
            }
        }
        compose.onNodeWithText("Resume").performClick()
        compose.onNodeWithText("From Start").performClick()
        compose.onNodeWithText("70 min left", substring = true).assertExists()
        assertEquals(1, play)
        assertEquals(1, fromStart)
        // TalkBack hears whether the title is in My List, not only "My List".
        compose.onNode(hasText("My List") and hasStateDescription("Not in My List"), useUnmergedTree = false).assertExists()
    }

    @Test
    fun seriesDetailPlaysTheNextEpisodeAndSwitchesSeasons() {
        val series = Fixtures.harborLights
        var season: Int? = null
        var play = 0
        compose.setContent {
            OndoTheme {
                SeriesDetailScreen(UiMappers.seriesDetail(Fixtures.state(), series, null, null), DetailActions(onPlay = { play++ }, onSeason = { season = it }))
            }
        }
        compose.onNodeWithTag("detailPlay").assert(hasText("Play S01E01", substring = true)).performClick()
        assertEquals(1, play)
        compose.onNodeWithTag("detail").performScrollToNode(hasText("Season 2"))
        compose.onNodeWithText("Season 2").performClick()
        assertEquals(2, season)
    }

    @Test
    fun settingsListThePlaylistAndSwitchAdultCategoriesOn() {
        var shown: Boolean? = null
        val state = UiMappers.settings(Fixtures.state(), "1.0.0") { if (it) "Xtream Codes" else "M3U playlist" }
        compose.setContent { OndoTheme { SettingsScreen(state, SettingsActions(onShowAdultCategories = { shown = it })) } }
        compose.onNodeWithText("M3U playlist · example.com").assertExists()
        compose.onNodeWithTag("adultSwitch").performClick()
        assertEquals(true, shown)
    }
}
