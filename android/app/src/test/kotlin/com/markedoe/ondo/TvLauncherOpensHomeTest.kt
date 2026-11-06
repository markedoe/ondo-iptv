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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Ondo opened from the TV's home screen always shows Home at its top, Play focused: also when it was left on another
 * page (a title's page) and is still running. The TV's entry ([TvLauncherActivity]) brings [MainActivity] back with the
 * pages over it closed and asks it for Home; a phone's app icon, or any other intent, leaves the page as it is.
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
        scenario?.onActivity { it.intent = phoneLauncher() }
        scenario?.close()
    }

    /** A phone's app icon: MainActivity itself. */
    private fun phoneLauncher() =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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
        scenario = ActivityScenario.launch(phoneLauncher())
        waitFor("Home's Play") { exists("heroPlay") }
        // A remote's key: the TV's keyboard mode, where items take the focus.
        compose.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        waitFor("Play focused") { focusedTag() == "heroPlay" }
    }

    /** The viewer leaves Ondo on a title's page (the remote's Home button), then opens it again with [intent]. */
    private fun leaveOnATitlesPageAndOpen(intent: Intent) {
        compose.onNodeWithTag("heroInfo").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("the title's page") { exists("detailPlay") }
        // The intent, as Android hands it to the running instance (onNewIntent).
        scenario!!.onActivity { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(it, intent) }
        settle(1_500)
    }

    @Test
    fun theTvsHomeScreenOpensHomeWithPlayFocused() {
        start()
        // What TvLauncherActivity starts: MainActivity, the pages over it closed (clear top), its running instance kept.
        leaveOnATitlesPageAndOpen(TvLauncherActivity.homeIntent(app))
        waitFor("Home") { !exists("detailPlay") && exists("heroPlay") }
        waitFor("Play focused") { focusedTag() == "heroPlay" }
    }

    @Test
    fun thePhonesAppIconComesBackWhereItWasLeft() {
        start()
        leaveOnATitlesPageAndOpen(phoneLauncher())
        assertTrue("still on the title's page", exists("detailPlay"))
    }

    @Test
    fun anotherIntentLeavesThePageAsItIs() {
        start()
        leaveOnATitlesPageAndOpen(Intent(Intent.ACTION_VIEW).setClass(app, MainActivity::class.java))
        assertTrue("still on the title's page", exists("detailPlay"))
    }

    @Test
    fun theTvsEntryStartsMainActivityForHomeAndCloses() {
        val entry = Robolectric.buildActivity(TvLauncherActivity::class.java).create().get()
        assertTrue("nothing of its own stays", entry.isFinishing)
        val started = shadowOf(entry).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertTrue("Home asked for", MainActivity.opensHome(started))
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals("the running MainActivity kept, the pages over it closed", flags, started.flags and flags)
        assertFalse(MainActivity.opensHome(phoneLauncher()))
        assertFalse(MainActivity.opensHome(null))
    }

    /** The TV's home screen opens TvLauncherActivity; a phone's icon opens MainActivity, which keeps its launch mode. */
    @Test
    fun theManifestsEntries() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        fun activity(name: String) = Regex("""<activity\s+android:name="\.$name"[^>]*>(.*?)</activity>""", RegexOption.DOT_MATCHES_ALL).find(manifest)?.value.orEmpty()
        val main = activity("MainActivity")
        val tv = activity("TvLauncherActivity")
        assertTrue("MainActivity in the manifest", main.isNotEmpty())
        assertFalse("MainActivity's own launch mode: a phone comes back where it was left", "launchMode" in main)
        assertTrue("a phone's icon opens MainActivity", "android.intent.category.LAUNCHER\"" in main)
        assertFalse("the TV's home screen does not open MainActivity directly", "LEANBACK_LAUNCHER" in main)
        assertTrue("the TV's home screen opens TvLauncherActivity", "LEANBACK_LAUNCHER" in tv)
    }
}
