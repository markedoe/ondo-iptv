package com.markedoe.ondo.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.PROFILE_TAG
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
 * Android TV / Google TV: profiles with the remote's keys only (docs/PROFILES.md) — the avatar next to Settings opens
 * the switcher, "Who's watching?" at launch with "Always open this profile" and a PIN, a profile added and edited, and
 * a Kids profile whose settings ask a parent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TelevisionProfilesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    private lateinit var repository: OndoRepository
    private var focusManager: FocusManager? = null

    private val me = Profile.main("Me")
    private val sam = Profile(id = "11111111-1111-1111-1111-111111111111", name = "Sam", colorIndex = 3, symbol = "star.fill", pinHash = Profile.hashPin("2468"))
    private val kids = Profile(id = "22222222-2222-2222-2222-222222222222", name = "Kids", colorIndex = 4, symbol = "🦊", isKids = true)

    private fun start(profiles: List<Profile>?, settings: AppSettings = AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID), library: LibraryState? = null, ready: String = "hero") {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(settings)
        profiles?.let(store::saveProfiles)
        library?.let { store.saveLibrary(it) }
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(fetchedAt = System.currentTimeMillis()))
        repository = OndoRepository(store, FakeHttp(emptyMap()), CoroutineScope(SupervisorJob() + Dispatchers.Main), mainProfileName = "Me")
        val viewModel = OndoViewModel(repository, Labels(), minutes = flowOf(Fixtures.NOW))
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            focusManager = LocalFocusManager.current
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        compose.waitUntil(10_000) { exists(ready) }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
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

    private fun waitFor(timeout: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out; the focus is on ${focused()}" }
            settle(60)
        }
    }

    /** A key of the remote, where the focus is; on a text field, an arrow moves the focus as Compose does for a remote. */
    private fun press(key: Key, times: Int = 1) = repeat(times) {
        val focusedNode = focusedNode()
        val direction = mapOf(Key.DirectionUp to FocusDirection.Up, Key.DirectionDown to FocusDirection.Down, Key.DirectionLeft to FocusDirection.Left, Key.DirectionRight to FocusDirection.Right)[key]
        if (focusedNode != null && direction != null && SemanticsProperties.EditableText in focusedNode.config) {
            compose.runOnIdle { focusManager?.moveFocus(direction) }
        } else {
            val target = if (focusedNode != null) focusedInteraction() else compose.onAllNodes(isRoot()).onLast()
            target.performKeyInput { pressKey(key) }
        }
        settle(120)
    }

    private fun pressUntil(key: Key, max: Int = 16, done: () -> Boolean) {
        repeat(max) {
            if (done()) return
            press(key)
        }
        check(done()) { "never reached with $key; the focus is on ${focused()}" }
    }

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun focusedInteraction(): SemanticsNodeInteraction = compose.onAllNodes(isFocused()).onLast()

    private fun focusedTag(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun focused(): String = focusedNode()?.let { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            ?: node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
    } ?: "(nothing)"

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun active() = repository.state.value.activeProfile

    /** From Home: up to the bar, right to the avatar after Settings, OK. */
    private fun openSwitcher() {
        pressUntil(Key.DirectionUp, max = 40) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionRight) { focusedTag() == PROFILE_TAG }
        press(Key.DirectionCenter)
        waitFor { exists("profileSwitcher") && focusedTag().startsWith("profile-") }
    }

    /** OK on the focused PIN field, [pin] typed, Done. */
    private fun typePin(pin: String) {
        waitFor { focusedTag() == "pinField" }
        press(Key.DirectionCenter)
        focusedInteraction().performTextInput(pin)
        focusedInteraction().performImeAction()
        settle()
    }

    @Test
    fun theAvatarNextToSettingsOpensTheSwitcherAndSwitchesProfile() {
        val samsList = LibraryState(favorites = listOf(Favorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.glassCity.id, Fixtures.glassCity.name, addedAt = Fixtures.NOW)))
        // "Always open this profile" on the main one: Home opens, not "Who's watching?".
        // Sam's library as a launch finds it (each profile's library is read at launch).
        AppStore(folder.root).saveLibrary(samsList, sam.libraryDocumentName)
        start(listOf(me, sam.copy(pinHash = null)), AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, launchProfileId = me.id))
        // The avatar is the last item of the bar, right after Settings.
        pressUntil(Key.DirectionUp, max = 40) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionRight) { focusedTag() == SETTINGS_TAG }
        press(Key.DirectionRight)
        assertEquals(PROFILE_TAG, focusedTag())
        press(Key.DirectionCenter)
        waitFor { exists("profileSwitcher") && focusedTag() == "profile-${me.id}" }
        press(Key.DirectionRight)
        assertEquals("profile-${sam.id}", focusedTag())
        press(Key.DirectionCenter)
        waitFor { active().id == sam.id && exists("hero") }
        assertTrue(repository.state.value.library.isFavorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.glassCity.id))
        // Back to the main profile: its library, not Sam's.
        openSwitcher()
        press(Key.DirectionCenter)
        waitFor { active().isMain && exists("hero") }
        assertFalse(repository.state.value.library.isFavorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.glassCity.id))
    }

    @Test
    fun whoIsWatchingAsksAProfilesPinAndCanAlwaysOpenIt() {
        start(listOf(me, sam), ready = "whoIsWatching")
        waitFor { focusedTag() == "profile-${me.id}" }
        // "Always open this profile", below the profiles.
        pressUntil(Key.DirectionDown) { focusedTag() == "alwaysOpen" }
        press(Key.DirectionCenter)
        pressUntil(Key.DirectionUp) { focusedTag().startsWith("profile-") }
        pressUntil(Key.DirectionRight) { focusedTag() == "profile-${sam.id}" }
        press(Key.DirectionCenter)
        // A wrong PIN is refused, the right one opens Sam.
        typePin("1111")
        assertTrue(exists("pinDialog"))
        assertEquals(me.id, active().id)
        typePin("2468")
        waitFor { active().id == sam.id && exists("hero") }
        assertEquals(sam.id, repository.state.value.settings.launchProfileId)
        assertFalse(repository.state.value.needsProfileSelection)
    }

    @Test
    fun aProfileIsAddedAndRenamedWithTheRemote() {
        start(listOf(me))
        // The clock moves with settle() only, as in TelevisionLibraryTest: the profile page keeps a frame scheduled
        // (its focused field), which an auto-advancing clock would follow forever.
        compose.mainClock.autoAdvance = false
        openSwitcher()
        pressUntil(Key.DirectionRight) { focusedTag() == "addProfile" }
        press(Key.DirectionCenter)
        waitFor { exists("profileEdit") && focusedTag() == "profileName" }
        press(Key.DirectionCenter)
        focusedInteraction().performTextInput("Ana")
        settle()
        // Down: the colours (below the field's middle); to the first, then Right twice to the third.
        press(Key.DirectionDown)
        waitFor { focusedTag().startsWith("profileColor-") }
        pressUntil(Key.DirectionLeft) { focusedTag() == "profileColor-0" }
        press(Key.DirectionRight, times = 2)
        assertEquals("profileColor-2", focusedTag())
        press(Key.DirectionCenter)
        pressUntil(Key.DirectionDown) { focusedTag() == "profileKids" }
        press(Key.DirectionCenter)
        pressUntil(Key.DirectionDown) { focusedTag() in setOf("profileCancel", "profileSave") }
        pressUntil(Key.DirectionRight) { focusedTag() == "profileSave" }
        press(Key.DirectionCenter)
        waitFor { repository.state.value.profiles.size == 2 }
        val ana = repository.state.value.profiles.last()
        assertEquals("Ana", ana.name)
        assertEquals(2, ana.colorIndex)
        assertTrue(ana.isKids)
        // Back on the switcher: Edit, then Ana, renames her.
        waitFor { exists("profileSwitcher") }
        pressUntil(Key.DirectionDown) { focusedTag() == "editProfiles" }
        press(Key.DirectionCenter)
        pressUntil(Key.DirectionUp) { focusedTag().startsWith("profile-") }
        pressUntil(Key.DirectionRight) { focusedTag() == "profile-${ana.id}" }
        press(Key.DirectionCenter)
        waitFor { exists("profileEdit") && focusedTag() == "profileName" }
        press(Key.DirectionCenter)
        focusedInteraction().performTextReplacement("Anana")
        settle()
        pressUntil(Key.DirectionDown) { focusedTag() in setOf("profileDelete", "profileCancel", "profileSave") }
        pressUntil(Key.DirectionRight) { focusedTag() == "profileSave" }
        press(Key.DirectionCenter)
        waitFor { repository.state.value.profiles.last().name == "Anana" }
    }

    @Test
    fun aKidsProfilesSettingsAskAParentsPin() {
        start(listOf(me.copy(pinHash = Profile.hashPin("1357")), kids), AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, activeProfileId = kids.id, launchProfileId = kids.id))
        assertTrue(active().isKids)
        pressUntil(Key.DirectionUp, max = 40) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionRight) { focusedTag() == SETTINGS_TAG }
        press(Key.DirectionCenter)
        waitFor { exists("parentGate") }
        assertFalse(exists("settings"))
        typePin("1357")
        waitFor { exists("settings") }
    }
}
