package com.markedoe.ondo

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.SqliteMetadataStore
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Ondo opened from the TV's launcher always shows Home at its top, Play focused: also when it was left on another page
 * (a title's page) and is still running. The launcher's intent reaches the running app ([MainActivity] is the task's
 * single instance), which goes back to Home; any other intent leaves the page as it is.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = OndoApplication::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvLauncherOpensHomeTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createEmptyComposeRule()

    private val app: OndoApplication = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun close() {
        // The scenario knows its activity by the intent it started it with, which onNewIntent replaced (setIntent).
        scenario?.onActivity { it.intent = launcher() }
        scenario?.close()
    }

    private fun launcher(category: String = Intent.CATEGORY_LEANBACK_LAUNCHER) =
        Intent(Intent.ACTION_MAIN).addCategory(category).setClass(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun settle(ms: Long = 200) {
        val deadline = System.currentTimeMillis() + ms
        do {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            compose.waitForIdle()
        } while (System.currentTimeMillis() < deadline)
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what; the focus on ${focusedTag()}" }
            settle(60)
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun focusedTag(): String =
        compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    /** The app with a saved playlist (its featured titles' release dates kept), opened from the launcher on Home. */
    private fun start() {
        val store = AppStore(File(app.filesDir, "ondo"))
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        val now = System.currentTimeMillis()
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(fetchedAt = now))
        SqliteMetadataStore(app).save(Fixtures.released(Fixtures.content, now).values.toList())
        scenario = ActivityScenario.launch(launcher())
        waitFor("Home's Play") { exists("heroPlay") }
        // A remote's key: the TV's keyboard mode, where items take the focus.
        compose.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        waitFor("Play focused") { focusedTag() == "heroPlay" }
    }

    /** The viewer leaves Ondo on a title's page (the remote's Home button), then opens it again with [intent]. */
    private fun leaveOnATitlesPageAndOpen(intent: Intent) {
        compose.onNodeWithTag("heroInfo").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("the title's page") { exists("detailPlay") }
        // The launcher's intent, as Android hands it to the running instance (onNewIntent).
        scenario!!.onActivity { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(it, intent) }
        settle(1_500)
    }

    @Test
    fun theLeanbackLauncherOpensHomeWithPlayFocused() {
        start()
        leaveOnATitlesPageAndOpen(launcher())
        waitFor("Home") { !exists("detailPlay") && exists("heroPlay") }
        waitFor("Play focused") { focusedTag() == "heroPlay" }
    }

    @Test
    fun thePhonesLauncherIconOpensHomeToo() {
        start()
        leaveOnATitlesPageAndOpen(launcher(Intent.CATEGORY_LAUNCHER))
        waitFor("Home") { !exists("detailPlay") && exists("heroPlay") }
        waitFor("Play focused") { focusedTag() == "heroPlay" }
    }

    @Test
    fun anotherIntentLeavesThePageAsItIs() {
        start()
        leaveOnATitlesPageAndOpen(Intent(Intent.ACTION_VIEW).setClass(app, MainActivity::class.java))
        assertTrue("still on the title's page", exists("detailPlay"))
    }

    @Test
    fun whichIntentsAreTheLaunchers() {
        assertTrue(MainActivity.isLauncherIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)))
        assertTrue(MainActivity.isLauncherIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertFalse(MainActivity.isLauncherIntent(Intent(Intent.ACTION_MAIN)))
        assertFalse(MainActivity.isLauncherIntent(Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertFalse(MainActivity.isLauncherIntent(null))
    }

    /** The launcher's intent reaches the running app: it is the task's only Ondo screen, the player on top of it closed. */
    @Test
    fun mainActivityIsTheTasksSingleInstance() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val main = Regex("""<activity\s+android:name="\.MainActivity"[^>]*>""").find(manifest)?.value.orEmpty()
        assertTrue("MainActivity in the manifest", main.isNotEmpty())
        assertTrue("android:launchMode=\"singleTask\": $main", "android:launchMode=\"singleTask\"" in main)
    }
}
