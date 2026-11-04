package com.markedoe.ondo.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.ui.TopLevel
import com.markedoe.ondo.ui.screens.ProfileEditScreen
import com.markedoe.ondo.ui.screens.ProfilesActions
import com.markedoe.ondo.ui.screens.ProfilesScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.PROFILE_TAG
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.markedoe.ondo.HangGuard

/**
 * Reference screenshots of profiles (docs/PROFILES.md): on a TV, "Who's watching?", the switcher, a profile's page and
 * the bar's avatar next to Settings with the remote on it; on a phone, the switcher. Compared by `verifyRoborazziDebug`
 * with [BASELINES]`/tv/` and [BASELINES]`/phone/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w960dp-h540dp-land-television-xhdpi")
class ProfileScreenshots {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val me = Profile.main("Me")
    private val sam = Profile(id = "11111111-1111-1111-1111-111111111111", name = "Sam", colorIndex = 3, symbol = "star.fill", pinHash = Profile.hashPin("2468"))
    private val kids = Profile(id = "22222222-2222-2222-2222-222222222222", name = "Kids", colorIndex = 4, symbol = "pawprint.fill", isKids = true)
    private val profiles = listOf(me, sam, kids)

    private fun show(television: Boolean = true, content: @Composable () -> Unit) {
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { if (television) TelevisionFrame(content) else content() }
        }
        compose.waitForIdle()
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun capture(folder: String, name: String) = compose.onRoot().captureRoboImage("$BASELINES/$folder/en-US/$name.png", BaselineOptions)

    @Test
    fun whoIsWatching() {
        show { ProfilesScreen(profiles, me, launchProfileId = null, whoIsWatching = true, actions = ProfilesActions()) }
        capture("tv", "profiles-whos-watching")
    }

    @Test
    fun switcher() {
        show { ProfilesScreen(profiles, sam, launchProfileId = sam.id, whoIsWatching = false, actions = ProfilesActions()) }
        compose.onNodeWithTag("profile-${sam.id}").requestFocus()
        compose.mainClock.advanceTimeBy(500)
        capture("tv", "profiles-switcher")
    }

    @Test
    fun profilePage() {
        show { ProfileEditScreen(kids, canDelete = true, onSave = { _, _, _, _, _, _ -> }, onDelete = {}, onBack = {}) }
        capture("tv", "profiles-edit")
    }

    @Test
    fun newProfilePage() {
        show { ProfileEditScreen(null, canDelete = false, onSave = { _, _, _, _, _, _ -> }, onDelete = {}, onBack = {}) }
        capture("tv", "profiles-new")
    }

    @Test
    fun theBarsAvatarNextToSettings() {
        show {
            TvNavigationFrame(TopLevel.HOME, showBar = true, onSelect = {}, onSearch = {}, onSettings = {}, profile = sam, onProfiles = {}) {
                Box(Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithTag(PROFILE_TAG).requestFocus()
        compose.mainClock.advanceTimeBy(500)
        capture("tv", "profiles-bar-avatar")
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun phoneSwitcher() {
        show(television = false) { ProfilesScreen(profiles, me, launchProfileId = null, whoIsWatching = false, actions = ProfilesActions()) }
        capture("phone", "profiles-switcher")
    }
}
