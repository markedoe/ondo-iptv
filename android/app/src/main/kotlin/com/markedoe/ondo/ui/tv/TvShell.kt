package com.markedoe.ondo.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.em
import com.markedoe.ondo.ui.screens.OndoLogo
import com.markedoe.ondo.ui.theme.OndoColors
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.TopLevel
import kotlinx.coroutines.launch

/**
 * What the top bar knows of the tab page shown: how to bring it back to its top (Back) and where the remote was in it
 * (Down from the bar). Each tab page registers its list ([rememberTabListState], [rememberTabGridState]).
 */
@Stable
class TvTopBar {
    internal class Page(val toTop: suspend () -> Unit, val memory: FocusMemory?)

    internal var page: Page? by mutableStateOf(null)

    /** A short line at the bar's end, before Search (Home: the playlist and when it was updated). */
    internal var note: String? by mutableStateOf(null)

    /**
     * The remote is moving along the bar (Left, Right): each tab it reaches opens, and the page that opens leaves the
     * focus on the bar. OK, Down or leaving the bar ends it.
     */
    var browsing: Boolean = false
        internal set
}

/** Shows [text] at the top bar's end while the calling page is on screen (on a TV; nothing elsewhere). */
@Composable
fun TvTopBarNote(text: String?) {
    val bar = LocalTvTopBar.current ?: return
    DisposableEffect(bar, text) {
        bar.note = text
        onDispose { if (bar.note == text) bar.note = null }
    }
}

val LocalTvTopBar = staticCompositionLocalOf<TvTopBar?> { null }

/** How far a tab page's content starts below the screen's top on a TV, clear of the bar; 0 elsewhere. */
@Composable
fun tvTopBarSpace(): Dp = if (LocalTvTopBar.current != null) TvTokens.TopBarSpace else 0.dp

@Composable
private fun RegisterTabPage(toTop: suspend () -> Unit, key: Any) {
    val bar = LocalTvTopBar.current ?: return
    val memory = LocalFocusMemory.current
    DisposableEffect(bar, key, memory) {
        val page = TvTopBar.Page(toTop, memory)
        bar.page = page
        onDispose { if (bar.page === page) bar.page = null }
    }
}

/** A tab page's list, which Back brings to its top. */
@Composable
fun rememberTabListState(): LazyListState {
    val state = rememberLazyListState()
    RegisterTabPage({ state.scrollToItem(0) }, state)
    return state
}

/** The same, for a tab page that is a grid. */
@Composable
fun rememberTabGridState(): LazyGridState {
    val state = rememberLazyGridState()
    RegisterTabPage({ state.scrollToItem(0) }, state)
    return state
}

/**
 * The TV's navigation: a bar always at the top of the tab pages, which scroll under it. Ondo's mark, the tabs, then
 * Search and Settings at the far end.
 * [selected] is the tab shown. Pages beyond a tab (Search, a movie, the guide, Settings) take the whole screen
 * ([showBar] false).
 *
 * The tab the remote moves to along the bar opens at once; OK goes into its page. Up from a page's top reaches the bar, on the tab shown; Down from the
 * bar goes to the page's first item. Back
 * follows Google's rule (Navigation on TV): from a tab's page, back to its top with the focus on the bar; from the
 * bar, to Home; from Home's bar, out of the app.
 */
@Composable
fun TvNavigationFrame(
    selected: TopLevel,
    showBar: Boolean,
    onSelect: (TopLevel) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(tag: String) = requesters.getOrPut(tag) { FocusRequester() }
    val selectedTag = selected.tag
    val bar = remember { TvTopBar() }
    var barHasFocus by remember { mutableStateOf(false) }
    val shown = showBar
    val scope = rememberCoroutineScope()
    val pages = remember { FocusRequester() }
    // Down from the bar: the page's first item, the one just below the bar (Up from it comes back to the bar).
    fun below(): FocusRequester = pages
    val atHome = selected == TopLevel.HOME
    Box(Modifier.fillMaxSize()) {
        // The pages start at the screen's top, under the bar: Down from the bar goes to them by name ([below]), as
        // nothing of theirs lies wholly below it.
        Box(Modifier.fillMaxSize().focusRequester(pages).focusGroup()) {
            androidx.compose.runtime.CompositionLocalProvider(LocalTvTopBar provides if (showBar) bar else null, content = content)
        }
        if (showBar) {
            val ground = MaterialTheme.colorScheme.background
            // The page scrolls under a black band, which fades into it just below the bar.
            val band = TvTokens.TopBarSpace / (TvTokens.TopBarSpace + BAND_FADE)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TvTokens.TopBarSpace + BAND_FADE)
                    .background(Brush.verticalGradient(0f to ground, band to ground, 1f to Color.Transparent)),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = TvTokens.ContentStart, top = TvTokens.EdgeTop, end = TvTokens.EdgeEnd)
                    .onFocusChanged {
                        barHasFocus = it.hasFocus
                        if (!it.hasFocus) bar.browsing = false
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.DirectionLeft, Key.DirectionRight -> bar.browsing = true
                                Key.DirectionDown -> bar.browsing = false
                                else -> Unit
                            }
                        }
                        false
                    }
                    // Coming from the page, the focus lands on the tab shown, not on the item above where it was.
                    .focusProperties {
                        onEnter = { if (requestedFocusDirection != FocusDirection.Enter) runCatching { requester(selectedTag).requestFocus() } }
                    }
                    .focusGroup()
                    .selectableGroup()
                    .testTag("topBar"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Ondo's mark and name, then the tabs; Search and Settings at the far end.
                Row(Modifier.padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    OndoLogo(36.dp)
                    Text("Ondo", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em, modifier = Modifier.padding(start = 2.dp))
                }
                TopLevel.entries.forEach { tab ->
                    BarTab(stringResource(tab.label), tab == selected, shown, tab.tag, requester(tab.tag), ::below, browsing = { bar.browsing }, onReach = { onSelect(tab) }) {
                        val browsed = bar.browsing
                        bar.browsing = false
                        onSelect(tab)
                        // The tab opened as the remote reached it: OK goes into its page, where the page would put the focus.
                        if (browsed && tab == selected) {
                            scope.launch {
                                withFrameNanos { }
                                runCatching { (bar.page?.memory?.entry() ?: pages).requestFocus() }
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                bar.note?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 360.dp).padding(end = 12.dp).testTag("lastUpdate"),
                    )
                }
                BarIcon(stringResource(R.string.search), Icons.Rounded.Search, shown, SEARCH_TAG, requester(SEARCH_TAG), ::below, onSearch)
                BarIcon(stringResource(R.string.settings), Icons.Rounded.Settings, shown, SETTINGS_TAG, requester(SETTINGS_TAG), ::below, onSettings)
            }
        }
        // After the pages: Back reaches this before the pages' own back stack.
        BackHandler(enabled = showBar && !(barHasFocus && atHome)) {
            if (barHasFocus) {
                onSelect(TopLevel.HOME)
            } else {
                scope.launch {
                    // The bar takes the focus first, then the page goes up: the item that had the focus leaves the
                    // screen without it, and nothing takes it back to the page.
                    runCatching { requester(selectedTag).requestFocus() }
                    withFrameNanos { }
                    withFrameNanos { }
                    bar.page?.toTop()
                }
            }
        }
    }
}

/** How far below the bar the black band behind it fades into the page. */
private val BAND_FADE = 20.dp

const val SEARCH_TAG = "tabSearch"
const val SETTINGS_TAG = "tabSettings"

@Composable
private fun BarTab(label: String, selected: Boolean, shown: Boolean, tag: String, requester: FocusRequester, below: () -> FocusRequester, browsing: () -> Boolean, onReach: () -> Unit, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(requester)
            .focusProperties {
                canFocus = shown
                down = below()
            }
            // The tab the remote moves to along the bar is shown at once, without OK (as on the TV's own home screen);
            // the focus only passing through the bar (Up from a page) opens nothing.
            .onFocusChanged { if (it.isFocused && !selected && browsing()) onReach() }
            .semantics {
                contentDescription = label
                this.selected = selected
            }
            .testTag(tag),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) OndoColors.SurfaceHigh else Color.Transparent,
            contentColor = if (selected) colors.onBackground else colors.onBackground.copy(alpha = 0.72f),
            focusedContainerColor = colors.onBackground,
            focusedContentColor = colors.background,
            pressedContainerColor = colors.onBackground,
            pressedContentColor = colors.background,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Text(
            label,
            fontSize = 15.sp,
            letterSpacing = 0.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
        )
    }
}

@Composable
private fun BarIcon(label: String, icon: ImageVector, shown: Boolean, tag: String, requester: FocusRequester, below: () -> FocusRequester, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(requester)
            .focusProperties {
                canFocus = shown
                down = below()
            }
            .semantics { contentDescription = label }
            .testTag(tag),
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
        Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
