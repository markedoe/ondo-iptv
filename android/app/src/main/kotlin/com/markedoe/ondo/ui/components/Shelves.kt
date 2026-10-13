package com.markedoe.ondo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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

/** Poster opening the detail page. */
@Composable
fun PosterCard(
    item: ShowcaseItem,
    width: Dp?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    showsTitle: Boolean = false,
) {
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
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
fun ContinueCard(item: ResumeItem, width: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.width(width).clip(shape).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)) {
            Artwork(url = item.imageUrl, title = item.title, modifier = Modifier.fillMaxSize(), showsTitleFallback = false)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
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
        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        item.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
