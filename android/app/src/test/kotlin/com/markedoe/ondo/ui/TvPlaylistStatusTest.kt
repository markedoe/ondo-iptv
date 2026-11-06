package com.markedoe.ondo.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.R
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.SEARCH_TAG
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.TvPlaylistStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A TV's top bar shows when the playlist was updated in one slot before Search: a check and a short age ("5 min"),
 * a spinner in the check's place while the playlist is fetched again, a warning sign when that failed. The slot keeps
 * its width as it changes (nothing in the bar moves), never runs into the tabs or Search in any language, takes no
 * stop of the remote, and gives TalkBack the whole sentence with the playlist's name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvPlaylistStatusTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_760_000_000_000L
    private val name = "Example Playlist"
    private var status by mutableStateOf(TvPlaylistStatus(name, updatedAt = now - 5 * 60_000L, now = now))

    private fun show() {
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                TelevisionFrame {
                    TvNavigationFrame(TopLevel.HOME, showBar = true, onSelect = {}, onSearch = {}, onSettings = {}, profile = Profile.main("Me"), playlist = status) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ageText(): String =
        compose.onNodeWithTag("playlistAge", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString()

    private fun description(): String =
        compose.onNodeWithTag("playlistStatus").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    private fun string(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private val locale get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources.configuration.locales[0]

    /** The slot within the bar, clear of the last tab and of Search (on their side in a right-to-left language). */
    private fun assertClearOfTheTabsAndSearch(rtl: Boolean = false) {
        val slot = bounds("playlistStatus")
        val search = bounds(SEARCH_TAG)
        val lastTab = bounds(TopLevel.entries.last().tag)
        if (rtl) {
            assertTrue("the slot $slot right of Search $search", slot.left >= search.right - 0.5f)
            assertTrue("the slot $slot left of the last tab $lastTab", slot.right <= lastTab.left + 0.5f)
        } else {
            assertTrue("the slot $slot left of Search $search", slot.right <= search.left + 0.5f)
            assertTrue("the slot $slot right of the last tab $lastTab", slot.left >= lastTab.right - 0.5f)
        }
        // The age on one line, whole.
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("playlistAge", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        assertEquals(1, results.single().lineCount)
        assertFalse("the age not cut", results.single().hasVisualOverflow)
        assertTrue("the age inside the slot", bounds("playlistAge").right <= slot.right + 0.5f && bounds("playlistAge").left >= slot.left - 0.5f)
    }

    @Test
    fun aCheckAndAShortAgeNotTheNameNorASentence() {
        show()
        assertTrue(exists("playlistUpdated"))
        assertEquals(shortAge(now - 5 * 60_000L, now, locale), ageText())
        assertTrue("short: ${ageText()}", ageText().contains("5") && ageText().contains("min") && ageText().length <= 6)
        assertTrue("TalkBack: the name and the sentence ($description)", description().let { name in it && "Updated 5 minutes ago" in it })
        assertTrue("nothing else written in the bar", compose.onAllNodes(hasAnyAncestor(hasTestTag("topBar")) and androidx.compose.ui.test.hasText(name, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertClearOfTheTabsAndSearch()
    }

    @Test
    fun updatedThenRefreshingThenUpdatedThenFailedInTheSameSlot() {
        show()
        val slot = bounds("playlistStatus")
        val search = bounds(SEARCH_TAG)
        val lastTab = bounds(TopLevel.entries.last().tag)
        fun assertNothingMoved(step: String) {
            assertEquals("$step: the slot", slot, bounds("playlistStatus"))
            assertEquals("$step: Search", search, bounds(SEARCH_TAG))
            assertEquals("$step: the tabs", lastTab, bounds(TopLevel.entries.last().tag))
        }

        status = status.copy(refreshing = true)
        compose.waitForIdle()
        assertTrue("a spinner while fetched again", exists("playlistRefreshing"))
        assertFalse(exists("playlistUpdated"))
        assertEquals("the age of the last fetch meanwhile", shortAge(now - 5 * 60_000L, now, locale), ageText())
        assertTrue(description().contains(string(R.string.refreshing_playlist)))
        assertNothingMoved("refreshing")

        status = status.copy(refreshing = false, updatedAt = now - 30_000L)
        compose.waitForIdle()
        assertTrue(exists("playlistUpdated"))
        assertFalse(exists("playlistRefreshing"))
        assertEquals(shortAge(now - 30_000L, now, locale), ageText())
        assertTrue("under a minute: ${ageText()}", ageText().startsWith("<"))
        assertNothingMoved("updated")

        status = status.copy(updatedAt = now - 3 * 3_600_000L, failed = true)
        compose.waitForIdle()
        assertTrue("a warning sign once it failed", exists("playlistFailed"))
        assertFalse(exists("playlistUpdated"))
        assertEquals("the last good fetch's age", shortAge(now - 3 * 3_600_000L, now, locale), ageText())
        assertTrue(description().contains(string(R.string.playlist_update_failed)))
        assertNothingMoved("failed")

        status = status.copy(updatedAt = now - 400L * 24 * 3_600_000L, failed = false)
        compose.waitForIdle()
        assertEquals("days at the most", shortAge(now - SHORT_AGE_MAX_DAYS * 24 * 3_600_000L, now, locale), ageText())
        assertNothingMoved("a long time ago")
    }

    @Test
    fun theRemoteGoesFromTheTabsStraightToSearch() {
        show()
        assertTrue(
            "nothing focusable in the slot",
            compose.onAllNodes(isFocusable() and (hasTestTag("playlistStatus") or hasAnyAncestor(hasTestTag("playlistStatus"))), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag(TopLevel.entries.last().tag).requestFocus()
        compose.waitForIdle()
        compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        assertEquals(SEARCH_TAG, compose.onAllNodes(isFocused()).fetchSemanticsNodes().last().config.getOrNull(SemanticsProperties.TestTag))
    }

    @Test
    @Config(qualifiers = "+ar")
    fun inArabicRightToLeft() {
        show()
        assertEquals(shortAge(now - 5 * 60_000L, now, locale), ageText())
        assertClearOfTheTabsAndSearch(rtl = true)
    }

    @Test
    @Config(qualifiers = "+hi")
    fun inHindi() {
        status = status.copy(updatedAt = now - 23 * 3_600_000L)
        show()
        assertClearOfTheTabsAndSearch()
    }

    @Test
    @Config(qualifiers = "+fr")
    fun inFrenchTheLongestAges() {
        status = status.copy(updatedAt = now - SHORT_AGE_MAX_DAYS * 24 * 3_600_000L)
        show()
        assertClearOfTheTabsAndSearch()
        assertTrue("days in French: ${ageText()}", ageText().contains("j"))
    }
}
