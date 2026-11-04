package com.markedoe.ondo.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.ui.screens.RowOrderActions
import com.markedoe.ondo.ui.screens.RowOrderScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference screenshot of Settings → Row order on a phone, from the demo catalogue: the rows of Movies with their
 * handles and switches, Default order at the top. The TV's is in [TvScreenshots].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w360dp-h640dp-xxhdpi")
class RowOrderScreenshots {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rowOrderOnAPhone() {
        val state = ShowcaseCatalogue.load(french = false).state()
        compose.setContent { OndoTheme { RowOrderScreen(UiMappers.rowOrder(state), RowOrderActions()) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$BASELINES/phone/en-US/row-order.png", BaselineOptions)
    }
}
