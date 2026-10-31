package com.markedoe.ondo.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil3.compose.AsyncImage
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.components.Artwork
import com.markedoe.ondo.ui.components.ChannelLogo
import com.markedoe.ondo.ui.components.LocalPosterMyList
import com.markedoe.ondo.ui.components.MyListBadge
import com.markedoe.ondo.ui.components.PlayIcon
import com.markedoe.ondo.ui.components.ResumeCardMenu
import com.markedoe.ondo.ui.components.posterLongClick
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.timeLeftText
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.theme.OndoColors
import com.markedoe.ondo.ui.theme.inter
import kotlinx.coroutines.delay

/**
 * The look of Ondo on Android TV and Google TV: Google's Compose for TV components on pure black, neutral greys and
 * Inter; a card that has the focus rises, with a shadow and a sheen, and a button turns white.
 * Sizes follow Google's TV guidelines for a 960 × 540 dp screen: 48 × 27 dp kept clear at the edges, rows of cards
 * 20 dp apart with the next one peeking.
 */
object TvTokens {
    /** Where the pages' text and rows start: clear of the 5 % a TV may crop, with room for a card that grows. */
    val ContentStart = 56.dp

    /** Where a tab page's content starts, below the top bar ([TvNavigationFrame]). */
    val TopBarSpace = 84.dp

    /** The right and bottom margins a TV may crop (TV-OV). */
    val EdgeEnd = 48.dp
    val EdgeTop = 27.dp

    val CardGap = 20.dp
    val PosterWidth = 124.dp
    val WideCardWidth = 196.dp
    val CardShape = RoundedCornerShape(10.dp)

    val Ink = OndoColors.Ink
    val Accent = OndoColors.Accent
}

/** Compose for TV's theme in Ondo's colours: pure black, neutral greys, white focus; Inter throughout. */
@Composable
fun OndoTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = OndoColors.Accent,
            onPrimary = Color.Black,
            secondary = Color.White,
            onSecondary = Color.Black,
            secondaryContainer = Color.White.copy(alpha = 0.22f),
            onSecondaryContainer = Color.White,
            background = OndoColors.Ink,
            onBackground = Color.White,
            surface = OndoColors.Ink,
            onSurface = Color.White,
            surfaceVariant = OndoColors.SurfaceHigh,
            onSurfaceVariant = OndoColors.Secondary,
            inverseSurface = Color.White,
            inverseOnSurface = OndoColors.Ink,
            border = Color.White,
            borderVariant = Color.White.copy(alpha = 0.4f),
            scrim = Color.Black,
        ),
        typography = TvTypography,
    ) {
        // Text outside a Compose for TV surface takes this colour (black otherwise).
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
    }
}

private val TvTypography: androidx.tv.material3.Typography = androidx.tv.material3.Typography().let { t ->
    androidx.tv.material3.Typography(
        displayLarge = t.displayLarge.inter(tight = true),
        displayMedium = t.displayMedium.inter(tight = true).copy(fontWeight = FontWeight.ExtraBold),
        displaySmall = t.displaySmall.inter(tight = true).copy(fontWeight = FontWeight.ExtraBold),
        headlineLarge = t.headlineLarge.inter(tight = true),
        headlineMedium = t.headlineMedium.inter(tight = true),
        headlineSmall = t.headlineSmall.inter(tight = true),
        titleLarge = t.titleLarge.inter(),
        titleMedium = t.titleMedium.inter(),
        titleSmall = t.titleSmall.inter(),
        bodyLarge = t.bodyLarge.inter(),
        bodyMedium = t.bodyMedium.inter(),
        bodySmall = t.bodySmall.inter(),
        labelLarge = t.labelLarge.inter(),
        labelMedium = t.labelMedium.inter(),
        labelSmall = t.labelSmall.inter(),
    )
}

/**
 * A gradient from the start edge towards the end: from the left, or from the right in a right-to-left language,
 * where the text and the drawer are on the right.
 */
@Composable
fun startToEndGradient(vararg stops: Pair<Float, Color>): Brush {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return Brush.horizontalGradient(
        *stops,
        startX = if (rtl) Float.POSITIVE_INFINITY else 0f,
        endX = if (rtl) 0f else Float.POSITIVE_INFINITY,
    )
}

/**
 * Gives the focus back to [requester] once a dialog that was [open] closes: without it, the remote's focus is lost
 * on the page behind and the next key press does nothing.
 */
@Composable
fun FocusBackAfter(open: Boolean, requester: FocusRequester) {
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            wasOpen = true
        } else if (wasOpen) {
            wasOpen = false
            runCatching { requester.requestFocus() }
        }
    }
}

/**
 * The choices of OK held on a "Continue watching" card ([com.markedoe.ondo.ui.components.ResumeCardMenu]): Go to its
 * page, where the remote starts, then Remove from History ([removeLabel], null for none). The OK that opened it, still
 * held, chooses nothing.
 */
@Composable
fun TvResumeMenu(title: String, openLabel: String, onOpen: () -> Unit, removeLabel: String?, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = SurfaceDefaults.colors(containerColor = OndoColors.SurfaceHigh, contentColor = Color.White),
            modifier = Modifier.widthIn(min = 360.dp, max = 480.dp).newOkPressesOnly().testTag("resumeMenu"),
        ) {
            Column(Modifier.padding(vertical = 24.dp, horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
                FocusOnOpen(first)
                ListItem(
                    selected = false,
                    onClick = onOpen,
                    headlineContent = { Text(openLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    modifier = Modifier.focusRequester(first).testTag("resumeOpen"),
                    colors = tvRowColors(),
                    shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
                )
                removeLabel?.let { label ->
                    ListItem(
                        selected = false,
                        onClick = onRemove,
                        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(24.dp)) },
                        modifier = Modifier.testTag("resumeRemove"),
                        colors = tvRowColors(),
                        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
                    )
                }
            }
        }
    }
}

/**
 * Puts the focus on [requester] when a dialog opens: called inside the dialog, it waits for the dialog's window to
 * lay the item out (a few frames), so the remote starts on it.
 */
@Composable
fun FocusOnOpen(requester: FocusRequester) {
    LaunchedEffect(requester) {
        repeat(20) {
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(16)
        }
    }
}

/**
 * What every card does with the remote's focus: it rises (grows, with a deep shadow beneath) and catches the light
 * ([CardSheen]); no border.
 */
@Composable
private fun cardFocus() = Triple(
    CardDefaults.scale(focusedScale = 1.08f),
    CardDefaults.border(),
    CardDefaults.glow(focusedGlow = Glow(elevationColor = Color.Black.copy(alpha = 0.9f), elevation = 18.dp)),
)

/** The light a focused card catches: a soft white sheen from its top-left corner. Drawn over the card's picture. */
@Composable
fun BoxScope.CardSheen(focused: Boolean) {
    if (focused) {
        Box(Modifier.matchParentSize().background(Brush.linearGradient(0f to Color.White.copy(alpha = 0.26f), 0.4f to Color.Transparent)))
    }
}

/** A titled row of cards, starting at [start] and peeking past the right edge. */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.tvShelf(title: String, tag: String, start: Dp = TvTokens.ContentStart, content: LazyListScope.() -> Unit) {
    item(key = "shelf:$tag") {
        Column(Modifier.testTag(tag)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = start, bottom = 12.dp),
            )
            // A screen of cards ahead is composed (and its pictures asked for) before it scrolls in.
            val row = rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 0.5f))
            TvRow {
                LazyRow(
                    state = row,
                    contentPadding = PaddingValues(start = start, end = TvTokens.EdgeEnd, top = 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(TvTokens.CardGap),
                    content = content,
                )
            }
        }
    }
}

/** A 2:3 poster; the title is the artwork's (or the generated cover's) and TalkBack's. */
@Composable
fun TvPosterCard(item: ShowcaseItem, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = TvTokens.PosterWidth) {
    val (scale, border, glow) = cardFocus()
    var focused by remember { mutableStateOf(false) }
    // OK held adds the title to My List or takes it away.
    val myList = LocalPosterMyList.current
    Card(
        onClick = onClick,
        onLongClick = posterLongClick(item),
        modifier = modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = item.title },
        shape = CardDefaults.shape(TvTokens.CardShape),
        scale = scale,
        border = border,
        glow = glow,
    ) {
        Box(Modifier.fillMaxSize()) {
            Artwork(url = item.posterUrl, title = item.title, modifier = Modifier.fillMaxSize())
            CardSheen(focused)
            if (myList != null && item.key in myList.keys) MyListBadge(Modifier.align(Alignment.TopEnd))
        }
    }
}

/**
 * A 16:9 "continue watching" card: the picture with its progress, the title and the time left beneath. OK held down
 * asks whether to take the card away ([onRemove]).
 */
@Composable
fun TvResumeCard(
    item: ResumeItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    preparing: Boolean = false,
    onRemove: (() -> Unit)? = null,
    /** OK held offers the movie's page, or the series' of the episode, too ([ResumeCardMenu]). */
    onOpen: (() -> Unit)? = null,
) {
    val (scale, border, glow) = cardFocus()
    var asking by remember { mutableStateOf(false) }
    if (asking) ResumeCardMenu(item, onOpen, onRemove, onDismiss = { asking = false })
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(TvTokens.WideCardWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            onClick = onClick,
            onLongClick = if (onRemove != null || onOpen != null) ({ asking = true }) else null,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = item.title },
            shape = CardDefaults.shape(TvTokens.CardShape),
            scale = scale,
            border = border,
            glow = glow,
        ) {
            Box(Modifier.fillMaxSize()) {
                Artwork(url = item.imageUrl, title = item.title, modifier = Modifier.fillMaxSize(), showsTitleFallback = false)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
                PlayIcon(preparing, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 12.dp).size(22.dp), tint = Color.White)
                TvProgress(item.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                CardSheen(focused)
            }
        }
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                item.subtitle,
                if (item.minutesLeft > 1) timeLeftText(item.minutesLeft) else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A live channel: its logo on a dark card, the name and what is on now beneath. */
@Composable
fun TvChannelCard(row: ChannelRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (scale, border, glow) = cardFocus()
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(156.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = row.channel.name },
            shape = CardDefaults.shape(TvTokens.CardShape),
            colors = CardDefaults.colors(containerColor = OndoColors.SurfaceHigh),
            scale = scale,
            border = border,
            glow = glow,
        ) {
            Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
                ChannelLogo(url = row.channel.logoUrl, name = row.channel.name)
            }
            row.nowProgress?.let { TvProgress(it, Modifier.fillMaxWidth()) }
            Box(Modifier.fillMaxSize()) { CardSheen(focused) }
        }
        Text(row.channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        row.nowTitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A thin progress bar: white over a dim track. */
@Composable
fun TvProgress(value: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.height(height).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(height).background(TvTokens.Accent))
    }
}

/** A small outlined tag for the format or the quality (MKV, 4K, 5.1…). */
@Composable
fun TvBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Facts of a title ("2024 · 1 h 52 · ★ 7.1"), the format last as a badge. */
@Composable
fun TvFacts(facts: List<String>, badges: List<String>, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (facts.isNotEmpty()) {
            Text(
                facts.joinToString("  ·  "),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        badges.forEach { TvBadge(it) }
    }
}

/**
 * A round 1:1 card for people (cast and crew): their photo ([photoUrl], TMDb's), over their initials, which show
 * while it loads and when there is none; [role] beneath the name (Director).
 */
@Composable
fun TvPersonCard(name: String, onClick: () -> Unit, modifier: Modifier = Modifier, role: String? = null, photoUrl: String? = null) {
    val (scale, _, glow) = cardFocus()
    Column(modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.size(72.dp).semantics { contentDescription = name },
            shape = CardDefaults.shape(CircleShape),
            colors = CardDefaults.colors(containerColor = OndoColors.SurfaceHigh),
            scale = scale,
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CircleShape)),
            glow = glow,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(initials(name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f))
                if (photoUrl != null) {
                    AsyncImage(model = photoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag("personPhoto"))
                }
            }
        }
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        role?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
    }
}

internal fun initials(name: String): String =
    name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

/** "Play" / "Resume" in the language of the interface. */
@Composable
fun playLabel(resumable: Boolean): String = stringResource(if (resumable) R.string.resume else R.string.play)

/**
 * A button of the TV pages: translucent, white with dark text when the remote is on it (Compose for TV's own
 * focus), slightly larger.
 */
@Composable
fun TvActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, prominent: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
        // The main action (Play) is white even without the focus, which then makes it grow, with a shadow.
        glow = ButtonDefaults.glow(focusedGlow = Glow(elevationColor = Color.Black.copy(alpha = 0.8f), elevation = 14.dp)),
        colors = ButtonDefaults.colors(
            containerColor = if (prominent) Color.White else Color.White.copy(alpha = 0.2f),
            contentColor = if (prominent) TvTokens.Ink else Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = TvTokens.Ink,
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        content = content,
    )
}

/**
 * A secondary action of a title's page: a round button with its icon, its name written beneath it (TalkBack reads
 * the name once, from the button).
 */
@Composable
fun TvCircleAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = onClick,
            modifier = modifier.semantics { contentDescription = label },
            shape = ButtonDefaults.shape(CircleShape),
            scale = ButtonDefaults.scale(focusedScale = 1.1f),
            glow = ButtonDefaults.glow(focusedGlow = Glow(elevationColor = Color.Black.copy(alpha = 0.8f), elevation = 14.dp)),
            colors = ButtonDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.2f),
                contentColor = Color.White,
                focusedContainerColor = Color.White,
                focusedContentColor = TvTokens.Ink,
            ),
            contentPadding = PaddingValues(12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/**
 * Brings this block into view as a whole when something in it takes the focus, rather than only the focused child
 * (Compose for TV does the same for its carousel): a header with buttons is never left cut at the top.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bringIntoViewAsAWhole(): Modifier = composed {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    this
        .onSizeChanged { bounds = Rect(Offset.Zero, Offset(it.width.toFloat(), it.height.toFloat())) }
        .bringIntoViewResponder(
            remember {
                object : androidx.compose.foundation.relocation.BringIntoViewResponder {
                    override fun calculateRectForParent(localRect: Rect): Rect = bounds
                    override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
                }
            },
        )
}
