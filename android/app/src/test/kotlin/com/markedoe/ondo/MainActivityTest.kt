package com.markedoe.ondo

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The real app starts: application, repository, splash screen, theme and the first screen. */
@RunWith(AndroidJUnit4::class)
@Config(application = OndoApplication::class, qualifiers = "w411dp-h891dp-xxhdpi")
class MainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunchWelcomesTheUserWithTheDemoAndTheDisclaimer() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithTag("welcome").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("demoButton").assertExists()
        compose.onNodeWithTag("addPlaylist").assertExists()
        compose.onNodeWithText("player only", substring = true).assertExists()
    }
}
