package com.markedoe.ondo.ui

import android.os.Looper
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.SETTINGS_TAG
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Text fields on a TV, with the remote: the keyboard opens when the viewer presses OK on a field, not when the remote
 * passes over it (the keyboard would then take the remote's arrows until closed, empty), and Up and Down move on from a
 * field. The keys come here without a device, as from a phone used as the TV's remote (a virtual device): the case
 * Compose keeps in a text field on its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TelevisionTextFieldsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    private lateinit var repository: OndoRepository

    /** Categories named the way providers name them: language codes (each in several categories), and a style. */
    private val content = Fixtures.content.copy(
        movieCategories = listOf(
            MediaCategory(Fixtures.drama.id, "|FR| Drame", ContentType.MOVIE),
            MediaCategory(Fixtures.sciFi.id, "|PT| Ficção", ContentType.MOVIE),
            MediaCategory(Fixtures.thriller.id, "Manga Thriller", ContentType.MOVIE),
            Fixtures.adultMovies,
        ),
        seriesCategories = listOf(
            MediaCategory(Fixtures.seriesDrama.id, "|FR| Séries", ContentType.SERIES),
            MediaCategory(Fixtures.seriesMystery.id, "|PT| Mistério", ContentType.SERIES),
        ),
        liveCategories = listOf(
            MediaCategory(Fixtures.news.id, "|FR| Infos", ContentType.LIVE),
            MediaCategory(Fixtures.sports.id, "|PT| Desporto", ContentType.LIVE),
        ),
        // Fresh: no refresh on start.
        fetchedAt = System.currentTimeMillis(),
    )

    private fun start(answers: Map<String, String> = emptyMap()) {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        store.saveContent(Fixtures.PLAYLIST_ID, content)
        repository = OndoRepository(store, FakeHttp(answers), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        val viewModel = OndoViewModel(repository, Labels(), minutes = flowOf(Fixtures.NOW))
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        compose.waitUntil(10_000) { exists("home") }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        settle()
    }

    private fun settle(ms: Long = 300) {
        val deadline = System.currentTimeMillis() + ms
        do {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            compose.mainClock.advanceTimeBy(20)
            compose.waitForIdle()
        } while (System.currentTimeMillis() < deadline)
    }

    private fun waitFor(timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out; the focus is on ${focused()}" }
            settle(60)
        }
    }

    /** A key of the remote, to whatever has the focus, as the TV sends it: nothing steps over a text field here. */
    private fun press(key: Key) {
        compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(key) }
        settle(120)
    }

    private fun pressUntil(key: Key, max: Int = 20, done: () -> Boolean) {
        repeat(max) {
            if (done()) return
            press(key)
        }
        check(done()) { "never reached with $key; the focus is on ${focused()}" }
    }

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun focusedTag(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun inAField(): Boolean = focusedNode()?.let { SemanticsProperties.EditableText in it.config } == true

    private fun focused(): String = focusedNode()?.let { node ->
        (node.config.getOrNull(SemanticsProperties.TestTag) ?: "") + " " +
            (node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()).take(60)
    } ?: "(nothing)"

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun keyboardShown(): Boolean = shadowOf(compose.activity.getSystemService(InputMethodManager::class.java)).isSoftInputVisible

    private fun openSettings() {
        pressUntil(Key.DirectionUp, max = 40) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionRight) { focusedTag() == SETTINGS_TAG }
        press(Key.DirectionCenter)
        waitFor { exists("settings") && focusedTag() == "settingsPlaylists" }
    }

    private fun openCategories() {
        waitFor { focusedNode() != null }
        openSettings()
        pressUntil(Key.DirectionDown) { focusedTag() == "settingsCatalogue" }
        press(Key.DirectionRight)
        waitFor { focusedTag() == "categoriesRow" }
        press(Key.DirectionCenter)
        waitFor { exists("categories") }
        settle(1_000)
    }

    @Test
    fun theRemotePassesTheCategoriesFilterWithoutOpeningTheKeyboard() {
        start()
        openCategories()
        pressUntil(Key.DirectionDown) { inAField() }
        assertFalse("the keyboard opened as the remote reached the filter (${focused()})", keyboardShown())
        press(Key.DirectionDown)
        assertFalse("Down stayed in the filter", inAField())
        assertTrue("Down went on to the languages (${focused()})", focusedTag().startsWith("tag-"))
        assertFalse(keyboardShown())
        // OK on the field opens it.
        pressUntil(Key.DirectionUp) { inAField() }
        press(Key.DirectionCenter)
        assertTrue("OK on the filter opens the keyboard", keyboardShown())
    }

    @Test
    fun aWordToHideIsTypedAfterOkThenTheRemoteGoesOn() {
        start()
        openCategories()
        pressUntil(Key.DirectionDown, max = 30) { focusedTag() == "wordToHide" }
        assertFalse("the keyboard opened as the remote reached the field", keyboardShown())
        press(Key.DirectionCenter)
        assertTrue("OK opens the keyboard", keyboardShown())
        compose.onAllNodes(isFocused()).onLast().performTextInput("Western")
        compose.onAllNodes(isFocused()).onLast().performImeAction()
        settle()
        waitFor { "western" in repository.state.value.library.hiddenWords }
        assertFalse("Done closes the keyboard", keyboardShown())
        assertFalse("and goes on from the field (${focused()})", inAField())
    }

    @Test
    fun theAddPlaylistFormKeepsTheKeyboardFromOneFieldToTheNext() {
        start()
        waitFor { focusedNode() != null }
        openSettings()
        press(Key.DirectionRight)
        pressUntil(Key.DirectionDown) { focusedTag() == "addPlaylistRow" }
        press(Key.DirectionCenter)
        waitFor { exists("addPlaylistScreen") }
        settle(500)
        pressUntil(Key.DirectionDown) { focusedTag() == "serverField" }
        assertFalse("the keyboard opened as the remote reached the field", keyboardShown())
        press(Key.DirectionCenter)
        assertTrue("OK opens the keyboard", keyboardShown())
        compose.onAllNodes(isFocused()).onLast().performTextInput("http://example.com:8080")
        compose.onAllNodes(isFocused()).onLast().performImeAction()
        settle()
        assertEquals("Next goes on to the user name", "usernameField", focusedTag())
        assertTrue("with the keyboard still open", keyboardShown())
    }

    @Test
    fun searchOpensWithTheKeyboardUp() {
        start()
        waitFor { focusedNode() != null }
        pressUntil(Key.DirectionUp, max = 40) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionRight) { focusedTag() == "tabSearch" }
        press(Key.DirectionCenter)
        waitFor { exists("searchField") }
        waitFor { focusedTag() == "searchField" }
        settle(300)
        assertTrue("the field has the remote, its keyboard open", keyboardShown())
    }

    /** The TMDb token typed with the remote: OK opens the keyboard, Done checks it with TMDb, Remove takes it away. */
    @Test
    fun aTmdbTokenIsTypedCheckedAndRemovedWithTheRemote() {
        start(mapOf("https://api.themoviedb.org/3/authentication?language=en-US&api_key=GOOD" to """{"success":true}"""))
        waitFor { focusedNode() != null }
        openSettings()
        pressUntil(Key.DirectionDown) { focusedTag() == "settingsCatalogue" }
        press(Key.DirectionRight)
        pressUntil(Key.DirectionDown, max = 12) { focusedTag() == "tmdbTokenField" }
        assertFalse("the keyboard opened as the remote reached the field", keyboardShown())
        press(Key.DirectionCenter)
        assertTrue("OK opens the keyboard", keyboardShown())
        compose.onAllNodes(isFocused()).onLast().performTextInput("GOOD")
        compose.onAllNodes(isFocused()).onLast().performImeAction()
        waitFor { repository.state.value.settings.tmdbToken == "GOOD" }
        waitFor { exists("tmdbStatus") }
        compose.onNodeWithText("Accepted by TMDb", substring = true).assertExists()
        assertFalse("Done goes on from the field (${focused()})", inAField())
        pressUntil(Key.DirectionRight, max = 3) { focusedTag() == "tmdbRemove" }
        press(Key.DirectionCenter)
        waitFor { repository.state.value.settings.tmdbToken == null }
        assertFalse(exists("tmdbRemove"))
    }
}
