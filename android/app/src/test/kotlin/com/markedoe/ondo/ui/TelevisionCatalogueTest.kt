package com.markedoe.ondo.ui

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.sync.SyncFavorite
import com.markedoe.ondo.core.sync.SyncPlaylist
import com.markedoe.ondo.core.sync.SyncProgress
import com.markedoe.ondo.core.sync.SyncSnapshot
import com.markedoe.ondo.core.xtream.XtreamClient
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.SavedPlaylist
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.SETTINGS_TAG
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvMovieDetailScreen
import com.markedoe.ondo.ui.tv.TvSeriesDetailScreen
import java.time.Duration
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android TV / Google TV, the moment an Xtream playlist of real size ([BigPanel]) is added: typed on the welcome
 * screen, typed in Settings over the demo, or arriving from another device by sync; then the screens walked with the
 * remote to the end of their rows. An error anywhere fails the test with its cause: in a page at once, in a coroutine of
 * the view model or the repository (which stops the app on a device) when the test ends, as the test's own scope collects it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TelevisionCatalogueTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    private lateinit var repository: OndoRepository
    private var focusManager: FocusManager? = null

    private val demo = SavedPlaylist(id = "demo-tv", name = "Demo", url = "https://example.com/demo.m3u", addedAt = Fixtures.NOW + 10, updatedAt = Fixtures.NOW + 10)

    private val demoContent: PlaylistContent by lazy {
        val news = MediaCategory("d-news", "News", ContentType.LIVE)
        PlaylistContent(
            channels = (1..200).map { Channel(id = "d$it", name = "Demo $it", number = it, categoryId = news.id, streamUrl = "https://example.com/live/$it.m3u8") },
            liveCategories = listOf(news),
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun start(playlists: List<SavedPlaylist>, syncOn: Boolean = false) {
        val store = AppStore(folder.root)
        store.savePlaylists(playlists)
        store.saveSettings(AppSettings(activePlaylistId = playlists.firstOrNull()?.id, syncOnLocalNetwork = syncOn))
        if (demo in playlists) store.saveContent(demo.id, demoContent)
        repository = OndoRepository(store, FakeHttp(BigPanel.answers()), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        val viewModel = OndoViewModel(repository, Labels(), minutes = flowOf(Fixtures.NOW))
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            focusManager = LocalFocusManager.current
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        compose.waitUntil(10_000) { exists("home") || exists("welcome") }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        // Spinners turn while the panel loads: the clock moves with settle() only.
        compose.mainClock.autoAdvance = false
        settle()
    }

    private fun settle(ms: Long = 300) {
        val deadline = System.currentTimeMillis() + ms
        do {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            compose.mainClock.advanceTimeBy(20)
            compose.waitForIdle()
        } while (System.currentTimeMillis() < deadline)
    }

    private fun waitFor(timeout: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out; the focus is on ${focused()}" }
            settle(60)
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        val focusedNode = focusedNode()
        val direction = mapOf(Key.DirectionUp to FocusDirection.Up, Key.DirectionDown to FocusDirection.Down, Key.DirectionLeft to FocusDirection.Left, Key.DirectionRight to FocusDirection.Right)[key]
        if (focusedNode != null && direction != null && SemanticsProperties.EditableText in focusedNode.config) {
            compose.runOnIdle { focusManager?.moveFocus(direction) }
        } else {
            val target = if (focusedNode != null) focusedInteraction() else compose.onAllNodes(isRoot()).onLast()
            target.performKeyInput { pressKey(key) }
        }
        settle(120)
    }

    private fun pressUntil(key: Key, max: Int = 14, done: () -> Boolean) {
        repeat(max) {
            if (done()) return
            press(key)
        }
        check(done()) { "never reached with $key; the focus is on ${focused()}" }
    }

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun focusedInteraction(): SemanticsNodeInteraction = compose.onAllNodes(isFocused()).onLast()

    private fun focusedTag(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun focused(): String = focusedNode()?.let { node ->
        (node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            ?: node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()).take(80) + "@" + node.id
    } ?: "(nothing)"

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /**
     * The remote down the page, a few cards along each row; then every row of the page scrolled to its end and the page
     * to its bottom, so that each item is laid out once.
     */
    private fun walkRows(page: String, rows: Int = 10, cards: Int = 4) {
        for (row in 0 until rows) {
            val first = focused()
            repeat(cards) { press(Key.DirectionRight) }
            println("walk $page: row $row from $first to ${focused()}")
            repeat(cards) { press(Key.DirectionLeft) }
            press(Key.DirectionDown)
        }
        scrollEverything(page)
    }

    private fun scrollEverything(page: String) {
        val inPage = hasScrollAction() and hasAnyAncestor(hasTestTag(page))
        val vertical = SemanticsMatcher("vertical") { SemanticsProperties.VerticalScrollAxisRange in it.config }
        // The page itself, or the list in it.
        val list = if (compose.onAllNodes(hasTestTag(page) and vertical).fetchSemanticsNodes().isNotEmpty()) compose.onNode(hasTestTag(page) and vertical) else compose.onAllNodes(inPage and vertical).onFirst()
        var rowsScrolled = 0
        repeat(400) {
            compose.onAllNodes(inPage).fetchSemanticsNodes()
                .filter { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }
                .forEach { row -> if (scrollToEnd(compose.onNode(inPage and SemanticsMatcher("id ${row.id}") { it.id == row.id }), horizontal = true)) rowsScrolled++ }
            val range = list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: return
            if (range.value() >= range.maxValue()) {
                println("scrolled $page to its bottom, $rowsScrolled rows to their end")
                return
            }
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 400f) }
            settle(60)
        }
    }

    /** Scrolls a list to its end; false when it was there already or is gone. */
    private fun scrollToEnd(node: SemanticsNodeInteraction, horizontal: Boolean): Boolean {
        var moved = false
        // Right to left, a row scrolls the other way.
        var step = 1500f
        repeat(100) {
            val config = runCatching { node.fetchSemanticsNode().config }.getOrNull() ?: return moved
            val range = config.getOrNull(if (horizontal) SemanticsProperties.HorizontalScrollAxisRange else SemanticsProperties.VerticalScrollAxisRange) ?: return moved
            if (range.value() >= range.maxValue()) return moved
            val before = range.value()
            node.performSemanticsAction(SemanticsActions.ScrollBy) { it(if (horizontal) step else 0f, if (horizontal) 0f else step) }
            settle(40)
            if (range.value() <= before) {
                if (step < 0) return moved
                step = -step
            } else {
                moved = true
            }
        }
        return moved
    }

    /** Along the top bar, towards its start: left, or right in a right-to-left language; and towards its end. */
    private val towardsStart: Key
        get() = if (compose.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) Key.DirectionRight else Key.DirectionLeft
    private val towardsEnd: Key
        get() = if (towardsStart == Key.DirectionLeft) Key.DirectionRight else Key.DirectionLeft

    /** Up to the top bar: a few presses near a page's top, else Back (far down a long list, it is one press). */
    private fun toBar() {
        repeat(6) { if (!focusedTag().startsWith("tab")) press(Key.DirectionUp) }
        if (!focusedTag().startsWith("tab")) {
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            waitFor { focusedTag().startsWith("tab") }
        }
    }

    /** From the page, up to the top bar, onto [tag], and opens it. */
    private fun openFromDrawer(tag: String) {
        toBar()
        pressUntil(towardsStart) { focusedTag() == "tabHome" }
        pressUntil(towardsEnd) { focusedTag() == tag }
        press(Key.DirectionCenter)
        waitFor { !focusedTag().startsWith("tab") && focusedNode() != null }
    }

    /** Types the panel's login in the add-playlist form (Xtream Codes, its first kind) and saves it. */
    private fun typePanel() {
        fun type(tag: String, text: String) {
            pressUntil(Key.DirectionDown) { focusedTag() == tag }
            // OK opens the field's keyboard.
            press(Key.DirectionCenter)
            focusedInteraction().performTextInput(text)
            settle()
        }
        type("serverField", BigPanel.SERVER)
        type("usernameField", BigPanel.USERNAME)
        type("passwordField", BigPanel.PASSWORD)
        pressUntil(Key.DirectionDown) { focusedTag() == "savePlaylist" }
        press(Key.DirectionCenter)
    }

    private fun panelInUse() = repository.state.value.let { state -> state.activePlaylist?.url == BigPanel.SERVER && state.content.movies.size >= BigPanel.MOVIES }

    @Test
    fun anXtreamPlaylistTypedOnTheWelcomeScreenOpensHome() {
        start(playlists = emptyList())
        pressUntil(Key.DirectionDown) { focusedTag() == "addPlaylist" }
        press(Key.DirectionCenter)
        waitFor { exists("addPlaylistScreen") }
        typePanel()
        waitFor(60_000) { panelInUse() && exists("home") }
        waitFor { focusedTag() == "heroPlay" }
        walkRows("home")
        openFromDrawer("tabMovies")
        walkRows("vod")
    }

    /** The same in Arabic, right to left, with its plurals and the suggestion to keep Arabic. */
    @Test
    @Config(qualifiers = "ar-rSA-w960dp-h540dp-land-television-xhdpi")
    fun anXtreamPlaylistTypedOnTheWelcomeScreenOpensHomeInArabic() = anXtreamPlaylistTypedOnTheWelcomeScreenOpensHome()

    @Test
    fun anXtreamPlaylistTypedInSettingsTakesTheDemosPlace() {
        start(playlists = listOf(demo))
        // On a demo channel: Home will come back to a card the new playlist does not have.
        pressUntil(Key.DirectionDown) { focused().startsWith("Demo") }
        press(Key.DirectionRight, times = 3)
        toBar()
        pressUntil(towardsEnd) { focusedTag() == SETTINGS_TAG }
        press(Key.DirectionCenter)
        waitFor { exists("settings") && focusedTag() == "settingsPlaylists" }
        press(Key.DirectionRight)
        pressUntil(Key.DirectionDown) { focusedTag() == "addPlaylistRow" }
        press(Key.DirectionCenter)
        waitFor { exists("addPlaylistScreen") && focusedTag() != "addPlaylistRow" }
        typePanel()
        waitFor(60_000) { panelInUse() && exists("settings") }
        // The demo deleted from its card.
        pressUntil(Key.DirectionUp, max = 10) { focusedTag() == "delete-Demo" || focusedTag() == "use-Demo" }
        pressUntil(Key.DirectionRight) { focusedTag() == "delete-Demo" }
        press(Key.DirectionCenter)
        waitFor { focusedTag() == "confirmCancel" }
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        waitFor { repository.state.value.playlists.size == 1 }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor { exists("home") && exists("hero") }
        waitFor { focusedNode() != null }
        walkRows("home")
        openFromDrawer("tabSeries")
        walkRows("vod")
    }

    /** What the phone sends: the panel, and the viewer's My List, favourite channels and titles in progress there. */
    private fun phoneSnapshot(): SyncSnapshot {
        val id = "f0e1d2c3-0000-4000-8000-00000000abcd"
        val movies = (1..40).map { "x${100_000 + it}" }
        val series = (1..10).map { "x${500_000 + it}" }
        val channels = (1..25).map { "x${10_000 + it}" }
        return SyncSnapshot(
            device = "a0a0a0a0-0000-4000-8000-000000000001",
            deviceName = "iPhone",
            playlists = listOf(SyncPlaylist(id, "Panel", SyncPlaylist.XTREAM, BigPanel.SERVER, BigPanel.USERNAME, BigPanel.PASSWORD, addedAt = Fixtures.NOW, updatedAt = Fixtures.NOW)),
            favorites = movies.map { SyncFavorite(id, "movie", it, "Movie $it", null, Fixtures.NOW - 1) } +
                series.map { SyncFavorite(id, "series", it, "Series $it", null, Fixtures.NOW - 2) } +
                channels.map { SyncFavorite(id, "live", it, "Channel $it", null, Fixtures.NOW - 3) } +
                SyncFavorite(id, "movie", "x1", "Gone", null, Fixtures.NOW),
            progress = movies.take(10).mapIndexed { index, movie -> SyncProgress(id, "movie", movie, null, "Movie", null, null, 600.0 + index, 6000.0, Fixtures.NOW - index) } +
                series.take(5).mapIndexed { index, show -> SyncProgress(id, "series", show, "${show.drop(1)}0101", "Series", "S1 E1", null, 300.0, 2400.0, Fixtures.NOW - 50 - index) },
        )
    }

    /**
     * Typed here, and the same panel arrives from the phone, where it was added first: the phone's stays, this one
     * becomes it, and Home shows its catalogue again while the remote is on one of its cards.
     */
    @Test
    fun theSamePanelTypedHereAndSyncedFromThePhoneBecomesOne() {
        start(playlists = emptyList(), syncOn = true)
        pressUntil(Key.DirectionDown) { focusedTag() == "addPlaylist" }
        press(Key.DirectionCenter)
        waitFor { exists("addPlaylistScreen") }
        typePanel()
        waitFor(60_000) { panelInUse() && exists("home") && exists("hero") }
        val typed = repository.state.value.activePlaylistId
        press(Key.DirectionDown, times = 3)
        press(Key.DirectionRight, times = 2)
        compose.runOnIdle { repository.mergeSync(phoneSnapshot(), "Living room TV") }
        waitFor(60_000) { repository.state.value.activePlaylistId != typed && panelInUse() && exists("hero") }
        assertTrue(repository.state.value.playlists.size == 1)
        settle(1_000)
        walkRows("home")
        openFromDrawer("tabMyList")
        walkRows("myList", rows = 3)
    }

    @Test
    fun anXtreamPlaylistSyncedToANewTvOpensHome() {
        start(playlists = emptyList(), syncOn = true)
        waitFor { exists("welcome") && focusedNode() != null }
        compose.runOnIdle { repository.mergeSync(phoneSnapshot(), "Living room TV") }
        waitFor(60_000) { panelInUse() && exists("home") && exists("hero") }
        waitFor { focusedNode() != null }
        walkRows("home")
        openFromDrawer("tabLive")
        walkRows("live", rows = 4, cards = 1)
        // The guide of every channel, from the top of Live TV.
        // Up to the chips above the channels (walking the rows may have moved them along), left onto the guide.
        pressUntil(Key.DirectionUp, max = 60) { focusedTag() == "openGuide" || focusedTag() == "categoryFilter" || focused() == "All" || focused() == "My List" }
        pressUntil(Key.DirectionLeft, max = 60) { focusedTag() == "openGuide" }
        press(Key.DirectionCenter)
        waitFor { exists("guide") }
        settle(1_000)
        scrollEverything("guide")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor { exists("live") && !exists("guide") }
        openFromDrawer("tabMyList")
        walkRows("myList", rows = 4)
        // Search, typed with the TV's keyboard (OK on the field opens it).
        openFromDrawer("tabSearch")
        waitFor { focusedTag() == "searchField" }
        press(Key.DirectionCenter)
        for (query in listOf("movie 1", "أفلام", "ana")) {
            compose.onNodeWithTag("searchField").performTextClearance()
            compose.onNodeWithTag("searchField").performTextInput(query)
            settle(1_000)
            scrollEverything("search")
        }
        // Back closes Search (its keyboard first), to the page that opened it and its drawer.
        repeat(3) { if (exists("search")) compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }; settle(300) }
        waitFor { !exists("search") }
        // Settings, Languages and categories.
        toBar()
        pressUntil(towardsEnd) { focusedTag() == SETTINGS_TAG }
        press(Key.DirectionCenter)
        waitFor { exists("settings") && focusedTag() == "settingsPlaylists" }
        pressUntil(Key.DirectionDown) { focusedTag() == "settingsCatalogue" }
        press(Key.DirectionRight)
        waitFor { focusedTag() == "categoriesRow" }
        press(Key.DirectionCenter)
        waitFor { exists("categories") }
        settle(1_000)
        scrollEverything("categories")
    }

    @Test
    fun anXtreamPlaylistSyncedWhileOnLiveTvIsUsedFromSettings() {
        start(playlists = listOf(demo), syncOn = true)
        openFromDrawer("tabLive")
        press(Key.DirectionDown, times = 3)
        compose.runOnIdle { repository.mergeSync(phoneSnapshot(), "Living room TV") }
        waitFor { repository.state.value.playlists.size == 2 }
        press(Key.DirectionDown, times = 3)
        toBar()
        pressUntil(towardsEnd) { focusedTag() == SETTINGS_TAG }
        press(Key.DirectionCenter)
        waitFor { exists("settings") && focusedTag() == "settingsPlaylists" }
        press(Key.DirectionRight)
        pressUntil(Key.DirectionDown) { focusedTag() == "use-Panel" }
        press(Key.DirectionCenter)
        waitFor(60_000) { panelInUse() }
        settle(1_000)
        // Back to Live TV, now the panel's channels; then Home.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor { exists("live") && focusedNode() != null }
        walkRows("live", rows = 3, cards = 1)
        openFromDrawer("tabHome")
        waitFor { exists("home") && exists("hero") }
        walkRows("home")
        openFromDrawer("tabSearch")
    }

    /** The TV pages of the panel's odd titles: no name, a very long one, emoji, Arabic; a series with odd seasons. */
    @Test
    fun theTvPagesOfThePanelsOddTitlesOpen() {
        val credentials = XtreamCredentials(BigPanel.SERVER, BigPanel.USERNAME, BigPanel.PASSWORD)
        val http = FakeHttp(BigPanel.answers(moviePlot = "A long plot. ".repeat(80_000)))
        val content = runBlocking { PlaylistLoader(http).load(Playlist(id = "p", name = "Panel", source = PlaylistSource.Xtream(credentials))) }
        val state = OndoState(isReady = true, settings = AppSettings(activePlaylistId = "p"), content = content)
        val movies = content.movies.filter { it.name.isEmpty() || it.name.length > 1_000 || it.name.any { c -> c.isSurrogate() || c in '\u0600'..'\u06FF' } || it.plot.orEmpty().length > 1_000 }.take(6)
        val series = content.series.filter { it.name.isEmpty() || it.name.length > 1_000 || it.name.any { c -> c.isSurrogate() || c in '\u0600'..'\u06FF' } }.take(4) + content.series.first()
        assertTrue(movies.size >= 4 && series.size >= 3)
        var page by mutableStateOf<(@Composable () -> Unit)?>(null)
        compose.setContent { OndoTheme { TelevisionFrame { page?.invoke() } } }
        for (movie in movies) {
            val ui = UiMappers.movieDetail(state, movie, Locale.ENGLISH)
            page = { key(movie.id) { TvMovieDetailScreen(ui, DetailActions()) } }
            settle(600)
            scrollToEnd(compose.onNodeWithTag("detail"), horizontal = false)
        }
        for (show in series) {
            val details = runBlocking { XtreamClient(credentials, http).seriesDetails(show.xtreamSeriesId!!) }
            val withDetails = state.copy(seriesDetails = mapOf(show.id to details))
            for (season in details.seasons.map { it.number } + 7) {
                val ui = UiMappers.seriesDetail(withDetails, show, season, null)
                page = { key(show.id, season) { TvSeriesDetailScreen(ui, DetailActions()) } }
                settle(600)
                scrollEverything("detail")
            }
        }
    }
}
