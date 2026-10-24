package com.markedoe.ondo.ui

import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import com.markedoe.ondo.Fixtures
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
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
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
 * the playlist is replaced by another device's copy.
 * What a page does when its title leaves the catalogue.
 * What is kept of the screens when Ondo goes to the background.
 * The text fields (add a playlist, Search, filters), with arrows that come from a virtual device (a phone used as a
 * remote).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TelevisionFocusTest {
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

    /** The welcome page; with [stored], that playlist and its catalogue already saved, Home. */
    private fun start(answers: Map<String, String> = smallPanel(), stored: PlaylistContent? = null) {
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
        val viewModel = OndoViewModel(repository, Labels())
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            focusManager = LocalFocusManager.current
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        compose.waitUntil(10_000) { exists(if (stored == null) "welcome" else "hero") }
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
     * The add-playlist form with a phone as the remote: its arrows come from a virtual device, which Compose keeps in a
     * text field (it moves the focus out only for a physical D-pad; that stays so). The keyboard's action key goes on
     * from field to field, then to Add.
     */
    @Test
    fun theAddPlaylistFormIsFilledWithArrowsFromAVirtualDevice() {
        start()
        pressUntil(Key.DirectionUp) { focusedTag() == "addPlaylist" }
        press(Key.DirectionCenter)
        waitFor { exists("addPlaylistScreen") }
        pressUntil(Key.DirectionDown) { focusedNode()?.config?.contains(SemanticsProperties.EditableText) == true }
        val name = focusedNode()!!.id
        repeat(3) { virtualKey(Key.DirectionDown) }
        assertEquals("the focus is on ${focused()}", name, focusedNode()?.id)
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

    /** From the page, to the drawer, onto [tag], and opens it; the remote then moves into the page. */
    private fun openFromDrawer(tag: String) {
        pressUntil(Key.DirectionLeft) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionUp) { focusedTag() == SEARCH_TAG }
        pressUntil(Key.DirectionDown) { focusedTag() == tag }
        press(Key.DirectionCenter)
        waitFor { focusedNode() != null && !focusedTag().startsWith("tab") }
        settle(500)
    }

    /** Search with a phone as the remote: arrows stay in the field; the keyboard's action key goes on to the results. */
    @Test
    fun searchsKeyboardActionGoesOnToTheResults() {
        start(answers = emptyMap(), stored = Fixtures.content)
        openFromDrawer(SEARCH_TAG)
        waitFor { focusedTag() == "searchField" }
        focusedInteraction().performTextInput("the")
        settle(1_000)
        virtualKey(Key.DirectionDown)
        assertEquals("searchField", focusedTag())
        keyboardActionKey()
        assertTrue("the focus is on ${focused()} (${focusedTag()})", focusedIn("search") && !editing() && focusedTag() != "searchFilters")
    }

    /** A poster grid's filter, the same: its keyboard's action key goes on to the posters. */
    @Test
    fun aPosterGridsFilterGoesOnToThePostersWithTheKeyboardsAction() {
        start(answers = emptyMap(), stored = Fixtures.content)
        openFromDrawer("tabMovies")
        // Up from the first poster: its category's chip, which opens the category's posters.
        pressUntil(Key.DirectionDown) { focused() in movieTitles }
        pressUntil(Key.DirectionUp) { focused() == "Drama" }
        press(Key.DirectionCenter)
        waitFor { exists("grid") && focusedIn("grid") }
        // Onto the first poster, then up onto the filter: the key goes down, then up a moment later, as on a remote.
        pressUntil(Key.DirectionDown) { focused() == "Northern Lights" }
        focusedInteraction().performKeyInput { keyDown(Key.DirectionUp) }
        settle()
        focusedInteraction().performKeyInput { keyUp(Key.DirectionUp) }
        settle()
        assertTrue("the focus is on ${focused()}", editing())
        focusedInteraction().performTextInput("north")
        settle(1_000)
        virtualKey(Key.DirectionDown)
        assertTrue("the focus is on ${focused()}", editing())
        keyboardActionKey()
        assertEquals("the focus is on ${if (editing()) "the filter" else focused()}", "Northern Lights", focused())
    }

    /**
     * Which categories are shown, the same: the filter's keyboard action goes on to the next field (a word to hide),
     * whose action adds the word and goes on to the categories.
     */
    @Test
    fun theCategoriesFieldsGoOnWithTheKeyboardsAction() {
        start(answers = emptyMap(), stored = Fixtures.content)
        openFromDrawer("tabMovies")
        pressUntil(Key.DirectionUp) { focusedTag() == "categoryFilter" }
        press(Key.DirectionCenter)
        waitFor { exists("categories") && focusedIn("categories") }
        pressUntil(Key.DirectionDown) { editing() }
        virtualKey(Key.DirectionDown)
        assertTrue("the focus is on ${focused()}", editing() && focusedTag() != "wordToHide")
        keyboardActionKey()
        assertEquals("the focus is on ${focused()}", "wordToHide", focusedTag())
        focusedInteraction().performTextInput("trailer")
        settle()
        keyboardActionKey()
        waitFor { "trailer" in repository.state.value.library.hiddenWords }
        assertTrue("the focus is on ${focused()}", focusedIn("categories") && !editing())
    }

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
