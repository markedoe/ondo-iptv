package com.markedoe.ondo.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
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
        LazyRow(
            contentPadding = PaddingValues(horizontal = metrics.gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
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
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .focusFrame(PosterShape)
            .focusMemory(focusKey, initialFocus)
            .clip(PosterShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = item.title },
    ) {
        Poster(url = item.posterUrl, title = item.title, progress = progress)
        if (showsTitle) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
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
) {
    val shape = RoundedCornerShape(12.dp)
    var asking by remember { mutableStateOf(false) }
    val click = if (onRemove == null) Modifier.clickable(onClick = onClick)
    else Modifier.combinedClickable(onClick = onClick, onLongClickLabel = stringResource(R.string.remove_from_history), onLongClick = { asking = true })
    Column(modifier.width(width).focusFrame(shape).focusMemory("resume:${item.key}", initialFocus).clip(shape).then(click).padding(bottom = 4.dp)) {
        if (asking && onRemove != null) RemoveFromHistory(item, onRemove, onDismiss = { asking = false })
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)) {
            Artwork(url = item.imageUrl, title = item.title, modifier = Modifier.fillMaxSize(), showsTitleFallback = false)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayIcon(preparing, Modifier.size(16.dp), tint = Color.White)
                    Text(
                        text = if (item.minutesLeft > 1) pluralStringResource(R.plurals.minutes_left, item.minutesLeft, item.minutesLeft)
                        else stringResource(R.string.resume),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(6.dp))
                ProgressBar(item.progress)
            }
        }
        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp))
        item.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** What a long press on a "Continue watching" card offers: a menu on a phone, a question on a TV. */
@Composable
private fun RemoveFromHistory(item: ResumeItem, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val label = stringResource(R.string.remove_from_history)
    if (isTelevision()) {
        TvConfirmDialog(item.title, stringResource(R.string.remove_from_history_text), label, onConfirm = { onDismiss(); onRemove() }, onDismiss = onDismiss)
        return
    }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            onClick = { onDismiss(); onRemove() },
            modifier = Modifier.testTag("removeFromHistory"),
        )
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
