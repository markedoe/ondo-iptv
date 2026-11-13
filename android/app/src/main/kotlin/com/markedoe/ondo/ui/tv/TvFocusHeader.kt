package com.markedoe.ondo.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import coil3.compose.AsyncImage
import com.markedoe.ondo.core.library.CatalogueMatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.model.TitleHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/** How long the remote rests on a poster before the header shows it: moving fast along a row changes nothing above. */
const val HEADER_DEBOUNCE_MS = 200L

/** How much longer it rests there before what the header lacks is fetched (one title at a time). */
const val HEADER_LOAD_DWELL_MS = 450L

/**
 * The header's height below the top bar, the same whatever it shows: the rows below never move with it. Compact (the
 * title, the facts and two lines of synopsis), for a row of posters about five across to show whole below it on a
 * 960 × 540 dp TV.
 */
val TvHeaderHeight = 140.dp

/** The posters' width under the header: as Home's, about five across with the sixth peeking. */
val TvHeaderPosterWidth = TvTokens.PosterWidth

/**
 * The landscape cards' width under the header (continue watching): as Home's, 16:9 with their two lines below, for
 * the row with its name to fit under the header on a 960 × 540 dp TV.
 */
val TvHeaderWideCardWidth = TvTokens.WideCardWidth

/** The synopsis' lines, their room kept even when there is none. */
private const val PLOT_LINES = 2

/**
 * Where the header gets what it shows ([header]) and what is missing ([load]); the app's comes from the view model,
 * the tests give their own. None on a phone: its pages have no header.
 */
@Stable
interface TitleHeaders {
    /** What is known of [item], again as more is (its details, TMDb's answer); worked out off the main thread. */
    fun header(item: ShowcaseItem): Flow<TitleHeader>

    /** The remote stays on [item]: what the header lacks is fetched, cancelled when the remote moves on. */
    suspend fun load(item: ShowcaseItem)

    /** The backdrop at [url] did not load. */
    fun backdropFailed(item: ShowcaseItem, url: String) {}
}

val LocalTitleHeaders = staticCompositionLocalOf<TitleHeaders?> { null }

/** The poster with the remote's focus on a page with a header; set by the posters themselves ([reportsFocus]). */
@Stable
class FocusedTitle {
    var item: ShowcaseItem? by mutableStateOf(null)
        private set

    /** The poster that has it: the same title in two rows is two posters. */
    private var owner: Any? = null

    fun focus(owner: Any, item: ShowcaseItem) {
        this.owner = owner
        this.item = item
    }

    /**
     * A poster's title changed while the header shows it (made of the history, now the catalogue's): the header follows,
     * the same title, whichever poster of it reports it.
     */
    fun update(owner: Any, item: ShowcaseItem) {
        val current = this.item ?: return
        if ((this.owner === owner || current.key == item.key) && current != item) this.item = item
    }

    fun release(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        item = null
    }
}

/** The page's [FocusedTitle]; none on a page without a header (Search, a title's page, a phone). */
val LocalFocusedTitle = staticCompositionLocalOf<FocusedTitle?> { null }

/**
 * Tells the page's header that this poster of [item] has the focus, where the page has one; nothing elsewhere, nor for
 * a card of no title (a channel's).
 */
fun Modifier.reportsFocus(item: ShowcaseItem?): Modifier = composed {
    val focus = LocalFocusedTitle.current ?: return@composed this
    val title: ShowcaseItem = item ?: return@composed this
    val owner = remember { Any() }
    val current by rememberUpdatedState(title)
    DisposableEffect(focus, owner) { onDispose { focus.release(owner) } }
    SideEffect { focus.update(owner, title) }
    onFocusChanged { if (it.isFocused) focus.focus(owner, current) else focus.release(owner) }
}

/**
 * The title the header shows: the focused one once the remote has rested on it [HEADER_DEBOUNCE_MS] (on the
 * composition's clock); the first poster the remote reaches (Down from the top bar), at once. [keepLast]: with no
 * poster focused (a See all card, the top bar), the last one stays.
 */
@Composable
fun rememberShownTitle(focus: FocusedTitle, keepLast: Boolean): ShowcaseItem? {
    val focused = focus.item
    var shown by remember(focus) { mutableStateOf(focused) }
    // Keyed on the title itself, not only its key: the same title changed (made of the history while the catalogue was
    // read, now the catalogue's) is shown at once, with no wait; another title only once the remote rests on it.
    LaunchedEffect(focus, focused) {
        if (focused == null && keepLast) return@LaunchedEffect
        // Nothing shown yet (the page's fallback is): the first poster reached shows at once, with no blank between.
        if (shown != null && focused?.key != shown?.key) delay(HEADER_DEBOUNCE_MS)
        shown = focused
    }
    return shown
}

/** Once the remote has rested on [item] a moment longer, what the header lacks is fetched; moving on cancels it. */
@Composable
fun LoadWhenStill(item: ShowcaseItem?, headers: TitleHeaders?) {
    LaunchedEffect(item?.key, headers) {
        if (item == null || headers == null) return@LaunchedEffect
        delay(HEADER_LOAD_DWELL_MS)
        try {
            headers.load(item)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Out of reach or refused: the header shows what it has.
        }
    }
}

/**
 * What the header shows of [item]: the title, year and poster at once ([initial], when what is known of it is at hand),
 * the rest as it is known. The title shown before stays until the new one's is ready, so nothing flashes empty between
 * two posters. [restartOn]: worked out anew when it changes too.
 */
@Composable
fun rememberTitleHeader(item: ShowcaseItem?, headers: TitleHeaders?, initial: TitleHeader? = null, restartOn: Any? = null): TitleHeader? {
    val header by produceState(initial ?: item?.let { TitleHeader(it) }, item?.key, headers, restartOn) {
        if (initial != null) value = initial
        when {
            item == null -> value = null
            headers == null -> value = TitleHeader(item)
            else -> headers.header(item).collect { value = it }
        }
    }
    return header
}

/** The picture behind the header: it fades from one to the next only when the title or its picture changes. */
private data class HeaderArt(val item: ShowcaseItem, val backdropUrl: String?)

/**
 * What the header of a page of posters shows: the poster with the focus once the remote rests on it, else [fallback]
 * (the page's first card's title, the one Down from the tab reaches), and what is known of it; what it lacks is fetched
 * once the remote stays. Null on a page without a header.
 */
@Composable
private fun rememberFocusHeader(fallback: ShowcaseItem?, ready: TitleHeader?): TitleHeader? {
    val focus = LocalFocusedTitle.current ?: return null
    val headers = LocalTitleHeaders.current
    // The poster's title as it reports it now ([FocusedTitle.update]), else the page's.
    val item = rememberShownTitle(focus, keepLast = true) ?: fallback
    LoadWhenStill(item, headers)
    // What the page's state holds of it, worked out with the page: whole at once.
    val prepared = ready?.takeIf { item != null && it.item == item }
    // Again for the same title changed (one made of the history gives way to the catalogue's).
    val header = rememberTitleHeader(item, headers, initial = prepared, restartOn = item)
    // Never an empty band over the posters: what is known of the title at once, if nothing was shown yet.
    return header ?: item?.let { prepared ?: TitleHeader(it) }
}

/**
 * The picture behind a whole TV page, edge to edge and from top to bottom, the rows drawn over it: [item]'s backdrop
 * ([backdropUrl], else its poster, blurred), keeping its upper part ([TvArtworkAlignment]: faces stay whole), fading
 * from one title to the next. Soft shades only, no edge anywhere: dark at the start side behind the words, clear at the
 * end, and darker gradually towards the bottom behind the rows, the picture still showing between the cards.
 */
@Composable
fun TvPageBackdrop(item: ShowcaseItem?, backdropUrl: String?, modifier: Modifier = Modifier, onFailed: (ShowcaseItem, String) -> Unit = { _, _ -> }) {
    val ground = MaterialTheme.colorScheme.background
    Box(modifier.clipToBounds().testTag("pageBackdrop")) {
        if (item != null) {
            Crossfade(HeaderArt(item, backdropUrl), label = "pageBackdrop", modifier = Modifier.fillMaxSize()) { art ->
                TvArtwork(art.backdropUrl, art.item.posterUrl, art.item.title, Modifier.fillMaxSize()) { url -> onFailed(art.item, url) }
            }
        }
        Box(Modifier.matchParentSize().background(startToEndGradient(0f to ground.copy(alpha = 0.9f), 0.35f to ground.copy(alpha = 0.6f), 0.75f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 0.65f to ground.copy(alpha = 0.45f), 1f to ground.copy(alpha = 0.85f))))
    }
}

/**
 * The header of a TV's Movies and Series tabs and of a category's posters, pinned above them: the words of [header]
 * (its title, year and genres, TMDb's rating, else the provider's, when it was added, and two lines of its synopsis)
 * over the page's picture ([TvPageBackdrop]), from the screen's very top under the top bar's shade. Its height never
 * changes. [label] at its foot names the posters, [labelEnd] at the far end of that line (the page's sort chips).
 */
@Composable
fun TvFocusHeader(header: TitleHeader?, modifier: Modifier = Modifier, label: String? = null, labelEnd: (@Composable () -> Unit)? = null) {
    val top = tvTopBarSpace()
    // The bar's band turns into a shade over the picture, from the screen's top.
    TvTopBarOverArt()
    Box(modifier.fillMaxWidth().height(top + TvHeaderHeight + if (label != null) LABEL_ROOM else 0.dp).testTag("titleHeader")) {
        if (header != null) {
            TitleHeaderText(
                header,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = TvTokens.ContentStart, top = top + 8.dp, end = TvTokens.EdgeEnd)
                    .widthIn(max = 560.dp),
            )
        }
        if (label != null) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = TvTokens.ContentStart, end = TvTokens.EdgeEnd, bottom = 2.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("headerLabel"),
                )
                labelEnd?.invoke()
            }
        }
    }
}

/** The room of a grid's label at the header's foot ([TvFocusHeader]): one small line. */
private val LABEL_ROOM = 24.dp

/**
 * A page of posters under the TV's header ([TvFocusHeader]), pinned above it: [content] is given the modifier that
 * fills the room left, its top padding, and whether it is under the header (its posters can then show nothing beneath
 * them), [label] at its foot. Its list keeps the focused poster [lead] below its top; with [byRows] (a grid), the focused row's start at
 * its top padding ([TvRowPivotScrolling]): whole rows, none cut above it. With [sort], the posters' order and Hide
 * watched are chips at the far end of the label's line ([TvSortChips]), which Up from the first row reaches
 * ([upToTheSortChip]). Without a header (a phone), [content] fills the page as it did.
 */
@Composable
fun TvHeaderAbove(
    fallback: ShowcaseItem?,
    lead: Dp = GRID_LEAD,
    byRows: Boolean = false,
    /** The name of the posters, at the header's foot right above the first row (a category's). */
    label: String? = null,
    /** The posters' order, as chips at the end of the label's line; only with [label]. */
    sort: TvSortChoice? = null,
    /** What the page's state holds of [fallback] for the header ([TitleHeader]), worked out with it: shown at once. */
    ready: TitleHeader? = null,
    content: @Composable (modifier: Modifier, top: Dp, underHeader: Boolean) -> Unit,
) {
    val headers = if (isTelevision()) LocalTitleHeaders.current else null
    if (headers == null) {
        content(Modifier.fillMaxSize().tvSafeArea(tab = true), tvTopBarSpace(), false)
        return
    }
    val focus = remember { FocusedTitle() }
    val chips = sort?.takeIf { label != null }
    val sortChip = remember { SortChip() }
    // The chips take the remote only from the first row of posters (Up), or while they or their menu have it.
    var chipsFocused by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var chip by remember { mutableStateOf<LayoutCoordinates?>(null) }
    fun closeMenu() {
        // The chip takes the focus back before the menu leaves: the remote is never left nowhere.
        runCatching { sortChip.requester.requestFocus() }
        sorting = false
    }
    CompositionLocalProvider(LocalFocusedTitle provides focus, LocalSortChip provides sortChip.takeIf { chips != null }) {
        val header = rememberFocusHeader(fallback, ready)
        Box(Modifier.fillMaxSize().onGloballyPositioned { page = it }) {
            // The picture behind the whole page, the header's words and the posters over it.
            TvPageBackdrop(header?.item, header?.backdropUrl, Modifier.matchParentSize()) { item, url -> headers.backdropFailed(item, url) }
            Column(Modifier.fillMaxSize()) {
                TvFocusHeader(
                    header,
                    label = label,
                    labelEnd = chips?.let { choice ->
                        {
                            TvSortChips(
                                choice,
                                sortChip.requester,
                                reachable = { sortChip.fromFirstRow || chipsFocused || sorting },
                                onOpen = { sorting = true },
                                // A group of their own: whether the remote is on either chip, as one.
                                modifier = Modifier
                                    .onFocusChanged {
                                        chipsFocused = it.hasFocus
                                        if (!it.hasFocus) sortChip.fromFirstRow = false
                                    }
                                    .focusGroup(),
                                sortModifier = Modifier.onGloballyPositioned { chip = it },
                            )
                        }
                    },
                )
                val rest = Modifier.weight(1f).fillMaxWidth().tvSafeArea(tab = true)
                // A grid sets its own scrolling once it knows its posters' height ([TvGridScrolling]).
                // Rows run down to the 5 % a TV may crop. Their scrolling keeps a focused poster's growth more above it, as
                // Home's keeps the 5 % ([TvPinnedScrolling]'s edge): wherever a scroll lands the focused card, grown it
                // stays clear of the screen's bottom. Kept as room outside the list instead, it moved the list's own
                // edge up into the growth of a row left where the page opened it, and cut its poster.
                if (byRows) content(rest, UNDER_HEADER_TOP, true) else TvPinnedHeaderList(lead, edge = FOCUS_GROWTH_ROOM) { content(rest, UNDER_HEADER_TOP, true) }
            }
            // Last: over the posters, and its Back before the page's.
            if (sorting && chips != null) {
                val anchor = page?.let { frame -> chip?.takeIf { it.isAttached }?.let { frame.localBoundingBoxOf(it) } }
                if (anchor != null) {
                    TvSortMenu(chips, anchor) { choose ->
                        closeMenu()
                        choose()
                    }
                }
                BackHandler { closeMenu() }
            }
        }
    }
}

/** How far a focused poster (2:3, [TvTokens.PosterWidth] wide) is drawn past its place's end, grown by its focus. */
private val FOCUS_GROWTH_ROOM = TvTokens.PosterWidth * 1.5f * CARD_FOCUS_SPILL

/** A grid's focused poster lands this far below the grid's top. */
private val GRID_LEAD = 12.dp

/** The room above the first row under the header: a focused poster grows into it, never cut by the header. */
private val UNDER_HEADER_TOP = 8.dp

/**
 * A grid under a TV's header scrolls by whole rows ([TvRowPivotScrolling]): each focused row's poster at [top] below the
 * grid's top, as the first row's is when the page opens. What the grid scrolls into view is the focused poster as it is
 * drawn, grown ([FOCUS_GROW]) around its middle: its grown top goes that much higher, the rows below where [top] says.
 */
@Composable
fun TvGridScrolling(top: Dp, posterHeight: Dp, content: @Composable () -> Unit) {
    val grownBy = posterHeight * ((FOCUS_GROW - 1f) / 2f)
    TvPinnedHeaderList(lead = top - grownBy, byRows = true, content = content)
}

/** The posters across a grid under a TV's header: five, bigger than a phone's, to read from the sofa. */
const val TV_GRID_COLUMNS = 5

/**
 * The room below a grid's last row under a TV's header ([TvRowPivotScrolling]), in a grid [width] by [height] with
 * [gutter] at either side, [gap] between its posters (2:3) and [top] above its first row: enough for the last row too
 * to come up to the top, the row above it gone, rather than stop lower with that row cut under the header.
 */
fun tvGridBottomRoom(width: Dp, height: Dp, gutter: Dp, gap: Dp, top: Dp): Dp =
    // A pixel's rounding to spare.
    maxOf(height - top - tvGridPosterHeight(width, gutter, gap) + 2.dp, 32.dp)

/** A poster's height (2:3) in a grid [width] wide with [gutter] at either side and [gap] between its posters. */
fun tvGridPosterHeight(width: Dp, gutter: Dp, gap: Dp): Dp = (width - gutter * 2 - gap * (TV_GRID_COLUMNS - 1)) / TV_GRID_COLUMNS * 1.5f

/**
 * The header's words: the title on one line, then its facts on one line, then the synopsis on two lines kept even
 * when there is none, so the header keeps one height. Unknown facts leave no gap: a title without genres shows its
 * category.
 */
@Composable
fun TitleHeaderText(header: TitleHeader, modifier: Modifier = Modifier, titleStyle: TextStyle = MaterialTheme.typography.headlineMedium) {
    Column(modifier.testTag("headerText")) {
        TitleSlot(header, titleStyle)
        TitleFacts(header, Modifier.padding(top = 4.dp))
        // The synopsis' room is its two lines' height and the header's width, set rather than measured: an empty
        // one keeps it too (an empty text alone would be as narrow as its words, nothing).
        val plotStyle = MaterialTheme.typography.bodyMedium
        val lineHeight = if (plotStyle.lineHeight.isSp) plotStyle.lineHeight else plotStyle.fontSize * 1.4f
        val plotHeight = with(LocalDensity.current) { lineHeight.toDp() } * PLOT_LINES
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(plotHeight).testTag("headerPlotRoom")) {
            Text(
                header.plot.orEmpty(),
                style = plotStyle,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = PLOT_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("headerPlot"),
            )
        }
    }
}

/** The title's slot in the header: as high for its logo as for its name, so nothing moves when the logo arrives. */
private val TITLE_SLOT = 48.dp

/** A logo's widest, as a share of the screen's width. */
private const val LOGO_WIDTH_SHARE = 0.4f

/**
 * The title's line of the header: its logo ([TitleHeader.logoUrl], TMDb's picture of the title) once it has loaded,
 * crossfading from its name; its name, without the provider's tags ([CatalogueMatcher.displayTitle]), with no logo, while
 * it loads or when it fails.
 */
@Composable
private fun TitleSlot(header: TitleHeader, titleStyle: TextStyle) {
    TvTitleOrLogo(header.item.title, header.logoUrl, titleStyle, Modifier.height(TITLE_SLOT), maxLines = 1, textTag = "headerTitle", logoTag = "headerLogo")
}

/**
 * A title on a TV, as its header and its page show it: its logo ([logoUrl], TMDb's picture of the title) once it has
 * loaded, crossfading from its name; its name without the provider's tags ([CatalogueMatcher.displayTitle]) while the
 * logo loads, when it fails or when there is none. The name keeps its room under the logo ([modifier] gives the slot
 * its height): nothing moves when the logo arrives. [title] is the provider's name, as stored; only its display is cleaned.
 * [fillsSlot]: the logo takes the whole slot (a slot as wide as the page's words, its height the name's); otherwise it
 * takes the slot's set height, as wide as it needs up to [LOGO_WIDTH_SHARE] of the screen.
 */
@Composable
internal fun TvTitleOrLogo(
    title: String,
    logoUrl: String?,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    fillsSlot: Boolean = false,
    textTag: String,
    logoTag: String,
) {
    var logoLoaded by remember(logoUrl) { mutableStateOf(false) }
    val logoAlpha by animateFloatAsState(if (logoUrl != null && logoLoaded) 1f else 0f, label = "titleLogo")
    val name = remember(title) { CatalogueMatcher.displayTitle(title) }
    val widest = LocalWindowInfo.current.containerDpSize.width * LOGO_WIDTH_SHARE
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Text(
            name,
            style = style,
            fontWeight = FontWeight.ExtraBold,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer { alpha = 1f - logoAlpha }.testTag(textTag),
        )
        if (logoUrl != null) {
            AsyncImage(
                model = logoUrl,
                // The name says it: TalkBack reads it once.
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onSuccess = { logoLoaded = true },
                modifier = (if (fillsSlot) Modifier.matchParentSize() else Modifier.fillMaxHeight().widthIn(max = widest))
                    .graphicsLayer { alpha = logoAlpha }
                    .testTag(logoTag),
            )
        }
    }
}

/** TMDb's colour, for its rating's badge. */
private val TmdbBlue = Color(0xFF01B4E4)

/** "2024 · Drama, Romance", TMDb's rating with its badge (else the provider's "★ 7.1"), and when it was added. */
@Composable
private fun TitleFacts(header: TitleHeader, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val item = header.item
    val words = listOfNotNull(item.year?.toString(), header.genres.joinToString(", ").ifEmpty { header.category })
    val date = item.addedAt?.takeIf { it > 0 }?.let { seconds -> remember(seconds, locale) { addedDate(seconds, locale) } }
    Row(modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (words.isNotEmpty()) {
            Text(
                words.joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).testTag("headerFacts"),
            )
        }
        val tmdb = header.tmdbRating
        if (tmdb != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "TMDb",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TvTokens.Ink,
                    maxLines = 1,
                    modifier = Modifier.background(TmdbBlue, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp).testTag("headerTmdb"),
                )
                Text(tmdb, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 6.dp).testTag("headerRating"))
            }
        } else {
            header.providerRating?.let {
                Text("★ $it", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.testTag("headerRating"))
            }
        }
        if (date != null) {
            // A series' date is when the provider last changed it (a new episode), not when it was added.
            Text(
                stringResource(if (item is ShowcaseItem.SeriesItem) R.string.title_updated_on else R.string.title_added_on, date),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                modifier = Modifier.testTag("headerAdded"),
            )
        }
    }
}

/** A date of the provider's (epoch seconds) in the app's language: "12 Mar 2026". */
internal fun addedDate(seconds: Long, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))
