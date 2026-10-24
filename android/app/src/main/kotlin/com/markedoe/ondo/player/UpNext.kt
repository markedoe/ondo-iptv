package com.markedoe.ondo.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.ui.theme.OndoColors

/** The next episode, offered as an episode ends: what it is, and when it plays. */
data class UpNext(val title: String, val subtitle: String, val seconds: Int) {
    companion object {
        /** Up next shows in the last seconds of an episode. */
        const val SHOWN_MS = 20_000L

        /** The offer of [next] with [leftMs] of the episode left: none before the last [SHOWN_MS], or without a next one. */
        fun offer(next: Pair<Series, Episode>?, leftMs: Long): UpNext? {
            val (series, episode) = next ?: return null
            if (leftMs > SHOWN_MS) return null
            return UpNext(series.name, "${episode.code} · ${episode.title}", ((leftMs + 999) / 1_000).toInt().coerceAtLeast(0))
        }
    }
}

/**
 * Up next, in the corner as the episode ends: the next episode plays when this one does, or at once with Play now;
 * the cross keeps the player on this one. On a TV, OK plays it and Back declines it (the activity's keys): the
 * buttons only show it.
 */
@Composable
fun UpNextCard(upNext: UpNext, television: Boolean, onPlay: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = 360.dp)
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(16.dp)
            .testTag("upNext"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.up_next), style = MaterialTheme.typography.labelLarge, color = OndoColors.Lime, modifier = Modifier.weight(1f))
            if (!television) {
                IconButton(onClick = onDecline, modifier = Modifier.testTag("upNextDecline")) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel), tint = Color.White)
                }
            }
        }
        Text(upNext.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(upNext.subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Button(
            onClick = onPlay,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            modifier = Modifier.testTag("upNextPlay"),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Text(stringResource(R.string.play_now), modifier = Modifier.padding(start = 6.dp))
        }
        Text(stringResource(R.string.plays_in_seconds, upNext.seconds), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.testTag("upNextSeconds"))
    }
}
