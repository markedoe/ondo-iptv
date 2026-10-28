package com.markedoe.ondo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.playback.PlaybackHealth
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.LastCrash
import com.markedoe.ondo.data.SyncDevice
import com.markedoe.ondo.data.TmdbCheck
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.player.ProblemReportDialog
import com.markedoe.ondo.player.StreamStatistics
import com.markedoe.ondo.player.StreamStatisticsPanel
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.AddPlaylistScreen
import com.markedoe.ondo.ui.screens.BrowseKind
import com.markedoe.ondo.ui.screens.CategoriesActions
import com.markedoe.ondo.ui.screens.CategoriesScreen
import com.markedoe.ondo.ui.screens.CollectionActions
import com.markedoe.ondo.ui.screens.CollectionChoice
import com.markedoe.ondo.ui.screens.CollectionRow
import com.markedoe.ondo.ui.screens.CollectionScreen
import com.markedoe.ondo.ui.screens.CollectionUiState
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.screens.GuideActions
import com.markedoe.ondo.ui.screens.GuideRow
import com.markedoe.ondo.ui.screens.GuideScreen
import com.markedoe.ondo.ui.screens.GuideUiState
import com.markedoe.ondo.ui.screens.HelpScreen
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeScreen
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.screens.MovieDetailScreen
import com.markedoe.ondo.ui.screens.MyListActions
import com.markedoe.ondo.ui.screens.MyListKind
import com.markedoe.ondo.ui.screens.MyListScreen
import com.markedoe.ondo.ui.screens.MyListUiState
import com.markedoe.ondo.ui.screens.PersonScreen
import com.markedoe.ondo.ui.screens.PersonUiState
import com.markedoe.ondo.ui.screens.PlaylistForm
import com.markedoe.ondo.ui.screens.RemindersScreen
import com.markedoe.ondo.ui.screens.SearchFilters
import com.markedoe.ondo.ui.screens.SearchScreen
import com.markedoe.ondo.ui.screens.SearchUiState
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
        // The remote's keys are for a TV only; the gestures of the player for a touch screen.
        compose.onNodeWithTag("help-remote").assertDoesNotExist()
        compose.onNodeWithTag("help-player").performClick()
        compose.onNodeWithText("Double-tap the left or right side", substring = true).assertExists()
        compose.onNodeWithText("|PT|", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("help-home").performClick()
        compose.onNodeWithText("Movies in Progress and Series in Progress", substring = true).assertExists()
        compose.onNodeWithTag("help-categories").performClick()
        compose.onNodeWithText("|PT|", substring = true).assertExists()
        // The last row: the list composes it once scrolled to.
        compose.onNodeWithTag("helpList").performScrollToNode(hasText("Support"))
        compose.onNodeWithText("Support").performClick()
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
    fun searchShowsItsFiltersAndClearsThem() {
        var filters = SearchFilters(fromYear = 2024, toYear = 2024, awardGroup = "cannes", winnersOnly = true)
        val state = SearchUiState(
            movies = listOf(ShowcaseItem.MovieItem(Fixtures.glassCity)),
            filters = filters,
            years = listOf(2025, 2024),
            awardGroups = listOf("cannes"),
        )
        compose.setContent { OndoTheme { SearchScreen(state, onQuery = {}, onOpen = {}, onPlayChannel = {}, onFilters = { filters = it }) } }
        compose.onNodeWithText("Released in 2024").assertExists()
        compose.onNodeWithText("Winners: Cannes Film Festival").assertExists()
        compose.onNodeWithText("Glass City").assertExists()
        compose.onNodeWithContentDescription("Clear filters").performClick()
        assertEquals(SearchFilters(), filters)
        compose.onNodeWithTag("searchFilters").performClick()
        compose.onNodeWithTag("searchFiltersSheet").assertExists()
        compose.onNodeWithTag("filterWinners").performClick()
        assertEquals(SearchFilters(fromYear = 2024, toYear = 2024, awardGroup = "cannes"), filters)
    }

    @Test
    fun aNameInTheCreditsOpensThePerson() {
        val people = mutableListOf<String>()
        val state = UiMappers.movieDetail(
            Fixtures.state().copy(movieDetails = mapOf(Fixtures.glassCity.id to MediaDetails(title = "Glass City", cast = "Ada Vance, Tom Reyes (voice)"))),
            Fixtures.glassCity,
        )
        compose.setContent { OndoTheme { MovieDetailScreen(state, DetailActions(onPerson = { people += it })) } }
        compose.onNodeWithTag("credit-Cast").performScrollTo().assert(hasText("Ada Vance, Tom Reyes")).performFirstLinkClick()
        assertEquals(listOf("Ada Vance"), people)
    }

    @Test
    fun anAwardAGenreOrTheYearOpensTheTitlesSharingIt() {
        val browsed = mutableListOf<Pair<BrowseKind, String>>()
        val state = UiMappers.movieDetail(
            Fixtures.state().copy(movieDetails = mapOf(Fixtures.glassCity.id to MediaDetails(title = "Glass City", genre = "Science Fiction, Mystery"))),
            Fixtures.glassCity,
        ).copy(awards = listOf(AwardRow("Palme d'Or", 2024, won = true, festival = true, awardId = "palme-dor")))
        compose.setContent { OndoTheme { MovieDetailScreen(state, DetailActions(onBrowse = { kind, value -> browsed += kind to value })) } }
        compose.onNodeWithTag("credit-Genre").performScrollTo().performFirstLinkClick()
        compose.onNodeWithTag("credit-Year").performScrollTo().performFirstLinkClick()
        compose.onNodeWithTag("award-palme-dor").performScrollTo().performClick()
        assertEquals(listOf(BrowseKind.GENRE to "Science Fiction", BrowseKind.YEAR to "2024", BrowseKind.AWARD to "palme-dor"), browsed)
    }

    @Test
    fun aPersonWithNothingKnownSaysSo() {
        compose.setContent { OndoTheme { PersonScreen(PersonUiState("Ada Vance"), onOpen = {}, onBack = {}) } }
        compose.onNodeWithText("Nothing in this playlist").assertExists()
        compose.onNodeWithText("Ada Vance.", substring = true).assertExists()
    }

    // The collection name dialogs are not driven: Robolectric never gets idle with a text field in a dialog window.

    @Test
    fun aMoviePageAddsItToACollection() {
        val toggled = mutableListOf<String>()
        val state = UiMappers.movieDetail(Fixtures.state(), Fixtures.glassCity).copy(collections = listOf(CollectionChoice("c1", "Weekend", contains = false)))
        compose.setContent { OndoTheme { MovieDetailScreen(state, DetailActions(onToggleCollection = { toggled += it })) } }
        compose.onNodeWithText("Collection").performScrollTo().performClick()
        compose.onNodeWithTag("pick-Weekend").assertIsOff().performClick()
        compose.onNodeWithTag("pickNewCollection").assertExists()
        assertEquals(listOf("c1"), toggled)
    }

    @Test
    fun aCollectionShowsItsItemsAndCanBeDeleted() {
        var deleted = 0
        val state = CollectionUiState("c1", "Weekend", movies = listOf(ShowcaseItem.MovieItem(Fixtures.glassCity)))
        compose.setContent { OndoTheme { CollectionScreen(state, CollectionActions(onDelete = { deleted++ })) } }
        compose.onNodeWithText("Glass City").assertExists()
        compose.onNodeWithTag("collectionMenu").performClick()
        compose.onNodeWithText("Rename collection").assertExists()
        compose.onNodeWithText("Delete collection").performClick()
        compose.onNodeWithText("Delete this collection?").assertExists()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun myListShowsItsCollections() {
        var opened: String? = null
        val state = MyListUiState(collections = listOf(CollectionRow("c1", "Weekend", 3)))
        compose.setContent { OndoTheme { MyListScreen(state, MyListKind.COLLECTIONS, MyListActions(onOpenCollection = { opened = it })) } }
        compose.onNodeWithTag("newCollection").assertExists()
        compose.onNodeWithTag("collection-Weekend").assert(hasText("3")).performClick()
        assertEquals("c1", opened)
    }

    @Test
    fun theGuideOpensAProgrammeToWatchItOrBeReminded() {
        val now = Fixtures.NOW
        val channel = Fixtures.channels.first()
        val airing = EpgProgram("c1.test", now - 600_000, now + 1_200_000, "Morning News")
        val later = EpgProgram("c1.test", now + 1_200_000, now + 3_000_000, "Weather")
        val window = UiMappers.guideWindow(now)
        val state = GuideUiState(rows = listOf(GuideRow(channel, listOf(airing, later))), total = 1, start = window.first, end = window.last, now = now, hasGuide = true)
        val played = mutableListOf<String>()
        val reminded = mutableListOf<String>()
        compose.setContent {
            OndoTheme { GuideScreen(state, GuideActions(onPlay = { played += it.name }, onToggleReminder = { _, program -> reminded += program.title })) }
        }
        compose.onNodeWithText("Weather").performClick()
        compose.onNodeWithTag("programSheet").assertExists()
        compose.onNodeWithText("Watch live").assertDoesNotExist()
        compose.onNodeWithTag("reminderButton").assert(hasText("Remind me")).performClick()
        assertEquals(listOf("Weather"), reminded)
    }

    /** A channel with the provider's archive: a past programme replays, the one on now starts over; without, neither. */
    @Test
    fun theGuideReplaysWhatTheArchiveKeeps() {
        val now = Fixtures.NOW
        val archived = Fixtures.channels.first().copy(catchup = com.markedoe.ondo.core.model.CatchupInfo(com.markedoe.ondo.core.model.CatchupInfo.Mode.SHIFT, days = 3))
        val plain = Fixtures.channels[1]
        val past = EpgProgram(archived.epgId!!, now - 4_200_000, now - 600_000, "Early Edition")
        val airing = EpgProgram(archived.epgId!!, now - 600_000, now + 1_200_000, "Morning News")
        val plainPast = EpgProgram(plain.epgId!!, now - 4_200_000, now - 600_000, "Cartoons")
        val window = UiMappers.guideWindow(now)
        val state = GuideUiState(
            rows = listOf(GuideRow(archived, listOf(past, airing)), GuideRow(plain, listOf(plainPast))),
            total = 2, start = window.first, end = window.last, now = now, hasGuide = true,
        )
        val replayed = mutableListOf<String>()
        compose.setContent { OndoTheme { GuideScreen(state, GuideActions(onReplay = { _, program -> replayed += program.title })) } }
        compose.onNodeWithText("Early Edition").performClick()
        compose.onNodeWithTag("replayButton").assert(hasText("Play replay")).performClick()
        assertEquals(listOf("Early Edition"), replayed)
        compose.onNodeWithTag("programSheet").assertDoesNotExist()
        compose.onNodeWithText("Morning News").performClick()
        compose.onNodeWithText("Watch live").assertExists()
        compose.onNodeWithTag("restartButton").assert(hasText("Restart")).performClick()
        assertEquals(listOf("Early Edition", "Morning News"), replayed)
        compose.onNodeWithText("Cartoons").performClick()
        compose.onNodeWithTag("programSheet").assertExists()
        compose.onNodeWithTag("replayButton").assertDoesNotExist()
    }

    @Test
    fun remindersSayHowToSetOne() {
        compose.setContent { OndoTheme { RemindersScreen(emptyList(), onRemove = {}, onBack = {}) } }
        compose.onNodeWithText("No reminders").assertExists()
        compose.onNodeWithText("choose Remind me", substring = true).assertExists()
    }

    @Test
    fun categoriesHideAStyleOrAWordOfTheViewers() {
        val words = mutableListOf<Pair<String, Boolean>>()
        val state = CategoriesUiState(styles = listOf(StyleRow("manga", 2, hidden = false)), words = listOf(WordRow("bollywood", 1)))
        compose.setContent { OndoTheme { CategoriesScreen(state, ContentType.MOVIE, filter = "", actions = CategoriesActions(onWord = { word, hidden -> words += word to hidden })) } }
        compose.onNodeWithText("Manga & anime").assertExists()
        compose.onNodeWithTag("style-manga").performClick()
        compose.onNodeWithTag("showWord-bollywood").performClick()
        compose.onNodeWithTag("wordToHide").performTextInput("  Télénovela ")
        compose.onNodeWithTag("addWord").performClick()
        assertEquals(listOf("manga" to true, "bollywood" to false, "telenovela" to true), words)
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
        compose.onNodeWithTag("lastUpdate", useUnmergedTree = true).assert(hasText("Updated", substring = true))
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
    fun homeDoesNotCallThePlaylistEmptyWhileItsCatalogueIsRead() {
        var restoring by mutableStateOf(true)
        compose.setContent { OndoTheme { HomeScreen(HomeUiState("Home", isRestoring = restoring), HomeActions(), autoAdvance = false) } }
        compose.onNodeWithText("loading or empty", substring = true).assertDoesNotExist()
        restoring = false
        compose.onNodeWithText("loading or empty", substring = true).assertExists()
    }

    @Test
    fun playShowsProgressWhileTheTitleGetsReady() {
        val state = UiMappers.home(Fixtures.state(), labels, Fixtures.NOW)
        var preparing by mutableStateOf<String?>(null)
        compose.setContent { OndoTheme { HomeScreen(state, HomeActions(), autoAdvance = false, preparingKey = preparing) } }
        val progress = hasContentDescription("Loading…") and hasAnyAncestor(hasTestTag("heroPlay"))
        compose.onNode(progress, useUnmergedTree = true).assertDoesNotExist()
        preparing = state.hero.first().item.key
        compose.onNode(progress, useUnmergedTree = true).assertExists()
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
        compose.onNodeWithText("1 h 10 min left", substring = true).assertExists()
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

    /** Settings, Sync on this network: the switch, then the devices found and where sync is with each. */
    @Test
    fun settingsTurnSyncOnAndListTheDevicesFound() {
        val turned = mutableListOf<Boolean>()
        val off = UiMappers.settings(Fixtures.state(), "1.0.0") { if (it) "Xtream Codes" else "M3U playlist" }
        compose.setContent { OndoTheme { SettingsScreen(off, SettingsActions(onSyncOnLocalNetwork = { turned += it })) } }
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("syncSwitch"))
        compose.onNodeWithText("turn this on at home only", substring = true).assertExists()
        compose.onAllNodesWithTag("syncDevice").assertCountEquals(0)
        compose.onNodeWithTag("syncSwitch").performClick()
        assertEquals(listOf(true), turned)
    }

    @Test
    fun settingsShowTheDevicesSyncFound() {
        val devices = listOf(SyncDevice("a", "Living room TV", syncedAt = 1_760_000_000_000L, unreachable = false), SyncDevice("b", "Office Mac", syncedAt = null, unreachable = true))
        val state = UiMappers.settings(Fixtures.state().let { it.copy(settings = it.settings.copy(syncOnLocalNetwork = true)) }, "1.0.0", devices) { if (it) "Xtream Codes" else "M3U playlist" }
        compose.setContent { OndoTheme { SettingsScreen(state, SettingsActions()) } }
        compose.onNodeWithTag("settingsList").performScrollToNode(hasText("Office Mac"))
        compose.onNodeWithText("Living room TV").assertExists()
        compose.onNodeWithText("Synced at", substring = true).assertExists()
        compose.onNodeWithText("Not reached, trying again").assertExists()
    }

    /** A person's page says how to find all their titles: with a TMDb token, unless TMDb's credits were searched. */
    @Test
    fun aPersonsPageTellsHowToFindAllTheirTitles() {
        var state by mutableStateOf(PersonUiState("Ann Example", movies = listOf(ShowcaseItem.MovieItem(Fixtures.northernLights))))
        compose.setContent { OndoTheme { PersonScreen(state, onOpen = {}, onBack = {}) } }
        compose.onNodeWithText("Add your TMDb API Read Access Token", substring = true).assertExists()
        state = state.copy(hasTmdbToken = true, searching = true)
        compose.onAllNodesWithTag("personHint").assertCountEquals(0)
        // While TMDb is asked, the page says it is still looking, above the titles already found.
        compose.onNodeWithTag("personSearching").assertExists()
        state = state.copy(searching = false, searchedOnline = true)
        compose.onAllNodesWithTag("personHint").assertCountEquals(0)
        compose.onAllNodesWithTag("personSearching").assertCountEquals(0)
        // A token TMDb did not answer for: the pages opened only, and no advice to add one.
        state = state.copy(searchedOnline = false)
        compose.onNodeWithTag("personHint").assertExists()
        compose.onNodeWithText("Add your TMDb", substring = true).assertDoesNotExist()
    }

    /** Settings, TMDb: a token TMDb refuses is not saved and says why; an accepted one is, and Remove takes it away. */
    @Test
    fun settingsCheckTheTmdbTokenBeforeSavingIt() {
        val base = UiMappers.settings(Fixtures.state(), "1.0.0") { if (it) "Xtream Codes" else "M3U playlist" }
        var saved by mutableStateOf<String?>(null)
        val checked = mutableListOf<String>()
        compose.setContent {
            OndoTheme {
                SettingsScreen(
                    base.copy(hasTmdbToken = saved != null),
                    SettingsActions(
                        onCheckTmdbToken = { token ->
                            checked += token
                            if (token == "GOOD") TmdbCheck.ACCEPTED.also { saved = token } else TmdbCheck.REFUSED
                        },
                        onRemoveTmdbToken = { saved = null },
                    ),
                )
            }
        }
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("tmdbCheck"))
        compose.onNodeWithTag("tmdbCheck").assertIsNotEnabled()
        compose.onAllNodesWithTag("tmdbRemove").assertCountEquals(0)
        compose.onNodeWithTag("tmdbTokenField").performTextInput("BAD")
        compose.onNodeWithTag("tmdbCheck").performClick()
        compose.onNodeWithText("TMDb refused it", substring = true).assertExists()
        assertEquals(null, saved)
        compose.onAllNodesWithTag("tmdbRemove").assertCountEquals(0)

        compose.onNodeWithTag("tmdbTokenField").performTextClearance()
        compose.onNodeWithTag("tmdbTokenField").performTextInput("GOOD")
        compose.onNodeWithTag("tmdbCheck").performClick()
        compose.onNodeWithText("Accepted by TMDb", substring = true).assertExists()
        assertEquals("GOOD", saved)
        assertEquals(listOf("BAD", "GOOD"), checked)

        compose.onNodeWithTag("tmdbRemove").performClick()
        assertEquals(null, saved)
        compose.onAllNodesWithTag("tmdbStatus").assertCountEquals(0)
    }

    /** Settings, About: the last error that stopped the app, to photograph for a report. */
    @Test
    fun settingsShowTheLastCrash() {
        val crash = LastCrash(1_760_000_000_000L, "Ondo 1.1.0 (1007)\nThread: main\njava.lang.IllegalStateException: broken\n\tat com.markedoe.ondo.Test")
        val state = UiMappers.settings(Fixtures.state().copy(lastCrash = crash), "1.0.0") { if (it) "Xtream Codes" else "M3U playlist" }
        compose.setContent { OndoTheme { SettingsScreen(state, SettingsActions()) } }
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("lastCrash"))
        compose.onNodeWithText("Last crash:", substring = true).assertExists()
        compose.onNodeWithText("IllegalStateException: broken", substring = true).assertExists()
    }

    @Test
    fun welcomeOffersToSyncWithTheDevicesNearby() {
        val turned = mutableListOf<Boolean>()
        compose.setContent { OndoTheme { WelcomeScreen(onAddPlaylist = {}, onTryDemo = {}, isLoading = false, syncOn = true, onSync = { turned += it }) } }
        compose.onNodeWithText("Looking for other devices with Ondo open…").assertExists()
        compose.onNodeWithTag("welcomeSync").performScrollTo().performClick()
        assertEquals(listOf(false), turned)
    }

    /** Settings, Player: a buffer length is chosen from its menu, automatic first. */
    @Test
    fun settingsChooseTheBufferForMoviesAndSeries() {
        val chosen = mutableListOf<Int>()
        val state = UiMappers.settings(Fixtures.state(), "1.0.0") { if (it) "Xtream Codes" else "M3U playlist" }.copy(liveBuffer = 3)
        compose.setContent { OndoTheme { SettingsScreen(state, SettingsActions(onVodBuffer = { chosen += it })) } }
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("vodBufferRow"))
        compose.onNodeWithText("3 seconds").assertExists()
        compose.onNodeWithTag("vodBufferRow").assert(hasText("Automatic")).performClick()
        compose.onNodeWithText("10 seconds").performClick()
        compose.onNodeWithTag("vodBufferRow").performClick()
        compose.onNodeWithTag("bufferChoice-0").assert(hasText("Automatic")).performClick()
        assertEquals(listOf(10, 0), chosen)
    }

    /** More, Report a Problem…: the report in full, with what it holds, and its three ways out. */
    @Test
    fun theProblemReportShowsTheReportAndItsActions() {
        val actions = mutableListOf<String>()
        compose.setContent {
            OndoTheme {
                ProblemReportDialog(
                    report = "Ondo playback report\nEvents (seconds since opening):\n  1.4 picture moving after 1.4 s",
                    onDismiss = { actions += "close" },
                    onCopy = { actions += "copy" },
                    onShare = { actions += "share" },
                    onOpenIssue = { actions += "issue" },
                )
            }
        }
        compose.onNodeWithText("It holds no stream address, login or title", substring = true).assertExists()
        compose.onNodeWithText("picture moving after 1.4 s", substring = true).assertExists()
        compose.onNodeWithText("Copy Report").performClick()
        compose.onNodeWithText("Share").performClick()
        compose.onNodeWithText("Open an Issue on GitHub").performClick()
        compose.onNodeWithText("Close").performClick()
        assertEquals(listOf("copy", "share", "issue", "close"), actions)
    }

    /** More, Stream Statistics: the format, the rates, the freezes and their cause, in the player's words. */
    @Test
    fun streamStatisticsShowTheRowsAndTheVerdict() {
        var statistics by mutableStateOf(
            StreamStatistics(
                engine = "ExoPlayer",
                format = "MKV",
                video = "1920×1080",
                videoBitrate = "8.0 Mb/s",
                received = "6.1 Mb/s",
                smoothness = "92%",
                freezes = "2 · 7.0 s",
                lostPictures = "0",
                verdict = PlaybackHealth.Verdict.NETWORK,
            ),
        )
        compose.setContent { OndoTheme { StreamStatisticsPanel(statistics) } }
        for (text in listOf("Engine", "ExoPlayer", "Format", "MKV", "Video", "1920×1080", "Video bitrate", "8.0 Mb/s", "Received", "6.1 Mb/s", "Smoothness", "92%", "Freezes", "2 · 7.0 s", "Lost pictures")) {
            compose.onNodeWithText(text, useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithTag("statisticsVerdict", useUnmergedTree = true).assert(hasText("the network or the provider is the cause", substring = true))
        statistics = statistics.copy(format = null, verdict = PlaybackHealth.Verdict.PLAYER)
        compose.onNodeWithText("MKV", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("statisticsVerdict", useUnmergedTree = true).assert(hasText("come from the player or the device", substring = true))
    }

    /** A person found by search opens their page. */
    @Test
    fun searchListsPeopleAndOpensTheirPage() {
        var opened: String? = null
        compose.setContent {
            OndoTheme {
                SearchScreen(SearchUiState(query = "vance", people = listOf("Ada Vance")), onQuery = {}, onOpen = {}, onPlayChannel = {}, onPerson = { opened = it })
            }
        }
        compose.onNodeWithTag("person-Ada Vance").performClick()
        assertEquals("Ada Vance", opened)
    }

    /** Languages and categories, then the app's own language, which the system page sets. */
    @Test
    fun settingsOpenLanguagesAndCategoriesAndTheAppLanguage() {
        val opened = mutableListOf<String>()
        val state = UiMappers.settings(Fixtures.state(), "1.0.0") { if (it) "Xtream Codes" else "M3U playlist" }
        compose.setContent {
            OndoTheme {
                SettingsScreen(state, SettingsActions(onCategories = { opened += "categories" }, onAppLanguage = { opened += "language" }))
            }
        }
        compose.onNodeWithText("Languages and categories").assertExists()
        compose.onNodeWithTag("categoriesRow").performScrollTo().performClick()
        compose.onNodeWithTag("appLanguageRow").performScrollTo().performClick()
        assertEquals(listOf("categories", "language"), opened)
    }
}
