package com.markedoe.ondo.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.rememberTextMeasurer
import com.markedoe.ondo.ui.shortAge
import com.markedoe.ondo.ui.shortAgeSamples
import com.markedoe.ondo.ui.updatedAgoText
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.ui.screens.ProfileAvatar
import com.markedoe.ondo.ui.TopLevel
import com.markedoe.ondo.ui.screens.OndoLogo
import com.markedoe.ondo.ui.theme.OndoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the top bar knows of the tab page shown: how to bring it back to its top (Back) and where the remote was in it
 * (Down from the bar). Each tab page registers its list ([rememberTabListState], [rememberTabGridState]).
 */
@Stable
class TvTopBar {
    internal class Page(val toTop: suspend () -> Unit, val memory: FocusMemory?)

    internal var page: Page? by mutableStateOf(null)

    /** The page draws a picture under the bar ([TvTopBarOverArt]): the bar's band is a shade over it, not a wall. */
    internal var overArt: Any? by mutableStateOf(null)

    /**
     * The remote is moving along the bar (Left, Right): each tab it reaches opens, and the page that opens leaves the
     * focus on the bar. OK, Down or leaving the bar ends it.
     */
    var browsing: Boolean = false
        internal set
}

/**
 * While the calling page is on screen and [on], it draws a picture from the screen's top, under the bar (a header's
 * backdrop): the bar's band is then a shade that fades into the picture, not the ground colour cut across it.
 */
@Composable
fun TvTopBarOverArt(on: Boolean = true) {
    val bar = LocalTvTopBar.current ?: return
    DisposableEffect(bar, on) {
        if (!on) return@DisposableEffect onDispose { }
        val owner = Any()
        bar.overArt = owner
        onDispose { if (bar.overArt === owner) bar.overArt = null }
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
 * Search and Settings at the far end: nothing of the page's own (a category's titles name themselves and hold their
 * order above their posters, [TvHeaderAbove]). [selected] is the tab shown. A category's or a genre's titles keep the bar
 * ([showBar]) on the tab they belong to, though no tab page ([tabPage] false: Back goes back to the page before).
 * Pages beyond a tab (Search, a movie, the guide, Settings) take the whole screen ([showBar] false).
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
    tabPage: Boolean = showBar,
    onSelect: (TopLevel) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    /** The profile in use, its avatar at the far end, next to Settings: it opens the switcher ([onProfiles]). */
    profile: Profile? = null,
    onProfiles: () -> Unit = {},
    /** The playlist in use and when it was updated, at the bar's end before Search ([TvPlaylistStatusSlot]). */
    playlist: TvPlaylistStatus? = null,
    content: @Composable () -> Unit,
) {
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(tag: String) = requesters.getOrPut(tag) { FocusRequester() }
    val selectedTag = selected.tag
    val bar = remember { TvTopBar() }
    var barHasFocus by remember { mutableStateOf(false) }
    // The tab the remote rests on, about to open (a spinner shows it).
    var openingTab by remember { mutableStateOf<String?>(null) }
    val shown = showBar
    val scope = rememberCoroutineScope()
    val pages = remember { FocusRequester() }
    // Down from the bar: the page's first item, the one just below the bar (Up from it comes back to the bar).
    fun below(): FocusRequester = pages
    val atHome = selected == TopLevel.HOME
    val shownTab = rememberUpdatedState(selected)
    Box(Modifier.fillMaxSize()) {
        // The pages start at the screen's top, under the bar: Down from the bar goes to them by name ([below]), as
        // nothing of theirs lies wholly below it.
        Box(Modifier.fillMaxSize().focusRequester(pages).focusGroup()) {
            androidx.compose.runtime.CompositionLocalProvider(LocalTvTopBar provides if (showBar) bar else null, content = content)
        }
        if (showBar) {
            val ground = MaterialTheme.colorScheme.background
            // The page scrolls under a black band, which fades out at the bar's lower edge: never over the page's top
            // (its filters), which starts right below. Over a picture that runs from the screen's top (a header's
            // backdrop), a shade instead, darkest at the top, for the tabs to read: the picture shows through, with no
            // edge across it.
            val overArt = bar.overArt != null
            val band = (TvTokens.TopBarSpace - BAND_FADE) / TvTokens.TopBarSpace
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TvTokens.TopBarSpace)
                    .background(
                        if (overArt) {
                            Brush.verticalGradient(0f to ground.copy(alpha = 0.78f), 0.55f to ground.copy(alpha = 0.42f), 1f to Color.Transparent)
                        } else {
                            Brush.verticalGradient(0f to ground, band to ground, 1f to Color.Transparent)
                        },
                    )
                    .testTag(if (overArt) "topBarScrim" else "topBarBand"),
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
                horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Ondo's mark and name, then the tabs; Search and Settings at the far end.
                Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OndoLogo(36.dp)
                    Text("Ondo", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em, modifier = Modifier.padding(start = 2.dp))
                }
                // Side by side: each tab's own padding parts their names.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TopLevel.entries.forEach { tab ->
                        BarTab(
                            stringResource(tab.label), tab == selected, shown, tab.tag, requester(tab.tag), ::below,
                            browsing = { bar.browsing },
                            onOpening = { on -> openingTab = if (on) tab.tag else openingTab.takeIf { it != tab.tag } },
                            onReach = { onSelect(tab) },
                        ) {
                            bar.browsing = false
                            onSelect(tab)
                            // OK goes into the tab's page, where the page puts the focus, once that page is the one shown.
                            scope.launch {
                                repeat(ENTER_PAGE_FRAMES) {
                                    withFrameNanos { }
                                    if (!barHasFocus) return@launch
                                    if (shownTab.value == tab && runCatching { bar.page?.memory?.entry()?.requestFocus() == true }.getOrDefault(false)) return@launch
                                }
                                if (barHasFocus) runCatching { pages.requestFocus() }
                            }
                        }
                    }
                }
                // The room the tabs leave before Search and Settings.
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    // A tab about to open: a small spinner after the tabs, in the gap (it takes no room of its own).
                    if (openingTab != null) {
                        Box(Modifier.align(Alignment.CenterStart).padding(start = 10.dp).size(18.dp), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp).testTag("tabOpening"),
                            )
                        }
                    }
                    playlist?.let { TvPlaylistStatusSlot(it, Modifier.padding(end = 10.dp)) }
                }
                BarIcon(stringResource(R.string.search), Icons.Rounded.Search, shown, SEARCH_TAG, requester(SEARCH_TAG), ::below, onSearch)
                BarIcon(stringResource(R.string.settings), Icons.Rounded.Settings, shown, SETTINGS_TAG, requester(SETTINGS_TAG), ::below, onSettings)
                profile?.let { BarProfile(it, shown, requester(PROFILE_TAG), ::below, onProfiles) }
            }
        }
        // After the pages: Back reaches this before the pages' own back stack.
        // On Home's bar, Back does nothing: Ondo stays open (the remote's Home button leaves it). On a category's page,
        // Back is the page's own: back to the page before.
        BackHandler(enabled = showBar && tabPage) {
            if (barHasFocus && atHome) {
                return@BackHandler
            } else if (barHasFocus) {
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

/** The space between the bar's items. */
private val BAR_GAP = 4.dp

/** How much of the bar's lower edge the black band behind it takes to fade out. */
private val BAND_FADE = 12.dp

/** How many frames OK on a tab waits for its page to take the focus before giving it to the page's first item. */
private const val ENTER_PAGE_FRAMES = 30

/**
 * How long the remote rests on a tab before it opens: longer than the time between two presses of a remote moving on
 * along the bar, so that the tabs it passes (towards Search or Settings) do not open.
 */
private const val TAB_REST_MS = 500L

const val SEARCH_TAG = "tabSearch"
const val SETTINGS_TAG = "tabSettings"
const val PROFILE_TAG = "tabProfile"

@Composable
private fun BarTab(
    label: String,
    selected: Boolean,
    shown: Boolean,
    tag: String,
    requester: FocusRequester,
    below: () -> FocusRequester,
    browsing: () -> Boolean,
    onOpening: (Boolean) -> Unit,
    onReach: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    // The tab the remote rests on along the bar opens without OK (as on the TV's own home screen); passing quickly
    // (towards Settings) opens nothing, nor does the focus only passing through the bar (Up from a page). Meanwhile
    // the bar shows it is opening (a small spinner after the tabs): the remote need not press OK.
    LaunchedEffect(focused, selected) {
        if (!focused || selected || !browsing()) return@LaunchedEffect
        onOpening(true)
        try {
            delay(TAB_REST_MS)
            if (browsing()) onReach()
        } finally {
            onOpening(false)
        }
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(requester)
            .focusProperties {
                canFocus = shown
                down = below()
            }
            .onFocusChanged { focused = it.isFocused }
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
            modifier = Modifier.padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp)),
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

/** The profile in use, as a round avatar: OK opens the switcher (docs/PROFILES.md). */
@Composable
private fun BarProfile(profile: Profile, shown: Boolean, requester: FocusRequester, below: () -> FocusRequester, onClick: () -> Unit) {
    val label = stringResource(R.string.switch_profile)
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(requester)
            .focusProperties {
                canFocus = shown
                down = below()
            }
            .semantics { contentDescription = "$label, ${profile.name}" }
            .testTag(PROFILE_TAG),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.onBackground,
            pressedContainerColor = colors.onBackground,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Box(Modifier.padding(3.dp), contentAlignment = Alignment.Center) {
            ProfileAvatar(profile, 30.dp, showLock = false)
        }
    }
}

/**
 * The playlist in use for the top bar ([TvNavigationFrame]): its [name], when its catalogue was last fetched
 * ([updatedAt], null: not yet) at [now], whether it is being fetched again ([refreshing]) and whether the last try
 * failed ([failed]).
 */
data class TvPlaylistStatus(val name: String, val updatedAt: Long?, val now: Long, val refreshing: Boolean = false, val failed: Boolean = false)

/**
 * At the top bar's end, before Search: a check and how long ago the playlist was updated, short ("5 min"); a small
 * spinner in the check's place while it is fetched again; a warning sign when that failed (the age, the last good
 * fetch's). One slot, as wide as the widest age in the app's language: the tabs and the icons never move as it changes.
 * Not focusable (the remote goes from the tabs straight to Search); TalkBack reads the whole sentence, with the
 * playlist's name, which is not written.
 */
@Composable
fun TvPlaylistStatusSlot(status: TvPlaylistStatus, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val locale = resources.configuration.locales[0]
    val age = status.updatedAt?.let { shortAge(it, status.now, locale) }
    val sentence = listOfNotNull(
        status.name.takeIf { it.isNotBlank() },
        when {
            status.refreshing -> stringResource(R.string.refreshing_playlist)
            status.failed -> stringResource(R.string.playlist_update_failed)
            else -> null
        },
        status.updatedAt?.let { updatedAgoText(it, status.now) },
    ).joinToString(". ")
    val style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = remember(locale, style, density) {
        with(density) { shortAgeSamples(locale).maxOf { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }.toDp() }
    }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .width(STATUS_ICON + STATUS_GAP + widest)
            .semantics(mergeDescendants = true) { contentDescription = sentence }
            .testTag("playlistStatus"),
        horizontalArrangement = Arrangement.spacedBy(STATUS_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(STATUS_ICON), contentAlignment = Alignment.Center) {
            when {
                status.refreshing -> androidx.compose.material3.CircularProgressIndicator(
                    color = tint,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(STATUS_ICON - 2.dp).semantics { hideFromAccessibility() }.testTag("playlistRefreshing"),
                )
                status.failed -> Icon(Icons.Rounded.Warning, contentDescription = null, tint = STATUS_WARNING, modifier = Modifier.size(STATUS_ICON).testTag("playlistFailed"))
                else -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = tint, modifier = Modifier.size(STATUS_ICON).testTag("playlistUpdated"))
            }
        }
        if (age != null) {
            Text(
                age,
                style = style,
                color = tint,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.semantics { hideFromAccessibility() }.testTag("playlistAge"),
            )
        }
    }
}

private val STATUS_ICON = 16.dp
private val STATUS_GAP = 6.dp

/** The warning sign's colour: amber, read on the dark bar without the alarm of red. */
private val STATUS_WARNING = Color(0xFFFFB74D)
