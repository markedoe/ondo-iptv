package com.markedoe.ondo.ui.tv

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android TV and Google TV: the remote's five-way pad moves a visible focus from item to item (Google's TV app
 * quality, TV-DP), and nothing sits in the 5 % the screen may crop (overscan, TV-OV).
 */
@Composable
fun isTelevision(): Boolean = LocalConfiguration.current.isTelevision

val Configuration.isTelevision: Boolean
    get() = uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

/**
 * How a row or a list scrolls to the focused item on a TV: its leading edge to 30 % of the way, as the TV's own
 * screens do (the default of Compose on a device with the Leanback feature, made explicit), never to the very edge
 * where its frame would be cut.
 */
@ExperimentalFoundationApi
val TvScrolling: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val pivot = 0.3f * containerSize
        // An item taller than the rest of the way from the pivot is aligned with the end instead (when it fits).
        val target = if (size <= containerSize && containerSize - pivot < size) containerSize - size else pivot
        return offset - target
    }
}

/**
 * How a row of cards scrolls on a TV: only once the focused card comes near an edge, and then just enough to show it
 * with the start of the next one beside it (an eighth of the row). Along the cards on screen, only the focus moves;
 * a row that slid at every press, the focus staying in place, made the cards jump under it.
 */
@ExperimentalFoundationApi
val TvRowScrolling: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = containerSize / 8
        val trailing = offset + size - (containerSize - margin)
        return when {
            // Wider than the space between the margins: its leading edge at the margin.
            size > containerSize - 2 * margin -> offset - margin
            offset < margin -> offset - margin
            trailing > 0 -> trailing
            else -> 0f
        }
    }
}

/** [content]'s rows scroll as [TvRowScrolling] says on a TV, where the pages scroll as [TvScrolling] says; elsewhere, as they are. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvRow(content: @Composable () -> Unit) {
    if (LocalBringIntoViewSpec.current === TvScrolling) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides TvRowScrolling, content = content)
    } else {
        content()
    }
}

/** The margins Google asks TV layouts to keep clear (5 % of a 960 × 540 dp screen). */
val TvSafeArea = PaddingValues(horizontal = 48.dp, vertical = 27.dp)

/**
 * The whole app on a TV: Compose for TV's theme in Ondo's colours, the dark ground across the whole screen, and lists
 * scrolling the focused item to a third of the way ([TvScrolling]). The pages keep clear of the 5 % the screen may crop
 * themselves ([tvSafeArea]), so pictures can still run to the edges. Elsewhere, [content] as it is.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TelevisionFrame(content: @Composable () -> Unit) {
    if (!isTelevision()) {
        content()
        return
    }
    val heldOk = remember { HeldOk() }
    OndoTvTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).dropsRestOfHeldOk(heldOk)) {
            // Material's text outside a surface takes the light colour of the dark ground (no scaffold around pages).
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides TvScrolling,
                LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                LocalKeyboardHandoff provides remember { KeyboardHandoff() },
                LocalHeldOk provides heldOk,
                content = content,
            )
        }
    }
}

/**
 * OK held on an item that leaves the page with its long press (a channel taken out of My List while My List is
 * shown): the rest of that press, OK repeated then let go, would reach the item that takes the focus next, as a click
 * or as a long press of its own. The long press [arm]s it, and the TV's frame drops OK from then until it is let go.
 * A new press of OK works as always.
 */
class HeldOk {
    internal var armed = false

    /** Only when the item leaves: the item that stays needs the rest of its press (its own key up). */
    fun arm() {
        armed = true
    }
}

/** The TV's [HeldOk]; none elsewhere. */
val LocalHeldOk = staticCompositionLocalOf<HeldOk?> { null }

private fun Modifier.dropsRestOfHeldOk(held: HeldOk): Modifier = onPreviewKeyEvent { event ->
    when {
        !held.armed || !event.isOk -> false
        // A new press: the held one ended where it could not be seen (a dialog's window).
        event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 -> {
            held.armed = false
            false
        }
        else -> {
            if (event.type == KeyEventType.KeyUp) held.armed = false
            true
        }
    }
}

private val KeyEvent.isOk: Boolean
    get() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * On a TV, keeps a page clear of the 5 % the screen may crop (TV-OV), and of the closed drawer when [besideDrawer].
 * Elsewhere, nothing.
 */
fun Modifier.tvSafeArea(besideDrawer: Boolean = false): Modifier = composed {
    if (!isTelevision()) return@composed this
    padding(
        start = if (besideDrawer) TvTokens.ContentStart else TvTokens.EdgeEnd,
        top = TvTokens.EdgeTop,
        end = TvTokens.EdgeEnd,
        bottom = TvTokens.EdgeTop,
    )
}

/**
 * The state layer Material components draw when they have the focus, strong enough to be seen from the sofa
 * (Material's own is 10 %), for every device: a keyboard or a remote moves the focus on phones and tablets too.
 */
private val FocusRipple = RippleConfiguration(
    rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.32f, hoveredAlpha = 0.08f, pressedAlpha = 0.10f),
)

@Composable
fun ProvideFocusRipple(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRippleConfiguration provides FocusRipple, content = content)
}

/**
 * A frame around the focused item, slightly enlarged, for cards and rows that are not Material components. Put it
 * before the item's `clickable` (and before its `clip`): it follows the focus of what comes after it. Without
 * [frame], the item only grows (buttons that change colour with the focus).
 */
fun Modifier.focusFrame(shape: Shape = RoundedCornerShape(12.dp), grow: Float = 1.06f, frame: Boolean = true): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) grow else 1f, label = "focusScale")
    val color = if (focused && frame) MaterialTheme.colorScheme.onBackground else Color.Transparent
    this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(3.dp, color, shape)
}

/**
 * A single-line text field on a TV ([rememberTvTextField]): give it [readOnly] and [modifier]. Elsewhere, both leave
 * the field as it is.
 */
class TvTextField(val readOnly: Boolean, val modifier: Modifier)

/**
 * A single-line text field on a TV: its keyboard opens when the viewer presses OK on it, not when the remote passes
 * over it (the keyboard would then take the remote's arrows until closed, empty). Until OK the field is read-only:
 * Compose's text fields open the keyboard as soon as they take the focus otherwise. Up and Down move on from the
 * field, from whatever device they come (a phone used as the TV's remote sends its arrows from a virtual device, which
 * Compose keeps in a text field). Elsewhere, nothing changes.
 */
@Composable
fun rememberTvTextField(): TvTextField {
    val television = isTelevision()
    var editing by remember { mutableStateOf(false) }
    if (!television) return TvTextField(readOnly = false, modifier = Modifier)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val handoff = LocalKeyboardHandoff.current
    val modifier = Modifier
        .onFocusChanged {
            // On from the field before with its keyboard open ([rememberNextField]): typing goes on here.
            if (it.isFocused) {
                if (handoff.take()) editing = true
            } else {
                editing = false
            }
        }
        .onPreviewKeyEvent { event ->
            when {
                event.type != KeyEventType.KeyDown -> false
                event.key == Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                event.key == Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                // OK: the field becomes writable and its keyboard opens (again, once closed). Enter too, the first time
                // only: then it is the keyboard's own action key (Done, Next), for the field.
                event.key == Key.DirectionCenter || (!editing && (event.key == Key.Enter || event.key == Key.NumPadEnter)) -> {
                    editing = true
                    keyboard?.show()
                    true
                }
                else -> false
            }
        }
    return TvTextField(readOnly = !editing, modifier = modifier)
}

/**
 * The keyboard's Next key of a form's field: on to the next field as everywhere, and on a TV with the keyboard still
 * open when that one is a text field too ([rememberTvTextField]), the way a form is typed.
 */
@Composable
fun rememberNextField(): KeyboardActions {
    val television = isTelevision()
    val handoff = LocalKeyboardHandoff.current
    return remember(television, handoff) {
        KeyboardActions(onNext = {
            if (television) handoff.give()
            defaultKeyboardAction(ImeAction.Next)
        })
    }
}

/** The keyboard handed from a field to the next one by its Next key, for a moment (the focus moves after it). */
class KeyboardHandoff(private val clock: () -> Long = { SystemClock.uptimeMillis() }) {
    private var until = 0L

    fun give() {
        until = clock() + 1_000
    }

    fun take(): Boolean = (clock() < until).also { until = 0 }
}

val LocalKeyboardHandoff = staticCompositionLocalOf { KeyboardHandoff() }

/**
 * For the keyboard's action key of a single-line field: on a TV, moves the focus from the field to what is below it,
 * and tells whether it did. A phone used as the TV's remote sends its arrows from a virtual device, which Compose keeps
 * in a text field (it moves the focus out only for a physical D-pad): the action key is then the way on. Elsewhere,
 * false.
 */
@Composable
fun rememberLeaveField(): () -> Boolean {
    val television = isTelevision()
    val focusManager = LocalFocusManager.current
    return { television && focusManager.moveFocus(FocusDirection.Down) }
}

/**
 * On a TV, the focus enters [content] when nothing in it has the focus once it shows (a page without its own first
 * item, or one still loading): the remote's first press then moves from something on screen. That place is not one
 * the viewer chose: the page's first item still takes the focus when it shows (see [focusMemory]). Elsewhere,
 * [content] as it is.
 */
@Composable
fun FocusEntry(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (!isTelevision()) {
        Box(modifier) { content() }
        return
    }
    val requester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    val mode = LocalInputModeManager.current.inputMode
    val memory = LocalFocusMemory.current
    // Again when the item with the focus leaves the page with it ([FocusMemory.dropped]).
    LaunchedEffect(mode, memory?.drops) {
        // The page's own first item (or the one to come back to) takes it first; the content may still be loading.
        repeat(50) {
            delay(100)
            if (hasFocus) return@LaunchedEffect
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) memory?.given()
        }
    }
    Box(modifier.focusRequester(requester).onFocusChanged { hasFocus = it.hasFocus }.focusGroup()) { content() }
}

/**
 * Where the focus was on a page, kept across navigation: back from a detail page, the remote lands on the card it
 * left, not on the first one. [restore] is the key to give the focus back to, once.
 */
@Stable
class FocusMemory(key: String? = null) {
    var last: String? = key
        private set
    var restore: String? by mutableStateOf(key)
        private set

    /** Whether [last] is where the viewer moved to, rather than where the focus was put for them ([given]). */
    var chosen: Boolean = true
        private set

    fun focused(key: String) {
        last = key
        chosen = true
    }

    /**
     * [last] is no place the viewer chose: the focus was put there for them, or what had it left the page. The page's
     * first item may take the focus.
     */
    fun given() {
        chosen = false
    }

    /** How many times the item with the focus left the page with it ([dropped]). */
    var drops: Int by mutableIntStateOf(0)
        private set

    /**
     * The item with the focus left the page while it had it (its title gone from the catalogue, its question
     * answered): the focus went to the drawer, or nowhere. The page's first item, or the page ([FocusEntry]), takes it
     * back.
     */
    fun dropped() {
        chosen = false
        drops++
    }

    fun restored() {
        restore = null
    }

    /**
     * Gives the focus to the item [key] now, as when the page comes back to it: the item with the focus is about to
     * leave (a card taken away), and the remote goes on from beside it.
     */
    fun handTo(key: String) {
        restore = key
    }

    companion object {
        val Saver: Saver<FocusMemory, String> = Saver(save = { it.last ?: "" }, restore = { FocusMemory(it.ifEmpty { null }) })
    }
}

@Composable
fun rememberFocusMemory(): FocusMemory = rememberSaveable(saver = FocusMemory.Saver) { FocusMemory() }

/** The page's focus memory; none outside a page that keeps one. */
val LocalFocusMemory = staticCompositionLocalOf<FocusMemory?> { null }

/**
 * Records this item as the page's last focus under [key], and takes the focus back when the page comes back to it.
 * On a TV, the page's first item ([initial]) takes the focus when there is nothing to come back to: no place yet, or
 * one the viewer did not choose ([FocusMemory.chosen]), as when the item with the focus leaves the page with it.
 */
fun Modifier.focusMemory(key: String, initial: Boolean = false): Modifier = composed {
    val memory = LocalFocusMemory.current
    val television = isTelevision()
    val requester = remember { FocusRequester() }
    val restoring = memory?.restore
    // Items take the focus in keyboard mode only (a remote, a keyboard): tried again when the mode changes.
    val mode = LocalInputModeManager.current.inputMode
    LaunchedEffect(restoring, television, mode, memory?.drops) {
        val wanted = when {
            restoring != null -> restoring == key
            else -> television && initial && (memory?.last == null || !memory.chosen)
        }
        if (wanted && runCatching { requester.requestFocus() }.getOrDefault(false)) memory?.restored()
    }
    val focus = remember { HeldFocus() }
    SideEffect { focus.key = key }
    val scope = rememberCoroutineScope()
    // Gone with the focus, unless it was handed on just before ([FocusMemory.handTo]): it then went elsewhere already.
    DisposableEffect(memory) { onDispose { if (focus.held && memory?.last == focus.key) memory?.dropped() } }
    this
        .focusRequester(requester)
        .onFocusChanged {
            val change = ++focus.changes
            if (it.isFocused) {
                focus.held = true
                memory?.focused(key)
            } else if (focus.held) {
                // An item leaving the page loses the focus just before it goes: it still holds it until the next frame.
                scope.launch {
                    withFrameNanos {}
                    if (focus.changes == change) focus.held = false
                }
            }
        }
}

/**
 * A dialog opened while OK is still held (a long press): the rest of that press, OK repeated then let go, is no click
 * on what the dialog focuses first. A new press of OK works as always.
 */
fun Modifier.newOkPressesOnly(): Modifier = composed {
    val press = remember { OkPress() }
    onPreviewKeyEvent { event ->
        if (event.isOk && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) press.new = true
        event.isOk && !press.new
    }
}

private class OkPress {
    var new = false
}

/** Whether an item has the focus, as it leaves the page. */
private class HeldFocus {
    var held = false
    var changes = 0
    var key = ""
}
