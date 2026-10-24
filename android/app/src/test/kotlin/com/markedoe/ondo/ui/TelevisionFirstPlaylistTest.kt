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
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.UncaughtErrors
import com.markedoe.ondo.core.sync.SyncPlaylist
import com.markedoe.ondo.core.sync.SyncSnapshot
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.theme.OndoTheme
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * Android TV, first launch: no playlist yet, the welcome page. The first Xtream playlist arrives — typed with the
 * remote, or sent by another device — and every page opens with it, a small panel and one the size of a large
 * provider's. The repository is built as the app builds it; an error no code caught fails the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TelevisionFirstPlaylistTest {
    @get:Rule val uncaught = UncaughtErrors()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    private lateinit var repository: OndoRepository
    private var focusManager: FocusManager? = null

    private val panel = "http://panel.example.com"

    private fun start(answers: Map<String, String>) {
        val store = AppStore(folder.root.resolve("ondo"))
        repository = OndoRepository(
            store = store,
            http = FakeHttp(answers),
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
        compose.waitUntil(10_000) { exists("welcome") }
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
            check(System.currentTimeMillis() < deadline) { "timed out; the focus is on ${focused()}, state ${repository.state.value.let { "loading=${it.isLoading} error=${it.error} playlists=${it.playlists.size}" }}" }
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
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            ?: node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
    } ?: "nothing"

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun type(tag: String, text: String) {
        pressUntil(Key.DirectionDown) { focusedTag() == tag }
        focusedInteraction().performTextInput(text)
        settle()
    }

    /** Welcome → Add a Playlist → Xtream Codes (the first kind) → Save, with the remote. */
    private fun addWithTheRemote() {
        pressUntil(Key.DirectionUp) { focusedTag() == "addPlaylist" }
        press(Key.DirectionCenter)
        waitFor { exists("addPlaylistScreen") }
        type("serverField", panel)
        type("usernameField", "viewer")
        type("passwordField", "secret")
        pressUntil(Key.DirectionDown) { focusedTag() == "savePlaylist" }
        press(Key.DirectionCenter)
    }

    /** From the page to the drawer, onto the tab at [index] (Home is 0), opened; the focus enters the page. */
    private fun openTab(index: Int, page: String) {
        pressUntil(Key.DirectionLeft) { focusedTag().startsWith("tab") }
        press(Key.DirectionUp, times = 6)
        press(Key.DirectionDown, times = index + 1)
        press(Key.DirectionCenter)
        waitFor { exists(page) && !focusedTag().startsWith("tab") }
    }

    /** On a page of posters: down onto the first poster, OK, its page, Back. */
    private fun openAPosterAndComeBack(page: String) {
        repeat(4) { attempt ->
            if (attempt > 0 || focusedTag() == "categoryFilter") press(Key.DirectionDown)
            press(Key.DirectionCenter)
            settle(500)
            if (exists("detail")) {
                settle(1_000)
                press(Key.DirectionDown, times = 2)
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                waitFor { exists(page) && !exists("detail") }
                return
            }
            if (!exists(page)) {
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                waitFor { exists(page) }
            }
        }
        error("no poster opened on $page; the focus is on ${focused()}")
    }

    /** Home, every tab, a movie's page and a series' page, the guide: what a viewer opens first with a new playlist. */
    private fun walkEveryPage() {
        waitFor { exists("home") }
        settle(1_000)
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionRight, times = 3)
        for ((index, page) in listOf(1 to "vod", 2 to "vod", 3 to "live", 4 to "myList")) {
            openTab(index, page)
            settle(500)
            if (page == "vod") openAPosterAndComeBack(page)
            // Down the page a little, as a viewer looks around.
            press(Key.DirectionDown, times = 3)
            press(Key.DirectionRight, times = 3)
        }
        // Live TV → the guide.
        openTab(3, "live")
        // Up to the page's buttons (Search, Guide, Categories), right onto the guide's.
        pressUntil(Key.DirectionUp, max = 20) { focusedTag() == "openGuide" || focusedTag() == "categoryFilter" || focused() == "Search" }
        pressUntil(if (focusedTag() == "categoryFilter") Key.DirectionLeft else Key.DirectionRight) { focusedTag() == "openGuide" }
        press(Key.DirectionCenter)
        waitFor { exists("guide") }
        settle(1_000)
        press(Key.DirectionDown, times = 3)
        press(Key.DirectionRight, times = 3)
        settle(500)
    }

    private fun api(action: String? = null) = "$panel/player_api.php?username=viewer&password=secret" + (action?.let { "&action=$it" } ?: "")

    private val account = """{"user_info":{"username":"viewer","password":"secret","auth":1,"status":"Active","exp_date":"1900000000","is_trial":"0","active_cons":"0","max_connections":"1","allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"panel.example.com","port":"80","timezone":"UTC"}}"""

    private fun smallPanel() = mapOf(
        api() to account,
        api("get_live_categories") to """[{"category_id":"1","category_name":"News"}]""",
        api("get_live_streams") to """[{"num":1,"name":"Panel News","stream_id":11,"category_id":"1","epg_channel_id":"news.example"}]""",
        api("get_vod_categories") to """[{"category_id":"2","category_name":"Drama"}]""",
        api("get_vod_streams") to """[{"num":1,"name":"Quiet Water (2021)","stream_id":21,"category_id":"2","container_extension":"mkv"}]""",
        api("get_series_categories") to """[{"category_id":"3","category_name":"Shows"}]""",
        api("get_series") to """[{"num":1,"name":"Harbor","series_id":31,"category_id":"3"}]""",
    )

    /** A large provider: thousands of channels, tens of thousands of movies, a guide for its channels. */
    private fun largePanel(live: Int = 5_000, movies: Int = 20_000, series: Int = 5_000, guideChannels: Int = 600): Map<String, String> {
        val categories = 200
        fun name(prefix: String, i: Int) = if (i % 5 == 0) "AR: فيلم رقم $i" else "$prefix $i (${1980 + i % 45})"
        fun array(count: Int, entry: StringBuilder.(Int) -> Unit) = buildString {
            append('[')
            for (i in 1..count) {
                if (i > 1) append(',')
                entry(i)
            }
            append(']')
        }
        val now = System.currentTimeMillis()
        val format = java.text.SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val guide = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
            for (c in 1..guideChannels) append("<channel id=\"channel$c.example\"><display-name>Channel $c</display-name></channel>\n")
            val step = 3_600_000L
            for (c in 1..guideChannels) {
                for (p in -24 until 48) {
                    val begin = now - now % step + p * step
                    append("<programme start=\"${format.format(begin)}\" stop=\"${format.format(begin + step)}\" channel=\"channel$c.example\"><title>Programme $p of channel $c</title><desc>A made-up description of programme $p.</desc></programme>\n")
                }
            }
            append("</tv>\n")
        }
        return mapOf(
            api() to account,
            api("get_live_categories") to array(categories) { append("""{"category_id":"$it","category_name":"FR | Category $it","parent_id":0}""") },
            api("get_vod_categories") to array(categories) { append("""{"category_id":"$it","category_name":"FR | Films $it","parent_id":0}""") },
            api("get_series_categories") to array(categories) { append("""{"category_id":"$it","category_name":"FR | Series $it","parent_id":0}""") },
            api("get_live_streams") to array(live) { i ->
                append("""{"num":$i,"name":"${name("FR: Channel", i)} HD","stream_type":"live","stream_id":$i,"stream_icon":"http://img.example.com/logos/$i.png","epg_channel_id":"channel$i.example","added":"1700000000","is_adult":"0","category_id":"${i % categories + 1}","category_ids":[${i % categories + 1}],"custom_sid":"","tv_archive":${i % 7 / 6},"direct_source":"","tv_archive_duration":${i % 7 / 6 * 3}}""")
            },
            api("get_vod_streams") to array(movies) { i ->
                append("""{"num":$i,"name":"${name("Movie", i)}","title":"${name("Movie", i)}","year":"${1980 + i % 45}","stream_type":"movie","stream_id":$i,"stream_icon":"https://img.example.com/t/p/w600_and_h900_bestv2/poster$i.jpg","rating":"${i % 10}.${i % 7}","rating_5based":${i % 5}.5,"added":"${1600000000 + i}","is_adult":"0","category_id":"${i % categories + 1}","category_ids":[${i % categories + 1}],"container_extension":"mkv","custom_sid":"","direct_source":""}""")
            },
            api("get_series") to array(series) { i ->
                append("""{"num":$i,"name":"${name("Series", i)}","title":"${name("Series", i)}","year":"${1980 + i % 45}","stream_type":"series","series_id":$i,"cover":"https://img.example.com/t/p/w600_and_h900_bestv2/cover$i.jpg","plot":"A made-up story number $i.","cast":"Actor One, Actor Two","director":"Director $i","genre":"Drama / Comedy","releaseDate":"${1980 + i % 45}-05-01","last_modified":"${1600000000 + i}","rating":"7","rating_5based":3.5,"backdrop_path":["https://img.example.com/t/p/w1280/backdrop$i.jpg"],"youtube_trailer":"","episode_run_time":"45","category_id":"${i % categories + 1}","category_ids":[${i % categories + 1}]}""")
            },
            "$panel/xmltv.php?username=viewer&password=secret" to guide,
        )
    }

    @Test
    fun theFirstXtreamPlaylistIsTypedWithTheRemoteAndEveryPageOpens() {
        start(smallPanel())
        addWithTheRemote()
        waitFor { repository.state.value.playlists.size == 1 }
        walkEveryPage()
        assertEquals(listOf("Panel News"), repository.state.value.content.channels.map { it.name })
    }

    @Test
    fun aLargeFirstXtreamPlaylistIsTypedWithTheRemoteAndEveryPageOpens() {
        start(largePanel())
        addWithTheRemote()
        waitFor(timeout = 120_000) { repository.state.value.playlists.size == 1 }
        walkEveryPage()
        assertEquals(20_000, repository.state.value.content.movies.size)
        waitFor(timeout = 60_000) { repository.state.value.guide != null }
        settle(1_000)
    }

    /** What sync does when another device sends its playlists: on the main thread, as LocalSync calls it. */
    private fun receiveBySync() {
        val sent = SyncSnapshot(
            device = "11111111-2222-3333-4444-555555555555",
            deviceName = "Phone",
            playlists = listOf(SyncPlaylist(id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", name = "Panel", kind = SyncPlaylist.XTREAM, url = panel, username = "viewer", password = "secret", addedAt = 1, updatedAt = 1)),
        )
        compose.runOnIdle { repository.mergeSync(sent, "Phone") }
    }

    @Test
    fun theFirstXtreamPlaylistArrivesBySyncOnTheWelcomePageAndEveryPageOpens() {
        start(smallPanel())
        receiveBySync()
        waitFor { repository.state.value.content.channels.isNotEmpty() }
        walkEveryPage()
    }

    @Test
    fun aLargeFirstXtreamPlaylistArrivesBySyncOnTheWelcomePageAndEveryPageOpens() {
        start(largePanel())
        receiveBySync()
        waitFor(timeout = 120_000) { repository.state.value.content.movies.size == 20_000 }
        walkEveryPage()
        waitFor(timeout = 60_000) { repository.state.value.guide != null }
        settle(1_000)
        assertTrue(repository.state.value.error == null)
    }
}
