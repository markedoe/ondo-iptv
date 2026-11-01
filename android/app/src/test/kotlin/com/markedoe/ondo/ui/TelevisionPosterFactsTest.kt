package com.markedoe.ondo.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.library.InMemoryMetadataStore
import com.markedoe.ondo.core.library.MetadataKey
import com.markedoe.ondo.core.library.TitleMetadata
import com.markedoe.ondo.core.library.TmdbGenre
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.theme.OndoTheme
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

/** Above a category's posters on a TV, the header shows the focused one's rating and genres: TMDb's kept on the device, else the provider's. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TelevisionPosterFactsTest {
    /** The chips above the first row: Up from its smaller posters, under the header, lands on one of them. */
    private val chipsAboveTheRows = setOf("Categories", "All", "Drama")

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    private val movieTitles = Fixtures.content.movies.map { it.name }.toSet()

    private fun start() {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(fetchedAt = System.currentTimeMillis()))
        // What TMDb said of Northern Lights, kept on the device from an earlier launch.
        val metadata = InMemoryMetadataStore()
        val northern = Fixtures.northernLights
        metadata.save(
            listOf(
                TitleMetadata(
                    MetadataKey.title(false, northern.name, northern.year)!!, "movie", tmdbId = 11, voteAverage = 7.3, voteCount = 950,
                    fetchedAt = System.currentTimeMillis(), genres = listOf(TmdbGenre(18, "Drama"), TmdbGenre(10749, "Romance"), TmdbGenre(36, "History")),
                ),
            ),
        )
        val repository = OndoRepository(store, FakeHttp(emptyMap()), CoroutineScope(SupervisorJob() + Dispatchers.Main), metadata = metadata)
        val viewModel = OndoViewModel(repository, Labels(), minutes = flowOf(Fixtures.NOW))
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        compose.waitUntil(10_000) { exists("home") }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
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

    private fun waitFor(timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out; the focus is on ${focused()}" }
            settle(60)
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun focusedTag(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun focused(): String = focusedNode()?.let { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            ?: node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
    } ?: "(nothing)"

    private fun focusedIn(tag: String) = compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().isNotEmpty()

    private fun press(key: Key) {
        compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(key) }
        settle(150)
    }

    private fun pressUntil(key: Key, max: Int = 14, done: () -> Boolean) {
        repeat(max) {
            if (done()) return
            press(key)
        }
        check(done()) { "never reached with $key; the focus is on ${focused()}" }
    }

    /** The texts of the header's nodes tagged [tag]. */
    private fun header(tag: String): List<String> =
        compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("titleHeader")), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }

    /** Along the posters until [title] has the focus. */
    private fun reach(title: String) {
        for (key in listOf(Key.DirectionRight, Key.DirectionLeft, Key.DirectionDown)) {
            repeat(10) {
                if (focused() == title) return
                press(key)
            }
        }
        check(focused() == title) { "never reached $title; the focus is on ${focused()}" }
    }

    @Test
    fun theHeaderShowsTheFocusedPostersRatingAndGenres() {
        start()
        pressUntil(Key.DirectionUp, max = 20) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionRight) { focusedTag() == "tabMovies" }
        press(Key.DirectionCenter)
        waitFor { exists("vod") && !focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionDown) { focused() in movieTitles }
        pressUntil(Key.DirectionUp) { focused() in chipsAboveTheRows }
        pressUntil(if (focused() == "Categories") Key.DirectionRight else Key.DirectionLeft) { focused() == "All" }
        press(Key.DirectionCenter)
        waitFor { exists("grid") && focusedIn("grid") }
        pressUntil(Key.DirectionDown, max = 20) { focused() in movieTitles }
        // TMDb's, kept on the device: its rating with TMDb's badge, its genres.
        reach("Northern Lights")
        waitFor { header("headerTitle") == listOf("Northern Lights") && header("headerFacts").any { "Romance" in it } }
        assertEquals(listOf("7.3"), header("headerRating"))
        assertTrue(exists("headerTmdb"))
        assertEquals(listOf("2025 · Drama, Romance, History"), header("headerFacts"))
        assertEquals(listOf(Fixtures.northernLights.plot), header("headerPlot"))
        // No TMDb: the provider's rating, and the category for want of a genre (a movie's comes with its page).
        reach("Glass City")
        waitFor { header("headerTitle") == listOf("Glass City") }
        assertEquals(listOf("★ 7.1"), header("headerRating"))
        assertTrue(!exists("headerTmdb"))
        assertEquals(listOf("2024 · Science Fiction"), header("headerFacts"))
    }
}
