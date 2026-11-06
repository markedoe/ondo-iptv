package com.markedoe.ondo.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.library.Selections
import com.markedoe.ondo.core.sync.SyncSelection
import com.markedoe.ondo.ui.SelectionRows
import com.markedoe.ondo.ui.screens.SelectionActions
import com.markedoe.ondo.ui.screens.SelectionScreen
import com.markedoe.ondo.ui.screens.SelectionUiState
import com.markedoe.ondo.ui.screens.SelectionsActions
import com.markedoe.ondo.ui.screens.SelectionsScreen
import com.markedoe.ondo.ui.screens.SelectionsUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference screenshots of a new selection on a phone, from the demo catalogue: its name, tabs, All tags, and the tags by
 * group, a genre and a decade chosen; and Settings → My selections ([examples]). The TV's are in [TvScreenshots].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w360dp-h640dp-xxhdpi")
class SelectionScreenshots {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun newSelectionOnAPhone() {
        val state = ShowcaseCatalogue.load(french = false).state()
        val choices = SelectionRows.choices(state, emptyMap())
        val tags = listOfNotNull(choices.genres.firstOrNull()?.first, choices.decades.firstOrNull()?.let(Selections::decade))
        compose.setContent { OndoTheme { SelectionScreen(SelectionUiState(null, "", tags, false, listOf(RowOrder.HOME), choices), SelectionActions()) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$BASELINES/phone/en-US/selection.png", BaselineOptions)
    }

    @Test
    fun mySelectionsOnAPhone() {
        compose.setContent { OndoTheme { SelectionsScreen(SelectionsUiState(examples), SelectionsActions()) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$BASELINES/phone/en-US/selections.png", BaselineOptions)
    }

    companion object {
        /** Three selections: one named, one named by its tags, one needing all of them. */
        val examples = listOf(
            SyncSelection("a", "Weekend adventures", listOf(Selections.genre("Adventure")!!, Selections.genre("Fantasy")!!), SyncSelection.ANY, listOf(RowOrder.HOME, RowOrder.MOVIES), 1),
            SyncSelection("b", "", listOf(Selections.genre("Science-Fiction")!!, Selections.decade(1990)), SyncSelection.ALL, listOf(RowOrder.MOVIES), 1),
            SyncSelection("c", "Award winners", listOf(Selections.award("cannes"), Selections.style("documentary")), SyncSelection.ANY, listOf(RowOrder.HOME, RowOrder.SERIES), 1),
        )
    }
}
