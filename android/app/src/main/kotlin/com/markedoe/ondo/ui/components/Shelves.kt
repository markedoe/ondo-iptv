package com.markedoe.ondo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.tv.TvConfirmDialog
import com.markedoe.ondo.ui.tv.TvResumeMenu
import com.markedoe.ondo.ui.tv.TvRow
import com.markedoe.ondo.ui.tv.focusFrame
import com.markedoe.ondo.ui.tv.focusMemory
import com.markedoe.ondo.ui.tv.isTelevision

/** Sizes of posters, cards and margins for phones and wide screens. */
data class ShelfMetrics(val compact: Boolean) {
    val posterWidth: Dp get() = if (compact) 116.dp else 150.dp
    val wideCardWidth: Dp get() = if (compact) 264.dp else 320.dp
    val gutter: Dp get() = if (compact) 16.dp else 24.dp
}

@Composable
fun rememberShelfMetrics(): ShelfMetrics = ShelfMetrics(compact = LocalConfiguration.current.screenWidthDp < 600)

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
            if (onSeeAll != null) {
                TextButton(onClick = onSeeAll) {
                    Text(stringResource(R.string.see_all))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }
        TvRow {
            LazyRow(
                contentPadding = PaddingValues(horizontal = metrics.gutter),
                horizontalArrangement = Arrangement.spacedBy(if (metrics.compact) 10.dp else 14.dp),
                content = content,
            )
        }
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
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .pressScale(interaction)
            .focusFrame(PosterShape)
            .focusMemory(focusKey, initialFocus)
            .clip(PosterShape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .semantics { contentDescription = item.title },
    ) {
        Poster(url = item.posterUrl, title = item.title, progress = progress)
        if (showsTitle) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            item.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    Column(modifier.width(width).pressScale(interaction).focusFrame(CardShape).focusMemory("resume:${item.key}", initialFocus).clip(CardShape).then(click).padding(bottom = 4.dp)) {
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
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        val left = if (item.minutesLeft > 1) pluralStringResource(R.plurals.minutes_left, item.minutesLeft, item.minutesLeft) else stringResource(R.string.resume)
        Text(
            listOfNotNull(item.subtitle, left).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The corners of a landscape card (continue watching, channels): as on a TV. */
val CardShape = RoundedCornerShape(10.dp)

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
            TvConfirmDialog(item.title, stringResource(R.string.remove_from_history_text), remove, onConfirm = { onDismiss(); onRemove() }, onDismiss = onDismiss)
        } else if (onOpen != null) {
            TvResumeMenu(
                title = item.title,
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
