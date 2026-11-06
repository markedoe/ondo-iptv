package com.markedoe.ondo.ui

import android.content.Context
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.R
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoRepository.PinCheck
import com.markedoe.ondo.data.SubtitleCredential
import com.markedoe.ondo.data.SubtitleServices
import com.markedoe.ondo.ui.components.SubtitleServicesSettings
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.AddPlaylistScreen
import com.markedoe.ondo.ui.screens.CategoriesActions
import com.markedoe.ondo.ui.screens.CollectionNameDialog
import com.markedoe.ondo.ui.screens.ParentGate
import com.markedoe.ondo.ui.screens.ProfileEditScreen
import com.markedoe.ondo.ui.screens.SearchUiState
import com.markedoe.ondo.ui.screens.SelectionActions
import com.markedoe.ondo.ui.screens.SelectionUiState
import com.markedoe.ondo.ui.screens.SettingsActions
import com.markedoe.ondo.ui.screens.SettingsUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvCategoriesScreen
import com.markedoe.ondo.ui.tv.TvSearchScreen
import com.markedoe.ondo.ui.tv.TvSelectionScreen
import com.markedoe.ondo.ui.tv.TvSettingsScreen
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Every text field of the TV, with the remote, the way the TV's own fields work (Gboard for TV): with the remote on a
 * field, its keyboard stays closed and the field says how to type (a pencil, "Press OK to type"); OK opens the keyboard
 * with the caret at the end of the text already there; Back closes it and leaves the remote on the field; the
 * keyboard's action key goes on as each form wants (Next: the field below, keyboard still open; Done: on below, or
 * the form's own answer). A secret field (key, password, PIN) has its Show button, Right from it. Search alone opens
 * ready to type, its keyboard up, the first time (a page to type in); not when the remote comes back to it.
 *
 * A new TV screen with a text field is added to [surfaces].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w960dp-h540dp-land-television-xhdpi")
class TvTextFieldsEverySurfaceTest(private val surfaceName: String) {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** What the keyboard's action key does once text is typed in a field. */
    sealed interface After {
        /** On to that field, its keyboard still open. */
        data class Next(val tag: String) : After

        /** On to that item. */
        data class To(val tag: String) : After

        /** The keyboard closed, and the remote on below the field. */
        data object Leaves : After

        /** The keyboard closed, the remote still on the field (a PIN tried). */
        data object Stays : After

        /** The keyboard closed with the field's dialog (its answer given). */
        data object Gone : After
    }

    class Field(val tag: String, val typed: String, val after: After, val initial: String = "", val secret: Boolean = false)

    class Surface(
        val name: String,
        /** The lazy list the fields are in, scrolled to them. */
        val list: String?,
        val fields: List<Field>,
        val opensOnEntry: Boolean = false,
        val prepare: TvTextFieldsEverySurfaceTest.() -> Unit = {},
        val content: @Composable () -> Unit,
    )

    companion object {
        private const val HINT = "tvFieldHint"
        private const val REVEAL = "tvFieldReveal"

        private val kids = Profile(id = "22222222-2222-2222-2222-222222222222", name = "Kids", colorIndex = 4, symbol = "pawprint.fill", isKids = true)

        val surfaces: List<Surface> = listOf(
            Surface(
                "search", "search",
                listOf(Field("searchField", "s", After.Leaves, initial = "glass")),
                opensOnEntry = true,
            ) {
                var query by remember { mutableStateOf("glass") }
                TvSearchScreen(SearchUiState(query = query, movies = listOf(ShowcaseItem.MovieItem(Fixtures.glassCity))), { query = it }, {}, {}, {}, {})
            },
            Surface(
                "settingsTmdb", "settingsPane",
                listOf(Field("tmdbTokenField", "TOKEN", After.Leaves, secret = true)),
                prepare = {
                    compose.onNodeWithTag("settingsCatalogue").requestFocus()
                    settle()
                },
            ) { TvSettingsScreen(SettingsUiState(), SettingsActions()) },
            Surface(
                "subtitleServices", null,
                listOf(
                    Field("subtitleField-${SubtitleCredential.OPEN_SUBTITLES_KEY.name}", "KEY", After.Next("subtitleField-${SubtitleCredential.OPEN_SUBTITLES_USERNAME.name}"), secret = true),
                    Field("subtitleField-${SubtitleCredential.OPEN_SUBTITLES_USERNAME.name}", "viewer", After.Next("subtitleField-${SubtitleCredential.OPEN_SUBTITLES_PASSWORD.name}")),
                    Field("subtitleField-${SubtitleCredential.OPEN_SUBTITLES_PASSWORD.name}", "secret", After.Leaves, secret = true),
                    Field("subtitleField-${SubtitleCredential.SUBDL_KEY.name}", "KEY", After.Leaves, secret = true),
                    Field("subtitleField-${SubtitleCredential.SUBSOURCE_KEY.name}", "KEY", After.Leaves, secret = true),
                ),
            ) {
                val context = LocalContext.current
                val services = remember {
                    val prefs = context.getSharedPreferences(SubtitleServices.FILE, Context.MODE_PRIVATE).apply { edit().clear().commit() }
                    SubtitleServices(prefs, FakeHttp(emptyMap()), "Ondo test", context.filesDir.resolve("subtitles"), io = Dispatchers.Unconfined)
                }
                Column(Modifier.verticalScroll(rememberScrollState())) { SubtitleServicesSettings(services) }
            },
            Surface(
                "selectionName", "selection",
                listOf(Field("selectionName", "s", After.Leaves, initial = "Old")),
            ) {
                val ui = SelectionUiState("s9", "Old", emptyList(), false, listOf(RowOrder.MOVIES), SelectionRows.choices(Fixtures.state(LibraryState()), emptyMap()))
                TvSelectionScreen(ui, SelectionActions())
            },
            Surface(
                "categories", "categories",
                listOf(
                    Field("categoriesFilter", "dra", After.Leaves),
                    Field("wordToHide", "western", After.Leaves),
                ),
            ) {
                var filter by remember { mutableStateOf("") }
                val state = CategoriesUiState(styles = listOf(StyleRow("manga", 2, hidden = false)), words = listOf(WordRow("bollywood", 1)))
                TvCategoriesScreen(state, ContentType.MOVIE, filter, CategoriesActions(onFilter = { filter = it }))
            },
            Surface(
                "profileEdit", null,
                listOf(
                    Field("profileName", "s", After.Leaves, initial = "Kids"),
                    Field("profilePin", "1234", After.Stays, secret = true),
                ),
            ) { ProfileEditScreen(kids, canDelete = false, onSave = { _, _, _, _, _, _ -> }, onDelete = {}, onBack = {}) },
            Surface(
                "parentPin", null,
                listOf(Field("pinField", "1234", After.Stays, secret = true)),
            ) { ParentGate(locked = true, check = { PinCheck.WRONG }, onBack = {}) {} },
            Surface(
                "addPlaylistXtream", null,
                listOf(
                    Field("playlistNameField", "Home", After.Next("serverField")),
                    Field("serverField", "http://example.com", After.Next("usernameField")),
                    Field("usernameField", "viewer", After.Next("passwordField")),
                    Field("passwordField", "secret", After.To("savePlaylist"), secret = true),
                ),
            ) { AddPlaylistScreen(onSave = {}, onBack = {}, isLoading = false, error = null) },
            Surface(
                "addPlaylistM3u", null,
                listOf(
                    Field("urlField", "http://example.com/list.m3u", After.Next("epgField")),
                    Field("epgField", "http://example.com/guide.xml", After.To("savePlaylist")),
                ),
                prepare = {
                    compose.onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.m3u_link)).performClick()
                    settle()
                },
            ) { AddPlaylistScreen(onSave = {}, onBack = {}, isLoading = false, error = null) },
            Surface(
                "collectionName", null,
                listOf(Field("collectionName", "s", After.Gone, initial = "Collection 1")),
            ) {
                var showing by remember { mutableStateOf(true) }
                if (showing) CollectionNameDialog("New collection", "Collection 1", "Create", onConfirm = { showing = false }, onDismiss = {})
            },
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun names(): List<Array<Any>> = surfaces.map { arrayOf<Any>(it.name) }
    }

    private val surface = surfaces.single { it.name == surfaceName }
    private var focusManager: FocusManager? = null

    private fun settle(ms: Long = 300) {
        val deadline = System.currentTimeMillis() + ms
        do {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            compose.mainClock.advanceTimeBy(20)
            compose.waitForIdle()
        } while (System.currentTimeMillis() < deadline)
    }

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun focusedTag(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private val imm get() = shadowOf(compose.activity.getSystemService(InputMethodManager::class.java))

    private fun keyboardShown(): Boolean = imm.isSoftInputVisible

    private fun hintShown(): Boolean = compose.onAllNodes(hasTestTag(HINT) and hasAnyAncestor(isFocused()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun press(key: Key) {
        compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(key) }
        settle(150)
    }

    private fun waitFor(timeout: Long = 5_000, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "$surfaceName: never $what; the focus is on '${focusedTag()}'" }
            settle(60)
        }
    }

    /** The remote on [tag], from nowhere: the keyboard closed and no field typing before. */
    private fun reach(tag: String) {
        if (!exists(tag)) surface.list?.let { compose.onNodeWithTag(it).performScrollToNode(hasTestTag(tag)) }
        compose.runOnIdle { runCatching { focusManager?.clearFocus(force = true) } }
        settle(100)
        compose.runOnIdle {
            compose.activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        assertFalse(keyboardShown())
        compose.onNodeWithTag(tag).requestFocus()
        settle()
    }

    private fun start() {
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            focusManager = LocalFocusManager.current
            OndoTheme { TelevisionFrame { surface.content() } }
        }
        compose.waitForIdle()
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        settle(500)
        surface.prepare(this)
    }

    @Test
    fun eachTextFieldIsTypedWithTheRemote() {
        start()
        if (surface.opensOnEntry) {
            val first = surface.fields.first().tag
            waitFor(what = "opened on its field with the keyboard up") { focusedTag() == first && keyboardShown() }
            press(Key.Back)
            press(Key.DirectionDown)
            assertFalse("$surfaceName: Down leaves the field", focusedTag() == first)
        }
        for (field in surface.fields) {
            val name = "$surfaceName/${field.tag}"
            reach(field.tag)
            // (a) The remote on the field: the keyboard stays closed.
            assertEquals("$name: the remote is on the field", field.tag, focusedTag())
            assertFalse("$name: the keyboard opened as the remote reached the field", keyboardShown())
            // (e) It says how to type.
            assertTrue("$name: no \"Press OK to type\" with the remote on the field", hintShown())

            // (b) OK: the keyboard, the caret at the end of the text there.
            press(Key.DirectionCenter)
            assertTrue("$name: OK opens the keyboard", keyboardShown())
            val node = focusedNode()!!
            val text = node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
            assertEquals("$name: the text there", field.initial.length, text.length)
            assertEquals("$name: the caret at the end of \"$text\"", TextRange(text.length), node.config.getOrNull(SemanticsProperties.TextSelectionRange))
            assertFalse("$name: no \"Press OK to type\" while typing", hintShown())

            // (d) Back closes the keyboard, the remote still on the field, which reads again.
            press(Key.Back)
            assertFalse("$name: Back closes the keyboard", keyboardShown())
            assertEquals("$name: Back leaves the remote on the field", field.tag, focusedTag())
            assertTrue("$name: the field says how to type again", hintShown())

            // A secret's Show, Right from the field, and Left back.
            if (field.secret) {
                press(Key.DirectionRight)
                assertEquals("$name: Right reaches Show", REVEAL, focusedTag())
                val description = { focusedNode()?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty() }
                assertEquals("$name", "Show characters", description())
                press(Key.DirectionCenter)
                assertEquals("$name: OK shows the characters", "Hide characters", description())
                press(Key.DirectionLeft)
                assertEquals("$name: Left goes back to the field", field.tag, focusedTag())
            }

            // (c) Typed, then the keyboard's action key.
            press(Key.DirectionCenter)
            assertTrue("$name: OK opens the keyboard again", keyboardShown())
            compose.onAllNodes(isFocused()).onLast().performTextInput(field.typed)
            settle(100)
            compose.onAllNodes(isFocused()).onLast().performImeAction()
            settle()
            when (val after = field.after) {
                is After.Next -> {
                    waitFor(what = "went on to ${after.tag}") { focusedTag() == after.tag }
                    assertTrue("$name: Next keeps the keyboard open", keyboardShown())
                }
                is After.To -> waitFor(what = "went on to ${after.tag}") { focusedTag() == after.tag }
                After.Leaves -> {
                    assertFalse("$name: the action key closes the keyboard", keyboardShown())
                    assertTrue("$name: and goes on below (the focus on '${focusedTag()}')", focusedTag() != field.tag && focusedNode() != null)
                }
                After.Stays -> {
                    assertFalse("$name: the action key closes the keyboard", keyboardShown())
                    assertEquals("$name: the remote stays on the field", field.tag, focusedTag())
                }
                After.Gone -> {
                    waitFor(what = "closed its dialog") { !exists(field.tag) }
                    assertFalse("$name: the keyboard closed with the dialog", keyboardShown())
                }
            }
        }
    }
}
