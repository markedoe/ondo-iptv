package com.markedoe.ondo.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.ReleasedLately
import com.markedoe.ondo.UncaughtErrors
import com.markedoe.ondo.core.library.MetadataStore
import com.markedoe.ondo.core.library.TitleMetadata
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.theme.OndoTheme
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The whole app opened on a TV (a cold start), with titles to continue watching: what the device kept of TMDb (the
 * carousel's release dates) is read a moment after Home is drawn, as on a TV with a large catalogue. Home shows its
 * top once the featured titles come: Play focused, the frame full size, the rows from their first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TelevisionColdStartTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val uncaught = UncaughtErrors()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    /** What the device kept of TMDb, read slowly. */
    private class Slow(private val base: MetadataStore, private val delayMs: Long) : MetadataStore by base {
        override fun namedTitles(): List<TitleMetadata> {
            Thread.sleep(delayMs)
            return base.namedTitles()
        }
    }

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

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun focused(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()?.config?.let {
        it.getOrNull(SemanticsProperties.TestTag) ?: it.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
    }.orEmpty()

    private fun start(keptDelayMs: Long) {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        val content = Fixtures.content.copy(fetchedAt = System.currentTimeMillis())
        store.saveContent(Fixtures.PLAYLIST_ID, content)
        val movies = content.movies.take(2)
        val show = content.series.first()
        store.saveLibrary(
            LibraryState(
                history = movies.mapIndexed { index, movie ->
                    WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, movie.id, title = movie.name, position = 600.0, duration = 6_000.0, lastWatchedAt = Fixtures.NOW - 1_000L - index)
                } + WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.SERIES, show.id, "e1", show.name, position = 300.0, duration = 2_400.0, lastWatchedAt = Fixtures.NOW - 2_000L),
            ),
        )
        val metadata = Slow(ReleasedLately(content = { content }), keptDelayMs)
        val repository = OndoRepository(store, FakeHttp(emptyMap()), CoroutineScope(SupervisorJob() + Dispatchers.Main), metadata = metadata)
        val viewModel = OndoViewModel(repository, Labels(), minutes = flowOf(Fixtures.NOW))
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        // A TV's remote: keyboard mode from the start.
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
    }

    private fun assertAtTheTop() {
        val deadline = System.currentTimeMillis() + 15_000
        while (!exists("heroPlay")) {
            check(System.currentTimeMillis() < deadline) { "no featured titles; the focus on ${focused()}" }
            settle(100)
        }
        settle(2_000)
        assertTrue("Play has the focus, not ${focused()}", focused() == "heroPlay")
        val frame = compose.onNodeWithTag("heroFrame", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the frame full size: ${frame.height}", frame.height >= 260.dp)
        val heroBottom = compose.onNodeWithTag("hero", useUnmergedTree = true).getUnclippedBoundsInRoot().bottom
        val first = compose.onNodeWithTag("shelfResumeMovies", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the rows from their first: continue watching at ${first.top}, the frame ending at $heroBottom", first.top >= heroBottom && first.top <= heroBottom + 24.dp)
    }

    @Test
    fun homeOpensAtItsTopWhenTheFeaturedTitlesComeAMomentLater() {
        start(keptDelayMs = 1_500)
        assertAtTheTop()
    }

    @Test
    fun homeOpensAtItsTopWhenTheFeaturedTitlesComeMuchLater() {
        start(keptDelayMs = 6_000)
        assertAtTheTop()
    }
}
