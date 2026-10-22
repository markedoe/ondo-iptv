package com.markedoe.ondo.player

import android.annotation.SuppressLint
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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DecoderCounters
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
import com.markedoe.ondo.core.library.ChannelZapping
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.net.DEFAULT_IPTV_USER_AGENT
import com.markedoe.ondo.core.playback.MediaFormat
import com.markedoe.ondo.core.playback.PlaybackBuffer
import com.markedoe.ondo.core.playback.PlaybackHealth
import com.markedoe.ondo.core.playback.PlaybackSample
import com.markedoe.ondo.core.playback.ProblemReport
import com.markedoe.ondo.core.playback.RebufferPolicy
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TvSafeArea
import com.markedoe.ondo.ui.tv.isTelevision
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Full-screen player (Media3 / ExoPlayer): HLS, MPEG-TS, MP4, MKV; fallback addresses, resume position,
 * Picture-in-Picture (entered when the user leaves while a video plays), media session (headset and remote
 * buttons). Progress is saved every 10 s. More, Stream Statistics shows what arrives and the freezes, explained
 * ([PlaybackHealth], sampled twice a second); More, Report a Problem… shows a report of the playback ([PlaybackReporter]).
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
    /**
     * No Picture-in-Picture on a TV: Google's TV app quality wants it entered only by an explicit action (TV-IE), and
     * the remote's Home is not one.
     */
    private val supportsPictureInPicture by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) && !resources.configuration.isTelevision
    }

    /** A TV: no Picture-in-Picture, the overlays inside the safe area and shown with the controls only. */
    private val television by lazy { resources.configuration.isTelevision }

    /** The player's controls are on screen (the remote's arrows then move through them). */
    private var controlsShown by mutableStateOf(false)

    /** The channel just zapped to, shown a moment at the top. */
    private var banner by mutableStateOf<ChannelBanner?>(null)
    private var bannerJob: Job? = null

    /** A channel number being typed on the remote. */
    private val numberEntry = ChannelNumberEntry()
    private var typedNumber by mutableStateOf("")
    private var numberJob: Job? = null

    /** The channel watched before this one (the remote's "last channel" key). */
    private var lastChannel: PlayRequest? = null

    /** Keys the player handled when pressed: their release is consumed too. */
    private val handledKeys = HashSet<Int>()

    /** On a TV, Back hides the controls first (the system's back: the key itself no longer reaches the app). */
    private val hideControlsOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            playerView?.hideController()
        }
    }

    /** Freezes and lost pictures of this playback, explained; shown by More, Stream Statistics. */
    private var health = PlaybackHealth()
    private var healthStart = SystemClock.elapsedRealtime()
    private var showsStatistics by mutableStateOf(false)
    private var statistics by mutableStateOf<StreamStatistics?>(null)

    /** Bytes the player received from the network (counted on its loading threads). */
    private var receivedBytes = AtomicLong()

    /** Pictures shown and lost, kept across the video decoders the player creates (one per address or track). */
    private var decoder: DecoderCounters? = null
    private var shownBefore = 0L
    private var lostBefore = 0L
    private val period = Timeline.Period()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = requireNotNull(intent.playRequest()) { "PlayerActivity needs a play request" }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        reporter = PlaybackReporter(request, SystemClock::elapsedRealtime)
        // The channel list for the remote's channel keys, when the player was opened again alone (after Android
        // stopped the app in the background).
        repository.bootstrap()
        onBackPressedDispatcher.addCallback(this, hideControlsOnBack)
        val exoPlayer = buildPlayer()
        player = exoPlayer
        // Prepared first: the media session starts from a player that is already loading.
        play(urlIndex, request.startPositionMs)
        session = MediaSession.Builder(this, exoPlayer).build()

        setContent {
            val safeArea = if (television) Modifier.padding(TvSafeArea) else Modifier
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
                                // The screen stays on while the video plays only: paused, it may dim and a TV may
                                // show its screen saver (TV-BU, TV-BY).
                                keepScreenOn = exoPlayer.isPlaying
                                setControllerVisibilityListener(
                                    PlayerView.ControllerVisibilityListener {
                                        controlsShown = it == View.VISIBLE
                                        hideControlsOnBack.isEnabled = television && controlsShown
                                    },
                                )
                                if (television) {
                                    // The time bar and its times out of the 5 % a TV may crop (TV-OV).
                                    val (horizontal, vertical) = (48 * resources.displayMetrics.density).toInt() to (27 * resources.displayMetrics.density).toInt()
                                    findViewById<View>(androidx.media3.ui.R.id.exo_controller)?.setPadding(horizontal, vertical, horizontal, vertical)
                                }
                                // The video's place on screen, for the animation into Picture-in-Picture.
                                videoSurfaceView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePictureInPictureParams() }
                            }.also { playerView = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    statistics?.takeIf { showsStatistics && !inPictureInPicture }?.let {
                        StreamStatisticsPanel(
                            it,
                            Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).then(safeArea).padding(start = 16.dp, top = 64.dp, end = 16.dp),
                        )
                    }
                    // The small Picture-in-Picture window shows the video only: the system draws its own controls.
                    // On a TV, Close and More come and go with the controls (Back closes, the remote reaches More).
                    if (!inPictureInPicture && (!television || controlsShown)) {
                        IconButton(
                            onClick = { finish() },
                            modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).then(safeArea).padding(8.dp),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                        }
                        Box(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).then(safeArea).padding(8.dp)) {
                            var menu by remember { mutableStateOf(false) }
                            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("playerMore")) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more), tint = Color.White)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.stream_statistics)) },
                                    onClick = {
                                        menu = false
                                        showsStatistics = !showsStatistics
                                        statistics = if (showsStatistics) currentStatistics() else null
                                    },
                                    trailingIcon = { if (showsStatistics) Icon(Icons.Rounded.Check, contentDescription = null) },
                                    modifier = Modifier.testTag("statisticsToggle").semantics { selected = showsStatistics },
                                )
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
                        // Share when an app can take the text; the GitHub issue opens in a browser, which a TV
                        // does not open (TV-WB).
                        ProblemReportDialog(
                            report = report,
                            onDismiss = { shownReport = null },
                            onCopy = { copyReport(report) },
                            onShare = if (canShare) ({ shareReport(report) }) else null,
                            onOpenIssue = if (resources.configuration.isTelevision) null else ({ startOrIgnore(Intent(Intent.ACTION_VIEW, ProblemReport.issueUrl(report).toUri())) }),
                        )
                    }
                    errorMessage?.takeIf { !inPictureInPicture }?.let { message ->
                        // With a remote, Try Again has the focus: OK retries.
                        val retryFocus = remember { FocusRequester() }
                        LaunchedEffect(message) { runCatching { retryFocus.requestFocus() } }
                        Column(
                            modifier = Modifier.align(Alignment.Center).padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(message, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            Button(onClick = { retry() }, modifier = Modifier.padding(top = 16.dp).focusRequester(retryFocus)) {
                                Text(stringResource(R.string.try_again))
                            }
                        }
                    }
                    banner?.takeIf { !inPictureInPicture }?.let {
                        ChannelBannerView(it, Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).then(safeArea).padding(16.dp))
                    }
                    typedNumber.takeIf { it.isNotEmpty() && !inPictureInPicture }?.let {
                        Text(
                            it,
                            color = Color.White,
                            style = MaterialTheme.typography.displaySmall,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .windowInsetsPadding(WindowInsets.safeDrawing)
                                .then(safeArea)
                                .padding(16.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("typedNumber"),
                        )
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
        lifecycleScope.launch {
            while (isActive) {
                delay(500)
                sampleHealth()
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
            .setTransferListener(ByteCounter(AtomicLong().also { receivedBytes = it }))
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
                        playerView?.keepScreenOn = isPlaying
                        updatePictureInPictureParams()
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = reporter.playWhenReady(playWhenReady)

                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            reporter.seek(oldPosition.positionMs, newPosition.positionMs)
                            // The wait until the picture moves again is not a freeze.
                            health.restart()
                        }
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
        health.restart()
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
        setIntent(intent)
        switchTo(next)
    }

    /** Plays [next] instead of what plays now (the progress of the current one is saved first). */
    private fun switchTo(next: PlayRequest) {
        saveProgress()
        request = next
        urlIndex = 0
        errorMessage = null
        shownReport = null
        player?.release()
        session?.release()
        reporter = PlaybackReporter(request, SystemClock::elapsedRealtime)
        resetHealth()
        val exoPlayer = buildPlayer()
        player = exoPlayer
        playerView?.player = exoPlayer
        play(0, request.startPositionMs)
        session = MediaSession.Builder(this, exoPlayer).build()
    }

    /**
     * Every key reaches the remote's handling first, before the focused control. Activity.dispatchKeyEvent is public
     * Android API; AndroidX's ComponentActivity only marks its own override as internal to its libraries.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = remoteKey(event) || super.dispatchKeyEvent(event)

    /** A key of the remote or a keyboard ([RemoteKeys]); false when it does what it does by default. */
    private fun remoteKey(event: KeyEvent): Boolean {
        if (inPictureInPicture) return false
        if (event.action == KeyEvent.ACTION_UP) return handledKeys.remove(event.keyCode)
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val view = playerView ?: return false
        val exoPlayer = player ?: return false
        val zapping = request.isLive && request.channelId != null
        val action = RemoteKeys.action(event.keyCode, controlsShown, zapping, exoPlayer.isCurrentMediaItemSeekable) ?: return false
        // A stream that failed: OK presses Try Again; the channel keys still lead to another channel.
        val changesChannel = action is RemoteAction.Digit || action == RemoteAction.NextChannel ||
            action == RemoteAction.PreviousChannel || action == RemoteAction.LastChannel
        if (errorMessage != null && !changesChannel) return false
        when (action) {
            RemoteAction.TogglePlay -> {
                Util.handlePlayPauseButtonAction(exoPlayer)
                view.showController()
            }
            RemoteAction.Play -> Util.handlePlayButtonAction(exoPlayer)
            RemoteAction.Pause -> Util.handlePauseButtonAction(exoPlayer)
            RemoteAction.SeekBack -> {
                exoPlayer.seekBack()
                view.showController()
            }
            RemoteAction.SeekForward -> {
                exoPlayer.seekForward()
                view.showController()
            }
            RemoteAction.NextChannel -> zap(1)
            RemoteAction.PreviousChannel -> zap(-1)
            RemoteAction.LastChannel -> backToLastChannel()
            RemoteAction.ShowControls -> view.showController()
            RemoteAction.HideControls -> view.hideController()
            is RemoteAction.Digit -> typeDigit(action.value)
        }
        handledKeys += event.keyCode
        return true
    }

    private val repository get() = (application as OndoApplication).repository

    /** The channel [by] places after (or before) this one in Live TV. */
    private fun zap(by: Int) {
        val from = request.channelId ?: return
        ChannelZapping.step(UiMappers.zappingChannels(repository.state.value), from, by)?.let(::playChannel)
    }

    /** The channel of a number typed on the remote, or a word that there is none. */
    private fun zapTo(number: Int) {
        val channel = ChannelZapping.byNumber(UiMappers.zappingChannels(repository.state.value), number)
        if (channel == null) showBanner(ChannelBanner(getString(R.string.no_channel_number, number), null)) else playChannel(channel)
    }

    private fun playChannel(channel: Channel) {
        if (channel.id == request.channelId) return
        val next = repository.playRequest(channel) ?: return
        lastChannel = request
        switchTo(next)
        val now = repository.state.value.guide?.current(channel, System.currentTimeMillis())?.title
        showBanner(ChannelBanner(listOfNotNull(channel.number?.toString(), channel.name).joinToString("  "), now))
    }

    private fun backToLastChannel() {
        val previous = lastChannel ?: return
        lastChannel = request
        switchTo(previous)
        showBanner(ChannelBanner(previous.title, null))
    }

    private fun typeDigit(digit: Int) {
        val complete = numberEntry.add(digit)
        typedNumber = numberEntry.typed
        numberJob?.cancel()
        if (complete != null) {
            zapTo(complete)
            return
        }
        numberJob = lifecycleScope.launch {
            delay(ChannelNumberEntry.WAIT_MS)
            typedNumber = ""
            numberEntry.finish()?.let(::zapTo)
        }
    }

    private fun showBanner(value: ChannelBanner) {
        banner = value
        bannerJob?.cancel()
        bannerJob = lifecycleScope.launch {
            delay(4_000)
            banner = null
        }
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

    /** A new title: its freezes and counters start from nothing. */
    private fun resetHealth() {
        health = PlaybackHealth()
        healthStart = SystemClock.elapsedRealtime()
        decoder = null
        shownBefore = 0
        lostBefore = 0
        if (showsStatistics) statistics = currentStatistics()
    }

    /** Twice a second: what arrived, where the picture is, the pictures shown and lost. */
    private fun sampleHealth() {
        val exoPlayer = player ?: return
        val playing = exoPlayer.playWhenReady && exoPlayer.playerError == null &&
            exoPlayer.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            (exoPlayer.playbackState == Player.STATE_READY || exoPlayer.playbackState == Player.STATE_BUFFERING)
        val (shown, lost) = pictureCounts(exoPlayer)
        health.add(
            PlaybackSample(
                clock = (SystemClock.elapsedRealtime() - healthStart) / 1_000.0,
                mediaTime = if (playing) mediaSeconds(exoPlayer) else null,
                receivedBytes = receivedBytes.get(),
                displayedPictures = shown,
                lostPictures = lost,
                isPlaying = playing,
            ),
        )
        if (showsStatistics) statistics = currentStatistics()
    }

    /**
     * The position in the stream itself, in seconds: a live channel's position in its window steps back each time
     * the window moves on, the stream's own position does not.
     */
    private fun mediaSeconds(exoPlayer: ExoPlayer): Double {
        val timeline = exoPlayer.currentTimeline
        val periodStartMs = if (timeline.isEmpty) 0 else timeline.getPeriod(exoPlayer.currentPeriodIndex, period).positionInWindowMs
        return (exoPlayer.currentPosition - periodStartMs) / 1_000.0
    }

    /** Pictures shown and lost since the title opened, across the decoders the player made meanwhile. */
    private fun pictureCounts(exoPlayer: ExoPlayer): Pair<Long, Long> {
        val counters = exoPlayer.videoDecoderCounters
        if (counters != null && counters !== decoder) {
            decoder?.let { previous ->
                previous.ensureUpdated()
                shownBefore += previous.renderedOutputBufferCount
                lostBefore += previous.droppedBufferCount
            }
            decoder = counters
        }
        val current = decoder ?: return shownBefore to lostBefore
        current.ensureUpdated()
        return shownBefore + current.renderedOutputBufferCount to lostBefore + current.droppedBufferCount
    }

    private fun currentStatistics(): StreamStatistics = StreamStatistics.of(
        health,
        engine = "ExoPlayer",
        format = request.urls.getOrNull(urlIndex)?.let(MediaFormat::label),
        video = player?.videoSize?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" },
        locale = resources.configuration.locales[0],
    )

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

    /** An app on the device can take a shared text (not always so on a TV). */
    private val canShare by lazy { Intent(Intent.ACTION_SEND).setType("text/plain").resolveActivity(packageManager) != null }

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

    /** Counts the bytes received from the network, on the player's loading threads. */
    private class ByteCounter(private val bytes: AtomicLong) : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) bytes.addAndGet(bytesTransferred.toLong())
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
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
