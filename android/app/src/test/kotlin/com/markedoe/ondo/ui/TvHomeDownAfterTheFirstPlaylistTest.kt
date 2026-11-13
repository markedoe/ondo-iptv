package com.markedoe.ondo.ui

import android.os.Looper
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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.UncaughtErrors
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.theme.OndoTheme
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android TV: the first playlist typed on the welcome screen with the remote, Down pressed as soon as Home shows Play
 * and the movies' row: the remote goes into the row and stays there (Home's move to its top, as the catalogue comes,
 * never takes it back to Play). A few runs: the catalogue comes at a moment of its own each time.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvHomeDownAfterTheFirstPlaylistTest(private val run: Int) {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val uncaught = UncaughtErrors()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "run {0}")
        fun runs(): List<Array<Any>> = (1..3).map { arrayOf<Any>(it) }
    }

    private lateinit var repository: OndoRepository
    private var focusManager: FocusManager? = null

    private val panel = "http://panel.example.com"

    private class SlowHttp(private val answers: Map<String, String>) : HttpClient {
        val open = MutableStateFlow(true)

        override suspend fun get(url: String, headers: Map<String, String>): ByteArray {
            open.first { it }
            return answers[url]?.encodeToByteArray() ?: throw HttpException.BadStatus(404)
        }
    }

    private lateinit var http: SlowHttp

    private val app = mutableStateOf<OndoViewModel?>(null)

    private fun start(answers: Map<String, String> = smallPanel()) {
        val store = AppStore(folder.root.resolve("ondo"))
        http = SlowHttp(answers)
        repository = OndoRepository(
            store = store,
            http = http,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            downloadFolder = folder.root.resolve("downloads"),
            guideBudgetBytes = Runtime.getRuntime().maxMemory() / 4,
            metadata = com.markedoe.ondo.ReleasedLately(now = System::currentTimeMillis) { repository.state.value.content },
        )
        app.value = OndoViewModel(repository, Labels())
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            focusManager = LocalFocusManager.current
            val viewModel = app.value
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { key(viewModel) { if (viewModel != null) OndoApp(viewModel) } } }
        }
        compose.waitUntil(10_000) { exists("welcome") }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        settle()
    }

    /** As TelevisionFocusTest's: the main thread's clock and Compose's keep pace with real time; how many 20 ms steps it took. */
    private fun settle(ms: Long = 300): Int {
        val deadline = System.currentTimeMillis() + ms
        var steps = 0
        do {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            compose.mainClock.advanceTimeBy(20)
            compose.waitForIdle()
            uncaught.check()
            steps++
        } while (System.currentTimeMillis() < deadline)
        return steps
    }

    private fun waitFor(timeout: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out; the focus is on ${focused()}" }
            settle(60)
        }
    }

    private fun press(key: Key, times: Int = 1): Int {
        var steps = 0
        repeat(times) {
            val focusedNode = focusedNode()
            val direction = mapOf(Key.DirectionUp to FocusDirection.Up, Key.DirectionDown to FocusDirection.Down, Key.DirectionLeft to FocusDirection.Left, Key.DirectionRight to FocusDirection.Right)[key]
            if (focusedNode != null && direction != null && SemanticsProperties.EditableText in focusedNode.config) {
                compose.runOnIdle { focusManager?.moveFocus(direction) }
            } else {
                val target = if (focusedNode != null) focusedInteraction() else compose.onAllNodes(isRoot()).onLast()
                target.performKeyInput { pressKey(key) }
            }
            steps += settle(120)
        }
        return steps
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

    private fun addWithTheRemote() {
        pressUntil(Key.DirectionUp) { focusedTag() == "addPlaylist" }
        press(Key.DirectionCenter)
        waitFor { exists("addPlaylistScreen") }
        for ((tag, text) in listOf("serverField" to panel, "usernameField" to "viewer", "passwordField" to "secret")) {
            pressUntil(Key.DirectionDown) { focusedTag() == tag }
            press(Key.DirectionCenter)
            focusedInteraction().performTextInput(text)
            settle()
        }
        pressUntil(Key.DirectionDown) { focusedTag() == "savePlaylist" }
        press(Key.DirectionCenter)
    }

    @Test
    fun downAsSoonAsHomeShowsGoesIntoTheMoviesRow() {
        start()
        addWithTheRemote()
        waitFor { exists("home") && focusedTag() == "heroPlay" && exists("shelfNewMovies") }
        press(Key.DirectionDown)
        assertTrue("run $run: right after Down, the focus is on ${focused()}", focusedIn("shelfNewMovies"))
        settle(1_000)
        assertTrue("run $run: a second later, the focus is on ${focused()}", focusedIn("shelfNewMovies"))
    }
}
