package com.markedoe.ondo.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.ui.screens.label
import com.markedoe.ondo.ui.theme.OndoColors
import kotlin.math.roundToInt

/**
 * The order of a page's posters, the same for every page that has one ([PosterSort], the shared setting): [sort]
 * ticked; and whether finished movies are left out, where that applies ([hideWatched] null: it does not).
 */
class TvSortChoice(
    val sort: PosterSort,
    val onSort: (PosterSort) -> Unit,
    val hideWatched: Boolean? = null,
    val onHideWatched: (Boolean) -> Unit = {},
)

/**
 * The Sort chip of a page's label line ([TvSortChips]): its [requester], and whether the remote is coming up to it from
 * the first row of posters ([fromFirstRow]): with the chips themselves, the only way into them.
 */
@Stable
internal class SortChip {
    val requester = FocusRequester()
    var fromFirstRow by mutableStateOf(false)
}

/** The page's [SortChip]; none on a page without one. */
internal val LocalSortChip = staticCompositionLocalOf<SortChip?> { null }

/**
 * On a page with sort chips on its label line, Up from a poster of the first row goes to the Sort chip, from any column
 * (the chips stand at the line's far end, above the last posters only). Elsewhere, nothing.
 */
fun Modifier.upToTheSortChip(): Modifier = composed {
    val chip = LocalSortChip.current ?: return@composed this
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp) return@onPreviewKeyEvent false
        // The chips take the remote from here: Up lands on Sort rather than on the top bar.
        chip.fromFirstRow = true
        runCatching { chip.requester.requestFocus() }.getOrDefault(false).also { moved -> if (!moved) chip.fromFirstRow = false }
    }
}

/** The tag of the Sort chip. */
const val SORT_CHIP_TAG = "sortChip"

/** The tag of the Hide watched chip. */
const val HIDE_WATCHED_CHIP_TAG = "hideWatchedChip"

/**
 * The chips at the end of a page's label line, above its posters: "Sort: <the order shown>", which opens the orders
 * ([onOpen], [TvSortMenu]), and Hide watched where it applies, which OK turns on or off. They take the remote only
 * when it comes from the page's posters ([reachable]): Down from the top bar lands on the first poster, never on them.
 */
@Composable
internal fun TvSortChips(
    choice: TvSortChoice,
    sortRequester: FocusRequester,
    reachable: () -> Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    sortModifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val order = stringResource(choice.sort.label)
        SortChip(
            stringResource(R.string.sort_by, order),
            Icons.AutoMirrored.Rounded.Sort,
            selected = false,
            reachable = reachable,
            onClick = onOpen,
            modifier = sortModifier.focusRequester(sortRequester).testTag(SORT_CHIP_TAG),
        )
        choice.hideWatched?.let { hidden ->
            SortChip(
                stringResource(R.string.hide_watched),
                if (hidden) Icons.Rounded.Check else null,
                selected = hidden,
                reachable = reachable,
                onClick = { choice.onHideWatched(!hidden) },
                modifier = Modifier.testTag(HIDE_WATCHED_CHIP_TAG),
            )
        }
    }
}

/**
 * A chip of the label line: grey, white when it is on (Hide watched); the remote's focus puts a white ring around it
 * and makes it grow ([tvFocusRing]), on or not, as the other chips.
 */
@Composable
private fun SortChip(text: String, icon: ImageVector?, selected: Boolean, reachable: () -> Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val container = if (selected) colors.onBackground else OndoColors.SurfaceHigh
    val content = if (selected) colors.background else colors.onBackground.copy(alpha = 0.86f)
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .focusProperties { canFocus = focused || reachable() }
            .semantics { this.selected = selected }
            .tvFocusRing(focused, chipScale(focused)),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = content,
            focusedContainerColor = container,
            focusedContentColor = content,
            pressedContainerColor = container,
            pressedContentColor = content,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(start = if (icon != null) 8.dp else 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(16.dp))
            Text(text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = CHIP_TEXT_WIDTH))
        }
    }
}

/**
 * The Sort chip's orders, open: a panel drawn in the app's own window over the page, below the chip ([anchor], its
 * bounds in the panel's parent), not in a window of its own: the remote's focus goes into it at once, on the order
 * shown, and stays in it. OK on an order closes it and then sorts ([onChoose]); Back is the caller's.
 */
@Composable
internal fun TvSortMenu(choice: TvSortChoice, anchor: Rect, onChoose: (() -> Unit) -> Unit) {
    val ticked = remember { FocusRequester() }
    // The panel is laid out on the next frame or two: the focus then goes into it, on the order shown.
    LaunchedEffect(Unit) {
        repeat(MENU_FOCUS_FRAMES) {
            withFrameNanos { }
            if (runCatching { ticked.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    val margin = with(LocalDensity.current) { TvTokens.EdgeEnd.roundToPx() }
    val gap = with(LocalDensity.current) { MENU_GAP.roundToPx() }
    Layout(
        content = {
            Column(
                Modifier
                    .width(MENU_WIDTH)
                    .shadow(16.dp, MENU_SHAPE)
                    .clip(MENU_SHAPE)
                    .background(OndoColors.Surface)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), MENU_SHAPE)
                    // The remote stays in the menu until a choice or Back: no arrow takes it out to the page.
                    .focusProperties {
                        onExit = { if (requestedFocusDirection in TRAPPED_DIRECTIONS) cancelFocusChange() }
                    }
                    .focusGroup()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
                    .testTag("sortMenu"),
            ) {
                PosterSort.entries.forEach { option ->
                    val selected = option == choice.sort
                    MenuOption(stringResource(option.label), selected, if (selected) ticked else null, "sort-" + option.name.lowercase()) {
                        onChoose { choice.onSort(option) }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val top = anchor.bottom.roundToInt() + gap
        val panel = measurables.first().measure(
            Constraints(maxWidth = constraints.maxWidth, maxHeight = (constraints.maxHeight - top - margin).coerceAtLeast(0)),
        )
        // Under the chip, from its start edge, kept clear of the screen's edges.
        val start = if (layoutDirection == LayoutDirection.Rtl) anchor.right.roundToInt() - panel.width else anchor.left.roundToInt()
        val x = start.coerceAtMost(constraints.maxWidth - margin - panel.width).coerceAtLeast(margin.coerceAtMost(constraints.maxWidth - panel.width))
        layout(constraints.maxWidth, constraints.maxHeight) { panel.place(x, top) }
    }
}

/** A line of the menu: [text], ticked when [selected]; [requester]: the one focused first. */
@Composable
private fun MenuOption(text: String, selected: Boolean, requester: FocusRequester?, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .fillMaxWidth()
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .semantics { this.selected = selected }
            .testTag(tag),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = colors.onBackground.copy(alpha = 0.86f),
            focusedContainerColor = colors.onBackground,
            focusedContentColor = colors.background,
            pressedContainerColor = colors.onBackground,
            pressedContentColor = colors.background,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                text,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
        }
    }
}

/** The arrows that would take the remote out of the open menu. */
private val TRAPPED_DIRECTIONS = setOf(FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right, FocusDirection.Next, FocusDirection.Previous)

/** How many frames the menu waits for its panel to take the focus. */
private const val MENU_FOCUS_FRAMES = 10

/** The widest a chip's words show: a long order's name is cut short. */
private val CHIP_TEXT_WIDTH = 240.dp

private val MENU_WIDTH = 240.dp
private val MENU_GAP = 8.dp
private val MENU_SHAPE = RoundedCornerShape(14.dp)
