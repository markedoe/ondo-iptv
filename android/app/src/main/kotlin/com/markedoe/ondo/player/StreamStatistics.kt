package com.markedoe.ondo.player

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.core.playback.PlaybackHealth
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.min

/** The rows of More, Stream Statistics, as shown: what arrives, what the video needs, the freezes and their cause. */
data class StreamStatistics(
    val engine: String,
    /** "MKV", "HLS"…: the format the address names, if it names one. */
    val format: String?,
    /** "1920×1080", once the first picture is decoded. */
    val video: String?,
    val videoBitrate: String,
    val received: String,
    val smoothness: String,
    val freezes: String,
    val lostPictures: String,
    val verdict: PlaybackHealth.Verdict,
) {
    companion object {
        private const val NONE = "–"

        fun of(health: PlaybackHealth, engine: String, format: String?, video: String?, locale: Locale): StreamStatistics {
            val freezes = health.freezes.size + if (health.isFrozen) 1 else 0
            return StreamStatistics(
                engine = engine,
                format = format,
                video = video,
                videoBitrate = health.contentRate?.let { megabits(it, locale) } ?: NONE,
                received = health.receivedRate()?.let { megabits(it, locale) } ?: NONE,
                // Seconds of video shown per second of playing: 100 % when nothing stops.
                smoothness = health.pace?.let { NumberFormat.getPercentInstance(locale).format(min(it, 1.0)) } ?: NONE,
                freezes = "$freezes · ${decimal(health.frozenSeconds, locale)} s",
                lostPictures = health.lostPictures.toString(),
                verdict = health.verdict,
            )
        }

        private fun decimal(value: Double, locale: Locale): String = NumberFormat.getNumberInstance(locale).run {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
            format(value)
        }

        /** Bytes per second as megabits per second. */
        private fun megabits(bytesPerSecond: Double, locale: Locale) = decimal(bytesPerSecond * 8 / 1_000_000, locale) + " Mb/s"
    }
}

/** Over the picture when asked for (More, Stream Statistics); touches go through to the player. */
@Composable
fun StreamStatisticsPanel(statistics: StreamStatistics, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
    Column(
        modifier
            .widthIn(max = 340.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(12.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("streamStatistics"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatisticsRow(R.string.stats_engine, statistics.engine, style)
        statistics.format?.let { StatisticsRow(R.string.stats_format, it, style) }
        statistics.video?.let { StatisticsRow(R.string.stats_video, it, style) }
        StatisticsRow(R.string.stats_video_bitrate, statistics.videoBitrate, style)
        StatisticsRow(R.string.stats_received, statistics.received, style)
        StatisticsRow(R.string.stats_smoothness, statistics.smoothness, style)
        StatisticsRow(R.string.stats_freezes, statistics.freezes, style)
        StatisticsRow(R.string.stats_lost_pictures, statistics.lostPictures, style)
        val (verdict, color) = when (statistics.verdict) {
            PlaybackHealth.Verdict.MEASURING -> R.string.stats_measuring to Color.White.copy(alpha = 0.7f)
            PlaybackHealth.Verdict.SMOOTH -> R.string.stats_smooth to Color(0xFF81C784)
            PlaybackHealth.Verdict.NETWORK -> R.string.stats_network to Color(0xFFFFB74D)
            PlaybackHealth.Verdict.PLAYER -> R.string.stats_player to Color(0xFFFFF176)
        }
        Text(
            stringResource(verdict),
            color = color,
            style = style.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(top = 4.dp).testTag("statisticsVerdict"),
        )
    }
}

@Composable
private fun StatisticsRow(@StringRes label: Int, value: String, style: TextStyle) {
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(label), color = Color.White.copy(alpha = 0.7f), style = style, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, color = Color.White, style = style)
    }
}
