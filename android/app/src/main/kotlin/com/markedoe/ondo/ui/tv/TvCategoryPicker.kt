package com.markedoe.ondo.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.ui.theme.OndoColors
import kotlin.math.roundToInt

/**
 * What the top bar's Categories pill offers on the Movies and Series tabs and on a category's titles: All, then each
 * of the tab's [categories] ([locked] ones with a lock), [selected] ticked and named on the pill (null: All, the pill
 * reads Categories); then the screen choosing which categories show ([onManage]).
 */
class TvCategoryChoice(
    val categories: List<MediaCategory>,
    val locked: Set<String>,
    val selected: MediaCategory?,
    val onChoose: (MediaCategory?) -> Unit,
    val onManage: () -> Unit,
)

/** The page's [TvCategoryChoice], kept by the top bar while the page is on screen. */
@Stable
internal class TvCategoryHolder(choice: TvCategoryChoice) {
    var choice: TvCategoryChoice by mutableStateOf(choice)
}

/**
 * On a TV, shows the Categories pill in the top bar ([TvNavigationFrame]), next to Search, while the calling page is
 * on screen: the categories are no chips above the rows there, and the first row starts right below the header.
 * Nothing elsewhere.
 */
@Composable
fun TvTopBarCategories(choice: TvCategoryChoice) {
    val bar = LocalTvTopBar.current ?: return
    val holder = remember { TvCategoryHolder(choice) }
    SideEffect { holder.choice = choice }
    DisposableEffect(bar, holder) {
        bar.categories = holder
        onDispose { if (bar.categories === holder) bar.categories = null }
    }
}

/** The tag of the top bar's Categories pill. */
const val CATEGORIES_TAG = "categoryFilter"

/**
 * The top bar's Categories pill: the category shown, or Categories; OK opens the picker ([onOpen], [TvCategoryPicker]).
 * It takes the room the bar leaves it after the playlist's line: short of room for its whole label, it shows the
 * category shown cut short, or its icon alone (named for TalkBack).
 */
@Composable
internal fun TvCategoriesPill(
    choice: TvCategoryChoice,
    shown: Boolean,
    requester: FocusRequester,
    below: () -> FocusRequester,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val name = choice.selected?.name ?: stringResource(R.string.categories)
    val style = LocalTextStyle.current.copy(fontSize = 14.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints {
        val labelRoom = minOf(PILL_TEXT_WIDTH, maxWidth - PILL_ICON_WIDTH - PILL_LABEL_GAP)
        val whole = with(density) { measurer.measure(name, style, maxLines = 1).size.width.toDp() }
        // Categories shows whole or not at all; a category's name may be cut short, down to a few letters.
        val label = name.takeIf { whole <= labelRoom || (choice.selected != null && labelRoom >= PILL_SHORTEST_LABEL) }
        Surface(
            onClick = onOpen,
            modifier = modifier
                .focusRequester(requester)
                .focusProperties {
                    canFocus = shown
                    down = below()
                }
                .then(if (label == null) Modifier.semantics { contentDescription = name } else Modifier)
                .testTag(CATEGORIES_TAG),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = OndoColors.SurfaceHigh,
                contentColor = colors.onBackground.copy(alpha = 0.86f),
                focusedContainerColor = colors.onBackground,
                focusedContentColor = colors.background,
                pressedContainerColor = colors.onBackground,
                pressedContentColor = colors.background,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        ) {
            Row(Modifier.padding(start = PILL_START, end = PILL_END, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FilterList, contentDescription = null, modifier = Modifier.size(PILL_ICON))
                if (label != null) {
                    Text(
                        label,
                        style = style,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = PILL_LABEL_GAP).widthIn(max = labelRoom).testTag("categoryFilterLabel"),
                    )
                }
            }
        }
    }
}

/**
 * The Categories pill's picker, open: a panel drawn in the app's own window over the page, below the pill ([anchor],
 * its bounds in the panel's parent), not in a window of its own: the remote's focus goes into it at once, on the
 * category shown (All when it is not in the list), and stays in it, Up and Down along it. OK on a line closes it and
 * then does what the line says ([onChoose]: a category, All, or the screen choosing which categories show); Back is
 * the caller's.
 */
@Composable
internal fun TvCategoryPicker(choice: TvCategoryChoice, anchor: Rect, onChoose: (() -> Unit) -> Unit) {
    val ticked = remember { FocusRequester() }
    val tickedId = choice.selected?.id?.takeIf { id -> choice.categories.any { it.id == id } }
    // The panel is laid out on the next frame or two: the focus then goes into it, on the category shown.
    LaunchedEffect(Unit) {
        repeat(PICKER_FOCUS_FRAMES) {
            withFrameNanos { }
            if (runCatching { ticked.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    val margin = with(LocalDensity.current) { TvTokens.EdgeEnd.roundToPx() }
    val gap = with(LocalDensity.current) { PICKER_GAP.roundToPx() }
    Layout(
        content = {
            Column(
                Modifier
                    .width(PICKER_WIDTH)
                    .shadow(16.dp, PICKER_SHAPE)
                    .clip(PICKER_SHAPE)
                    .background(OndoColors.Surface)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), PICKER_SHAPE)
                    // The remote stays in the picker until a choice or Back: no arrow takes it out to the page.
                    .focusProperties {
                        onExit = { if (requestedFocusDirection in TRAPPED_DIRECTIONS) cancelFocusChange() }
                    }
                    .focusGroup()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
                    .testTag("categoryPicker"),
            ) {
                PickerOption(stringResource(R.string.all), choice.selected == null, if (tickedId == null) ticked else null, "categoryAll", locked = false) {
                    onChoose { choice.onChoose(null) }
                }
                choice.categories.forEach { category ->
                    val selected = category.id == choice.selected?.id
                    PickerOption(category.name, selected, if (selected && category.id == tickedId) ticked else null, "category:${category.id}", category.id in choice.locked) {
                        onChoose { choice.onChoose(category) }
                    }
                }
                Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
                PickerOption(stringResource(R.string.languages_and_categories), selected = false, requester = null, tag = "categoryManage", locked = false) {
                    onChoose { choice.onManage() }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val top = anchor.bottom.roundToInt() + gap
        val panel = measurables.first().measure(
            Constraints(maxWidth = constraints.maxWidth, maxHeight = (constraints.maxHeight - top - margin).coerceAtLeast(0)),
        )
        // Under the pill, from its start edge, kept clear of the screen's edges.
        val start = if (layoutDirection == LayoutDirection.Rtl) anchor.right.roundToInt() - panel.width else anchor.left.roundToInt()
        val x = start.coerceAtMost(constraints.maxWidth - margin - panel.width).coerceAtLeast(margin.coerceAtMost(constraints.maxWidth - panel.width))
        layout(constraints.maxWidth, constraints.maxHeight) { panel.place(x, top) }
    }
}

/** A line of the picker: [text], ticked when [selected], a lock when [locked]; [requester]: the one focused first. */
@Composable
private fun PickerOption(text: String, selected: Boolean, requester: FocusRequester?, tag: String, locked: Boolean, onClick: () -> Unit) {
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
            if (locked) Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(16.dp))
        }
    }
}

/** The arrows that would take the remote out of the open picker. */
private val TRAPPED_DIRECTIONS = setOf(FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right, FocusDirection.Next, FocusDirection.Previous)

/** How many frames the picker waits for its panel to take the focus. */
private const val PICKER_FOCUS_FRAMES = 10

/** The widest a category's name shows on the pill. */
private val PILL_TEXT_WIDTH = 180.dp

/** The narrowest a category's name shows on the pill, cut short; narrower, the pill shows its icon alone. */
private val PILL_SHORTEST_LABEL = 40.dp

private val PILL_START = 10.dp
private val PILL_END = 12.dp
private val PILL_ICON = 18.dp
private val PILL_LABEL_GAP = 6.dp

/** The Categories pill's width with its icon alone: the least the top bar leaves it. */
internal val PILL_ICON_WIDTH = PILL_START + PILL_ICON + PILL_END

private val PICKER_WIDTH = 300.dp
private val PICKER_GAP = 8.dp
private val PICKER_SHAPE = RoundedCornerShape(14.dp)

