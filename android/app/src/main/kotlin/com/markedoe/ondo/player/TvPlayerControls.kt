package com.markedoe.ondo.player

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.data.VideoScale
import com.markedoe.ondo.ui.theme.OndoColors
import com.markedoe.ondo.ui.tv.TvSwitchRow
import com.markedoe.ondo.ui.tv.TvTokens
import com.markedoe.ondo.ui.tv.tvRowColors
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** What the TV player's top lines say. */
data class TvPlayerInfo(
    /** The movie, the series or the channel. */
    val title: String,
    /** The episode's code and title, a movie's year, or the programme on now on a live channel. */
    val subtitle: String?,
    val live: Boolean,
    /** A live channel's programme on now, from the guide: its start and end (epoch milliseconds). */
    val programmeStart: Long? = null,
    val programmeEnd: Long? = null,
)

/**
 * The player's controls on a TV, drawn for the remote over Media3's video view (which still draws the picture, the
 * subtitles and the buffering spinner): the title at the top; at the bottom the progress bar (or LIVE) and a row of large
 * round buttons that turn white with the focus, each named beneath when the remote is on it; side panels for the
 * subtitles, the audio, the speed and More (the aspect ratio, the sleep timer, the statistics). Everything keeps clear of the 5 % the screen may crop (TV-OV). Playback
 * controls are not mirrored in a right-to-left language: they follow the video's time, left to right.
 */
@Composable
fun TvPlayerOverlay(
    player: Player,
    controls: TvControls,
    info: TvPlayerInfo,
    statisticsShown: Boolean,
    onToggleStatistics: () -> Unit,
    onReportProblem: () -> Unit,
    scale: VideoScale,
    onScale: (VideoScale) -> Unit,
    sleep: SleepTimer,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = controls.refresh(player)
        }
        controls.refresh(player)
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val playPause = remember { FocusRequester() }
    val buttons = remember { TvPanel.entries.associateWith { FocusRequester() } }
    // Play/Pause takes the focus each time the controls come; a closed panel gives it back to its button.
    LaunchedEffect(controls.shows) {
        if (controls.shown && controls.panel == null) takeFocus(playPause)
    }
    var openPanel by remember { mutableStateOf<TvPanel?>(null) }
    LaunchedEffect(controls.panel) {
        val closed = openPanel
        openPanel = controls.panel
        // From a panel of More, back to More's button.
        if (controls.panel == null && closed != null && controls.shown) takeFocus(buttons.getValue(closed.parent ?: closed))
    }
    val seekBar = !info.live || controls.seekable
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(visible = controls.shown || controls.peeking, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (controls.shown) Modifier.testTag("tvPlayerControls") else if (controls.peeking) Modifier.testTag("tvPlayerPeek") else Modifier),
            ) {
                TopLines(info, clock = controls.clockMs.takeIf { controls.shown }, sleepEndsAt = sleep.endsAt, Modifier.align(Alignment.TopCenter))
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.85f)))
                        .padding(start = TvTokens.EdgeEnd, end = TvTokens.EdgeEnd, top = 72.dp, bottom = TvTokens.EdgeTop),
                ) {
                    // The video's time runs left to right in every language.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        if (seekBar) {
                            SeekBar(player, controls, focusable = controls.shown, live = info.live)
                        } else {
                            LiveLine(info, controls.clockMs)
                        }
                        if (controls.shown) {
                            Spacer(Modifier.height(18.dp))
                            ButtonRow(player, controls, info, playPause, buttons)
                        }
                    }
                }
                // Back on the left, forward on the right, as the bar, in every language.
                if (controls.skipped != 0) SkipBadge(controls.skipped, Modifier.align(if (controls.skipped < 0) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight))
            }
        }
        val panel = controls.panel
        // A dim veil over the video and the controls while a panel is open.
        AnimatedVisibility(visible = panel != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        AnimatedVisibility(
            visible = panel != null,
            enter = slideInHorizontally { if (rtl) -it else it } + fadeIn(),
            exit = slideOutHorizontally { if (rtl) -it else it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            // The last panel stays drawn while it slides out.
            var shownPanel by remember { mutableStateOf(panel ?: TvPanel.SUBTITLES) }
            if (panel != null) shownPanel = panel
            SidePanel(
                panel = shownPanel,
                open = panel != null,
                player = player,
                controls = controls,
                statisticsShown = statisticsShown,
                onToggleStatistics = onToggleStatistics,
                onReportProblem = {
                    controls.closePanel()
                    onReportProblem()
                },
                scale = scale,
                onScale = onScale,
                sleep = sleep,
            )
        }
    }
}

/** Requests the focus a few frames in a row: the item may still be coming on screen. */
private suspend fun takeFocus(requester: FocusRequester) {
    repeat(20) {
        if (runCatching { requester.requestFocus() }.getOrDefault(false)) return
        delay(16)
    }
}

/**
 * The title and its line beneath, and the time of day while the controls are shown ([clock]), after the time the
 * sleep timer pauses the player ([sleepEndsAt]).
 */
@Composable
private fun TopLines(info: TvPlayerInfo, clock: Long?, sleepEndsAt: Long?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.75f), 0.6f to Color.Black.copy(alpha = 0.35f), 1f to Color.Transparent))
            .padding(start = TvTokens.EdgeEnd, top = TvTokens.EdgeTop, end = TvTokens.EdgeEnd, bottom = 56.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(info.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            info.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (clock != null) {
            if (sleepEndsAt != null) {
                Spacer(Modifier.width(24.dp))
                Row(Modifier.semantics(mergeDescendants = true) {}.testTag("sleepEndsAt"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bedtime, contentDescription = stringResource(R.string.sleep_timer), tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(clockText(sleepEndsAt), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f))
                }
            }
            Spacer(Modifier.width(24.dp))
            Text(clockText(clock), style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = 0.9f))
        }
    }
}

@Composable
private fun clockText(now: Long): String = DateFormat.getTimeFormat(LocalContext.current).format(Date(now))

/** A live channel: the LIVE badge, and the programme on now with its times and how far it is. */
@Composable
private fun LiveLine(info: TvPlayerInfo, now: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LiveBadge()
        val start = info.programmeStart
        val end = info.programmeEnd
        if (start != null && end != null && end > start) {
            val format = DateFormat.getTimeFormat(LocalContext.current)
            Text("${format.format(Date(start))} – ${format.format(Date(end))}", style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.85f))
            Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)).height(4.dp).background(TvTokens.Lime))
            }
        }
    }
}

@Composable
private fun LiveBadge() {
    Row(
        Modifier.background(LiveRed, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 3.dp).testTag("liveBadge"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).background(Color.White, CircleShape))
        Text(stringResource(R.string.live_badge), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, color = Color.White)
    }
}

private val LiveRed = Color(0xFFE53935)

/**
 * The progress bar, with the part already loaded. With the focus, Left and Right move a target along it (faster while
 * held) and the player goes there once the remote stops, or on OK ([TvControls.scrub]). A [live] stream that can go
 * back has it too, with LIVE.
 */
@Composable
private fun SeekBar(player: Player, controls: TvControls, focusable: Boolean, live: Boolean) {
    var focused by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val duration = controls.durationMs
    val target = controls.scrubMs
    val position = target ?: controls.positionMs
    val fraction = if (duration != null) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val buffered = if (duration != null) (controls.bufferedMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val elapsed = time(position, locale)
    val total = duration?.let { time(it, locale) }
    Column {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    if (!focused || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            controls.scrub(player, forward = event.key == Key.DirectionRight, repeat = event.nativeKeyEvent.repeatCount)
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (controls.scrubMs != null) controls.commitScrub(player) else return@onKeyEvent false
                            true
                        }
                        else -> false
                    }
                }
                .focusable(focusable)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    stateDescription = listOfNotNull(elapsed, total).joinToString(" / ")
                }
                .testTag("tvSeekBar"),
            contentAlignment = Alignment.CenterStart,
        ) {
            val barHeight = if (focused) 8.dp else 5.dp
            Box(Modifier.fillMaxWidth().height(barHeight).clip(CircleShape).background(Color.White.copy(alpha = if (focused) 0.32f else 0.22f))) {
                Box(Modifier.fillMaxWidth(buffered).height(barHeight).background(Color.White.copy(alpha = 0.35f)))
                Box(Modifier.fillMaxWidth(fraction).height(barHeight).background(TvTokens.Lime))
            }
            val thumb = if (focused) 22.dp else 12.dp
            val x = (maxWidth - thumb) * fraction
            Box(
                Modifier
                    .offset(x = x)
                    .size(thumb)
                    .background(if (focused) Color.White else TvTokens.Lime, CircleShape)
                    .then(if (focused) Modifier.border(4.dp, TvTokens.Lime.copy(alpha = 0.5f), CircleShape) else Modifier),
            )
            if (target != null) {
                // Where the player will go: the time above the thumb.
                Text(
                    elapsed,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TvTokens.Ink,
                    modifier = Modifier
                        .offset(x = (x + thumb / 2 - 36.dp).coerceIn(0.dp, maxWidth - 72.dp), y = (-30).dp)
                        .width(72.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(vertical = 4.dp)
                        .wrapContentSize(Alignment.Center)
                        .testTag("seekTarget"),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(elapsed, style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.testTag("elapsed"))
            Spacer(Modifier.weight(1f))
            // A live stream that can go back (timeshift): its bar, and LIVE.
            if (live) {
                LiveBadge()
                Spacer(Modifier.width(12.dp))
            }
            if (duration != null) {
                Text("−" + time((duration - position).coerceAtLeast(0), locale), style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.testTag("remaining"))
            }
        }
    }
}

/** "1:02:03", "4:05", in the app's digits. */
internal fun time(ms: Long, locale: Locale): String {
    val seconds = ms / 1_000
    val (h, m, s) = Triple(seconds / 3_600, seconds / 60 % 60, seconds % 60)
    return if (h > 0) String.format(locale, "%d:%02d:%02d", h, m, s) else String.format(locale, "%d:%02d", m, s)
}

@Composable
private fun ButtonRow(player: Player, controls: TvControls, info: TvPlayerInfo, playPause: FocusRequester, buttons: Map<TvPanel, FocusRequester>) {
    // Where the player cannot seek (a live channel, a film without an index), a skip would start the video over.
    val transport = controls.seekable
    val playing = controls.playWhenReady
    Row(Modifier.fillMaxWidth().focusGroup(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (transport) {
            RoundButton(Icons.Rounded.Replay10, stringResource(R.string.skip_back_10), tag = "tvSkipBack", labelAlignment = Alignment.TopStart) {
                controls.skip(player, -TvControls.SKIP_MS)
            }
        }
        RoundButton(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            stringResource(if (playing) R.string.pause else R.string.play),
            tag = "tvPlayPause",
            size = LargeButton,
            labelAlignment = if (transport) Alignment.TopCenter else Alignment.TopStart,
            modifier = Modifier.focusRequester(playPause),
        ) {
            if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause() else player.play()
            controls.touch()
        }
        if (transport) {
            RoundButton(Icons.Rounded.Forward10, stringResource(R.string.skip_forward_10), tag = "tvSkipForward") {
                controls.skip(player, TvControls.SKIP_MS)
            }
        }
        Spacer(Modifier.weight(1f))
        val subtitlesOn = !TrackChoices.noneSelected(controls.tracks, C.TRACK_TYPE_TEXT)
        RoundButton(Icons.Rounded.Subtitles, stringResource(R.string.subtitles), tag = "tvSubtitles", on = subtitlesOn, modifier = Modifier.focusRequester(buttons.getValue(TvPanel.SUBTITLES))) {
            controls.open(TvPanel.SUBTITLES)
        }
        RoundButton(Icons.Rounded.RecordVoiceOver, stringResource(R.string.audio), tag = "tvAudio", modifier = Modifier.focusRequester(buttons.getValue(TvPanel.AUDIO))) {
            controls.open(TvPanel.AUDIO)
        }
        if (!info.live) {
            RoundButton(Icons.Rounded.Speed, stringResource(R.string.speed), tag = "tvSpeed", on = controls.speed != 1f, modifier = Modifier.focusRequester(buttons.getValue(TvPanel.SPEED))) {
                controls.open(TvPanel.SPEED)
            }
        }
        RoundButton(Icons.Rounded.MoreHoriz, stringResource(R.string.more), tag = "tvMore", labelAlignment = Alignment.TopEnd, modifier = Modifier.focusRequester(buttons.getValue(TvPanel.MORE))) {
            controls.open(TvPanel.MORE)
        }
    }
}

/**
 * A large round button of the player: translucent, white with a dark icon when the remote is on it, its name beneath
 * then. [on] puts a lime dot under it (subtitles on, another speed). The name may be wider than the button:
 * [labelAlignment] keeps the first and last ones inside the screen.
 */
@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    on: Boolean = false,
    labelAlignment: Alignment = Alignment.TopCenter,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.height(LargeButton + 30.dp)) {
        IconButton(
            onClick = onClick,
            modifier = modifier
                .align(Alignment.TopCenter)
                .padding(top = (LargeButton - size) / 2)
                .size(size)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label }
                .testTag(tag),
            scale = IconButtonDefaults.scale(focusedScale = 1.12f),
            shape = IconButtonDefaults.shape(CircleShape),
            colors = IconButtonDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.14f),
                contentColor = Color.White,
                focusedContainerColor = Color.White,
                focusedContentColor = TvTokens.Ink,
            ),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.5f))
        }
        if (on) Box(Modifier.align(Alignment.TopCenter).padding(top = LargeButton + 3.dp).size(5.dp).background(TvTokens.Lime, CircleShape))
        if (focused) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.align(labelAlignment).padding(top = LargeButton + 8.dp).wrapContentSize(unbounded = true, align = labelAlignment),
            )
        }
    }
}

/** Play/Pause; the other buttons are a little smaller. */
private val LargeButton = 64.dp

/** After a skip with the controls hidden: the skip's icon on its side of the screen, for a moment. */
@Composable
private fun SkipBadge(direction: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = TvTokens.EdgeEnd + 48.dp)
            .size(88.dp)
            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            .testTag("tvSkipBadge"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (direction < 0) Icons.Rounded.Replay10 else Icons.Rounded.Forward10, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
    }
}

/** The side panel of [panel], on the end side, the remote kept inside it until Back. */
@Composable
private fun SidePanel(
    panel: TvPanel,
    open: Boolean,
    player: Player,
    controls: TvControls,
    statisticsShown: Boolean,
    onToggleStatistics: () -> Unit,
    onReportProblem: () -> Unit,
    scale: VideoScale,
    onScale: (VideoScale) -> Unit,
    sleep: SleepTimer,
) {
    val first = remember(panel) { FocusRequester() }
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(400.dp)
            // The remote stays in the panel: Back closes it.
            .focusProperties { onExit = { if (controls.panel != null) cancelFocusChange() } }
            .focusGroup()
            .then(if (open) Modifier.testTag("tvTrackPanel") else Modifier),
        shape = RectangleShape,
        colors = SurfaceDefaults.colors(containerColor = OndoColors.SurfaceHigh.copy(alpha = 0.97f), contentColor = Color.White),
    ) {
        val title = when (panel) {
            TvPanel.SUBTITLES -> R.string.subtitles
            TvPanel.AUDIO -> R.string.audio
            TvPanel.SPEED -> R.string.speed
            TvPanel.MORE -> R.string.more
            TvPanel.ASPECT -> R.string.aspect_ratio
            TvPanel.SLEEP -> R.string.sleep_timer
        }
        Column(Modifier.fillMaxSize().padding(start = 28.dp, top = TvTokens.EdgeTop + 16.dp, end = TvTokens.EdgeEnd, bottom = TvTokens.EdgeTop)) {
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, bottom = 16.dp))
            val words = trackWords()
            val locale = LocalConfiguration.current.locales[0]
            val type = if (panel == TvPanel.SUBTITLES) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
            val choices = if (panel == TvPanel.SUBTITLES || panel == TvPanel.AUDIO) TrackChoices.of(controls.tracks, type, words, locale) else emptyList()
            // Again when the tracks come (the line saying there are none goes, and had the focus).
            LaunchedEffect(panel, open, choices.isEmpty()) { if (open) takeFocus(first) }
            // The list opens on the row in use: a row far down a long list is not drawn yet, and could not take the focus.
            val selectedRow = when (panel) {
                TvPanel.SUBTITLES -> choices.indexOfFirst { it.selected } + 1
                TvPanel.AUDIO -> choices.indexOfFirst { it.selected }.coerceAtLeast(0)
                TvPanel.SPEED -> SPEEDS.indexOf(controls.speed).takeIf { it >= 0 } ?: SPEEDS.indexOf(1f)
                TvPanel.MORE -> 0
                TvPanel.ASPECT -> scale.ordinal
                TvPanel.SLEEP -> SLEEP_CHOICES.indexOf(sleep.minutes).coerceAtLeast(0)
            }
            val list = remember(panel) { LazyListState(firstVisibleItemIndex = selectedRow) }
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                when (panel) {
                    TvPanel.SUBTITLES, TvPanel.AUDIO -> tracks(player, controls, type, choices, first)
                    TvPanel.SPEED -> speeds(player, controls, locale, first)
                    TvPanel.MORE -> more(controls, scale, sleep, statisticsShown, onToggleStatistics, onReportProblem, first)
                    TvPanel.ASPECT -> scales(controls, scale, onScale, first)
                    TvPanel.SLEEP -> sleeps(controls, sleep, first)
                }
            }
        }
    }
}

@Composable
private fun trackWords(): TrackWords {
    val resources = LocalResources.current
    return TrackWords(
        track = { resources.getString(R.string.track_number, it) },
        forced = stringResource(R.string.track_forced),
        hearingImpaired = stringResource(R.string.track_hearing_impaired),
        commentary = stringResource(R.string.track_commentary),
        audioDescription = stringResource(R.string.track_audio_description),
        mono = stringResource(R.string.track_mono),
        stereo = stringResource(R.string.track_stereo),
    )
}

private fun LazyListScope.tracks(player: Player, controls: TvControls, type: Int, choices: List<TrackChoice>, first: FocusRequester) {
    val off = choices.none { it.selected }
    val choose = { choice: TrackChoice? ->
        player.trackSelectionParameters = TrackChoices.select(player.trackSelectionParameters, type, choice)
        controls.closePanel()
    }
    if (type == C.TRACK_TYPE_TEXT) {
        item(key = "off") {
            ChoiceRow(stringResource(R.string.off), null, selected = off, tag = "trackOff", modifier = if (off) Modifier.focusRequester(first) else Modifier) { choose(null) }
        }
    }
    choices.forEachIndexed { index, choice ->
        item(key = "track-$index") {
            val focusFirst = choice.selected || (type == C.TRACK_TYPE_AUDIO && index == 0 && choices.none { it.selected })
            ChoiceRow(choice.name, choice.detail, selected = choice.selected, tag = "track-$index", modifier = if (focusFirst) Modifier.focusRequester(first) else Modifier) { choose(choice) }
        }
    }
    if (choices.isEmpty()) {
        item(key = "none") {
            val hint = when {
                controls.tracks.isEmpty -> R.string.tracks_pending
                type == C.TRACK_TYPE_TEXT -> R.string.no_subtitles
                else -> R.string.no_audio
            }
            // With no row to choose, the line itself keeps the remote in the panel (Subtitles has Off).
            val holdsFocus = type == C.TRACK_TYPE_AUDIO
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .then(if (holdsFocus) Modifier.focusRequester(first).focusable() else Modifier)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("tracksHint"),
            )
        }
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun LazyListScope.speeds(player: Player, controls: TvControls, locale: Locale, first: FocusRequester) {
    val numbers = NumberFormat.getNumberInstance(locale)
    SPEEDS.forEach { speed ->
        item(key = "speed-$speed") {
            val selected = controls.speed == speed
            // Another speed than these (set elsewhere): the remote starts on Normal.
            val focusFirst = selected || (speed == 1f && controls.speed !in SPEEDS)
            val label = if (speed == 1f) stringResource(R.string.speed_normal) else numbers.format(speed) + "×"
            ChoiceRow(label, null, selected = selected, tag = "speed-$speed", modifier = if (focusFirst) Modifier.focusRequester(first) else Modifier) {
                player.setPlaybackSpeed(speed)
                controls.closePanel()
            }
        }
    }
}

private fun LazyListScope.more(
    controls: TvControls,
    scale: VideoScale,
    sleep: SleepTimer,
    statisticsShown: Boolean,
    onToggleStatistics: () -> Unit,
    onReportProblem: () -> Unit,
    first: FocusRequester,
) {
    // The remote starts on the first row, or on the row of the panel Back came from.
    val focusSleep = controls.cameBackFrom == TvPanel.SLEEP
    item(key = "aspect") {
        MoreRow(stringResource(R.string.aspect_ratio), stringResource(scale.label), Icons.Rounded.AspectRatio, tag = "tvAspect", modifier = if (!focusSleep) Modifier.focusRequester(first) else Modifier) {
            controls.open(TvPanel.ASPECT)
        }
    }
    item(key = "sleep") {
        val value = sleep.endsAt?.let { stringResource(R.string.sleep_ends_at, clockText(it)) } ?: stringResource(R.string.off)
        MoreRow(stringResource(R.string.sleep_timer), value, Icons.Rounded.Bedtime, tag = "tvSleep", modifier = if (focusSleep) Modifier.focusRequester(first) else Modifier) {
            controls.open(TvPanel.SLEEP)
        }
    }
    item(key = "statistics") {
        TvSwitchRow(
            title = stringResource(R.string.stream_statistics),
            subtitle = null,
            checked = statisticsShown,
            onChange = { onToggleStatistics() },
            icon = Icons.Rounded.QueryStats,
            modifier = Modifier.testTag("statisticsToggle"),
        )
    }
    item(key = "report") {
        ListItem(
            selected = false,
            onClick = onReportProblem,
            headlineContent = { Text(stringResource(R.string.report_problem), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(Icons.Rounded.BugReport, contentDescription = null, modifier = Modifier.size(24.dp)) },
            modifier = Modifier.testTag("reportProblem"),
            colors = tvRowColors(),
            shape = ListItemDefaults.shape(RoundedCornerShape(14.dp)),
            scale = ListItemDefaults.scale(focusedScale = 1.02f),
        )
    }
}

private fun LazyListScope.scales(controls: TvControls, scale: VideoScale, onScale: (VideoScale) -> Unit, first: FocusRequester) {
    VideoScale.entries.forEach { option ->
        item(key = "scale-$option") {
            val selected = option == scale
            ChoiceRow(stringResource(option.label), null, selected = selected, tag = "scale-${option.name}", modifier = if (selected) Modifier.focusRequester(first) else Modifier) {
                onScale(option)
                controls.closePanel()
            }
        }
    }
}

/** Off, then the minutes of [SleepTimer.CHOICES]. */
private val SLEEP_CHOICES: List<Int?> = listOf(null) + SleepTimer.CHOICES

private fun LazyListScope.sleeps(controls: TvControls, sleep: SleepTimer, first: FocusRequester) {
    SLEEP_CHOICES.forEach { minutes ->
        val tag = "sleep-${minutes ?: "off"}"
        item(key = tag) {
            val selected = sleep.minutes == minutes
            val name = if (minutes == null) stringResource(R.string.off) else stringResource(R.string.sleep_minutes, minutes)
            ChoiceRow(name, null, selected = selected, tag = tag, modifier = if (selected) Modifier.focusRequester(first) else Modifier) {
                sleep.set(minutes)
                controls.closePanel()
            }
        }
    }
}

/** A row of More that opens a panel: what it sets, its value beneath. */
@Composable
private fun MoreRow(title: String, value: String, icon: ImageVector, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(22.dp)) },
        modifier = modifier.testTag(tag),
        colors = tvRowColors(),
        shape = ListItemDefaults.shape(RoundedCornerShape(14.dp)),
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
    )
}

/** A row of a panel: the choice, what tells it apart, a tick on the one in use. */
@Composable
private fun ChoiceRow(name: String, detail: String?, selected: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = detail?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        trailingContent = { if (selected) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(22.dp)) },
        modifier = modifier.semantics { this.selected = selected }.testTag(tag),
        colors = tvRowColors(),
        shape = ListItemDefaults.shape(RoundedCornerShape(14.dp)),
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
    )
}
