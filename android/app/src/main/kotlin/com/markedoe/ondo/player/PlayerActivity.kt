package com.markedoe.ondo.player

import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.markedoe.ondo.BuildConfig
import com.markedoe.ondo.OndoApplication
import com.markedoe.ondo.R
import com.markedoe.ondo.core.net.DEFAULT_IPTV_USER_AGENT
import com.markedoe.ondo.core.playback.PlaybackBuffer
import com.markedoe.ondo.core.playback.ProblemReport
import com.markedoe.ondo.core.playback.RebufferPolicy
import com.markedoe.ondo.ui.theme.OndoTheme
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Full-screen player (Media3 / ExoPlayer): HLS, MPEG-TS, MP4, MKV; fallback addresses, resume position,
 * Picture-in-Picture (entered when the user leaves while a video plays), media session (headset and remote
 * buttons). Progress is saved every 10 s. More, Report a Problem… shows a report of the playback ([PlaybackReporter]).
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private lateinit var request: PlayRequest
    private var urlIndex = 0
    private var errorMessage by mutableStateOf<String?>(null)
    private var shownReport by mutableStateOf<String?>(null)
    private lateinit var reporter: PlaybackReporter
    private var inPictureInPicture by mutableStateOf(false)
    private var playerView: PlayerView? = null
    private val supportsPictureInPicture by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = requireNotNull(intent.playRequest()) { "PlayerActivity needs a play request" }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        reporter = PlaybackReporter(request, SystemClock::elapsedRealtime)
        val exoPlayer = buildPlayer()
        player = exoPlayer
        // Prepared first: the media session starts from a player that is already loading.
        play(urlIndex, request.startPositionMs)
        session = MediaSession.Builder(this, exoPlayer).build()

        setContent {
            OndoTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { context ->
                            PlayerView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                player = exoPlayer
                                setShowSubtitleButton(true)
                                setShowNextButton(false)
                                setShowPreviousButton(false)
                                // The spinner while the stream starts, after a seek and when it waits for data.
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                keepScreenOn = true
                                // The video's place on screen, for the animation into Picture-in-Picture.
                                videoSurfaceView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePictureInPictureParams() }
                            }.also { playerView = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // The small Picture-in-Picture window shows the video only: the system draws its own controls.
                    if (!inPictureInPicture) {
                        IconButton(
                            onClick = { finish() },
                            modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                        }
                        Box(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
                            var menu by remember { mutableStateOf(false) }
                            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("playerMore")) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more), tint = Color.White)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.report_problem)) },
                                    onClick = {
                                        menu = false
                                        shownReport = problemReport()
                                    },
                                )
                            }
                        }
                    }
                    shownReport?.takeIf { !inPictureInPicture }?.let { report ->
                        ProblemReportDialog(
                            report = report,
                            onDismiss = { shownReport = null },
                            onCopy = { copyReport(report) },
                            onShare = { shareReport(report) },
                            onOpenIssue = { startOrIgnore(Intent(Intent.ACTION_VIEW, ProblemReport.issueUrl(report).toUri())) },
                        )
                    }
                    errorMessage?.takeIf { !inPictureInPicture }?.let { message ->
                        Column(
                            modifier = Modifier.align(Alignment.Center).padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(message, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            Button(onClick = { retry() }, modifier = Modifier.padding(top = 16.dp)) {
                                Text(stringResource(R.string.try_again))
                            }
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                saveProgress()
            }
        }
    }

    private fun buildPlayer(): ExoPlayer {
        // Streams go out with the same player identity as the catalogue (providers filter on it), unless
        // the playlist sets its own.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_IPTV_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(request.headers)
        request.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.let { http.setUserAgent(it.value) }
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setLoadControl(loadControl())
            // A key frame within 1 s of the target, else the exact position: providers often space key frames
            // 10 s apart, and the closest one can land 5 to 10 s away from a 10 s skip.
            .setSeekParameters(SeekParameters(1_000_000, 1_000_000))
            // Audio focus: pause for a call, lower the volume under a navigation prompt, stop when another
            // app starts playing.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                val exoPlayer = this
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val messages = generateSequence<Throwable>(error) { it.cause }.take(4).mapNotNull { it.message }.distinct().toList()
                        reporter.error(error.errorCodeName, messages)
                        handleError(error)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        reporter.isPlaying(isPlaying)
                        updatePictureInPictureParams()
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = reporter.playWhenReady(playWhenReady)

                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) reporter.seek(oldPosition.positionMs, newPosition.positionMs)
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        reporter.playbackState(state, exoPlayer.playWhenReady)
                        // The end of a movie or an episode: it counts as watched, back to the app.
                        if (state == Player.STATE_ENDED && !request.isLive) {
                            saveProgress(ended = true)
                            finish()
                        }
                    }
                })
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) = reporter.firstFrame()

                    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) =
                        reporter.droppedFrames(droppedFrames, elapsedMs)

                    override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) =
                        reporter.bandwidth(bitrateEstimate)
                })
                playWhenReady = true
            }
    }

    /**
     * The buffer chosen in Settings. Automatic keeps Media3's own durations, and waits longer before playing again
     * once the provider has stopped twice ([AdaptiveLoadControl]).
     */
    private fun loadControl(): LoadControl {
        PlaybackBuffer.durations(request.bufferSeconds)?.let { buffer ->
            return DefaultLoadControl.Builder()
                .setBufferDurationsMs(buffer.minBufferMs, buffer.maxBufferMs, buffer.bufferForPlaybackMs, buffer.bufferForPlaybackAfterRebufferMs)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }
        val raisedMs = if (request.isLive) PlaybackBuffer.RAISED_LIVE_MS else PlaybackBuffer.RAISED_VOD_MS
        val main = Handler(Looper.getMainLooper())
        return AdaptiveLoadControl(DefaultLoadControl(), RebufferPolicy(raisedMs * 1_000L)) {
            // From the player's thread: the reporter lives on the main one.
            main.post { reporter.note("second rebuffering: waits ${raisedMs / 1_000} s of video before playing again") }
        }
    }

    private fun play(index: Int, startMs: Long) {
        val url = request.urls.getOrNull(index) ?: return
        reporter.attempt(index, url)
        val item = MediaItem.Builder()
            .setUri(url)
            .apply { if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(request.title)
                    .setSubtitle(request.subtitle)
                    .setArtworkUri(request.artworkUrl?.let(android.net.Uri::parse))
                    .build(),
            )
            .build()
        player?.apply {
            if (request.isLive || startMs <= 0) setMediaItem(item) else setMediaItem(item, startMs)
            prepare()
        }
    }

    /** Another title while the player is open (or in Picture-in-Picture). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val next = intent.playRequest() ?: return
        saveProgress()
        setIntent(intent)
        request = next
        urlIndex = 0
        errorMessage = null
        shownReport = null
        player?.release()
        session?.release()
        reporter = PlaybackReporter(request, SystemClock::elapsedRealtime)
        val exoPlayer = buildPlayer()
        player = exoPlayer
        playerView?.player = exoPlayer
        play(0, request.startPositionMs)
        session = MediaSession.Builder(this, exoPlayer).build()
    }

    private fun retry() {
        errorMessage = null
        reporter.retry()
        urlIndex = 0
        play(0, if (request.isLive) 0 else player?.currentPosition ?: 0)
    }

    private fun handleError(error: PlaybackException) {
        // A live stream fell behind its window: back to the live edge.
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            reporter.note("behind the live window: back to the live edge")
            player?.apply {
                seekToDefaultPosition()
                prepare()
            }
            return
        }
        // Another address (format or version of the channel) before giving up.
        if (urlIndex + 1 < request.urls.size) {
            reporter.note("fallback to the next address")
            urlIndex += 1
            play(urlIndex, player?.currentPosition ?: 0)
            return
        }
        val (message, cause) = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> R.string.error_provider_refused to "provider refused"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> R.string.error_network to "network"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> R.string.error_format to "format"
            else -> R.string.error_generic to "other"
        }
        reporter.note("no address left: error shown ($cause)")
        errorMessage = getString(message)
    }

    /** The report of this playback, as it stands now. */
    private fun problemReport(): String {
        val exoPlayer = player
        val snapshot = PlaybackReporter.PlayerSnapshot(
            app = "Ondo ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            system = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
            engine = "Media3 ${MediaLibraryInfo.VERSION}",
            bufferInUse = PlaybackBuffer.durations(request.bufferSeconds) ?: MEDIA3_BUFFER,
            video = exoPlayer?.videoSize?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" },
            durationMs = exoPlayer?.duration?.takeIf { it != C.TIME_UNSET },
            positionMs = exoPlayer?.currentPosition,
            state = describeState(exoPlayer),
            statistics = exoPlayer?.let(::statistics).orEmpty(),
        )
        return reporter.report(snapshot)
    }

    private fun describeState(exoPlayer: ExoPlayer?): String {
        exoPlayer ?: return "no player"
        exoPlayer.playerError?.let { return "failed: ${it.errorCodeName}" }
        return when (exoPlayer.playbackState) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> if (exoPlayer.playWhenReady) "buffering" else "buffering, paused"
            Player.STATE_ENDED -> "ended"
            else -> if (exoPlayer.isPlaying) "playing" else "paused"
        }
    }

    /** The formats played, the decoder's frame counts and what is buffered ahead. */
    private fun statistics(exoPlayer: ExoPlayer): List<String> = listOfNotNull(
        exoPlayer.videoFormat?.let { format ->
            "Video: " + listOfNotNull(
                format.sampleMimeType,
                format.codecs,
                format.frameRate.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.2f fps", it) },
                format.bitrate.takeIf { it > 0 }?.let { PlaybackReporter.megabits(it.toLong()) },
            ).joinToString(", ")
        },
        exoPlayer.audioFormat?.let { format ->
            "Audio: " + listOfNotNull(
                format.sampleMimeType,
                format.codecs,
                format.channelCount.takeIf { it > 0 }?.let { "$it channels" },
                format.sampleRate.takeIf { it > 0 }?.let { "$it Hz" },
            ).joinToString(", ")
        },
        exoPlayer.videoDecoderCounters?.let { counters ->
            counters.ensureUpdated()
            "Video decoder: ${counters.renderedOutputBufferCount} frames shown, ${counters.droppedBufferCount} dropped, ${counters.skippedOutputBufferCount} skipped"
        },
        "Buffered ahead: ${PlaybackReporter.seconds(exoPlayer.totalBufferedDuration)} s",
    )

    private fun copyReport(report: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(getString(R.string.problem_report), report))
    }

    private fun shareReport(report: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.playback_report_subject))
            .putExtra(Intent.EXTRA_TEXT, report)
        startOrIgnore(Intent.createChooser(send, null))
    }

    private fun startOrIgnore(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Nothing on the device can open it: Copy Report still works.
        }
    }

    private fun saveProgress(ended: Boolean = false) {
        val exoPlayer = player ?: return
        val key = request.progressKey ?: return
        if (request.isLive) return
        val duration = exoPlayer.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val position = if (ended) duration else exoPlayer.currentPosition
        (application as OndoApplication).repository.recordProgress(key, position / 1000.0, duration / 1000.0)
    }

    /**
     * Picture-in-Picture settings, kept up to date: the video's bounds (the window shrinks from them) and its
     * shape. From Android 12 the system enters Picture-in-Picture by itself when the user leaves while a
     * video plays (auto-enter); before, [onUserLeaveHint] does it.
     */
    private fun pictureInPictureParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        val bounds = Rect()
        if (playerView?.videoSurfaceView?.getGlobalVisibleRect(bounds) == true && !bounds.isEmpty) {
            // The system refuses shapes wider than 2.39:1 or taller than 1:2.39.
            val aspectRatio = when {
                bounds.width() > bounds.height() * 2.38f -> Rational(238, 100)
                bounds.height() > bounds.width() * 2.38f -> Rational(100, 238)
                else -> Rational(bounds.width(), bounds.height())
            }
            builder.setAspectRatio(aspectRatio).setSourceRectHint(bounds)
        } else {
            builder.setAspectRatio(Rational(16, 9))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(player?.isPlaying == true)
        return builder.build()
    }

    private fun updatePictureInPictureParams() {
        if (supportsPictureInPicture) runCatching { setPictureInPictureParams(pictureInPictureParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && supportsPictureInPicture && player?.isPlaying == true) {
            runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        playerView?.useController = !isInPictureInPictureMode
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            // The PiP window was closed (not expanded): the player goes away with it instead of staying allocated.
            saveProgress()
            player?.pause()
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        if (!isInPictureInPictureMode) player?.pause()
    }

    override fun onDestroy() {
        saveProgress()
        playerView?.player = null
        playerView = null
        session?.release()
        player?.release()
        session = null
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REQUEST = "request"

        /** The durations Media3 uses on its own (automatic), for the report. */
        private val MEDIA3_BUFFER = PlaybackBuffer.Durations(
            minBufferMs = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            maxBufferMs = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            bufferForPlaybackMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            bufferForPlaybackAfterRebufferMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )

        fun intent(context: Context, request: PlayRequest): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_REQUEST, Json.encodeToString(PlayRequest.serializer(), request))

        private fun Intent.playRequest(): PlayRequest? =
            getStringExtra(EXTRA_REQUEST)?.let { runCatching { Json.decodeFromString(PlayRequest.serializer(), it) }.getOrNull() }
    }
}
