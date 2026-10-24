package com.markedoe.ondo.ui.tv

import android.content.res.Configuration
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
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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
    OndoTvTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // Material's text outside a surface takes the light colour of the dark ground (no scaffold around pages).
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides TvScrolling,
                LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}

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
    LaunchedEffect(mode) {
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

    fun restored() {
        restore = null
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
 * one the viewer did not choose ([FocusMemory.chosen]).
 */
fun Modifier.focusMemory(key: String, initial: Boolean = false): Modifier = composed {
    val memory = LocalFocusMemory.current
    val television = isTelevision()
    val requester = remember { FocusRequester() }
    val restoring = memory?.restore
    // Items take the focus in keyboard mode only (a remote, a keyboard): tried again when the mode changes.
    val mode = LocalInputModeManager.current.inputMode
    LaunchedEffect(restoring, television, mode) {
        val wanted = when {
            restoring != null -> restoring == key
            else -> television && initial && (memory?.last == null || !memory.chosen)
        }
        if (wanted && runCatching { requester.requestFocus() }.getOrDefault(false)) memory?.restored()
    }
    this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) memory?.focused(key) }
}
