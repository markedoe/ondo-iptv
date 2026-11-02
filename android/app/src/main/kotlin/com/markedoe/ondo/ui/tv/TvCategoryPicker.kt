package com.markedoe.ondo.ui.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.ui.theme.OndoColors

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
 * The top bar's Categories pill: the category shown, or Categories; OK opens the picker, the focus on the category
 * shown, for the remote to go up and down it. [onChosen]: an entry of the picker was chosen (the page it opens takes
 * the focus).
 */
@Composable
internal fun TvCategoriesPill(choice: TvCategoryChoice, shown: Boolean, requester: FocusRequester, below: () -> FocusRequester, onChosen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val ticked = remember { FocusRequester() }
    Box {
        Surface(
            onClick = { open = true },
            modifier = Modifier
                .focusRequester(requester)
                .focusProperties {
                    canFocus = shown
                    down = below()
                }
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
            Row(Modifier.padding(start = 12.dp, end = 14.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    choice.selected?.name ?: stringResource(R.string.categories),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp).widthIn(max = PILL_TEXT_WIDTH).testTag("categoryFilterLabel"),
                )
            }
        }
        // The category shown takes the focus when the picker opens; All when it is not in the list.
        val tickedId = choice.selected?.id?.takeIf { id -> choice.categories.any { it.id == id } }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag("categoryPicker")) {
            // The picker's window is laid out a frame or two after OK: the focus goes into it, on the category shown.
            LaunchedEffect(Unit) {
                repeat(PICKER_FOCUS_FRAMES) {
                    withFrameNanos { }
                    if (runCatching { ticked.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                }
            }
            @Composable
            fun option(text: String, selected: Boolean, focusFirst: Boolean, tag: String, locked: Boolean, onClick: () -> Unit) = DropdownMenuItem(
                text = { androidx.compose.material3.Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { if (selected) androidx.compose.material3.Icon(Icons.Rounded.Check, contentDescription = null) },
                trailingIcon = if (locked) ({ androidx.compose.material3.Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
                onClick = {
                    open = false
                    onChosen()
                    onClick()
                },
                modifier = Modifier
                    .then(if (focusFirst) Modifier.focusRequester(ticked) else Modifier)
                    .semantics { this.selected = selected }
                    .testTag(tag),
            )
            option(stringResource(R.string.all), choice.selected == null, tickedId == null, "categoryAll", locked = false) { choice.onChoose(null) }
            choice.categories.forEach { category ->
                val selected = category.id == choice.selected?.id
                option(category.name, selected, selected && category.id == tickedId, "category:${category.id}", locked = category.id in choice.locked) { choice.onChoose(category) }
            }
            HorizontalDivider()
            option(stringResource(R.string.languages_and_categories), selected = false, focusFirst = false, tag = "categoryManage", locked = false) { choice.onManage() }
        }
    }
}

/** How many frames OK on the pill waits for the picker's window to take the focus. */
private const val PICKER_FOCUS_FRAMES = 10

/** The widest a category's name shows on the pill: the playlist's line keeps its room. */
private val PILL_TEXT_WIDTH = 180.dp
