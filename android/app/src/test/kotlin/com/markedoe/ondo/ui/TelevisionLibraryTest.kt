package com.markedoe.ondo.ui

import android.os.Looper
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
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.CrashLog
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.SavedPlaylist
import com.markedoe.ondo.data.SyncDevice
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.SETTINGS_TAG
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android TV / Google TV: what a viewer adds and removes, with the remote's keys only — playlists (M3U and Xtream,
 * checked before they are saved, deleted after a confirmation), My List, favourite channels and collections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TelevisionLibraryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    private lateinit var repository: OndoRepository
    private var focusManager: FocusManager? = null

    private val backup = SavedPlaylist(id = "p2", name = "Backup", url = "http://example.com/backup.m3u", addedAt = Fixtures.NOW + 1)

    private fun start(playlists: List<SavedPlaylist> = listOf(Fixtures.playlist), answers: Map<String, String> = emptyMap(), manualClock: Boolean = false, library: LibraryState? = null) {
        val store = AppStore(folder.root)
        store.savePlaylists(playlists)
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        library?.let(store::saveLibrary)
        playlists.forEach { store.saveContent(it.id, Fixtures.content.copy(fetchedAt = System.currentTimeMillis())) }
        repository = OndoRepository(store, FakeHttp(answers), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        val viewModel = OndoViewModel(repository, Labels(), minutes = flowOf(Fixtures.NOW))
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            focusManager = LocalFocusManager.current
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("hero").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        // The clock moves with settle() only: a spinner turning while a playlist loads never keeps Compose busy.
        if (manualClock) compose.mainClock.autoAdvance = false
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
        } while (System.currentTimeMillis() < deadline)
    }

    private fun waitFor(timeout: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out; the focus is on ${focused()}" }
            settle(60)
        }
    }

    /**
     * A key of the remote, sent where the focus is (a dialog's window included, the topmost window when nothing has
     * the focus). On a text field, an arrow moves the focus as Compose does for a remote (its key events come from a
     * D-pad device; the test's have no device).
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

    /** Presses [key] until [done], at most [max] times. */
    private fun pressUntil(key: Key, max: Int = 12, done: () -> Boolean) {
        repeat(max) {
            if (done()) return
            press(key)
        }
        check(done()) { "never reached with $key; the focus is on ${focused()}" }
    }

    /**
     * The focused item of the topmost window: a dialog's window has its own focus, while the page behind keeps the
     * item that opened it.
     */
    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun focusedInteraction(): SemanticsNodeInteraction = compose.onAllNodes(isFocused()).onLast()

    private fun focusedTag(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun focused(): String = focusedNode()?.let { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            ?: node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
    } ?: "(nothing)"

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun playlistNames() = repository.state.value.playlists.map { it.name }

    /** From Home: the drawer, down to Settings, OK; the remote then moves into the playlists. */
    private fun openSettings() {
        pressUntil(Key.DirectionLeft) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionDown) { focusedTag() == SETTINGS_TAG }
        press(Key.DirectionCenter)
        waitFor { exists("settings") && focusedTag() == "settingsPlaylists" }
        press(Key.DirectionRight)
    }

    @Test
    fun aPlaylistIsDeletedWithTheRemoteAfterAConfirmationThatStartsOnCancel() {
        start(playlists = listOf(Fixtures.playlist, backup))
        openSettings()
        pressUntil(Key.DirectionDown) { focusedTag() in setOf("use-Backup", "delete-Backup") }
        pressUntil(Key.DirectionRight) { focusedTag() == "delete-Backup" }
        // Cancel first: nothing is deleted.
        press(Key.DirectionCenter)
        waitFor { focusedTag() == "confirmCancel" }
        press(Key.DirectionCenter)
        waitFor { !exists("confirmDialog") }
        assertEquals(listOf("Home", "Backup"), playlistNames())
        // Then Delete.
        waitFor { focusedTag() == "delete-Backup" }
        press(Key.DirectionCenter)
        waitFor { focusedTag() == "confirmCancel" }
        press(Key.DirectionRight)
        assertEquals("confirmOk", focusedTag())
        press(Key.DirectionCenter)
        waitFor { playlistNames() == listOf("Home") }
        waitFor { !exists("playlist-Backup") }
        // The remote stays on the page, on something it can press.
        assertTrue("the focus is on ${focused()}", focusedTag().isNotEmpty())
    }

    @Test
    fun syncIsTurnedOnAndItsDevicesShowWithTheRemote() {
        start()
        pressUntil(Key.DirectionLeft) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionDown) { focusedTag() == SETTINGS_TAG }
        press(Key.DirectionCenter)
        waitFor { exists("settings") && focusedTag() == "settingsPlaylists" }
        pressUntil(Key.DirectionDown) { focusedTag() == "settingsSync" }
        waitFor { exists("syncSwitch") }
        press(Key.DirectionRight)
        waitFor { focusedTag() == "syncSwitch" }
        press(Key.DirectionCenter)
        waitFor { repository.state.value.settings.syncOnLocalNetwork }
        // Devices come and go while the page is open, as sessions succeed or fail.
        repository.reportSyncDevices(listOf(SyncDevice("a", "iPhone", syncedAt = Fixtures.NOW, unreachable = false)))
        settle()
        waitFor { exists("syncDevice") }
        repository.reportSyncDevices(listOf(SyncDevice("a", "iPhone", syncedAt = Fixtures.NOW, unreachable = true), SyncDevice("b", "Living room TV", syncedAt = null, unreachable = false)))
        settle()
        repository.reportSyncDevices(emptyList())
        settle()
        // Back to the menu and down again: the page stays.
        press(Key.DirectionLeft)
        pressUntil(Key.DirectionDown) { focusedTag() == "settingsPlayer" }
        pressUntil(Key.DirectionUp) { focusedTag() == "settingsSync" }
        press(Key.DirectionRight)
        waitFor { focusedTag() == "syncSwitch" }
    }

    @Test
    fun theSyncPageOpensWhenSyncIsAlreadyOnWithADevice() {
        start()
        repository.setSyncOnLocalNetwork(true)
        repository.reportSyncDevices(listOf(SyncDevice("1f0e6c1a-2b3c-4d5e-8f90-123456789abc", "iPhone", syncedAt = System.currentTimeMillis(), unreachable = false)))
        settle()
        pressUntil(Key.DirectionLeft) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionDown) { focusedTag() == SETTINGS_TAG }
        press(Key.DirectionCenter)
        waitFor { exists("settings") && focusedTag() == "settingsPlaylists" }
        pressUntil(Key.DirectionDown) { focusedTag() == "settingsSync" }
        waitFor { exists("syncDevice") }
        press(Key.DirectionRight)
        waitFor { focusedTag() == "syncSwitch" }
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        waitFor { !repository.state.value.settings.syncOnLocalNetwork }
    }

    /** The last error that stopped the app shows at the end of About, and the remote brings it into view. */
    @Test
    fun theLastCrashShowsInAboutWithTheRemote() {
        val error = IllegalStateException("Guide http://panel.example.com/xmltv.php?username=demo&password=secret")
        CrashLog.record(File(folder.root, CrashLog.FILE_NAME), "Ondo 1.1.0 (1007) · Android 12 (API 31) · Test TV", "main", error, Fixtures.NOW)
        start()
        pressUntil(Key.DirectionLeft) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionDown) { focusedTag() == SETTINGS_TAG }
        press(Key.DirectionCenter)
        waitFor { exists("settings") && focusedTag() == "settingsPlaylists" }
        pressUntil(Key.DirectionDown) { focusedTag() == "settingsAbout" }
        press(Key.DirectionRight)
        waitFor { focusedTag() == "helpRow" }
        pressUntil(Key.DirectionDown) { focusedTag() == "lastCrash" }
        compose.onNodeWithText("IllegalStateException: Guide http://…", substring = true).assertExists()
        assertTrue(compose.onAllNodesWithText("secret", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theOtherPlaylistIsUsedWithTheRemote() {
        start(playlists = listOf(Fixtures.playlist, backup))
        openSettings()
        pressUntil(Key.DirectionDown) { focusedTag() == "use-Backup" }
        press(Key.DirectionCenter)
        waitFor { repository.state.value.activePlaylistId == "p2" }
        // The playlist in use is refreshed from its own card, not used again.
        waitFor { exists("refresh-Backup") && !exists("use-Backup") }
    }

    private val m3u = """
        #EXTM3U
        #EXTINF:-1 tvg-id="news.test" group-title="News",Test News
        http://example.com/news.m3u8
        #EXTINF:5400 group-title="Movies | Drama",Quiet Water (2021)
        http://example.com/quiet.mp4
    """.trimIndent()

    /** Settings → Add a Playlist, by the remote. */
    private fun openAddPlaylist() {
        openSettings()
        pressUntil(Key.DirectionDown) { focusedTag() == "addPlaylistRow" }
        press(Key.DirectionCenter)
        waitFor { exists("addPlaylistScreen") && focusedTag() != "addPlaylistRow" }
    }

    /** Moves to the field [tag] and types [text] with the TV's keyboard. */
    private fun type(tag: String, text: String) {
        pressUntil(Key.DirectionDown) { focusedTag() == tag }
        focusedInteraction().performTextInput(text)
        settle()
    }

    private fun save() {
        pressUntil(Key.DirectionDown) { focusedTag() == "savePlaylist" }
        press(Key.DirectionCenter)
    }

    @Test
    fun anM3uPlaylistIsAddedWithTheRemote() {
        start(answers = mapOf("http://example.com/new.m3u" to m3u), manualClock = true)
        openAddPlaylist()
        // The second kind: M3U link.
        pressUntil(Key.DirectionDown) { focused() == "M3U link" || focused() == "Xtream Codes" }
        pressUntil(Key.DirectionRight) { focused() == "M3U link" }
        press(Key.DirectionCenter)
        type("urlField", "http://example.com/new.m3u")
        save()
        waitFor { playlistNames().size == 2 }
        val added = repository.state.value.playlists.last()
        assertEquals("example.com", added.name)
        assertEquals(added.id, repository.state.value.activePlaylistId)
        waitFor { !exists("addPlaylistScreen") }
    }

    private val panel = "http://panel.example.com"
    private fun api(password: String, action: String? = null) =
        "$panel/player_api.php?username=viewer&password=$password" + (action?.let { "&action=$it" } ?: "")

    @Test
    fun anXtreamAccountIsCheckedBeforeItIsSavedAndARefusedOneIsNot() {
        start(
            answers = mapOf(
                api("wrong") to """{"user_info":{"auth":0}}""",
                api("secret") to """{"user_info":{"auth":1,"status":"Active","username":"viewer"},"server_info":{}}""",
                api("secret", "get_live_categories") to """[{"category_id":"1","category_name":"News"}]""",
                api("secret", "get_live_streams") to """[{"num":1,"name":"Panel News","stream_id":11,"category_id":"1"}]""",
            ),
            manualClock = true,
        )
        openAddPlaylist()
        // Xtream Codes is the first kind.
        type("serverField", panel)
        type("usernameField", "viewer")
        type("passwordField", "wrong")
        save()
        // Refused by the panel: a message, and nothing saved.
        waitFor { repository.state.value.error != null }
        assertEquals(listOf("Home"), playlistNames())
        assertTrue(exists("addPlaylistScreen"))
        // The right password: saved and in use.
        assertEquals("savePlaylist", focusedTag())
        pressUntil(Key.DirectionUp) { focusedTag() == "passwordField" }
        focusedInteraction().performTextClearance()
        focusedInteraction().performTextInput("secret")
        settle()
        save()
        waitFor { playlistNames().size == 2 }
        val added = repository.state.value.playlists.last()
        assertEquals("viewer", added.username)
        assertEquals(added.id, repository.state.value.activePlaylistId)
        waitFor { repository.state.value.content.channels.map { it.name } == listOf("Panel News") }
    }

    /** Down from Home's Play onto the first poster, OK: its page, on Play. */
    private fun openFirstMovie(): String {
        press(Key.DirectionDown)
        val title = focused()
        press(Key.DirectionCenter)
        waitFor { exists("detail") && focusedTag() == "detailPlay" }
        return title
    }

    private fun inMyList(title: String) = repository.state.value.library.favorites.any { it.kind == ItemKind.MOVIE && it.title == title }

    @Test
    fun myListTakesAMovieAndGivesItBackWithTheRemote() {
        start()
        val title = openFirstMovie()
        pressUntil(Key.DirectionRight) { focusedTag() == "detailMyList" }
        press(Key.DirectionCenter)
        waitFor { inMyList(title) }
        waitFor { focusedTag() == "detailMyList" }
        press(Key.DirectionCenter)
        waitFor { !inMyList(title) }
    }

    @Test
    fun aMovieGoesInAndOutOfACollectionWithTheRemote() {
        // A text field in a dialog keeps Robolectric's Compose busy (the test, not the app), so the collection exists
        // already.
        start(library = LibraryState().creatingCollection("c1", "Weekend", Fixtures.NOW))
        val title = openFirstMovie()
        fun inWeekend() = repository.state.value.library.collections.single { it.name == "Weekend" }.items.any { it.title == title }
        pressUntil(Key.DirectionRight) { focusedTag() == "detailCollection" }
        press(Key.DirectionCenter)
        // The dialog starts on the first collection: OK ticks it.
        waitFor { focusedTag() == "pick-Weekend" }
        press(Key.DirectionCenter)
        waitFor { inWeekend() }
        // OK again: unticked.
        press(Key.DirectionCenter)
        waitFor { !inWeekend() }
        // New collection is right below.
        press(Key.DirectionDown)
        assertEquals("pickNewCollection", focusedTag())
    }

    @Test
    fun holdingOkOnAChannelAddsItToMyListAndHoldingItAgainTakesItOut() {
        start()
        pressUntil(Key.DirectionLeft) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionDown) { focusedTag() == "tabLive" }
        press(Key.DirectionCenter)
        waitFor { exists("live") && !focusedTag().startsWith("tab") }
        val channel = focused()
        fun favourite() = repository.state.value.library.favorites.any { it.kind == ItemKind.CHANNEL }
        repeat(2) { round ->
            focusedInteraction().performKeyInput { keyDown(Key.DirectionCenter) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
            compose.mainClock.advanceTimeBy(1_000)
            focusedInteraction().performKeyInput { keyUp(Key.DirectionCenter) }
            waitFor { favourite() == (round == 0) }
        }
        assertTrue("the channel was ${channel}", channel.isNotEmpty())
    }
}
