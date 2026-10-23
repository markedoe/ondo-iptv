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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
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
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.components.Artwork
import com.markedoe.ondo.ui.components.ChannelLogo
import com.markedoe.ondo.ui.components.PlayIcon
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.theme.OndoColors
import kotlinx.coroutines.delay

/**
 * The look of Ondo on Android TV and Google TV: Google's Compose for TV components (cards that grow with a white
 * border on focus, buttons that turn white), on Ondo's dark ground with its lime for progress and the selected tab.
 * Sizes follow Google's TV guidelines for a 960 × 540 dp screen: 48 × 27 dp kept clear at the edges, rows of cards
 * 20 dp apart with the next one peeking.
 */
object TvTokens {
    /** Where the drawer's icons start: clear of the 5 % a TV may crop. */
    val DrawerStart = 32.dp

    /** Where the pages' text and rows start, beside the closed drawer. */
    val ContentStart = 112.dp

    /** The right and bottom margins a TV may crop (TV-OV). */
    val EdgeEnd = 48.dp
    val EdgeTop = 27.dp

    val CardGap = 20.dp
    val PosterWidth = 124.dp
    val WideCardWidth = 196.dp
    val CardShape = RoundedCornerShape(10.dp)

    val Ink = OndoColors.Ink
    val Lime = OndoColors.Lime
}

/** Compose for TV's theme in Ondo's colours: dark, white focus, lime accent. */
@Composable
fun OndoTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = OndoColors.Lime,
            onPrimary = Color.Black,
            secondary = OndoColors.Cream,
            onSecondary = Color.Black,
            secondaryContainer = Color.White.copy(alpha = 0.22f),
            onSecondaryContainer = Color.White,
            background = OndoColors.Ink,
            onBackground = Color.White,
            surface = OndoColors.Ink,
            onSurface = Color.White,
            surfaceVariant = OndoColors.SurfaceHigh,
            onSurfaceVariant = Color(0xFFB8C2BD),
            inverseSurface = Color.White,
            inverseOnSurface = OndoColors.Ink,
            border = Color.White,
            borderVariant = Color.White.copy(alpha = 0.4f),
            scrim = Color.Black,
        ),
    ) {
        // Text outside a Compose for TV surface takes this colour (black otherwise).
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
    }
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

/** The scale, white border and soft glow every card takes with the remote's focus. */
@Composable
private fun cardFocus(shape: RoundedCornerShape = TvTokens.CardShape) = Triple(
    CardDefaults.scale(focusedScale = 1.08f),
    CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
    CardDefaults.glow(focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.18f), elevation = 12.dp)),
)

/** A titled row of cards, starting at [start] and peeking past the right edge. */
fun LazyListScope.tvShelf(title: String, tag: String, start: Dp = TvTokens.ContentStart, content: LazyListScope.() -> Unit) {
    item(key = "shelf:$tag") {
        Column(Modifier.testTag(tag)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = start, bottom = 12.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(start = start, end = TvTokens.EdgeEnd, top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(TvTokens.CardGap),
                content = content,
            )
        }
    }
}

/** A 2:3 poster; the title is the artwork's (or the generated cover's) and TalkBack's. */
@Composable
fun TvPosterCard(item: ShowcaseItem, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = TvTokens.PosterWidth) {
    val (scale, border, glow) = cardFocus()
    Card(
        onClick = onClick,
        modifier = modifier.width(width).aspectRatio(2f / 3f).semantics { contentDescription = item.title },
        shape = CardDefaults.shape(TvTokens.CardShape),
        scale = scale,
        border = border,
        glow = glow,
    ) {
        Artwork(url = item.posterUrl, title = item.title, modifier = Modifier.fillMaxSize())
    }
}

/** A 16:9 "continue watching" card: the picture with its progress, the title and the time left beneath. */
@Composable
fun TvResumeCard(item: ResumeItem, onClick: () -> Unit, modifier: Modifier = Modifier, preparing: Boolean = false) {
    val (scale, border, glow) = cardFocus()
    Column(modifier.width(TvTokens.WideCardWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).semantics { contentDescription = item.title },
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
            }
        }
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                item.subtitle,
                if (item.minutesLeft > 1) pluralStringResource(R.plurals.minutes_left, item.minutesLeft, item.minutesLeft) else null,
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
    Column(modifier.width(156.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).semantics { contentDescription = row.channel.name },
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
        }
        Text(row.channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        row.nowTitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A thin progress bar: lime over a light track. */
@Composable
fun TvProgress(value: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.height(height).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(height).background(TvTokens.Lime))
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

/** A round 1:1 card with initials, for people (cast and crew); [role] beneath the name (Director). */
@Composable
fun TvPersonCard(name: String, onClick: () -> Unit, modifier: Modifier = Modifier, role: String? = null) {
    val (scale, _, glow) = cardFocus()
    Column(modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.size(88.dp).semantics { contentDescription = name },
            shape = CardDefaults.shape(CircleShape),
            colors = CardDefaults.colors(containerColor = OndoColors.SurfaceHigh),
            scale = scale,
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CircleShape)),
            glow = glow,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(initials(name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f))
            }
        }
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
fun TvActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = TvTokens.Ink,
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        content = content,
    )
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
