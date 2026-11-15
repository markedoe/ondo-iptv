package com.markedoe.ondo.player

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.core.library.DefaultReason
import com.markedoe.ondo.core.library.ScreenNote
import com.markedoe.ondo.core.playback.PlaybackHealth
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.launch

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

/**
 * The title data next to the statistics (More, Stream Statistics): what the provider calls the title and its versions
 * ([TitleData]). Over the picture, touches going through: at most [TitleData.PANEL_VERSIONS] versions, the one playing
 * first, then a line counting the others (all of them on the film's TV page, OK held on a version's chip), then what the
 * app read of this screen. Never taller than the room it is given: it scrolls ([scroll]), with the remote's Up and Down
 * ([TitleDataScrolling]: the player hands them over while its controls are hidden, and the panel takes them itself
 * when it has the focus, which it can take only when [canFocus]: outlined then).
 */
@Composable
fun TitleDataPanel(
    data: TitleData,
    television: Boolean,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    canFocus: Boolean = television,
) {
    val style = (if (television) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall).copy(fontFeatureSettings = "tnum")
    val shown = data.versions.sortedByDescending { it.playing }.take(TitleData.PANEL_VERSIONS)
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .widthIn(max = if (television) 560.dp else 380.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val down = TitleDataScrolling.direction(event.nativeKeyEvent.keyCode) ?: return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown || !TitleDataScrolling.canScroll(scroll, down)) return@onPreviewKeyEvent false
                scope.launch { TitleDataScrolling.step(scroll, down) }
                true
            }
            .focusable(enabled = canFocus)
            .verticalScroll(scroll)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("titleData"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(R.string.title_data), color = Color.White, style = style.copy(fontWeight = FontWeight.SemiBold))
        TitleDataLines(data, style)
        // The versions before the screen: what a photo of the panel is taken for.
        if (shown.isNotEmpty()) {
            Text(
                stringResource(R.string.title_data_versions),
                color = Color.White,
                style = style.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 4.dp),
            )
            for (version in shown) TitleDataVersion(version, style)
            val more = data.versions.size - shown.size
            if (more > 0) Text(stringResource(R.string.title_data_more, more), color = Color.White.copy(alpha = 0.7f), style = style)
            data.reason?.let { TitleDataReason(it, style) }
        }
        TitleDataScreen(data, style)
    }
}

/**
 * The remote's Up and Down on the title data panel: a step of most of what it shows, only while it can still move that
 * way (at its top, Up does what it does without the panel; at its bottom, Down).
 */
object TitleDataScrolling {
    /** Down (true) or Up (false) for [keyCode]; null for another key. */
    fun direction(keyCode: Int): Boolean? = when (keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> true
        android.view.KeyEvent.KEYCODE_DPAD_UP -> false
        else -> null
    }

    fun canScroll(scroll: ScrollState, down: Boolean): Boolean = if (down) scroll.canScrollForward else scroll.canScrollBackward

    /** Three quarters of what the panel shows, so a line at its edge stays in sight. */
    suspend fun step(scroll: ScrollState, down: Boolean) {
        val by = (scroll.viewportSize * 3 / 4).coerceAtLeast(1).toFloat()
        scroll.animateScrollBy(if (down) by else -by)
    }
}

/** The film's or the episode's lines of [data]: the app's name of a field, else the provider's. */
@Composable
fun TitleDataLines(data: TitleData, style: TextStyle) {
    for (line in data.lines) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                if (line.label != 0) stringResource(line.label) else line.key,
                color = Color.White.copy(alpha = 0.7f),
                style = style,
                modifier = Modifier.widthIn(min = 96.dp, max = 160.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(line.value, color = Color.White, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A version in three lines: its chip and whether it plays ([current]: "Playing", or on a film's page the chip held);
 * its name as the provider writes it; its category, id, container, tags.
 */
@Composable
fun TitleDataVersion(
    version: TitleData.Version,
    style: TextStyle,
    modifier: Modifier = Modifier,
    @StringRes current: Int = R.string.title_data_playing,
) {
    Column(modifier.padding(top = 4.dp).testTag("titleDataVersion-${version.id}")) {
        val marks = listOfNotNull(
            if (version.playing) "▶ " + stringResource(current) else null,
            if (version.first) "★ " + stringResource(R.string.title_data_plays_first) else null,
            // One chip with the version it names: the same film, filed elsewhere by the provider.
            version.sameAs?.let { "= " + stringResource(R.string.title_data_same_as, it) },
        )
        Text(
            (listOf(version.chip ?: stringResource(R.string.title_data_hidden)) + marks).joinToString("  ·  "),
            color = if (version.playing) Color(0xFF81C784) else Color.White,
            style = style.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(version.name, color = Color.White, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(version.category, version.shownId?.let { "id $it" }, version.container, version.tags).joinToString("  ·  "),
            color = Color.White.copy(alpha = 0.7f),
            style = style,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // What its stream is made of, and where that comes from (measured, the provider's, its name).
        Text(
            stringResource(version.specSource) + ": " + version.specs,
            color = Color.White.copy(alpha = 0.7f),
            style = style,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("titleDataSpecs-${version.id}"),
        )
        TitleDataDiagnosis(version, style)
    }
}

/**
 * What the app knows of a version besides its specs: what its provider's `get_vod_info` held (or how it failed), whether
 * its details are kept, the names of its `info` fields, its Dolby Vision profile, and what it asks of this screen.
 */
@Composable
private fun TitleDataDiagnosis(version: TitleData.Version, style: TextStyle) {
    val dim = Color.White.copy(alpha = 0.7f)
    val status = when (version.provider) {
        TitleData.ProviderStatus.NOT_REQUESTED -> stringResource(R.string.title_data_provider_not_requested)
        TitleData.ProviderStatus.NOT_AVAILABLE -> stringResource(R.string.title_data_provider_not_available)
        TitleData.ProviderStatus.FAILED -> stringResource(R.string.title_data_provider_failed, version.providerFailure ?: "–")
        TitleData.ProviderStatus.KEPT_BEFORE -> stringResource(R.string.title_data_provider_kept_before)
        TitleData.ProviderStatus.NO_TECHNICAL_BLOCK -> stringResource(R.string.title_data_provider_no_block)
        TitleData.ProviderStatus.VIDEO_AND_AUDIO -> stringResource(R.string.title_data_provider_video_audio)
        TitleData.ProviderStatus.VIDEO_ONLY -> stringResource(R.string.title_data_provider_video)
        TitleData.ProviderStatus.AUDIO_ONLY -> stringResource(R.string.title_data_provider_audio)
    }
    val kept = stringResource(if (version.detailsKept) R.string.title_data_details_kept else R.string.title_data_details_not_kept)
    Text(
        stringResource(R.string.title_data_provider, status) + "  ·  " + kept,
        color = dim,
        style = style,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("titleDataProvider-${version.id}"),
    )
    if (version.providerKeys.isNotEmpty()) {
        Text(
            stringResource(R.string.title_data_info_keys, version.providerKeys.joinToString(", ")),
            color = dim,
            style = style,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("titleDataKeys-${version.id}"),
        )
    }
    val screen = listOfNotNull(
        if (version.dolbyVision) stringResource(R.string.title_data_dolby_vision_profile, version.dolbyVisionProfile ?: stringResource(R.string.title_data_unknown)) else null,
    ) + version.screenNotes.map { note ->
        stringResource(
            when (note) {
                ScreenNote.DOLBY_VISION_BASE_LAYER -> R.string.title_data_screen_dv_base_layer
                ScreenNote.DOLBY_VISION_WRONG_COLOURS -> R.string.title_data_screen_dv_wrong
                ScreenNote.DOLBY_VISION_UNKNOWN -> R.string.title_data_screen_dv_unknown
                ScreenNote.HDR_UNSUPPORTED -> R.string.title_data_screen_hdr_unsupported
                ScreenNote.UHD_UNSUPPORTED -> R.string.title_data_screen_uhd_unsupported
            },
        )
    }
    if (screen.isNotEmpty()) {
        Text(
            screen.joinToString("  ·  "),
            color = Color(0xFFFFCC80),
            style = style,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("titleDataScreenNotes-${version.id}"),
        )
    }
}

/**
 * What the app read of this screen ([TitleData.display]): its mode of the moment and its largest mode, the HDR formats it
 * takes, the decoders, whether it is 4K and why (with the display size Media3 reads), what the sound output takes (its
 * channels, the encodings it passes as they are), what the versions are chosen for, and the app's build. Nothing before the screen was read.
 */
@Composable
fun TitleDataScreen(data: TitleData, style: TextStyle) {
    val facts = data.display
    val dim = Color.White.copy(alpha = 0.7f)
    Column(Modifier.padding(top = 4.dp).testTag("titleDataScreen")) {
        Text(stringResource(R.string.title_data_screen), color = Color.White, style = style.copy(fontWeight = FontWeight.SemiBold))
        if (facts != null) {
            val yes = stringResource(R.string.title_data_yes)
            val no = stringResource(R.string.title_data_no)
            val unknown = stringResource(R.string.title_data_unknown)
            fun word(value: Boolean?) = when (value) {
                true -> yes
                false -> no
                null -> unknown
            }
            fun size(width: Int, height: Int) = if (width > 0 && height > 0) "$width×$height" else unknown
            Text(stringResource(R.string.title_data_screen_modes, size(facts.currentWidth, facts.currentHeight), size(facts.largestWidth, facts.largestHeight)), color = dim, style = style)
            Text(stringResource(R.string.title_data_screen_hdr, word(facts.hdr10), word(facts.hdr10Plus), word(facts.hlg), word(facts.dolbyVisionDisplay)), color = dim, style = style)
            Text(stringResource(R.string.title_data_screen_decoders, word(facts.dolbyVisionDecoder), word(facts.hevcMain10Decoder)), color = dim, style = style)
            // Where 4K came from: the modes, the display-size property Media3 reads, or an HDR TV with a 4K decoder.
            val uhd = when (facts.uhdSource) {
                DisplayFacts.UhdSource.MODES -> stringResource(R.string.title_data_uhd_modes)
                DisplayFacts.UhdSource.DISPLAY_SIZE -> stringResource(R.string.title_data_uhd_display_size)
                DisplayFacts.UhdSource.HDR_AND_DECODER -> stringResource(R.string.title_data_uhd_decoder)
                null -> if (facts.uhdChecked) no else unknown
            }
            Text(
                stringResource(R.string.title_data_screen_uhd, uhd, size(facts.displaySizeWidth, facts.displaySizeHeight)),
                color = dim,
                style = style,
                modifier = Modifier.testTag("titleDataUhd"),
            )
            // Whether the box hands 5.1 or Atmos to the TV or amplifier as it is.
            facts.audioOutput?.let { output ->
                Text(
                    stringResource(
                        R.string.title_data_screen_audio,
                        com.markedoe.ondo.core.model.StreamSpecs.layout(output.maxChannels),
                        output.passthrough.joinToString(", ").ifEmpty { stringResource(R.string.title_data_none) },
                    ),
                    color = dim,
                    style = style,
                    modifier = Modifier.testTag("titleDataAudioOutput"),
                )
            }
        }
        val chosen = listOfNotNull("4K".takeIf { data.support.uhd }, "HDR".takeIf { data.support.hdr }, "Dolby Vision".takeIf { data.support.dolbyVision })
        Text(stringResource(R.string.title_data_screen_chosen, chosen.joinToString(" · ").ifEmpty { "–" }), color = dim, style = style, modifier = Modifier.testTag("titleDataChosen"))
        Text(stringResource(R.string.title_data_app, data.app), color = dim, style = style)
    }
}

/**
 * The title data of a film on its TV page (OK held on a version's chip): every version, each a stop of the remote so
 * Down scrolls through them however many there are; Back closes it.
 */
@Composable
fun TitleDataDialog(data: TitleData, onDismiss: () -> Unit) {
    val style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LazyColumn(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxHeight(0.9f)
                .background(Color(0xF0141414), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 16.dp)
                .testTag("titleDataDialog"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "title") {
                FocusableBlock(Modifier.focusRequester(first)) {
                    Text(stringResource(R.string.title_data), color = Color.White, style = style.copy(fontWeight = FontWeight.SemiBold))
                    TitleDataLines(data, style)
                }
            }
            items(data.versions, key = { "version:" + it.id }) { version ->
                FocusableBlock { TitleDataVersion(version, style, current = R.string.title_data_this_chip) }
            }
            data.reason?.takeIf { data.versions.isNotEmpty() }?.let { reason ->
                item(key = "reason") { FocusableBlock { TitleDataReason(reason, style) } }
            }
            // After the versions, as in the player's panel.
            item(key = "screen") { FocusableBlock { TitleDataScreen(data, style) } }
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
}

/** A stop of the remote in [TitleDataDialog], outlined while it has the focus. */
@Composable
private fun FocusableBlock(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { content() }
}

/** Why the version marked first plays first. */
@Composable
fun TitleDataReason(reason: DefaultReason, style: TextStyle) {
    val text = when (reason) {
        DefaultReason.ONLY -> return
        DefaultReason.IN_PROGRESS -> R.string.title_data_first_in_progress
        DefaultReason.CHOSEN -> R.string.title_data_first_chosen
        DefaultReason.BEST_FOR_SCREEN -> R.string.title_data_first_best
        DefaultReason.FIRST -> R.string.title_data_first_first
    }
    Text(stringResource(text), color = Color.White.copy(alpha = 0.7f), style = style, modifier = Modifier.padding(top = 4.dp).testTag("titleDataReason"))
}
