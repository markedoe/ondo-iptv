package com.markedoe.ondo.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.library.Selections
import com.markedoe.ondo.ui.SelectionRows
import com.markedoe.ondo.ui.screens.SelectionActions
import com.markedoe.ondo.ui.screens.SelectionScreen
import com.markedoe.ondo.ui.screens.SelectionUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference screenshot of a new selection on a phone, from the demo catalogue: its name, tabs, All tags, and the tags by
 * group, a genre and a decade chosen. The TV's is in [TvScreenshots].
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
}
