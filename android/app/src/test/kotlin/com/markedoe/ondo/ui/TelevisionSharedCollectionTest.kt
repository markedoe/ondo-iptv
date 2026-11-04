package com.markedoe.ondo.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.CollectionActions
import com.markedoe.ondo.ui.screens.CollectionScreen
import com.markedoe.ondo.ui.screens.CollectionUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Shared with all profiles" switch of a collection with the remote (docs/PROFILES.md, "Shared lists"): reached with
 * the arrows, OK shares the collection, OK again asks before taking it back; a Kids profile has no switch to reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TelevisionSharedCollectionTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val collection = CollectionUiState("c1", "Weekend", movies = listOf(ShowcaseItem.MovieItem(Fixtures.glassCity)), canShare = true)

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun focusedTag(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun press(key: Key) {
        val target = if (focusedNode() != null) compose.onAllNodes(isFocused()).onLast() else compose.onAllNodes(isRoot()).onLast()
        target.performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun pressUntil(key: Key, max: Int = 8, done: () -> Boolean) {
        repeat(max) {
            if (done()) return
            press(key)
        }
        check(done()) { "not reached; the focus is on ${focusedTag()}" }
    }

    private fun show(initial: CollectionUiState, shares: MutableList<Boolean>) {
        var inputModes: InputModeManager? = null
        var state by mutableStateOf(initial)
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                CollectionScreen(state, CollectionActions(onShare = { shared -> shares += shared; state = state.copy(shared = shared) }))
            }
        }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.waitForIdle()
    }

    @Test
    fun theRemoteSharesACollectionAndAsksBeforeTakingItBack() {
        val shares = mutableListOf<Boolean>()
        show(collection, shares)
        pressUntil(Key.DirectionDown) { focusedTag() == "sharedSwitch" }
        press(Key.DirectionCenter)
        assertEquals(listOf(true), shares)
        // Taking it back: the other profiles lose it, so a confirmation comes first.
        press(Key.DirectionCenter)
        compose.onNodeWithText("Stop sharing this collection?").assertExists()
        assertEquals(listOf(true), shares)
        // The dialog's buttons sit side by side (Cancel, Stop sharing): into the dialog, then across.
        if (focusedTag() != "confirmUnshare") press(Key.DirectionDown)
        pressUntil(Key.DirectionRight, max = 4) { focusedTag() == "confirmUnshare" }
        press(Key.DirectionCenter)
        assertEquals(listOf(true, false), shares)
    }

    @Test
    fun aKidsProfileHasNoSwitchToReach() {
        val shares = mutableListOf<Boolean>()
        show(collection.copy(shared = true, canEdit = false, canShare = false), shares)
        repeat(6) { press(Key.DirectionDown) }
        assertFalse(compose.onAllNodesWithTag("sharedSwitch").fetchSemanticsNodes().isNotEmpty())
        assertFalse(compose.onAllNodesWithTag("collectionMenu").fetchSemanticsNodes().isNotEmpty())
        assertEquals(emptyList<Boolean>(), shares)
    }
}
