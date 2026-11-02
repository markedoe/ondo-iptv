package com.markedoe.ondo.ui.tv

import androidx.compose.animation.Crossfade
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

/** The header's height below the top bar, the same whatever it shows: the rows below never move with it. */
val TvHeaderHeight = 190.dp

/** The posters' width under the header: a little smaller, for a whole row to show below it. */
val TvHeaderPosterWidth = 112.dp

/**
 * The landscape cards' width under the header (continue watching): as Home's, 16:9 with their two lines below, for
 * the row with its name to fit under the header on a 960 × 540 dp TV.
 */
val TvHeaderWideCardWidth = TvTokens.WideCardWidth

/** The synopsis' lines, their room kept even when there is none. */
private const val PLOT_LINES = 3

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
    LaunchedEffect(focus, focused?.key) {
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
 * What the header shows of [item]: the title, year and poster at once, the rest as it is known. The title shown before
 * stays until the new one's is ready, so nothing flashes empty between two posters.
 */
@Composable
fun rememberTitleHeader(item: ShowcaseItem?, headers: TitleHeaders?): TitleHeader? {
    val header by produceState(item?.let { TitleHeader(it) }, item?.key, headers) {
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
 * The header of a TV's Movies and Series tabs and of a category's posters, pinned above them: the poster with the
 * focus, its backdrop behind (or its poster, blurred) from the screen's very top, under the top bar's shade, fading
 * into the page at the start side and at the bottom; its title, year and genres, TMDb's rating (else the provider's),
 * when it was added, and three lines of its synopsis. The backdrop keeps its upper part ([TvArtworkAlignment]): faces
 * stay whole. Its height never changes; without a poster focused yet (the focus on the top bar's tab), it shows
 * [fallback]: the page's first card's title, the one Down from the tab reaches.
 */
@Composable
fun TvFocusHeader(fallback: ShowcaseItem?, modifier: Modifier = Modifier) {
    val focus = LocalFocusedTitle.current ?: return
    val headers = LocalTitleHeaders.current
    val item = rememberShownTitle(focus, keepLast = true) ?: fallback
    LoadWhenStill(item, headers)
    val header = rememberTitleHeader(item, headers)
    val ground = MaterialTheme.colorScheme.background
    val top = tvTopBarSpace()
    // The bar's band turns into a shade over the picture, from the screen's top.
    TvTopBarOverArt()
    // From the screen's top, under the top bar too: the picture runs to the screen's edges, the bar's shade over it.
    Box(modifier.fillMaxWidth().height(top + TvHeaderHeight).clipToBounds().testTag("titleHeader")) {
        if (header != null) {
            Crossfade(HeaderArt(header.item, header.backdropUrl), label = "headerArt", modifier = Modifier.fillMaxSize()) { art ->
                TvArtwork(art.backdropUrl, art.item.posterUrl, art.item.title, Modifier.fillMaxSize()) { url -> headers?.backdropFailed(art.item, url) }
            }
        }
        Box(Modifier.matchParentSize().background(startToEndGradient(0f to ground, 0.3f to ground.copy(alpha = 0.88f), 0.72f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to ground)))
        if (header != null) {
            TitleHeaderText(
                header,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = TvTokens.ContentStart, top = top + 12.dp, end = TvTokens.EdgeEnd)
                    .widthIn(max = 560.dp),
            )
        }
    }
}

/**
 * A page of posters under the TV's header ([TvFocusHeader]), pinned above it: [content] is given the modifier that
 * fills the room left, its top padding, and whether it is under the header (its posters can then be smaller, with
 * nothing beneath them). Its list keeps the focused poster [lead] below its top. Without a header (a phone), [content]
 * fills the page as it did.
 */
@Composable
fun TvHeaderAbove(fallback: ShowcaseItem?, lead: Dp = GRID_LEAD, content: @Composable (modifier: Modifier, top: Dp, underHeader: Boolean) -> Unit) {
    val headers = if (isTelevision()) LocalTitleHeaders.current else null
    if (headers == null) {
        content(Modifier.fillMaxSize().tvSafeArea(tab = true), tvTopBarSpace(), false)
        return
    }
    val focus = remember { FocusedTitle() }
    CompositionLocalProvider(LocalFocusedTitle provides focus) {
        Column(Modifier.fillMaxSize()) {
            TvFocusHeader(fallback)
            val rest = Modifier.weight(1f).fillMaxWidth().tvSafeArea(tab = true)
            TvPinnedHeaderList(lead) { content(rest, 8.dp, true) }
        }
    }
}

/** A grid's focused poster lands this far below the grid's top. */
private val GRID_LEAD = 12.dp

/**
 * The header's words: the title on one line, then its facts on one line, then the synopsis on three lines kept even
 * when there is none, so the header keeps one height. Unknown facts leave no gap: a title without genres shows its
 * category.
 */
@Composable
fun TitleHeaderText(header: TitleHeader, modifier: Modifier = Modifier, titleStyle: TextStyle = MaterialTheme.typography.headlineMedium) {
    Column(modifier.testTag("headerText")) {
        Text(
            header.item.title,
            style = titleStyle,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("headerTitle"),
        )
        TitleFacts(header, Modifier.padding(top = 8.dp))
        // The synopsis' room is its three lines' height and the header's width, set rather than measured: an empty
        // one keeps it too (an empty text alone would be as narrow as its words, nothing).
        val plotStyle = MaterialTheme.typography.bodyMedium
        val lineHeight = if (plotStyle.lineHeight.isSp) plotStyle.lineHeight else plotStyle.fontSize * 1.4f
        val plotHeight = with(LocalDensity.current) { lineHeight.toDp() } * PLOT_LINES
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(plotHeight).testTag("headerPlotRoom")) {
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
