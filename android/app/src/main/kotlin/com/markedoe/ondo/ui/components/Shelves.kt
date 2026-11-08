package com.markedoe.ondo.ui.components

import com.markedoe.ondo.core.library.CatalogueMatcher
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.StarHalf
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.model.PosterFacts
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.theme.OndoColors
import com.markedoe.ondo.ui.timeLeftText
import com.markedoe.ondo.ui.tv.LocalHeldOk
import com.markedoe.ondo.ui.tv.TvConfirmDialog
import com.markedoe.ondo.ui.tv.TvResumeMenu
import com.markedoe.ondo.ui.tv.TvRow
import com.markedoe.ondo.ui.tv.focusFrame
import com.markedoe.ondo.ui.tv.focusMemory
import com.markedoe.ondo.ui.tv.reportsFocus
import com.markedoe.ondo.ui.tv.isTelevision

/** Sizes of posters, cards and margins for phones and wide screens. */
data class ShelfMetrics(val compact: Boolean) {
    val posterWidth: Dp get() = if (compact) 116.dp else 150.dp
    val wideCardWidth: Dp get() = if (compact) 264.dp else 320.dp
    val gutter: Dp get() = if (compact) 16.dp else 24.dp
}

@Composable
fun rememberShelfMetrics(): ShelfMetrics = ShelfMetrics(compact = LocalWindowInfo.current.containerDpSize.width < 600.dp)

/** A titled horizontal row with an optional "See All". */
@Composable
fun Shelf(
    title: String,
    metrics: ShelfMetrics,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = metrics.gutter, end = metrics.gutter - 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // On a TV, "See all" is the row's last card instead (hard to reach above the row with a remote).
            if (onSeeAll != null && !isTelevision()) {
                TextButton(onClick = onSeeAll, modifier = Modifier.testTag("seeAll")) {
                    Text(stringResource(R.string.see_all))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }
        TvRow {
            LazyRow(
                modifier = Modifier.testTag("shelfRow"),
                contentPadding = PaddingValues(horizontal = metrics.gutter),
                horizontalArrangement = Arrangement.spacedBy(if (metrics.compact) 10.dp else 14.dp),
                content = content,
            )
        }
    }
}

/**
 * My List on a TV's posters: those kept ([keys], as [ShowcaseItem.key]) show a check mark, and OK held on a poster
 * adds it or takes it away ([toggle]). Null elsewhere: a phone's posters stay as they are.
 */
@Stable
class PosterMyList(val keys: Set<String>, val toggle: (ShowcaseItem) -> Unit)

val LocalPosterMyList = compositionLocalOf<PosterMyList?> { null }

/** The posters shown are My List's: one taken out of it leaves the page (see [HeldOk]). */
val LocalShowsMyList = staticCompositionLocalOf { false }

/** OK held on a poster (its long press): in My List, or out of it; null where posters have no long press. */
@Composable
fun posterLongClick(item: ShowcaseItem): (() -> Unit)? {
    val myList = LocalPosterMyList.current ?: return null
    val held = LocalHeldOk.current
    val leaves = LocalShowsMyList.current
    return {
        // Taken out of My List while My List is shown, the poster leaves: the rest of OK held is for no other poster.
        if (leaves && item.key in myList.keys) held?.arm()
        myList.toggle(item)
    }
}

/** The click of a poster, with OK held (a long press) adding it to My List or taking it away where [LocalPosterMyList] says so. */
@Composable
internal fun Modifier.posterClick(item: ShowcaseItem, interaction: MutableInteractionSource, indication: Indication?, onClick: () -> Unit): Modifier {
    val longClick = posterLongClick(item) ?: return clickable(interactionSource = interaction, indication = indication, onClick = onClick)
    return combinedClickable(
        interactionSource = interaction,
        indication = indication,
        onLongClickLabel = stringResource(R.string.toggle_favorite_action),
        onLongClick = longClick,
        onClick = onClick,
    )
}

/** TalkBack's words for a poster: its title, that it is watched, and that it is in My List where its check mark shows it. */
@Composable
internal fun Modifier.posterSemantics(item: ShowcaseItem, kept: Boolean): Modifier {
    val states = listOfNotNull(
        if (isWatched(item)) stringResource(R.string.watched) else null,
        if (kept) stringResource(R.string.in_my_list) else null,
    )
    val state = states.takeIf { it.isNotEmpty() }?.joinToString(", ")
    return semantics {
        contentDescription = item.title
        if (state != null) stateDescription = state
    }
}

/**
 * The keys ([ShowcaseItem.key]) of the titles watched in the current playlist (`UiMappers.watchedKeys`), on every device:
 * each thumbnail shows its watched badge with one lookup.
 */
val LocalWatchedTitles = compositionLocalOf<Set<String>> { emptySet() }

/** Whether the title is watched, as [LocalWatchedTitles] says. */
@Composable
fun isWatched(item: ShowcaseItem): Boolean = item.key in LocalWatchedTitles.current

/**
 * The check mark of a watched title on its thumbnail: white on a dark disc with a white ring, legible on any poster, in
 * the start corner (the end one has My List's), never over the title below. TalkBack says it through the card.
 */
@Composable
fun WatchedBadge(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Box(
        modifier
            .padding(6.dp)
            .size(size)
            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            .border(1.2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            .testTag(WATCHED_BADGE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.7f))
    }
}

/** The tag of [WatchedBadge], for the tests. */
const val WATCHED_BADGE_TAG = "watchedBadge"

/** Whether the poster is in My List, where [LocalPosterMyList] says so. */
@Composable
fun inMyList(item: ShowcaseItem): Boolean = LocalPosterMyList.current?.keys?.contains(item.key) == true

/** A small white check in a poster's corner: the title is in My List. */
@Composable
fun MyListBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(6.dp)
            .size(22.dp)
            .background(Color.White, CircleShape)
            .testTag("inMyList"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
    }
}

/**
 * Poster opening the detail page. [focusKey] tells it apart from the same title elsewhere on the page (another
 * shelf), for the focus to come back to the right one.
 */
@Composable
fun PosterCard(
    item: ShowcaseItem,
    width: Dp?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    showsTitle: Boolean = false,
    focusKey: String = item.key,
    /** The page's first item: it takes the focus on a TV when the page opens. */
    initialFocus: Boolean = false,
    /**
     * With [showsTitle], the rating and the genres below the year (a category's page on a TV); their room is kept
     * while they are not known yet, or when there are none. Null: none.
     */
    facts: PosterFacts? = null,
    showsFacts: Boolean = facts != null,
) {
    val interaction = remember { MutableInteractionSource() }
    val kept = inMyList(item)
    val watched = isWatched(item)
    if (!showsTitle) {
        Column(
            modifier = modifier
                .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
                .pressScale(interaction)
                .focusFrame(PosterShape)
                .focusMemory(focusKey, initialFocus)
                .reportsFocus(item)
                .clip(PosterShape)
                .posterClick(item, interaction, LocalIndication.current, onClick)
                .posterSemantics(item, kept),
        ) {
            Box {
                Poster(url = item.posterUrl, title = item.title, progress = progress.takeUnless { watched })
                if (watched) WatchedBadge(Modifier.align(Alignment.TopStart))
                if (kept) MyListBadge(Modifier.align(Alignment.TopEnd))
            }
        }
        return
    }
    // With its title and year below: the focus frame and the rounded corners are the poster's, the texts stay clear.
    val focused by interaction.collectIsFocusedAsState()
    val grow by animateFloatAsState(if (focused) 1.06f else 1f, label = "posterScale")
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .pressScale(interaction)
            .focusMemory(focusKey, initialFocus)
            .reportsFocus(item)
            .posterClick(item, interaction, null, onClick)
            .posterSemantics(item, kept),
    ) {
        Box(
            Modifier.graphicsLayer {
                scaleX = grow
                scaleY = grow
            },
        ) {
            Poster(
                url = item.posterUrl,
                title = item.title,
                progress = progress.takeUnless { watched },
                modifier = Modifier.border(3.dp, if (focused) MaterialTheme.colorScheme.onBackground else Color.Transparent, PosterShape),
            )
            if (watched) WatchedBadge(Modifier.align(Alignment.TopStart))
            if (kept) MyListBadge(Modifier.align(Alignment.TopEnd))
        }
        Text(
            // Without the provider's tags: the title as people read it (the stored name stays as it is).
            text = CatalogueMatcher.displayTitle(item.title),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
        item.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (showsFacts) PosterFactsLines(facts ?: PosterFacts())
    }
}

/**
 * A poster's rating ("7.4" and its five stars) and two genres as small pills: two lines of a fixed height, kept even
 * when empty, so that the posters of a grid stay in line while their facts arrive.
 */
@Composable
fun PosterFactsLines(facts: PosterFacts, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.padding(top = 6.dp).testTag("posterFacts")) {
        Row(Modifier.height(18.dp), verticalAlignment = Alignment.CenterVertically) {
            facts.rating?.let { rating ->
                Text(rating, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.testTag("posterRating"))
                Row(Modifier.padding(start = 6.dp).testTag("posterStars")) {
                    for (star in 0 until 5) {
                        val filled = facts.halfStars - 2 * star
                        Icon(
                            when {
                                filled >= 2 -> Icons.Rounded.Star
                                filled == 1 -> Icons.AutoMirrored.Rounded.StarHalf
                                else -> Icons.Rounded.StarOutline
                            },
                            contentDescription = null,
                            tint = if (filled > 0) colors.onBackground else colors.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
        Row(
            Modifier.padding(top = 6.dp).height(24.dp).fillMaxWidth().clipToBounds(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            facts.genres.forEach { genre ->
                Text(
                    genre,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .background(OndoColors.SurfaceHigh, CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .testTag("posterGenre"),
                )
            }
        }
    }
}

/** 16:9 "continue watching" card with the time left and the progress. */
@Composable
fun ContinueCard(
    item: ResumeItem,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    preparing: Boolean = false,
    initialFocus: Boolean = false,
    /** A long press (OK held on a remote) offers to take the card away; [onRemove] once the viewer says so. */
    onRemove: (() -> Unit)? = null,
    /** The long press offers the movie's page, or the series' of the episode, too. */
    onOpen: (() -> Unit)? = null,
) {
    var asking by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val click = if (onRemove == null && onOpen == null) {
        Modifier.clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
    } else {
        Modifier.combinedClickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onClick = onClick,
            onLongClickLabel = stringResource(if (onRemove != null) R.string.remove_from_history else goToLabel(item)),
            onLongClick = { asking = true },
        )
    }
    Column(
        modifier
            .width(width)
            .pressScale(interaction)
            .focusFrame(CardShape)
            .focusMemory("resume:${item.key}", initialFocus)
            .reportsFocus(item.showcase)
            .clip(CardShape)
            .then(click)
            .padding(bottom = 4.dp),
    ) {
        if (asking) ResumeCardMenu(item, onOpen, onRemove, onDismiss = { asking = false })
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(CardShape)) {
            Artwork(url = item.imageUrl, title = item.title, modifier = Modifier.fillMaxSize(), showsTitleFallback = false)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))),
            )
            PlayIcon(preparing, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 12.dp).size(22.dp), tint = Color.White)
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(item.progress.coerceIn(0f, 1f)).height(3.dp).background(Color.White))
            }
        }
        // Inside the card's frame and its rounded corners, which would cut the lines' first and last letters.
        Text(
            CatalogueMatcher.displayTitle(item.title),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = CardTextInset, top = 8.dp, end = CardTextInset),
        )
        val left = when {
            item.upNext -> stringResource(R.string.play)
            item.minutesLeft > 1 -> timeLeftText(item.minutesLeft)
            else -> stringResource(R.string.resume)
        }
        Text(
            listOfNotNull(item.subtitle, left).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = CardTextInset),
        )
    }
}

/** The corners of a landscape card (continue watching, channels): as on a TV. */
val CardShape = RoundedCornerShape(10.dp)

/** How far a card's lines keep from its sides: clear of its focus frame (3 dp) and of its rounded corners. */
val CardTextInset = 8.dp

/** A card that gives under the finger: 97 % of its size while [interaction] is pressed. */
fun Modifier.pressScale(interaction: InteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "pressScale")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** "Go to Series" for an episode, "Go to Movie" for a movie. */
private fun goToLabel(item: ResumeItem): Int = if (item.isSeries) R.string.go_to_series else R.string.go_to_movie

/**
 * What a long press on a "Continue watching" card offers: the title's page ([onOpen]: the movie's, or the series' with
 * its seasons and episodes) and Remove from History ([onRemove]). A menu on a phone; on a TV a list, the remote on Go
 * to, whose Remove asks first, as the question alone does on a card without a page (a channel).
 */
@Composable
fun ResumeCardMenu(item: ResumeItem, onOpen: (() -> Unit)?, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    val remove = stringResource(R.string.remove_from_history)
    val open = stringResource(goToLabel(item))
    if (isTelevision()) {
        var confirming by remember { mutableStateOf(onOpen == null) }
        if (confirming && onRemove != null) {
            TvConfirmDialog(CatalogueMatcher.displayTitle(item.title), stringResource(R.string.remove_from_history_text), remove, onConfirm = { onDismiss(); onRemove() }, onDismiss = onDismiss)
        } else if (onOpen != null) {
            TvResumeMenu(
                title = CatalogueMatcher.displayTitle(item.title),
                openLabel = open,
                onOpen = { onDismiss(); onOpen() },
                removeLabel = remove.takeIf { onRemove != null },
                onRemove = { confirming = true },
                onDismiss = onDismiss,
            )
        }
        return
    }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        if (onOpen != null) {
            DropdownMenuItem(
                text = { Text(open) },
                leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                onClick = { onDismiss(); onOpen() },
                modifier = Modifier.testTag("openFromHistory"),
            )
        }
        if (onRemove != null) {
            DropdownMenuItem(
                text = { Text(remove) },
                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                onClick = { onDismiss(); onRemove() },
                modifier = Modifier.testTag("removeFromHistory"),
            )
        }
    }
}

/**
 * The Play icon, or in its place, at the same size, a spinner while the title gets ready to play (a series'
 * episodes loading): the tap is seen at once.
 */
@Composable
fun PlayIcon(preparing: Boolean, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    if (!preparing) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tint, modifier = modifier)
        return
    }
    val label = stringResource(R.string.loading)
    Box(modifier.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.fillMaxSize().padding(2.dp), color = tint, strokeWidth = 2.dp)
    }
}
