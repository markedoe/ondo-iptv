package com.markedoe.ondo.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Season
import com.markedoe.ondo.ui.AwardRow
import com.markedoe.ondo.ui.components.Artwork
import com.markedoe.ondo.ui.components.Poster
import com.markedoe.ondo.ui.components.PosterCard
import com.markedoe.ondo.ui.components.ProgressBar
import com.markedoe.ondo.ui.components.Shelf
import com.markedoe.ondo.ui.components.ShelfMetrics
import com.markedoe.ondo.ui.components.rememberShelfMetrics
import com.markedoe.ondo.ui.model.ShowcaseItem

/** Progress of something being watched. */
data class WatchProgress(val fraction: Float, val minutesLeft: Int, val finished: Boolean)

data class MovieDetailUiState(
    val item: ShowcaseItem.MovieItem,
    val details: MediaDetails? = null,
    val progress: WatchProgress? = null,
    val inMyList: Boolean = false,
    val similar: List<ShowcaseItem> = emptyList(),
    /** Awards and festival selections, wins first. */
    val awards: List<AwardRow> = emptyList(),
)

data class SeriesDetailUiState(
    val item: ShowcaseItem.SeriesItem,
    val details: MediaDetails? = null,
    val seasons: List<Season> = emptyList(),
    val selectedSeason: Int? = null,
    /** Episode to play next and its progress. */
    val next: Episode? = null,
    val nextProgress: WatchProgress? = null,
    val episodeProgress: Map<String, WatchProgress> = emptyMap(),
    val inMyList: Boolean = false,
    val similar: List<ShowcaseItem> = emptyList(),
    val error: String? = null,
)

class DetailActions(
    val onBack: () -> Unit = {},
    val onPlay: () -> Unit = {},
    val onPlayFromStart: () -> Unit = {},
    val onToggleMyList: () -> Unit = {},
    val onOpen: (ShowcaseItem) -> Unit = {},
    val onSeason: (Int) -> Unit = {},
    val onPlayEpisode: (Episode) -> Unit = {},
)

@Composable
fun MovieDetailScreen(state: MovieDetailUiState, actions: DetailActions) {
    val movie = state.item.movie
    val facts = listOfNotNull(
        movie.year?.toString() ?: state.details?.releaseDate?.take(4),
        state.details?.durationSeconds?.takeIf { it > 0 }?.let { durationText(it, LocalConfiguration.current.locales[0]) },
        (state.details?.rating ?: movie.rating)?.takeIf { it > 0 }?.let { "★ " + ratingText(it) },
        state.details?.genre,
    )
    val resumable = state.progress?.let { !it.finished && it.fraction > 0f } == true
    DetailScaffold(
        title = movie.name,
        posterUrl = state.details?.posterUrl ?: movie.posterUrl,
        backdropUrl = state.details?.backdropUrl,
        facts = facts,
        onBack = actions.onBack,
        actions = { metrics ->
            PlayButton(if (resumable) stringResource(R.string.resume) else stringResource(R.string.play), state.progress.takeIf { resumable }, actions.onPlay)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 16.dp)) {
                CircleAction(stringResource(R.string.my_list), if (state.inMyList) Icons.Rounded.Check else Icons.Rounded.Add, actions.onToggleMyList, state = myListState(state.inMyList))
                if (resumable) CircleAction(stringResource(R.string.from_start), Icons.Rounded.Replay, actions.onPlayFromStart)
            }
        },
        content = { metrics ->
            item { TextBlock(state.details?.plot ?: movie.plot, state.details, metrics) }
            if (state.awards.isNotEmpty()) item { AwardsBlock(state.awards, metrics) }
            similarRow(state.similar, metrics, actions)
        },
    )
}

/** Awards and festival selections of a movie: the first few, then "And N more". */
@Composable
private fun AwardsBlock(awards: List<AwardRow>, metrics: ShelfMetrics) {
    var all by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = metrics.gutter).widthIn(max = 680.dp).animateContentSize().testTag("awards")) {
        Text(stringResource(R.string.awards), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
        (if (all) awards else awards.take(4)).forEach { award ->
            Row(Modifier.padding(top = 6.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                Icon(
                    if (award.won) Icons.Rounded.EmojiEvents else Icons.Rounded.WorkspacePremium,
                    contentDescription = null,
                    tint = if (award.won) Color(0xFFE6B422) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp).size(18.dp),
                )
                Column {
                    Text(award.name, style = MaterialTheme.typography.bodyMedium)
                    val status = when {
                        award.won -> R.string.award_winner
                        award.festival -> R.string.award_in_competition
                        else -> R.string.award_nominated
                    }
                    Text(stringResource(status, award.year.toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!all && awards.size > 4) {
            TextButton(onClick = { all = true }, contentPadding = PaddingValues(0.dp)) {
                Text(pluralStringResource(R.plurals.and_more, awards.size - 4, awards.size - 4))
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(state: SeriesDetailUiState, actions: DetailActions) {
    val series = state.item.series
    val seasonCount = state.seasons.size
    val facts = listOfNotNull(
        series.year?.toString(),
        seasonCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.seasons, it, it) },
        (state.details?.rating ?: series.rating)?.takeIf { it > 0 }?.let { "★ " + ratingText(it) },
        state.details?.genre ?: series.genre,
    )
    val season = state.seasons.firstOrNull { it.number == state.selectedSeason } ?: state.seasons.firstOrNull()
    DetailScaffold(
        title = series.name,
        posterUrl = state.details?.posterUrl ?: series.coverUrl,
        backdropUrl = state.details?.backdropUrl,
        facts = facts,
        onBack = actions.onBack,
        actions = {
            state.next?.let { next ->
                val resumable = state.nextProgress?.let { !it.finished && it.fraction > 0f } == true
                PlayButton(
                    stringResource(if (resumable) R.string.resume_episode else R.string.play_episode, next.code),
                    state.nextProgress.takeIf { resumable },
                    actions.onPlay,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 16.dp)) {
                CircleAction(stringResource(R.string.my_list), if (state.inMyList) Icons.Rounded.Check else Icons.Rounded.Add, actions.onToggleMyList, state = myListState(state.inMyList))
            }
        },
        content = { metrics ->
            item { TextBlock(state.details?.plot ?: series.plot, state.details, metrics) }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = metrics.gutter)) } }
            if (state.seasons.size > 1) {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = metrics.gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.seasons, key = { it.number }) { item ->
                            FilterChip(selected = item.number == season?.number, onClick = { actions.onSeason(item.number) }, label = { Text(stringResource(R.string.season_number, item.number)) })
                        }
                    }
                }
            } else if (season != null) {
                item { Text(stringResource(R.string.episodes), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = metrics.gutter)) }
            }
            season?.let { current ->
                items(current.episodes, key = { it.id }) { episode ->
                    EpisodeRow(episode, state.episodeProgress[episode.id], metrics) { actions.onPlayEpisode(episode) }
                }
            }
            similarRow(state.similar, metrics, actions)
        },
    )
}

private fun LazyListScope.similarRow(similar: List<ShowcaseItem>, metrics: ShelfMetrics, actions: DetailActions) {
    if (similar.isEmpty()) return
    item {
        Shelf(stringResource(R.string.more_like_this), metrics, modifier = Modifier.padding(top = 8.dp)) {
            items(similar, key = { it.key }) { PosterCard(it, metrics.posterWidth, onClick = { actions.onOpen(it) }) }
        }
    }
}

/** Artwork header (backdrop, else the blurred poster), poster and title over its faded edge, then the page. */
@Composable
private fun DetailScaffold(
    title: String,
    posterUrl: String?,
    backdropUrl: String?,
    facts: List<String>,
    onBack: () -> Unit,
    actions: @Composable (ShelfMetrics) -> Unit,
    content: LazyListScope.(ShelfMetrics) -> Unit,
) {
    val metrics = rememberShelfMetrics()
    Box(Modifier.fillMaxSize().testTag("detail")) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Box {
                    Box(Modifier.fillMaxWidth().height(if (metrics.compact) 300.dp else 380.dp)) {
                        if (backdropUrl != null) {
                            Artwork(url = backdropUrl, title = title, modifier = Modifier.fillMaxSize(), showsTitleFallback = false)
                        } else {
                            Artwork(url = posterUrl, title = title, modifier = Modifier.fillMaxSize().blur(40.dp), showsTitleFallback = false)
                        }
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.55f),
                                    0.35f to Color.Transparent,
                                    1f to MaterialTheme.colorScheme.background,
                                ),
                            ),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(y = 90.dp)
                            .padding(horizontal = metrics.gutter),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Poster(url = posterUrl, title = title, modifier = Modifier.width(if (metrics.compact) 112.dp else 168.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.padding(bottom = 4.dp)) {
                            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (facts.isNotEmpty()) {
                                Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
            item { Column(Modifier.padding(horizontal = metrics.gutter)) { actions(metrics) } }
            content(metrics)
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f)),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
        }
    }
}

@Composable
private fun PlayButton(title: String, progress: WatchProgress?, onClick: () -> Unit) {
    Column {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
            contentPadding = PaddingValues(vertical = 14.dp, horizontal = 24.dp),
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().testTag("detailPlay"),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(title, fontWeight = FontWeight.SemiBold)
        }
        if (progress != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Box(Modifier.width(200.dp)) { ProgressBar(progress.fraction) }
                Spacer(Modifier.width(10.dp))
                Text(pluralStringResource(R.plurals.minutes_left, progress.minutesLeft, progress.minutesLeft), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** What TalkBack says after "My List": the check mark alone is visual only. */
@Composable
internal fun myListState(inMyList: Boolean): String = stringResource(if (inMyList) R.string.in_my_list else R.string.not_in_my_list)

@Composable
private fun CircleAction(title: String, icon: ImageVector, onClick: () -> Unit, state: String? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { if (state != null) stateDescription = state }
            .padding(4.dp),
    ) {
        Box(Modifier.size(50.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null)
        }
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun TextBlock(plot: String?, details: MediaDetails?, metrics: ShelfMetrics) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = metrics.gutter).widthIn(max = 680.dp).animateContentSize()) {
        if (!plot.isNullOrBlank()) {
            Text(plot, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
            if (plot.length > 220) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(if (expanded) R.string.less else R.string.more))
                }
            }
        }
        details?.director?.let { Credit(stringResource(R.string.director), it) }
        details?.cast?.let { Credit(stringResource(R.string.cast), it) }
    }
}

@Composable
private fun Credit(label: String, value: String) {
    Row(Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EpisodeRow(episode: Episode, progress: WatchProgress?, metrics: ShelfMetrics, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = metrics.gutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(if (metrics.compact) 150.dp else 220.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
            Artwork(url = episode.posterUrl, title = episode.code, modifier = Modifier.fillMaxSize())
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(4.dp),
            )
            if (progress != null && !progress.finished && progress.fraction > 0f) {
                Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 6.dp, vertical = 3.dp)) { ProgressBar(progress.fraction) }
            }
        }
        Column(Modifier.weight(1f)) {
            Text("${episode.number}. ${episode.title}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                episode.durationSeconds?.takeIf { it > 0 }?.let { Text(durationText(it, LocalConfiguration.current.locales[0]), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (progress?.finished == true) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.watched), tint = Color(0xFF4CAF50), modifier = Modifier.padding(start = 6.dp).size(14.dp))
                }
            }
            episode.plot?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (metrics.compact) 2 else 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** "7.8" / "7,8", in the language of the interface. */
@Composable
@ReadOnlyComposable
private fun ratingText(value: Double): String = String.format(LocalConfiguration.current.locales[0], "%.1f", value)

/** "2 hr, 8 min" / "2 h et 8 min" / "45 min": hours and minutes in the language of the interface. */
fun durationText(seconds: Int, locale: java.util.Locale = java.util.Locale.getDefault()): String {
    val minutes = seconds / 60
    val format = android.icu.text.MeasureFormat.getInstance(locale, android.icu.text.MeasureFormat.FormatWidth.SHORT)
    val minute = android.icu.util.Measure(minutes % 60, android.icu.util.MeasureUnit.MINUTE)
    return if (minutes >= 60) format.formatMeasures(android.icu.util.Measure(minutes / 60, android.icu.util.MeasureUnit.HOUR), minute)
    else format.formatMeasures(android.icu.util.Measure(minutes, android.icu.util.MeasureUnit.MINUTE))
}
