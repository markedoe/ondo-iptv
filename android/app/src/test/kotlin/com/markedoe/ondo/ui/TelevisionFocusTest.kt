package com.markedoe.ondo.ui

import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.UncaughtErrors
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.sync.SyncPlaylist
import com.markedoe.ondo.core.sync.SyncProgress
import com.markedoe.ondo.core.sync.SyncSnapshot
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.tv.SORT_CHIP_TAG
import com.markedoe.ondo.ui.tv.SEARCH_TAG
import com.markedoe.ondo.ui.theme.OndoTheme
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android TV: where the remote's focus is when the catalogue under it changes — it arrives on a Home already shown, or
 * the playlist is replaced by another device's copy, or what the remote is on leaves the page (a refresh, an answer).
 * What a page does when its title leaves the catalogue.
 * What is kept of the screens when Ondo goes to the background.
 * The text fields (add a playlist, Search, filters), with arrows that come from a virtual device (a phone used as a
 * remote).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TelevisionFocusTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val uncaught = UncaughtErrors()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    private lateinit var repository: OndoRepository
    private var focusManager: FocusManager? = null

    private val panel = "http://panel.example.com"

    /** Answers by URL while [open]; a request made while it is closed waits, as a slow network does. */
    private class SlowHttp(private val answers: Map<String, String>) : HttpClient {
        val open = MutableStateFlow(true)

        override suspend fun get(url: String, headers: Map<String, String>): ByteArray {
            open.first { it }
            return answers[url]?.encodeToByteArray() ?: throw HttpException.BadStatus(404)
        }
    }

    private lateinit var http: SlowHttp

    /** The app's view model; another one is Ondo started anew on the same stored data ([restart]). */
    private val app = mutableStateOf<OndoViewModel?>(null)

    /** Ondo closed and started again: its stored data read anew, its screens built anew, from Home. */
    private fun restart() {
        val store = AppStore(folder.root.resolve("ondo"))
        repository = OndoRepository(
            store = store,
            http = http,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            downloadFolder = folder.root.resolve("downloads"),
            guideBudgetBytes = Runtime.getRuntime().maxMemory() / 4,
        )
        compose.runOnIdle { app.value = OndoViewModel(repository, Labels()) }
        waitFor { exists("hero") }
        settle(500)
    }

    /** The welcome page; with [stored], that playlist and its catalogue already saved, Home ([shown] on it). */
    private fun start(answers: Map<String, String> = smallPanel(), stored: PlaylistContent? = null, shown: String = "hero") {
        val store = AppStore(folder.root.resolve("ondo"))
        if (stored != null) {
            store.savePlaylists(listOf(Fixtures.playlist))
            store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
            store.saveContent(Fixtures.PLAYLIST_ID, stored.copy(fetchedAt = System.currentTimeMillis()))
        }
        http = SlowHttp(answers)
        repository = OndoRepository(
            store = store,
            http = http,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            downloadFolder = folder.root.resolve("downloads"),
            guideBudgetBytes = Runtime.getRuntime().maxMemory() / 4,
        )
        app.value = OndoViewModel(repository, Labels())
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            focusManager = LocalFocusManager.current
            // A new view model is Ondo started anew ([restart]): nothing of the screens before is kept.
            val viewModel = app.value
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { key(viewModel) { if (viewModel != null) OndoApp(viewModel) } } }
        }
        compose.waitUntil(10_000) { exists(if (stored == null) "welcome" else shown) }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        settle()
    }

    /** The main thread's clock and Compose's keep pace with real time, as on a device (pages load in the background). */
    private fun settle(ms: Long = 300) {
        val deadline = System.currentTimeMillis() + ms
        do {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            compose.mainClock.advanceTimeBy(20)
            compose.waitForIdle()
            uncaught.check()
        } while (System.currentTimeMillis() < deadline)
    }

    private fun waitFor(timeout: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out; the focus is on ${focused()}" }
            settle(60)
        }
    }

    /**
     * A key of the remote, sent where the focus is. On a text field, an arrow moves the focus as Compose does for a
     * physical D-pad (the test's key events have no device).
     */
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

    private fun pressUntil(key: Key, max: Int = 12, done: () -> Boolean) {
        // Where the focus went after each press, and the page shown then: the failure says how it got lost.
        val trail = mutableListOf<String>()
        repeat(max) {
            if (done()) return
            press(key)
            trail += "${focused()} [${pageShown()}]"
        }
        check(done()) { "never reached with $key; the focus is on ${focused()}; along the way: ${trail.joinToString(" → ")}" }
    }

    /** The tab page on screen, for a failure's trail. */
    private fun pageShown(): String = listOf("home", "vod", "live", "myList", "grid", "settings").filter(::exists).joinToString("+")

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun focusedInteraction(): SemanticsNodeInteraction = compose.onAllNodes(isFocused()).onLast()

    private fun focusedTag(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun focused(): String = focusedNode()?.let { node ->
        (node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            ?: node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()).take(80)
    } ?: "nothing"

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun focusedIn(tag: String) = compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().isNotEmpty()

    private fun api(action: String? = null) = "$panel/player_api.php?username=viewer&password=secret" + (action?.let { "&action=$it" } ?: "")

    private val account = """{"user_info":{"username":"viewer","password":"secret","auth":1,"status":"Active","exp_date":"1900000000","is_trial":"0","active_cons":"0","max_connections":"1","allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"panel.example.com","port":"80","timezone":"UTC"}}"""

    /** A panel of a few titles, this year's: the carousel features them. */
    private fun smallPanel(): Map<String, String> {
        val year = java.time.Year.now().value
        val movies = (21..26).joinToString(",") { """{"num":$it,"name":"Movie $it ($year)","year":"$year","stream_id":$it,"category_id":"2","container_extension":"mkv","added":"${1700000000 + it}"}""" }
        return mapOf(
            api() to account,
            api("get_live_categories") to """[{"category_id":"1","category_name":"News"}]""",
            api("get_live_streams") to """[{"num":1,"name":"Panel News","stream_id":11,"category_id":"1"}]""",
            api("get_vod_categories") to """[{"category_id":"2","category_name":"Drama"}]""",
            api("get_vod_streams") to "[$movies]",
            api("get_series_categories") to """[{"category_id":"3","category_name":"Shows"}]""",
            api("get_series") to """[{"num":1,"name":"Harbor","series_id":31,"category_id":"3","year":"$year"}]""",
        )
    }

    private val phonePlaylist = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    /** What a phone sends: the same panel, and a movie in progress there. */
    private fun fromThePhone(progress: Boolean = true) = SyncSnapshot(
        device = "11111111-2222-3333-4444-555555555555",
        deviceName = "Phone",
        playlists = listOf(SyncPlaylist(id = phonePlaylist, name = "Panel", kind = SyncPlaylist.XTREAM, url = panel, username = "viewer", password = "secret", addedAt = 1, updatedAt = 1)),
        progress = if (progress) listOf(SyncProgress(phonePlaylist, "movie", "x24", null, "Movie 24", null, null, 600.0, 6000.0, System.currentTimeMillis())) else emptyList(),
    )

    /** Welcome → Add a Playlist → Xtream Codes (the first kind) → Save, with the remote. */
    private fun addWithTheRemote() {
        pressUntil(Key.DirectionUp) { focusedTag() == "addPlaylist" }
        press(Key.DirectionCenter)
        waitFor { exists("addPlaylistScreen") }
        for ((tag, text) in listOf("serverField" to panel, "usernameField" to "viewer", "passwordField" to "secret")) {
            pressUntil(Key.DirectionDown) { focusedTag() == tag }
            // OK opens the field's keyboard.
            press(Key.DirectionCenter)
            focusedInteraction().performTextInput(text)
            settle()
        }
        pressUntil(Key.DirectionDown) { focusedTag() == "savePlaylist" }
        press(Key.DirectionCenter)
    }

    /** A new TV: the playlist and what is being watched come from the phone at once, the catalogue later. */
    @Test
    fun aCatalogueArrivingOnHomeAlreadyShownPutsTheRemoteOnPlay() {
        start()
        http.open.value = false
        compose.runOnIdle { repository.mergeSync(fromThePhone(), "Phone") }
        // Continue watching shows first, the remote on its card.
        waitFor { exists("home") && exists("shelfResumeMovies") && focusedNode() != null }
        http.open.value = true
        waitFor { repository.state.value.content.movies.isNotEmpty() && exists("heroPlay") }
        settle(1_000)
        assertEquals("the focus is on ${focused()}", "heroPlay", focusedTag())
    }

    @Test
    fun theFirstPlaylistTypedOnTheWelcomeScreenOpensHomeOnPlay() {
        start()
        addWithTheRemote()
        waitFor { exists("home") && repository.state.value.content.movies.isNotEmpty() }
        settle(1_000)
        assertEquals("the focus is on ${focused()}", "heroPlay", focusedTag())
    }

    /**
     * Typed here, then the same panel from the phone, where it was added first: it replaces this one, and Home is empty
     * while the catalogue comes again from the panel (the remote can only be in the drawer then). Once it is back, the
     * remote is on Home again.
     */
    @Test
    fun theRemoteComesBackToHomeWhenThePlaylistTypedHereBecomesThePhones() {
        start()
        addWithTheRemote()
        waitFor { exists("home") && focusedTag() == "heroPlay" }
        press(Key.DirectionDown)
        assertTrue("the focus is on ${focused()}", focusedIn("shelfNewMovies"))
        val typed = repository.state.value.activePlaylistId
        http.open.value = false
        compose.runOnIdle { repository.mergeSync(fromThePhone(progress = false), "Phone") }
        waitFor { repository.state.value.activePlaylistId == phonePlaylist && repository.state.value.activePlaylistId != typed }
        settle(1_000)
        http.open.value = true
        waitFor { repository.state.value.content.movies.isNotEmpty() && exists("hero") }
        settle(1_000)
        // The next press moves from something on the page, not from the drawer.
        press(Key.DirectionDown)
        assertTrue("the focus is on ${focused()} (${focusedTag()})", focusedIn("home") && !focusedTag().startsWith("tab"))
    }

    /** The stored playlist's list once its movies and series are gone from the provider. */
    private val withoutTitles = mapOf(
        Fixtures.playlist.url to "#EXTM3U\n#EXTINF:-1 group-title=\"News\",Ondo News\nhttp://example.com/live/c1.m3u8\n",
    )

    /** From Home's Play, down [rows] rows, OK: a title's page. */
    private fun openFromHome(rows: Int) {
        waitFor { focusedTag() == "heroPlay" }
        press(Key.DirectionDown, times = rows)
        press(Key.DirectionCenter)
        waitFor { exists("detail") }
        settle(500)
    }

    private fun spinner() = compose.onAllNodes(hasContentDescription("Loading…")).fetchSemanticsNodes().isNotEmpty()

    /** The catalogue refreshed without the title of the page open: the page goes back, as a deleted collection's does. */
    private fun refreshWithoutTheTitle(gone: () -> Boolean) {
        compose.runOnIdle { repository.refresh() }
        waitFor { gone() && !repository.state.value.isLoading }
        settle(1_000)
        assertFalse("the page waits for a title the catalogue no longer has", spinner())
        waitFor { exists("home") && focusedNode() != null }
    }

    @Test
    fun aMoviePageWhoseTitleLeavesTheCatalogueGoesBack() {
        start(answers = withoutTitles, stored = Fixtures.content)
        openFromHome(rows = 1)
        refreshWithoutTheTitle { repository.state.value.content.movies.isEmpty() }
    }

    @Test
    fun aSeriesPageWhoseTitleLeavesTheCatalogueGoesBack() {
        start(answers = withoutTitles, stored = Fixtures.content)
        openFromHome(rows = 2)
        refreshWithoutTheTitle { repository.state.value.content.series.isEmpty() }
    }

    /**
     * A cast field with no separator in a long run of text, then a name: the first card opens its page. What Android
     * keeps of the screens when Ondo goes to the background (the pages open, their arguments and focus) must stay far
     * within the 1 MB a binder transaction carries, or the app stops with a TransactionTooLargeException.
     */
    @Test
    fun aCastFieldWithoutSeparatorsKeepsWhatIsSavedOfTheScreensSmall() {
        val cast = "A" + "b".repeat(300_000) + ", Ada Example"
        val content = Fixtures.content.copy(series = Fixtures.content.series.map { if (it.id == Fixtures.harborLights.id) it.copy(cast = cast) else it })
        start(answers = emptyMap(), stored = content)
        openFromHome(rows = 2)
        pressUntil(Key.DirectionDown, max = 20) { focusedIn("people") }
        press(Key.DirectionCenter)
        waitFor { exists("person") }
        settle(500)
        val saved = Bundle()
        compose.runOnIdle { InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(compose.activity, saved) }
        val parcel = Parcel.obtain()
        val size = try {
            saved.writeToParcel(parcel, 0)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
        assertTrue("saved state of $size bytes", size < 100_000)
    }

    /** A key with no physical device behind it: what `adb shell input` and apps that turn a phone into a remote send. */
    private fun virtualKey(key: Key) {
        focusedInteraction().performKeyInput { pressKey(key) }
        settle(120)
    }

    /**
     * The add-playlist form with a phone as the remote: its arrows come from a virtual device, which Compose would keep
     * in a text field; they move from field to field as the remote's do. OK opens the keyboard, whose action key goes
     * on from field to field with the keyboard open, then to Add.
     */
    @Test
    fun theAddPlaylistFormIsFilledWithArrowsFromAVirtualDevice() {
        start()
        pressUntil(Key.DirectionUp) { focusedTag() == "addPlaylist" }
        press(Key.DirectionCenter)
        waitFor { exists("addPlaylistScreen") }
        pressUntil(Key.DirectionDown) { focusedNode()?.config?.contains(SemanticsProperties.EditableText) == true }
        val name = focusedNode()!!.id
        virtualKey(Key.DirectionDown)
        assertEquals("the focus is on ${focused()}", "serverField", focusedTag())
        virtualKey(Key.DirectionUp)
        assertEquals("the focus is on ${focused()}", name, focusedNode()?.id)
        virtualKey(Key.DirectionCenter)
        for ((text, next) in listOf("Panel" to "serverField", panel to "usernameField", "viewer" to "passwordField", "secret" to "savePlaylist")) {
            focusedInteraction().performTextInput(text)
            settle()
            focusedInteraction().performImeAction()
            settle()
            assertEquals("the focus is on ${focused()}", next, focusedTag())
        }
    }

    /**
     * The keyboard's action key on the focused field, as the system keyboard sends it: the action the field gave the
     * keyboard (Done for a single-line field without one of its own).
     */
    private fun keyboardActionKey() {
        compose.runOnIdle {
            fun find(view: View): View? = if (view.javaClass.name.endsWith("AndroidComposeView")) view else
                (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
            val view = checkNotNull(find(compose.activity.window.decorView)) { "no Compose view" }
            val info = EditorInfo()
            val connection = checkNotNull(view.onCreateInputConnection(info)) { "the focused field takes no keyboard" }
            connection.performEditorAction(info.imeOptions and EditorInfo.IME_MASK_ACTION)
        }
        settle()
    }

    private fun editing() = focusedNode()?.config?.contains(SemanticsProperties.EditableText) == true

    private val movieTitles = Fixtures.content.movies.map { it.name }.toSet()

    /** Whether the remote is on a row's See all card. */
    private fun onSeeAll() = focusedTag() == "seeAll" || focusedIn("seeAll")

    /**
     * A category's titles, opened as the viewer does from the tab's rows: down to the category's row ([id]), Right
     * along it to its See all card, OK.
     */
    private fun seeAll(id: String) {
        pressUntil(Key.DirectionDown) { focusedIn("shelf:$id") }
        pressUntil(Key.DirectionRight) { onSeeAll() }
        press(Key.DirectionCenter)
    }

    private fun openFromDrawer(tag: String) {
        pressUntil(Key.DirectionUp, max = 40) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionLeft) { focusedTag() == "tabHome" }
        pressUntil(Key.DirectionRight) { focusedTag() == tag }
        press(Key.DirectionCenter)
        waitFor { focusedNode() != null && !focusedTag().startsWith("tab") }
        settle(500)
    }

    /** Search with a phone as the remote: OK opens the keyboard, whose action key goes on to the results. */
    @Test
    fun searchsKeyboardActionGoesOnToTheResults() {
        start(answers = emptyMap(), stored = Fixtures.content)
        openFromDrawer(SEARCH_TAG)
        waitFor { focusedTag() == "searchField" }
        virtualKey(Key.DirectionCenter)
        focusedInteraction().performTextInput("the")
        settle(1_000)
        keyboardActionKey()
        assertTrue("the focus is on ${focused()} (${focusedTag()})", focusedIn("search") && !editing() && focusedTag() != "searchFilters")
    }

    /** The posters of the grid shown, in their order. */
    private fun gridPosters(): List<String> = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("grid"))).fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }

    /**
     * A category's posters on a TV have no row of their own above them: no Back (the remote's Back goes back), no title
     * in the grid (its name is above the first row, at the header's foot), no Sort button (a chip on that line), no
     * filter (Search finds a title). Down from the tab lands on the first poster, its title in the header at once.
     */
    @Test
    fun aCategorysPostersHaveNoRowNorFilterAboveThem() {
        start(answers = emptyMap(), stored = Fixtures.content)
        openDramaGrid()
        fun inGrid(matcher: SemanticsMatcher) = compose.onAllNodes(matcher and hasAnyAncestor(hasTestTag("grid")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        assertFalse("no filter field", inGrid(hasSetTextAction()))
        assertFalse("no Filter placeholder", inGrid(hasText("Filter")))
        assertFalse("no Back button", inGrid(hasContentDescription("Back")))
        assertFalse("no Sort button", exists("sort"))
        assertFalse("no title of its own", inGrid(hasText("Drama")))
        pressUntil(Key.DirectionUp, max = 20) { focusedTag().startsWith("tab") }
        press(Key.DirectionDown)
        assertEquals("Down from the tab: the first poster", gridPosters().first(), focused())
        assertEquals(listOf(gridPosters().first()), compose.onAllNodes(hasTestTag("headerTitle"), useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() })
        // Back still goes back to the tab's page.
        back()
        waitFor { exists("vod") && !exists("grid") }
    }

    /**
     * A category's order is a chip on its name's line: Up from the first row reaches it, its orders open on the one
     * shown, and Rating puts the best rated first, kept as the shared setting.
     */
    @Test
    fun theSortChipSortsACategorysPostersAndKeepsTheOrder() {
        start(answers = emptyMap(), stored = Fixtures.content)
        openFromDrawer("tabMovies")
        seeAll(Fixtures.sciFi.id)
        waitFor { exists("grid") && focusedIn("grid") }
        assertEquals("the playlist's order", "Glass City", gridPosters().first())
        assertEquals("the grid opens on its first poster", "Glass City", focused())
        press(Key.DirectionUp)
        assertEquals("Up from the first row: the Sort chip", SORT_CHIP_TAG, focusedTag())
        press(Key.DirectionCenter)
        waitFor { focusedTag() == "sort-playlist" }
        compose.onNodeWithTag("sort-playlist").assertIsSelected()
        compose.onNodeWithTag("sort-rating").assertIsNotSelected()
        pressUntil(Key.DirectionDown) { focusedTag() == "sort-rating" }
        press(Key.DirectionCenter)
        waitFor { repository.state.value.settings.posterSort == PosterSort.RATING }
        waitFor { gridPosters().firstOrNull() == "The Last Signal" }
        assertFalse("the menu closed", exists("sortMenu"))
        assertEquals("the focus back on the chip", SORT_CHIP_TAG, focusedTag())
        // Ticked the next time.
        press(Key.DirectionCenter)
        waitFor { exists("sortMenu") }
        compose.onNodeWithTag("sort-rating").assertIsSelected()
    }

    /**
     * See all at the end of a row opens the category's titles, Down from the tab lands on their first poster, and Back
     * comes back to the rows: the same row and card (See all), the rows scrolled as they were.
     */
    @Test
    fun seeAllOpensTheCategoryAndBackComesBackToTheSameCard() {
        start(answers = emptyMap(), stored = Fixtures.content)
        openFromDrawer("tabMovies")
        pressUntil(Key.DirectionDown) { focused() in movieTitles }
        pressUntil(Key.DirectionUp, max = 20) { focusedTag().startsWith("tab") }
        press(Key.DirectionDown)
        assertTrue("Down from the tab: the first card of the rows (${focused()})", focused() in movieTitles && !onSeeAll() && !exists(SORT_CHIP_TAG))
        pressUntil(Key.DirectionDown) { focusedIn("shelf:${Fixtures.thriller.id}") }
        pressUntil(Key.DirectionRight) { onSeeAll() }
        settle(500)
        val row = compose.onNodeWithTag("shelf:${Fixtures.thriller.id}").fetchSemanticsNode().boundsInRoot
        press(Key.DirectionCenter)
        waitFor { exists("grid") && focusedIn("grid") }
        assertEquals("the category's name above its titles", listOf("Thriller"), labels())
        pressUntil(Key.DirectionUp, max = 20) { focusedTag().startsWith("tab") }
        press(Key.DirectionDown)
        assertEquals("Down from the tab: the first poster, not a chip", gridPosters().first(), focused())
        back()
        waitFor { exists("vod") && !exists("grid") }
        waitFor { onSeeAll() }
        assertTrue("back on the Thriller row (${focused()})", focusedIn("shelf:${Fixtures.thriller.id}"))
        val again = compose.onNodeWithTag("shelf:${Fixtures.thriller.id}").fetchSemanticsNode().boundsInRoot
        assertEquals("the rows scrolled as they were", row.top, again.top, 2f)
        assertTrue("the tab shows its rows, no category chosen", tabSelected("tabMovies"))
    }

    /** The texts at the header's foot (a category's name). */
    private fun labels(): List<String> = compose.onAllNodesWithTag("headerLabel", useUnmergedTree = true).fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }

    /**
     * A category is a page opened over the rows, never the tab's state: another tab, then Movies again along the bar,
     * shows the rows, not the category's titles left open.
     */
    @Test
    fun anotherTabThenMoviesAgainShowsTheRows() {
        start(answers = emptyMap(), stored = Fixtures.content)
        openFromDrawer("tabMovies")
        seeAll(Fixtures.drama.id)
        waitFor { exists("grid") && focusedIn("grid") }
        // Along the bar: Home opens as the remote rests on it, then Movies.
        pressUntil(Key.DirectionUp, max = 20) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionLeft) { focusedTag() == "tabHome" }
        settle(1_000)
        waitFor { exists("home") }
        pressUntil(Key.DirectionRight) { focusedTag() == "tabMovies" }
        settle(1_000)
        waitFor { exists("vod") }
        assertFalse("no category's titles left open", exists("grid"))
        assertTrue(tabSelected("tabMovies"))
        // And OK on the tab, from a category's titles, comes back to the rows too.
        press(Key.DirectionDown)
        seeAll(Fixtures.drama.id)
        waitFor { exists("grid") && focusedIn("grid") }
        pressUntil(Key.DirectionUp, max = 20) { focusedTag().startsWith("tab") }
        press(Key.DirectionCenter)
        waitFor { exists("vod") && !exists("grid") }
    }

    /** Ondo started anew after a category was opened: the Movies tab shows its rows. */
    @Test
    fun afterARestartTheMoviesTabShowsItsRows() {
        start(answers = emptyMap(), stored = Fixtures.content)
        openFromDrawer("tabMovies")
        seeAll(Fixtures.drama.id)
        waitFor { exists("grid") && focusedIn("grid") }
        restart()
        openFromDrawer("tabMovies")
        waitFor { exists("vod") }
        assertFalse("no category's titles", exists("grid"))
        assertTrue("the rows", exists("shelfAll"))
    }

    /** Whether the top bar's tab [tag] is the one shown. */
    private fun tabSelected(tag: String): Boolean =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Selected) == true }

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle(500)
    }

    /** A category's posters on Movies, opened from its row's See all card. */
    private fun openDramaGrid() {
        openFromDrawer("tabMovies")
        seeAll(Fixtures.drama.id)
        waitFor { exists("grid") && focusedIn("grid") }
    }

    /**
     * A category's posters keep the top bar, on the tab they belong to: Up from the page's top reaches it on that tab,
     * Down comes back to the page, Back goes back to the tab's page (not to Home), OK on the tab too.
     */
    @Test
    fun aCategorysPostersKeepTheTopBar() {
        start(answers = emptyMap(), stored = Fixtures.content)
        openDramaGrid()
        assertTrue("the bar stays over the category", exists("topBar"))
        assertTrue("on the tab it belongs to", tabSelected("tabMovies"))
        assertEquals("the category's name above its titles", listOf("Drama"), labels())
        pressUntil(Key.DirectionUp, max = 20) { focusedTag().startsWith("tab") }
        assertEquals("Up lands on the tab shown", "tabMovies", focusedTag())
        press(Key.DirectionDown)
        settle(300)
        assertTrue("Down goes back into the page (${focused()})", focusedIn("grid"))
        // Back from the page: the tab's page, the bar still on Movies.
        back()
        waitFor { exists("vod") && !exists("grid") }
        assertTrue(tabSelected("tabMovies"))
        // Again, and back to the tab's page with OK on its tab.
        seeAll(Fixtures.drama.id)
        waitFor { exists("grid") && focusedIn("grid") }
        pressUntil(Key.DirectionUp, max = 20) { focusedTag().startsWith("tab") }
        press(Key.DirectionCenter)
        waitFor { exists("vod") && !exists("grid") }
        // The bar's Back, from the tab's page, still goes Home.
        pressUntil(Key.DirectionUp, max = 20) { focusedTag().startsWith("tab") }
        back()
        waitFor { exists("home") }
    }

    /** Back on the bar over a category's posters goes back to the page before, not Home. */
    @Test
    fun backOnTheBarOverACategoryGoesBackAPage() {
        start(answers = emptyMap(), stored = Fixtures.content)
        openDramaGrid()
        pressUntil(Key.DirectionUp, max = 20) { focusedTag().startsWith("tab") }
        back()
        waitFor { exists("vod") && !exists("grid") }
        assertFalse(exists("home"))
    }

    /** A genre's titles keep the bar too, the tab shown unchanged. */
    @Test
    fun aGenresTitlesKeepTheTopBar() {
        start(answers = emptyMap(), stored = Fixtures.content)
        openFromHome(rows = 2)
        pressUntil(Key.DirectionDown, max = 20) { focusedIn("explore") }
        press(Key.DirectionCenter)
        waitFor { exists("browse") }
        settle(500)
        assertTrue("the bar shows over the genre", exists("topBar"))
        assertTrue(tabSelected("tabHome"))
        pressUntil(Key.DirectionUp, max = 30) { focusedTag().startsWith("tab") }
        assertEquals("tabHome", focusedTag())
        back()
        waitFor { exists("detail") && !exists("browse") }
    }

    /**
     * Which categories are shown, the same: the filter's keyboard action goes on to the row below it; the word to hide's
     * adds the word and goes on below.
     */
    @Test
    fun theCategoriesFieldsGoOnWithTheKeyboardsAction() {
        start(answers = emptyMap(), stored = Fixtures.content)
        // Settings, Languages and categories: which categories are shown.
        openFromDrawer("tabSettings")
        waitFor { exists("settings") && focusedTag() == "settingsPlaylists" }
        pressUntil(Key.DirectionDown) { focusedTag() == "settingsCatalogue" }
        press(Key.DirectionRight)
        waitFor { focusedTag() == "categoriesRow" }
        press(Key.DirectionCenter)
        waitFor { exists("categories") && focusedIn("categories") }
        pressUntil(Key.DirectionDown) { editing() }
        assertEquals("categoriesFilter", focusedTag())
        virtualKey(Key.DirectionCenter)
        keyboardActionKey()
        assertTrue("the focus is on ${focused()}", focusedIn("categories") && !editing())
        pressUntil(Key.DirectionDown) { focusedTag() == "wordToHide" }
        virtualKey(Key.DirectionCenter)
        focusedInteraction().performTextInput("trailer")
        settle()
        keyboardActionKey()
        waitFor { "trailer" in repository.state.value.library.hiddenWords }
        assertTrue("the focus is on ${focused()}", focusedIn("categories") && !editing())
    }

    /** The stored playlist's list once refreshed: another channel and another film in place of those shown. */
    private val otherTitles = mapOf(
        Fixtures.playlist.url to "#EXTM3U\n#EXTINF:-1 group-title=\"News\",Other News\nhttp://example.com/live/c9.m3u8\n" +
            "#EXTINF:-1 group-title=\"Drama\",Other Film\nhttp://example.com/movie/9.mp4\n",
    )

    /** A refresh takes away what the remote is on; the next press moves on the page, not in the drawer. */
    private fun refreshUnderTheRemote(page: String) {
        compose.runOnIdle { repository.refresh() }
        waitFor { repository.state.value.content.channels.map { it.name } == listOf("Other News") && !repository.state.value.isLoading }
        settle(1_000)
        press(Key.DirectionDown)
        assertTrue("the focus is on ${focused()} (${focusedTag()})", focusedIn(page) && !focusedTag().startsWith("tab"))
    }

    @Test
    fun theRemoteStaysOnLiveTvWhenARefreshTakesAwayItsChannel() {
        start(answers = otherTitles, stored = Fixtures.content)
        openFromDrawer("tabLive")
        assertTrue("the focus is on ${focused()}", focusedIn("live"))
        refreshUnderTheRemote("live")
    }

    @Test
    fun theRemoteStaysOnMyListWhenARefreshTakesAwayItsPoster() {
        start(answers = otherTitles, stored = Fixtures.content)
        // The carousel's first title in My List.
        waitFor { focusedTag() == "heroPlay" }
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        waitFor { repository.state.value.library.favorites.isNotEmpty() }
        val title = repository.state.value.library.favorites.single().title
        openFromDrawer("tabMyList")
        pressUntil(Key.DirectionDown) { focused() == title }
        refreshUnderTheRemote("myList")
    }

    /** Movies, a poster chosen, then the playlist typed here becomes the phone's: its catalogue empty, then back. */
    @Test
    fun theRemoteComesBackToMoviesWhenThePlaylistTypedHereBecomesThePhones() {
        start()
        addWithTheRemote()
        waitFor { exists("home") && focusedTag() == "heroPlay" }
        openFromDrawer("tabMovies")
        pressUntil(Key.DirectionDown) { focused().startsWith("Movie ") }
        press(Key.DirectionRight)
        http.open.value = false
        compose.runOnIdle { repository.mergeSync(fromThePhone(progress = false), "Phone") }
        waitFor { repository.state.value.activePlaylistId == phonePlaylist }
        settle(1_000)
        http.open.value = true
        waitFor { repository.state.value.content.movies.isNotEmpty() && exists("vod") }
        settle(1_000)
        press(Key.DirectionDown)
        assertTrue("the focus is on ${focused()} (${focusedTag()})", focusedIn("vod") && !focusedTag().startsWith("tab"))
    }

    /** Channels only (no carousel, no Play): the stored playlist, then the same list from the phone, which replaces it. */
    @Test
    fun theRemoteComesBackToAHomeOfChannelsOnlyWhenThePlaylistBecomesThePhones() {
        val channelsOnly = Fixtures.content.copy(movies = emptyList(), series = emptyList(), movieCategories = emptyList(), seriesCategories = emptyList())
        start(answers = mapOf(Fixtures.playlist.url to "#EXTM3U\n#EXTINF:-1 group-title=\"News\",Ondo News\nhttp://example.com/live/c1.m3u8\n"), stored = channelsOnly, shown = "shelfChannels")
        waitFor { focusedIn("shelfChannels") }
        http.open.value = false
        val phone = SyncSnapshot(
            device = "11111111-2222-3333-4444-555555555555",
            deviceName = "Phone",
            playlists = listOf(SyncPlaylist(id = phonePlaylist, name = "Home", kind = SyncPlaylist.M3U, url = Fixtures.playlist.url, addedAt = 1, updatedAt = 1)),
        )
        compose.runOnIdle { repository.mergeSync(phone, "Phone") }
        waitFor { repository.state.value.activePlaylistId == phonePlaylist }
        settle(1_000)
        http.open.value = true
        waitFor { repository.state.value.content.channels.isNotEmpty() && exists("shelfChannels") }
        settle(1_000)
        press(Key.DirectionDown)
        assertTrue("the focus is on ${focused()} (${focusedTag()})", focusedIn("home") && !focusedTag().startsWith("tab"))
    }

    /** Categories with a language tag, each on two of them: English, French and German (the viewer reads English). */
    private val tagged = Fixtures.content.copy(
        movieCategories = listOf(Fixtures.drama.copy(name = "EN | Drama"), Fixtures.sciFi.copy(name = "FR | Science Fiction"), Fixtures.thriller.copy(name = "DE | Thriller"), Fixtures.adultMovies),
        seriesCategories = listOf(Fixtures.seriesDrama.copy(name = "FR | Drama"), Fixtures.seriesMystery.copy(name = "DE | Mystery")),
        liveCategories = listOf(Fixtures.news.copy(name = "EN | News"), Fixtures.sports),
    )

    /** The suggestion to keep only the viewer's languages, answered with the remote: the remote stays on Home. */
    private fun answerTheSuggestion(button: String) {
        start(answers = emptyMap(), stored = tagged)
        waitFor { focusedTag() == "heroPlay" && exists("tagSuggestion") }
        pressUntil(Key.DirectionDown) { focusedIn("tagSuggestion") }
        pressUntil(Key.DirectionRight) { focused() == button }
        press(Key.DirectionCenter)
        waitFor { !exists("tagSuggestion") }
        settle(1_000)
        press(Key.DirectionDown)
        assertTrue("the focus is on ${focused()} (${focusedTag()})", focusedIn("home") && !focusedTag().startsWith("tab"))
    }

    @Test
    fun notNowKeepsTheRemoteOnHome() = answerTheSuggestion("Not now")

    @Test
    fun keepOnlyTheseKeepsTheRemoteOnHome() = answerTheSuggestion("Keep only these")

    /**
     * A genre field with no separator in a long run of text, then a genre: the first chip opens the titles of its genre.
     * What Android keeps of the screens when Ondo goes to the background must stay far within the 1 MB a binder
     * transaction carries.
     */
    @Test
    fun aGenreFieldWithoutSeparatorsKeepsWhatIsSavedOfTheScreensSmall() {
        val genre = "A" + "b".repeat(300_000) + ", Drama"
        val content = Fixtures.content.copy(series = Fixtures.content.series.map { if (it.id == Fixtures.harborLights.id) it.copy(genre = genre) else it })
        start(answers = emptyMap(), stored = content)
        openFromHome(rows = 2)
        pressUntil(Key.DirectionDown, max = 20) { focusedIn("explore") }
        press(Key.DirectionCenter)
        waitFor { exists("browse") }
        settle(500)
        val saved = Bundle()
        compose.runOnIdle { InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(compose.activity, saved) }
        val parcel = Parcel.obtain()
        val size = try {
            saved.writeToParcel(parcel, 0)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
        assertTrue("saved state of $size bytes", size < 100_000)
    }
}
