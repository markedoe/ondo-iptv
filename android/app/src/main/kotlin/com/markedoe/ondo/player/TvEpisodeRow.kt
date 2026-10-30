package com.markedoe.ondo.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.components.Artwork
import com.markedoe.ondo.ui.tv.TvChip
import com.markedoe.ondo.ui.tv.TvProgress
import com.markedoe.ondo.ui.tv.TvTokens

/** An episode of the player's Episodes row: its number and title, its picture, how far the viewer went. */
@Immutable
data class EpisodeChoice(
    val id: String,
    /** "S01E05". */
    val code: String,
    val number: Int,
    val title: String,
    val imageUrl: String? = null,
    /** How far the viewer went, from 0 (not started) to 1. */
    val progress: Float = 0f,
    val watched: Boolean = false,
)

@Immutable
data class EpisodeSeason(val number: Int, val episodes: List<EpisodeChoice>)

/**
 * The episodes of the series playing, for the TV player's Episodes row: by season, the one playing ([playingId])
 * marked. [onChoose] plays another one, from where the viewer left it.
 */
@Immutable
class EpisodeList(val seasons: List<EpisodeSeason>, val playingId: String, val onChoose: (String) -> Unit) {
    /** The season of the episode playing. */
    val playingSeason: Int? = seasons.firstOrNull { season -> season.episodes.any { it.id == playingId } }?.number
}

/**
 * The Episodes row under the player's buttons (a series): the seasons when there are several, then the season's
 * episodes, the remote starting on the one playing (Right: the next one). OK on another plays it, OK on the one playing
 * goes on with it ([onPlaying]). The row goes when the remote leaves it (Up) or on Back ([PlayerControls.back]).
 */
@Composable
internal fun TvEpisodeRow(episodes: EpisodeList, controls: PlayerControls, onPlaying: () -> Unit, modifier: Modifier = Modifier) {
    var season by remember(episodes) { mutableIntStateOf(episodes.playingSeason ?: episodes.seasons.first().number) }
    val shown = episodes.seasons.firstOrNull { it.number == season } ?: episodes.seasons.first()
    val first = remember { FocusRequester() }
    val seasonChip = remember { FocusRequester() }
    val seasons = episodes.seasons.size > 1
    var hadFocus by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (it.hasFocus) {
                    hadFocus = true
                } else if (hadFocus) {
                    hadFocus = false
                    controls.hideEpisodes()
                }
            }
            .focusGroup()
            .testTag("tvEpisodeRow"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.episodes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        if (seasons) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = FocusRoom, vertical = 4.dp),
                modifier = Modifier.roomToGrow().testTag("tvEpisodeSeasons"),
            ) {
                items(episodes.seasons, key = { it.number }) { item ->
                    TvChip(
                        text = stringResource(R.string.season_number, item.number),
                        selected = item.number == shown.number,
                        onClick = { season = item.number },
                        // Down: the episode playing, else the season's first; Up from an episode: the season shown.
                        modifier = Modifier
                            .then(if (item.number == shown.number) Modifier.focusRequester(seasonChip) else Modifier)
                            .focusProperties { down = first }
                            .testTag("episodeSeason-${item.number}"),
                    )
                }
            }
        }
        val playingIndex = shown.episodes.indexOfFirst { it.id == episodes.playingId }
        // The row opens on the episode playing: one far along is not drawn until the row scrolls to it.
        val list = remember(shown.number) { LazyListState(firstVisibleItemIndex = playingIndex.coerceAtLeast(0)) }
        LazyRow(
            state = list,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = FocusRoom, vertical = 8.dp),
            modifier = Modifier.roomToGrow(),
        ) {
            itemsIndexed(shown.episodes, key = { _, episode -> episode.id }) { index, episode ->
                val playing = episode.id == episodes.playingId
                val focusFirst = playing || (playingIndex < 0 && index == 0)
                val requester = if (focusFirst) Modifier.focusRequester(first) else Modifier
                EpisodeCard(episode, playing, modifier = if (seasons) requester.focusProperties { up = seasonChip } else requester) {
                    if (playing) onPlaying() else episodes.onChoose(episode.id)
                }
            }
        }
    }
    LaunchedEffect(Unit) { takeFocus(first) }
}

private val EpisodeWidth = 208.dp

/** Room on each side of a row for the card or chip the remote is on, which grows and gets a frame. */
private val FocusRoom = 16.dp

/**
 * The row reaches [FocusRoom] past both edges of the panel, its content padded by as much: the cards stay in line with
 * the title, and the first one, grown under the remote, is not cut on its left.
 */
private fun Modifier.roomToGrow(): Modifier = layout { measurable, constraints ->
    val room = FocusRoom.roundToPx()
    val wider = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * room else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = wider))
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else (placeable.width - 2 * room).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(-room, 0) }
}

@Composable
private fun EpisodeCard(episode: EpisodeChoice, playing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val nowPlaying = stringResource(R.string.now_playing)
    val watched = stringResource(R.string.watched)
    Column(Modifier.width(EpisodeWidth), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .semantics {
                    contentDescription = listOfNotNull("${episode.code} ${episode.title}", nowPlaying.takeIf { playing }, watched.takeIf { episode.watched && !playing }).joinToString(", ")
                    selected = playing
                }
                .testTag("episode-${episode.id}"),
            shape = CardDefaults.shape(TvTokens.CardShape),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = CardDefaults.border(
                border = if (playing) Border(BorderStroke(2.dp, TvTokens.Accent), shape = TvTokens.CardShape) else Border.None,
                focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = TvTokens.CardShape),
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                Artwork(url = episode.imageUrl, title = episode.code, modifier = Modifier.fillMaxSize())
                if (playing) {
                    Text(
                        nowPlaying,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TvTokens.Ink,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(TvTokens.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                } else if (episode.watched) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = TvTokens.Accent, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp))
                }
                if (!episode.watched && episode.progress > 0f) {
                    TvProgress(episode.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                }
            }
        }
        Text(
            "${episode.number}. ${episode.title}",
            style = MaterialTheme.typography.titleSmall,
            color = if (playing) TvTokens.Accent else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
