package com.markedoe.ondo.player

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import com.markedoe.ondo.R
import com.markedoe.ondo.data.SubtitleSize
import com.markedoe.ondo.data.VideoScale
import com.markedoe.ondo.ui.theme.OndoColors
import java.text.NumberFormat
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The screen's brightness and the media volume, as the vertical swipes change them (0 to 1). */
interface PlayerLevels {
    val brightness: Float
    fun setBrightness(level: Float)
    val volume: Float
    fun setVolume(level: Float)
}

/**
 * The player's controls on a phone or a tablet, drawn over Media3's video view (which still draws the picture, the
 * subtitles and the buffering spinner), as video apps do today:
 * - a tap shows or hides the controls; a double tap on the left or right third goes back or forward 10 seconds (more
 *   taps, more seconds), in the middle it plays or pauses;
 * - a finger held down plays at twice the speed until it lifts;
 * - sliding up or down on the left changes the brightness, on the right the volume; pinching fills the screen or fits
 *   the whole picture;
 * - the controls: back, title, Picture-in-Picture, lock, More at the top; back 10 s, Play/Pause, forward 10 s in the
 *   middle; the progress bar (or LIVE and the programme) and Subtitles, Audio, Speed at the bottom; their lists open
 *   in sheets from the bottom;
 * - locked, the screen ignores everything but a tap, which shows the unlock button.
 * Playback controls are not mirrored in a right-to-left language: they follow the video's time, left to right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhonePlayerOverlay(
    player: Player,
    controls: PlayerControls,
    info: PlayerInfo,
    statisticsShown: Boolean,
    onToggleStatistics: () -> Unit,
    onReportProblem: () -> Unit,
    scale: VideoScale,
    onScale: (VideoScale) -> Unit,
    sleep: SleepTimer,
    levels: PlayerLevels,
    onClose: () -> Unit,
    onPictureInPicture: (() -> Unit)?,
    modifier: Modifier = Modifier,
    channels: ChannelList? = null,
    onRestart: () -> Unit = {},
    onWatchLive: () -> Unit = {},
    subtitles: SubtitleSizing? = null,
) {
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = controls.refresh(player)
        }
        controls.refresh(player)
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var locked by remember { mutableStateOf(false) }
    var unlockShown by remember { mutableStateOf(false) }
    var unlockJob by remember { mutableStateOf<Job?>(null) }
    fun showUnlock() {
        unlockShown = true
        unlockJob?.cancel()
        unlockJob = scope.launch {
            delay(UNLOCK_SHOWN_MS)
            unlockShown = false
        }
    }
    // Double taps: the side and the seconds added up while they follow each other.
    var seekSide by remember { mutableIntStateOf(0) }
    var seekSeconds by remember { mutableIntStateOf(0) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    fun doubleTapSkip(direction: Int) {
        if (!player.isCurrentMediaItemSeekable) return
        if (seekSide != direction) seekSeconds = 0
        seekSide = direction
        seekSeconds += (PlayerControls.SKIP_MS / 1_000).toInt()
        controls.skip(player, direction * PlayerControls.SKIP_MS)
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(SEEK_FEEDBACK_MS)
            seekSide = 0
            seekSeconds = 0
        }
    }
    // A finger held down: twice the speed until it lifts.
    var boosting by remember { mutableStateOf(false) }
    // The level being changed with a slide, shown while the finger moves.
    var levelShown by remember { mutableStateOf<LevelKind?>(null) }
    var level by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .fillMaxSize()
            .testTag("phonePlayer")
            .pointerInput(locked) {
                detectTapGestures(
                    onTap = {
                        when {
                            locked -> showUnlock()
                            controls.shown -> controls.hide()
                            else -> controls.show()
                        }
                    },
                    onDoubleTap = { offset ->
                        if (!locked) {
                            val third = size.width / 3f
                            when {
                                offset.x < third -> doubleTapSkip(-1)
                                offset.x > size.width - third -> doubleTapSkip(1)
                                else -> if (player.isPlaying) player.pause() else player.play()
                            }
                        }
                    },
                    onPress = {
                        if (locked) return@detectTapGestures
                        val before = player.playbackParameters.speed
                        val hold = scope.launch {
                            delay(BOOST_AFTER_MS)
                            if (player.isPlaying && before < BOOST_SPEED) {
                                boosting = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                player.setPlaybackSpeed(BOOST_SPEED)
                            }
                        }
                        tryAwaitRelease()
                        hold.cancel()
                        if (boosting) {
                            player.setPlaybackSpeed(before)
                            boosting = false
                        }
                    },
                )
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                var kind: LevelKind? = null
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        // Not from the screen's edges: the system's own gestures start there.
                        val edge = EDGE.toPx()
                        kind = when {
                            offset.y < edge || offset.y > size.height - edge -> null
                            offset.x < size.width / 2f -> LevelKind.BRIGHTNESS
                            else -> LevelKind.VOLUME
                        }
                        level = when (kind) {
                            LevelKind.BRIGHTNESS -> levels.brightness
                            LevelKind.VOLUME -> levels.volume
                            null -> 0f
                        }
                        levelShown = kind
                    },
                    onDragEnd = { levelShown = null },
                    onDragCancel = { levelShown = null },
                    onVerticalDrag = { change, dragAmount ->
                        val changing = kind ?: return@detectVerticalDragGestures
                        change.consume()
                        level = (level - dragAmount / (size.height * LEVEL_SPAN)).coerceIn(0f, 1f)
                        if (changing == LevelKind.BRIGHTNESS) levels.setBrightness(level) else levels.setVolume(level)
                    },
                )
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                // Two fingers: pinching out fills the screen, pinching in shows the whole picture.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var zoom = 1f
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            zoom *= event.calculateZoom()
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    when {
                        zoom > PINCH_OUT -> onScale(VideoScale.FILL)
                        zoom < PINCH_IN -> onScale(VideoScale.FIT)
                    }
                }
            },
    ) {
        AnimatedVisibility(visible = controls.shown && !locked, enter = fadeIn(), exit = fadeOut()) {
            Controls(player, controls, info, sleep, channels, onClose, onPictureInPicture, onRestart, onWatchLive, onLock = {
                locked = true
                controls.hide()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                showUnlock()
            })
        }
        // Locked: the unlock button for a moment after a tap.
        AnimatedVisibility(
            visible = locked && unlockShown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp),
        ) {
            Surface(
                onClick = {
                    locked = false
                    unlockShown = false
                    controls.show()
                },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                contentColor = Color.White,
                modifier = Modifier.testTag("playerUnlock"),
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.LockOpen, contentDescription = null)
                    Text(stringResource(R.string.unlock_controls), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        // Back on the left, forward on the right, as the bar, in every language.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            if (seekSide != 0) SeekFeedback(seekSide, seekSeconds, Modifier.align(if (seekSide < 0) Alignment.CenterStart else Alignment.CenterEnd))
        }
        if (boosting) {
            Pill(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 16.dp).testTag("speedBoost")) {
                Icon(Icons.Rounded.FastForward, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).format(BOOST_SPEED) + "×", style = MaterialTheme.typography.labelLarge)
            }
        }
        levelShown?.let { kind ->
            LevelIndicator(kind, level, Modifier.align(Alignment.Center))
        }
    }
    // The lists, in a sheet from the bottom.
    controls.panel?.let { panel ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { controls.closePanel() },
            sheetState = sheet,
            containerColor = OndoColors.SurfaceHigh,
            contentColor = Color.White,
            modifier = Modifier.testTag("playerSheet"),
        ) {
            PanelContent(panel, player, controls, statisticsShown, onToggleStatistics, onReportProblem, scale, onScale, sleep, channels, subtitles)
        }
    }
}

private enum class LevelKind { BRIGHTNESS, VOLUME }

@Composable
private fun Controls(
    player: Player,
    controls: PlayerControls,
    info: PlayerInfo,
    sleep: SleepTimer,
    channels: ChannelList?,
    onClose: () -> Unit,
    onPictureInPicture: (() -> Unit)?,
    onRestart: () -> Unit,
    onWatchLive: () -> Unit,
    onLock: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .testTag("phonePlayerControls"),
    ) {
        // Top: back, the title, Picture-in-Picture, lock, More.
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("playerClose")) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.close), tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(info.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                info.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            sleep.endsAt?.let { end ->
                Row(Modifier.padding(horizontal = 8.dp).semantics(mergeDescendants = true) {}.testTag("sleepEndsAt"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bedtime, contentDescription = stringResource(R.string.sleep_timer), tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(DateFormat.getTimeFormat(LocalContext.current).format(Date(end)), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
                }
            }
            onPictureInPicture?.let { enter ->
                IconButton(onClick = enter, modifier = Modifier.testTag("playerPip")) {
                    Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = stringResource(R.string.picture_in_picture), tint = Color.White)
                }
            }
            IconButton(onClick = onLock, modifier = Modifier.testTag("playerLock")) {
                Icon(Icons.Rounded.Lock, contentDescription = stringResource(R.string.lock_controls), tint = Color.White)
            }
            IconButton(onClick = { controls.open(PlayerPanel.MORE) }, modifier = Modifier.testTag("playerMore")) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more), tint = Color.White)
            }
        }
        // Middle: back 10 s, Play/Pause, forward 10 s; left to right in every language.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                if (controls.seekable) {
                    RoundIcon(Icons.Rounded.Replay10, stringResource(R.string.skip_back_10), 56.dp, "phoneSkipBack") { controls.skip(player, -PlayerControls.SKIP_MS) }
                }
                val playing = controls.playWhenReady
                RoundIcon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (playing) R.string.pause else R.string.play),
                    76.dp,
                    "phonePlayPause",
                    filled = true,
                ) {
                    if (player.playWhenReady) player.pause() else player.play()
                    controls.touch()
                }
                if (controls.seekable) {
                    RoundIcon(Icons.Rounded.Forward10, stringResource(R.string.skip_forward_10), 56.dp, "phoneSkipForward") { controls.skip(player, PlayerControls.SKIP_MS) }
                }
            }
        }
        // Bottom: where the video is, then the lists.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f)))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                if (!info.live || controls.seekable) {
                    TimeBar(player, controls, live = info.live, replay = info.replay)
                } else {
                    PhoneLiveLine(info, controls.clockMs)
                }
            }
            // A narrow screen scrolls the chips rather than cutting the last one.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (channels != null) {
                    ActionChip(Icons.AutoMirrored.Rounded.List, stringResource(R.string.channels), "phoneChannels") { controls.open(PlayerPanel.CHANNELS) }
                }
                // The programme on now from its start, from the provider's archive; from a replay, back to the channel.
                if (info.live && info.canRestart) {
                    ActionChip(Icons.Rounded.SkipPrevious, stringResource(R.string.restart_programme), "phoneRestart") { onRestart() }
                }
                if (info.replay) {
                    ActionChip(Icons.Rounded.LiveTv, stringResource(R.string.back_to_live), "phoneWatchLive") { onWatchLive() }
                }
                ActionChip(Icons.Rounded.Subtitles, stringResource(R.string.subtitles), "phoneSubtitles") { controls.open(PlayerPanel.SUBTITLES) }
                ActionChip(Icons.Rounded.RecordVoiceOver, stringResource(R.string.audio), "phoneAudio") { controls.open(PlayerPanel.AUDIO) }
                if (controls.seekable) {
                    val speed = controls.speed
                    val label = if (speed == 1f) stringResource(R.string.speed) else NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).format(speed) + "×"
                    ActionChip(Icons.Rounded.Speed, label, "phoneSpeed") { controls.open(PlayerPanel.SPEED) }
                }
                ActionChip(Icons.Rounded.AspectRatio, stringResource(R.string.aspect_ratio), "phoneAspect") { controls.open(PlayerPanel.ASPECT) }
            }
        }
    }
}

/**
 * The progress bar, with the time played and the time left (or LIVE and Back to live on a live stream with a window);
 * REPLAY on a channel played from its archive.
 */
@Composable
private fun TimeBar(player: Player, controls: PlayerControls, live: Boolean, replay: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    val duration = controls.durationMs ?: 0L
    val shown = (controls.scrubMs ?: controls.positionMs).coerceIn(0, maxOf(duration, 0))
    Column(Modifier.fillMaxWidth()) {
        SeekTrack(player, controls, duration, shown)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(time(shown, locale), style = MaterialTheme.typography.labelMedium, color = Color.White)
            Spacer(Modifier.weight(1f))
            if (live) {
                // At the live edge, or behind it: then a tap goes back to it.
                val atEdge = duration - shown < LIVE_EDGE_MS
                TextButton(onClick = { player.seekToDefaultPosition(); controls.touch() }, enabled = !atEdge, modifier = Modifier.testTag("backToLive")) {
                    Box(Modifier.size(8.dp).background(if (atEdge) LiveRed else Color.White.copy(alpha = 0.6f), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (atEdge) R.string.live_badge else R.string.back_to_live), style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            } else {
                if (replay) {
                    ReplayBadge()
                    Spacer(Modifier.width(10.dp))
                }
                if (duration > 0) Text("−" + time(duration - shown, locale), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

/** The track itself: played, buffered, the thumb; a tap or a drag moves the video there when the finger lifts. */
@Composable
private fun SeekTrack(player: Player, controls: PlayerControls, duration: Long, shown: Long) {
    val fraction = if (duration > 0) shown.toFloat() / duration else 0f
    val buffered = if (duration > 0) (controls.bufferedMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    var dragging by remember { mutableStateOf(false) }
    val description = time(shown, LocalConfiguration.current.locales[0])
    Box(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { target ->
                    controls.scrubTo(player, (target * duration).toLong())
                    controls.commitScrub(player)
                    true
                }
            }
            .testTag("phoneSeekBar")
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                detectTapGestures { offset ->
                    controls.scrubTo(player, (offset.x / size.width * duration).toLong())
                    controls.commitScrub(player)
                }
            }
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        controls.scrubTo(player, (offset.x / size.width * duration).toLong())
                    },
                    onDragEnd = {
                        dragging = false
                        controls.commitScrub(player)
                    },
                    onDragCancel = {
                        dragging = false
                        controls.cancelScrub()
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        controls.scrubTo(player, (change.position.x / size.width * duration).toLong())
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(if (dragging) 24.dp else 20.dp)) {
            val track = if (dragging) 6.dp.toPx() else 4.dp.toPx()
            val y = size.height / 2
            val radius = CornerRadius(track / 2, track / 2)
            drawRoundRect(Color.White.copy(alpha = 0.25f), topLeft = Offset(0f, y - track / 2), size = Size(size.width, track), cornerRadius = radius)
            drawRoundRect(Color.White.copy(alpha = 0.45f), topLeft = Offset(0f, y - track / 2), size = Size(size.width * buffered, track), cornerRadius = radius)
            drawRoundRect(OndoColors.Accent, topLeft = Offset(0f, y - track / 2), size = Size(size.width * fraction, track), cornerRadius = radius)
            drawCircle(OndoColors.Accent, radius = if (dragging) 10.dp.toPx() else 7.dp.toPx(), center = Offset(size.width * fraction, y))
        }
    }
}

/** A live channel without a window: LIVE, and the programme on now with its progress. */
@Composable
private fun PhoneLiveLine(info: PlayerInfo, now: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.background(LiveRed, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp).testTag("liveBadge"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).background(Color.White, CircleShape))
            Text(stringResource(R.string.live_badge), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        val start = info.programmeStart
        val end = info.programmeEnd
        if (start != null && end != null && end > start) {
            val format = DateFormat.getTimeFormat(LocalContext.current)
            Text("${format.format(Date(start))} – ${format.format(Date(end))}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
            Box(Modifier.weight(1f).height(4.dp).background(Color.White.copy(alpha = 0.25f), CircleShape)) {
                Box(Modifier.fillMaxWidth(((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)).height(4.dp).background(OndoColors.Accent, CircleShape))
            }
        }
    }
}

/** A channel played from the provider's archive, not live. */
@Composable
private fun ReplayBadge() {
    Text(
        stringResource(R.string.replay_badge),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.ExtraBold,
        color = Color.White,
        modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp).testTag("replayBadge"),
    )
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, size: androidx.compose.ui.unit.Dp, tag: String, filled: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (filled) Color.White.copy(alpha = 0.18f) else Color.Transparent,
        contentColor = Color.White,
        modifier = Modifier.size(size).testTag(tag).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.6f))
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
    }
}

/** After a double tap: its side of the screen lit, the direction and the seconds added up. */
@Composable
private fun SeekFeedback(direction: Int, seconds: Int, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxHeight().testTag("seekFeedback")) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(maxWidth.coerceAtMost(220.dp))
                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(percent = 50)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (direction < 0) Icons.Rounded.FastRewind else Icons.Rounded.FastForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                Text(stringResource(R.string.seconds_short, seconds), style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LevelIndicator(kind: LevelKind, level: Float, modifier: Modifier) {
    Pill(modifier.testTag(if (kind == LevelKind.BRIGHTNESS) "brightnessLevel" else "volumeLevel")) {
        Icon(if (kind == LevelKind.BRIGHTNESS) Icons.Rounded.BrightnessMedium else Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = stringResource(if (kind == LevelKind.BRIGHTNESS) R.string.brightness else R.string.volume))
        LinearProgressIndicator(progress = { level }, modifier = Modifier.width(120.dp), color = OndoColors.Accent, trackColor = Color.White.copy(alpha = 0.25f))
    }
}

@Composable
private fun Pill(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.background(Color.Black.copy(alpha = 0.65f), CircleShape).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) { content() }
    }
}

/** The list of a sheet: the same choices as the TV's panels. */
@Composable
private fun PanelContent(
    panel: PlayerPanel,
    player: Player,
    controls: PlayerControls,
    statisticsShown: Boolean,
    onToggleStatistics: () -> Unit,
    onReportProblem: () -> Unit,
    scale: VideoScale,
    onScale: (VideoScale) -> Unit,
    sleep: SleepTimer,
    channels: ChannelList?,
    subtitles: SubtitleSizing?,
) {
    val title = when (panel) {
        PlayerPanel.SUBTITLES -> R.string.subtitles
        PlayerPanel.AUDIO -> R.string.audio
        PlayerPanel.SPEED -> R.string.speed
        PlayerPanel.MORE -> R.string.more
        PlayerPanel.ASPECT -> R.string.aspect_ratio
        PlayerPanel.SUBTITLE_SIZE -> R.string.subtitle_size
        PlayerPanel.SLEEP -> R.string.sleep_timer
        PlayerPanel.CHANNELS -> R.string.channels
    }
    val words = trackWords()
    val locale = LocalConfiguration.current.locales[0]
    val type = if (panel == PlayerPanel.SUBTITLES) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
    val choices = if (panel == PlayerPanel.SUBTITLES || panel == PlayerPanel.AUDIO) TrackChoices.of(controls.tracks, type, words, locale) else emptyList()
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
        val channelChoices = remember(panel) { if (panel == PlayerPanel.CHANNELS) channels?.load().orEmpty() else emptyList() }
        // The channel playing in view, among the others.
        val list = remember(panel) { LazyListState(firstVisibleItemIndex = (channelChoices.indexOfFirst { it.playing } - 2).coerceAtLeast(0)) }
        LazyColumn(state = list) {
            when (panel) {
                PlayerPanel.SUBTITLES, PlayerPanel.AUDIO -> trackRows(player, controls, type, choices)
                PlayerPanel.SPEED -> speedRows(player, controls, locale)
                PlayerPanel.MORE -> moreRows(controls, scale, subtitles, sleep, statisticsShown, onToggleStatistics, onReportProblem)
                PlayerPanel.ASPECT -> scaleRows(controls, scale, onScale)
                PlayerPanel.SUBTITLE_SIZE -> subtitles?.let { sizeRows(controls, it) }
                PlayerPanel.SLEEP -> sleepRows(controls, sleep)
                PlayerPanel.CHANNELS -> channelRows(controls, channelChoices) { id -> channels?.onChoose?.invoke(id) }
            }
        }
    }
}

private fun LazyListScope.trackRows(player: Player, controls: PlayerControls, type: Int, choices: List<TrackChoice>) {
    val choose = { choice: TrackChoice? ->
        player.trackSelectionParameters = TrackChoices.select(player.trackSelectionParameters, type, choice)
        controls.closePanel()
    }
    if (type == C.TRACK_TYPE_TEXT) {
        item(key = "off") { SheetChoice(stringResource(R.string.off), null, selected = choices.none { it.selected }, tag = "trackOff") { choose(null) } }
    }
    choices.forEachIndexed { index, choice ->
        item(key = "track-$index") { SheetChoice(choice.name, choice.detail, selected = choice.selected, tag = "track-$index") { choose(choice) } }
    }
    if (choices.isEmpty()) {
        item(key = "none") {
            val hint = when {
                controls.tracks.isEmpty -> R.string.tracks_pending
                type == C.TRACK_TYPE_TEXT -> R.string.no_subtitles
                else -> R.string.no_audio
            }
            Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).testTag("tracksHint"))
        }
    }
}

private fun LazyListScope.speedRows(player: Player, controls: PlayerControls, locale: java.util.Locale) {
    val numbers = NumberFormat.getNumberInstance(locale)
    SPEEDS.forEach { speed ->
        item(key = "speed-$speed") {
            val label = if (speed == 1f) stringResource(R.string.speed_normal) else numbers.format(speed) + "×"
            SheetChoice(label, null, selected = controls.speed == speed, tag = "speed-$speed") {
                player.setPlaybackSpeed(speed)
                controls.closePanel()
            }
        }
    }
}

private fun LazyListScope.moreRows(
    controls: PlayerControls,
    scale: VideoScale,
    subtitles: SubtitleSizing?,
    sleep: SleepTimer,
    statisticsShown: Boolean,
    onToggleStatistics: () -> Unit,
    onReportProblem: () -> Unit,
) {
    item(key = "aspect") {
        SheetRow(Icons.Rounded.AspectRatio, stringResource(R.string.aspect_ratio), stringResource(scale.label), tag = "menuAspect") { controls.open(PlayerPanel.ASPECT) }
    }
    if (subtitles != null) {
        item(key = "subtitleSize") {
            SheetRow(Icons.Rounded.FormatSize, stringResource(R.string.subtitle_size), stringResource(subtitles.size.label), tag = "menuSubtitleSize") { controls.open(PlayerPanel.SUBTITLE_SIZE) }
        }
    }
    item(key = "sleep") {
        val value = sleep.endsAt?.let { stringResource(R.string.sleep_ends_at, DateFormat.getTimeFormat(LocalContext.current).format(Date(it))) } ?: stringResource(R.string.off)
        SheetRow(Icons.Rounded.Bedtime, stringResource(R.string.sleep_timer), value, tag = "menuSleep") { controls.open(PlayerPanel.SLEEP) }
    }
    item(key = "statistics") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.stream_statistics)) },
            leadingContent = { Icon(Icons.Rounded.QueryStats, contentDescription = null) },
            trailingContent = { Switch(checked = statisticsShown, onCheckedChange = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .testTag("statisticsToggle")
                .semantics { selected = statisticsShown }
                .clickableRow {
                    controls.closePanel()
                    onToggleStatistics()
                },
        )
    }
    item(key = "report") {
        SheetRow(Icons.Rounded.BugReport, stringResource(R.string.report_problem), null, tag = "reportProblem", chevron = false) {
            controls.closePanel()
            onReportProblem()
        }
    }
}

private fun LazyListScope.sizeRows(controls: PlayerControls, subtitles: SubtitleSizing) {
    SubtitleSize.entries.forEach { option ->
        item(key = "size-$option") {
            SheetChoice(stringResource(option.label), null, selected = option == subtitles.size, tag = "size-${option.name}") {
                subtitles.onChange(option)
                controls.closePanel()
            }
        }
    }
}

private fun LazyListScope.scaleRows(controls: PlayerControls, scale: VideoScale, onScale: (VideoScale) -> Unit) {
    VideoScale.entries.forEach { option ->
        item(key = "scale-$option") {
            SheetChoice(stringResource(option.label), null, selected = option == scale, tag = "scale-${option.name}") {
                onScale(option)
                controls.closePanel()
            }
        }
    }
}

private fun LazyListScope.sleepRows(controls: PlayerControls, sleep: SleepTimer) {
    SLEEP_CHOICES.forEach { minutes ->
        val tag = "sleep-${minutes ?: "off"}"
        item(key = tag) {
            val name = if (minutes == null) stringResource(R.string.off) else stringResource(R.string.sleep_minutes, minutes)
            SheetChoice(name, null, selected = sleep.minutes == minutes, tag = tag) {
                sleep.set(minutes)
                controls.closePanel()
            }
        }
    }
}

private fun LazyListScope.channelRows(controls: PlayerControls, choices: List<ChannelChoice>, onChoose: (String) -> Unit) {
    choices.forEach { channel ->
        item(key = "channel-${channel.id}") {
            SheetChoice(channel.label, channel.now, selected = channel.playing, tag = "channel-${channel.id}") {
                controls.closePanel()
                if (!channel.playing) onChoose(channel.id)
            }
        }
    }
}

/** A choice of a sheet, ticked when in use. */
@Composable
private fun SheetChoice(name: String, detail: String?, selected: Boolean, tag: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = detail?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        trailingContent = { if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = OndoColors.Accent) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag(tag).semantics { this.selected = selected }.clickableRow(onClick),
    )
}

/** A row of More that opens a list: what it sets, its value beneath. */
@Composable
private fun SheetRow(icon: ImageVector, title: String, value: String?, tag: String, chevron: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = value?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = if (chevron) ({ Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag(tag).clickableRow(onClick),
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

/** Within this of the end of a live window, the stream is live. */
private const val LIVE_EDGE_MS = 15_000L

/** A finger held this long plays at [BOOST_SPEED]. */
private const val BOOST_AFTER_MS = 500L
private const val BOOST_SPEED = 2f

/** How long the double tap's seconds stay on screen after the last tap. */
private const val SEEK_FEEDBACK_MS = 900L

/** How long the unlock button stays after a tap on a locked screen. */
private const val UNLOCK_SHOWN_MS = 3_000L

/** A slide over this share of the screen's height goes from no brightness (or volume) to full. */
private const val LEVEL_SPAN = 0.7f

/** Pinches beyond these fill the screen or fit the whole picture. */
private const val PINCH_OUT = 1.15f
private const val PINCH_IN = 0.87f

/** Slides starting this close to the top or bottom edge belong to the system. */
private val EDGE = 48.dp
