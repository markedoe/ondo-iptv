package com.markedoe.ondo.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference screenshots of the TV's text fields ([TvTextInput]): a form with a field the remote is on ("Press OK to
 * type", a pencil), then the same field typed in (its caret at the end, the keyboard's icon), beside a field with a
 * value, one with a placeholder and a secret one with its Show button. Compared with [BASELINES]`/tv/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w960dp-h540dp-land-television-xhdpi")
class TvTextFieldScreenshots {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show() {
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                TelevisionFrame {
                    Column(Modifier.padding(48.dp).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        var name by remember { mutableStateOf("Living room") }
                        var server by remember { mutableStateOf("") }
                        var password by remember { mutableStateOf("secret") }
                        var search by remember { mutableStateOf("") }
                        TvTextInput(search, { search = it }, Modifier.testTag("search"), placeholder = "Movies, series, channels, people", leadingIcon = Icons.Rounded.Search)
                        TvTextInput(name, { name = it }, Modifier.testTag("name"), label = "Name")
                        TvTextInput(server, { server = it }, Modifier.testTag("server"), label = "Server", placeholder = "http://provider.example:8080", keyboardType = KeyboardType.Uri)
                        TvTextInput(password, { password = it }, Modifier.testTag("password"), label = "Password", secret = true)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("$BASELINES/tv/en-US/$name.png", BaselineOptions)

    @Test
    fun aFieldWithTheRemoteOnIt() {
        show()
        compose.onNodeWithTag("name").requestFocus()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        capture("text-field-focused")
    }

    @Test
    fun aFieldTypedIn() {
        show()
        compose.onNodeWithTag("name").requestFocus()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("name").performKeyInput { pressKey(Key.DirectionCenter) }
        // Right after OK: the caret shows (it blinks from there).
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        capture("text-field-editing")
    }
}
