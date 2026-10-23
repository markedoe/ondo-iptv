package com.markedoe.ondo.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.TopLevel

/** Where the TV's drawer points: one of the tabs, or Search. */
sealed interface TvPlace {
    data class Tab(val tab: TopLevel) : TvPlace
    data object Search : TvPlace
}

/**
 * The TV's navigation: a drawer on the left, closed to its icons over the page and opened with its labels when the
 * remote moves onto it (Google's TV navigation). Search sits first, the tabs follow, Settings last. Pages beyond a
 * tab (a movie, the guide, Settings) take the whole screen ([showDrawer] false).
 *
 * Back follows Google's rule (Navigation on TV): from a tab's page, the focus goes to the drawer, on the tab shown;
 * from the drawer, back to Home; from Home's drawer, out of the app.
 */
@Composable
fun TvNavigationFrame(
    place: TvPlace,
    showDrawer: Boolean,
    onSelect: (TopLevel) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(tag: String) = requesters.getOrPut(tag) { FocusRequester() }
    val selectedTag = when (place) {
        is TvPlace.Tab -> place.tab.tag
        TvPlace.Search -> SEARCH_TAG
    }
    var drawerHasFocus by remember { mutableStateOf(false) }
    val atHome = place == TvPlace.Tab(TopLevel.HOME)
    // The content stays in the same place whether the drawer shows or not: its pages keep their state.
    ModalNavigationDrawer(
        drawerState = rememberDrawerState(DrawerValue.Closed),
        scrimBrush = startToEndGradient(0f to Color.Black.copy(alpha = 0.92f), 0.45f to Color.Black.copy(alpha = 0.7f), 1f to Color.Transparent),
        drawerContent = {
            if (showDrawer) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .padding(start = TvTokens.DrawerStart, end = 16.dp, top = TvTokens.EdgeTop, bottom = TvTokens.EdgeTop)
                        .onFocusChanged { drawerHasFocus = it.hasFocus }
                        // Coming from the page, the focus lands on the tab shown, not on the item beside it.
                        .focusProperties {
                            onEnter = { if (requestedFocusDirection != FocusDirection.Enter) runCatching { requester(selectedTag).requestFocus() } }
                        }
                        .focusGroup()
                        .selectableGroup()
                        .testTag("drawer"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OndoMark(Modifier.padding(start = 8.dp, bottom = 28.dp))
                    DrawerItem(stringResource(R.string.search), Icons.Rounded.Search, place == TvPlace.Search, SEARCH_TAG, requester(SEARCH_TAG), onSearch)
                    TopLevel.entries.forEach { tab ->
                        DrawerItem(stringResource(tab.label), tab.icon, place == TvPlace.Tab(tab), tab.tag, requester(tab.tag)) { onSelect(tab) }
                    }
                    Spacer(Modifier.weight(1f))
                    DrawerItem(stringResource(R.string.settings), Icons.Rounded.Settings, false, SETTINGS_TAG, requester(SETTINGS_TAG), onSettings)
                }
            }
        },
    ) {
        content()
        // After the pages: Back reaches this before the pages' own back stack.
        BackHandler(enabled = showDrawer && !(drawerHasFocus && atHome)) {
            if (!drawerHasFocus) runCatching { requester(selectedTag).requestFocus() } else onSelect(TopLevel.HOME)
        }
    }
}

const val SEARCH_TAG = "tabSearch"
const val SETTINGS_TAG = "tabSettings"

@Composable
private fun NavigationDrawerScope.DrawerItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    tag: String,
    requester: FocusRequester,
    onClick: () -> Unit,
) {
    val lime = TvTokens.Lime
    NavigationDrawerItem(
        selected = selected,
        onClick = onClick,
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier
            .focusRequester(requester)
            .semantics { contentDescription = label }
            .testTag(tag)
            // The tab shown keeps a lime mark beside its icon, on the screen's edge side, the drawer open or closed.
            .drawBehind {
                if (selected) {
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width + 8.dp.toPx() else -12.dp.toPx()
                    drawRoundRect(lime, topLeft = Offset(x, size.height / 2 - 9.dp.toPx()), size = Size(4.dp.toPx(), 18.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
                }
            },
        colors = NavigationDrawerItemDefaults.colors(
            contentColor = Color.White.copy(alpha = 0.8f),
            inactiveContentColor = Color.White.copy(alpha = 0.65f),
            selectedContainerColor = Color.White.copy(alpha = 0.14f),
            selectedContentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = TvTokens.Ink,
            focusedSelectedContainerColor = Color.White,
            focusedSelectedContentColor = TvTokens.Ink,
        ),
    ) {
        Text(label)
    }
}

/** Ondo's mark: a wave on a lime square. */
@Composable
private fun OndoMark(modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).background(TvTokens.Lime, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.12f, size.height * 0.6f)
                cubicTo(size.width * 0.3f, size.height * 0.3f, size.width * 0.42f, size.height * 0.3f, size.width * 0.5f, size.height * 0.5f)
                cubicTo(size.width * 0.58f, size.height * 0.7f, size.width * 0.7f, size.height * 0.7f, size.width * 0.88f, size.height * 0.4f)
            }
            drawPath(path, TvTokens.Ink, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
