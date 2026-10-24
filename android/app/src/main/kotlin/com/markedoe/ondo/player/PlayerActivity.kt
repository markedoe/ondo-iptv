package com.markedoe.ondo.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.markedoe.ondo.BuildConfig
import com.markedoe.ondo.OndoApplication
import com.markedoe.ondo.R
import com.markedoe.ondo.core.library.ChannelZapping
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.net.DEFAULT_IPTV_USER_AGENT
import com.markedoe.ondo.core.playback.MediaFormat
import com.markedoe.ondo.core.playback.PlaybackBuffer
import com.markedoe.ondo.core.playback.PlaybackHealth
import com.markedoe.ondo.core.playback.PlaybackSample
import com.markedoe.ondo.core.playback.ProblemReport
import com.markedoe.ondo.data.VideoScale
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.OndoTvTheme
import com.markedoe.ondo.ui.tv.TvSafeArea
import com.markedoe.ondo.ui.tv.isTelevision
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Full-screen player (Media3 / ExoPlayer): HLS, MPEG-TS, MP4, MKV; fallback addresses, resume position,
 * Picture-in-Picture (entered when the user leaves while a video plays), media session (headset and remote
 * buttons). Progress is saved every 10 s. More, Stream Statistics shows what arrives and the freezes, explained
 * ([PlaybackHealth], sampled twice a second); More, Report a Problem… shows a report of the playback ([PlaybackReporter]).
 * The controls are the player's own ([PlayerControls]): drawn for the remote on a TV ([TvPlayerOverlay]), for touch on
 * a phone or a tablet ([PhonePlayerOverlay], with gestures); Media3's view only shows the video, the subtitles and the
 * spinner.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    /** The player of what plays now (a new one for each channel): the controls follow it. */
    private var player: ExoPlayer? by mutableStateOf(null)
    private var session: MediaSession? = null
    private val requestState = mutableStateOf<PlayRequest?>(null)
    private var request: PlayRequest
        get() = requireNotNull(requestState.value)
        set(value) {
            requestState.value = value
        }
    private var urlIndex = 0
    private var errorMessage by mutableStateOf<String?>(null)
    private val liveRecovery = LiveRecovery()
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

    /**
     * A TV: no Picture-in-Picture, the overlays inside the safe area, Back to close, and the player's own controls drawn
     * for the remote ([TvPlayerOverlay]) instead of Media3's.
     */
    private val television by lazy { resources.configuration.isTelevision }

    /** The player's own controls, on a TV and on a phone: shown, their panel, what the player does. */
    private val controls: PlayerControls by lazy {
        PlayerControls(lifecycleScope) { shown ->
            // On a TV, Back hides the controls before it leaves; on a phone it leaves.
            hideControlsOnBack.isEnabled = shown && television
            // The subtitles rise above the controls while they show.
            playerView?.subtitleView?.setBottomPaddingFraction(if (shown) RAISED_SUBTITLES else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        }.apply { playing = { player?.playWhenReady == true } }
    }

    /** The player's controls are on screen (the remote's arrows then move through them). */
    private val controlsShown: Boolean get() = controls.shown

    /** The channel just zapped to, shown a moment at the top. */
    private var banner by mutableStateOf<ChannelBanner?>(null)
    private var bannerJob: Job? = null

    /** A channel number being typed on the remote. */
    private val numberEntry = ChannelNumberEntry()
    private var typedNumber by mutableStateOf("")
    private var numberJob: Job? = null

    /** The channel watched before this one (the remote's "last channel" key). */
    private var lastChannel: PlayRequest? = null

    /** When the viewer paused a live channel ([LivePause]). */
    private var livePausedAt: Long? = null

    /** Keys the player handled when pressed: their release is consumed too. */
    private val handledKeys = HashSet<Int>()

    /**
     * On a TV, Back closes a panel, then hides the controls, before it leaves (the system's back: the key itself no longer
     * reaches the app). Enabled while the controls show.
     */
    private val hideControlsOnBack: OnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (controls.back()) return
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

    /** Freezes and lost pictures of this playback, explained; shown by More, Stream Statistics. */
    private var health = PlaybackHealth()
    private var healthStart = SystemClock.elapsedRealtime()
    private var showsStatistics by mutableStateOf(false)
    private var statistics by mutableStateOf<StreamStatistics?>(null)


    /** The episode after this one (a series), looked up when it starts: null for a movie, a channel or the last one. */
    private var nextEpisode: Pair<Series, Episode>? = null

    /** Up next is on screen, near the end of an episode; null otherwise, and once the viewer declined it. */
    private var upNext by mutableStateOf<UpNext?>(null)
    private var upNextDeclined = false

    /** On a TV, Back while Up next shows declines it (the remote's Back no longer reaches the keys). */
    private val declineUpNextOnBack: OnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = declineUpNext()
    }

    /** The aspect ratio last chosen (the player's More), kept in the settings for the next videos. */
    private val videoScale: StateFlow<VideoScale> by lazy {
        repository.state.map { it.settings.videoScale }.stateIn(lifecycleScope, SharingStarted.Eagerly, repository.state.value.settings.videoScale)
    }

    /** Pauses the video when its time is up (the player's More); it keeps running across the channels zapped to. */
    private val sleepTimer by lazy {
        SleepTimer(lifecycleScope) {
            player?.pause()
            showControls()
        }
    }

    /** Bytes the player received from the network (counted on its loading threads). */
    private var receivedBytes = AtomicLong()

    /** Pictures shown and lost, kept across the video decoders the player creates (one per address or track). */
    private var decoder: DecoderCounters? = null
    private var shownBefore = 0L
    private var lostBefore = 0L
    private val period = Timeline.Period()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated (Android stopped the app in the background, or a setting changed): what played, from where it was.
        request = savedInstanceState?.getString(EXTRA_REQUEST)?.let(::decodeRequest)
            ?: requireNotNull(intent.playRequest()) { "PlayerActivity needs a play request" }
        // Edge to edge on every Android version (the default from Android 15), light bars over the black video.
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // A phone turns sideways for the video, either way round; a tablet stays as it is held.
        if (!television && resources.configuration.smallestScreenWidthDp < TABLET_WIDTH_DP) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        reporter = PlaybackReporter(request, SystemClock::elapsedRealtime)
        // The channel list for the remote's channel keys, when the player was opened again alone (after Android
        // stopped the app in the background).
        repository.bootstrap()
        onBackPressedDispatcher.addCallback(this, hideControlsOnBack)
        onBackPressedDispatcher.addCallback(this, declineUpNextOnBack)
        nextEpisode = repository.nextEpisode(request)
        val exoPlayer = buildPlayer()
        player = exoPlayer
        // Opened again alone, before the settings were read: the viewer's languages as soon as they are.
        if (!repository.state.value.isReady) lifecycleScope.launch {
            repository.state.first { it.isReady }
            player?.let { it.trackSelectionParameters = TrackPreferences.parameters(it.trackSelectionParameters, trackLanguages(), deviceLanguages()) }
        }
        // Prepared first: the media session starts from a player that is already loading.
        play(urlIndex, request.startPositionMs)
        openSession(exoPlayer)

        setContent {
            val safeArea = if (television) Modifier.padding(TvSafeArea) else Modifier
            val scale by videoScale.collectAsState()
            OndoTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { context ->
                            PlayerView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                player = exoPlayer
                                // The spinner while the stream starts, after a seek and when it waits for data.
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                // The screen stays on while the video plays only: paused, it may dim and a TV may
                                // show its screen saver (TV-BU, TV-BY).
                                keepScreenOn = exoPlayer.isPlaying
                                // The player draws its own controls over this view (TvPlayerOverlay, PhonePlayerOverlay):
                                // Media3's view keeps drawing the picture, the subtitles and the spinner.
                                useController = false
                                // The video's place on screen, for the animation into Picture-in-Picture.
                                videoSurfaceView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePictureInPictureParams() }
                            }.also { playerView = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { view -> view.resizeMode = scale.resizeMode },
                    )
                    // On a TV, the controls drawn for the remote; none over an error, where Try Again has the focus.
                    val shownPlayer = player
                    // The programme on now, looked up again each minute.
                    val minute by remember { derivedStateOf { controls.clockMs / 60_000 } }
                    if (television && !inPictureInPicture && shownPlayer != null && errorMessage == null) {
                        OndoTvTheme {
                            TvPlayerOverlay(
                                player = shownPlayer,
                                controls = controls,
                                // Looked up once a minute and for each channel, not on each frame of the controls.
                                info = remember(request, minute) { playerInfo(minute * 60_000) },
                                statisticsShown = showsStatistics,
                                onToggleStatistics = ::toggleStatistics,
                                onReportProblem = { shownReport = problemReport() },
                                scale = scale,
                                onScale = repository::setVideoScale,
                                sleep = sleepTimer,
                                channels = channelList.takeIf { request.channelId != null },
                                onRestart = ::restartProgramme,
                                onWatchLive = ::watchLive,
                            )
                        }
                    }
                    statistics?.takeIf { showsStatistics && !inPictureInPicture }?.let {
                        StreamStatisticsPanel(
                            it,
                            // On a TV, below the title of the controls.
                            Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).then(safeArea).padding(start = 16.dp, top = if (television) 96.dp else 64.dp, end = 16.dp),
                        )
                    }
                    // The small Picture-in-Picture window shows the video only: the system draws its own controls.
                    if (!television && !inPictureInPicture && shownPlayer != null && errorMessage == null) {
                        PhonePlayerOverlay(
                            player = shownPlayer,
                            controls = controls,
                            info = remember(request, minute) { playerInfo(minute * 60_000) },
                            statisticsShown = showsStatistics,
                            onToggleStatistics = ::toggleStatistics,
                            onReportProblem = { shownReport = problemReport() },
                            scale = scale,
                            onScale = repository::setVideoScale,
                            sleep = sleepTimer,
                            levels = levels,
                            onClose = ::finish,
                            onPictureInPicture = if (supportsPictureInPicture) ::enterPictureInPicture else null,
                            channels = channelList.takeIf { request.channelId != null },
                            onRestart = ::restartProgramme,
                            onWatchLive = ::watchLive,
                        )
                    }
                    upNext?.let { offer ->
                        UpNextCard(
                            offer,
                            television = television,
                            onPlay = ::playNextEpisode,
                            onDecline = ::declineUpNext,
                            modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).then(safeArea).padding(24.dp),
                        )
                    }
                    // Over an error, a way out on a phone (a TV's Back closes; no button for the focus to wander to).
                    if (!television && !inPictureInPicture && errorMessage != null) {
                        IconButton(
                            onClick = { finish() },
                            modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp).testTag("playerClose"),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
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
        // The title and where the video starts, a moment, without taking the remote's keys.
        if (television) controls.peek()
        lifecycleScope.launch {
            while (isActive) {
                delay(500)
                // Where the video is, while the controls show it.
                if (controls.shown || controls.peeking) player?.let(controls::refresh)
                checkUpNext()
            }
        }
    }

    private fun trackLanguages(): TrackLanguages = repository.state.value.settings.let {
        TrackLanguages(it.preferredAudioLanguage, it.preferredSubtitleLanguage, it.subtitlesOn)
    }

    /** The device's languages, the first first ("fr", "en"). */
    private fun deviceLanguages(): List<String> {
        val locales = resources.configuration.locales
        return (0 until locales.size()).map { locales[it].language }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * What the controls say at the top: the title and its line, or a channel's number, name and programme (on now, or
     * the one the replay is at).
     */
    private fun playerInfo(now: Long): PlayerInfo {
        val current = request
        val state = repository.state.value
        val channel = current.channelId?.let { id -> state.content.channels.firstOrNull { it.id == id } }
        val replayAt = current.replayStart?.takeIf { current.isReplay }?.let { it + (player?.currentPosition ?: 0) }
        val programme = channel?.let { state.guide?.current(it, replayAt ?: now) }
        return PlayerInfo(
            title = channel?.let { listOfNotNull(it.number?.toString(), it.name).joinToString("  ") } ?: current.title,
            subtitle = programme?.title ?: current.title.takeIf { channel != null && current.isReplay } ?: current.subtitle,
            live = current.isLive,
            programmeStart = programme?.start?.takeIf { current.isLive },
            programmeEnd = programme?.end?.takeIf { current.isLive },
            canRestart = current.isLive && channel != null && repository.canRestart(channel.id, now),
            replay = current.isReplay,
        )
    }

    /** The programme on now, from its start, from the provider's archive. */
    private fun restartProgramme() {
        val channelId = request.channelId?.takeIf { request.isLive } ?: return
        repository.restartRequest(channelId)?.let(::switchTo)
    }

    /** From a replay, the channel live again. */
    private fun watchLive() {
        val channelId = request.channelId?.takeIf { request.isReplay } ?: return
        val channel = repository.state.value.content.channels.firstOrNull { it.id == channelId } ?: return
        repository.playRequest(channel)?.let(::switchTo)
    }

    /**
     * The viewer paused or played ([LivePause]): a live channel paused a while resumes where it was paused, from the
     * provider's archive. After the player's own listener: the player changes once its callback is over.
     */
    private fun livePause(playing: Boolean) {
        val exoPlayer = player ?: return
        if (!playing) {
            livePausedAt = LivePause.pausedAt(request, exoPlayer.isCurrentMediaItemSeekable, System.currentTimeMillis())
            return
        }
        val from = LivePause.resumeFrom(livePausedAt, System.currentTimeMillis())
        livePausedAt = null
        val channelId = request.channelId ?: return
        val replay = from?.let { repository.pausedLiveRequest(channelId, it) } ?: return
        reporter.note("live pause: resumed from the archive")
        lifecycleScope.launch { switchTo(replay) }
    }

    /** Up next shows in the last seconds of an episode that has a next one ([UpNext.offer]). */
    private fun checkUpNext() {
        val next = nextEpisode
        val exoPlayer = player
        val duration = exoPlayer?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }
        val left = if (exoPlayer != null && duration != null) duration - exoPlayer.currentPosition else Long.MAX_VALUE
        upNext = if (upNextDeclined || errorMessage != null || inPictureInPicture) null else UpNext.offer(next, left)
        declineUpNextOnBack.isEnabled = television && upNext != null
    }

    private fun playNextEpisode() {
        val (series, episode) = nextEpisode ?: return
        saveProgress(ended = true)
        val next = repository.playRequest(series, episode)
        if (next == null) {
            finish()
            return
        }
        switchTo(next)
    }

    private fun declineUpNext() {
        upNextDeclined = true
        upNext = null
        declineUpNextOnBack.isEnabled = false
    }

    private fun toggleStatistics() {
        showsStatistics = !showsStatistics
        statistics = if (showsStatistics) currentStatistics() else null
    }

    /** The controls, with where the video is now (they are kept up to date only while they show). */
    private fun showControls(panel: PlayerPanel? = null) {
        player?.let(controls::refresh)
        controls.show(panel)
    }

    private fun hideControls() = controls.hide()

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
        // A decoder that fails to start (a 4K stream beyond the TV's first one, a codec it has twice) gives way to the next.
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(this, renderers)
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
                // The viewer's languages, before the listener: what the app sets is no choice of the viewer's.
                trackSelectionParameters = TrackPreferences.parameters(trackSelectionParameters, trackLanguages(), deviceLanguages())
                addListener(object : Player.Listener {
                    // A choice in the panels (or in Media3's phone controls) is kept for the next streams.
                    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                        val learned = TrackPreferences.learned(trackLanguages(), parameters)
                        repository.setTrackLanguages(learned.audio, learned.subtitles, learned.subtitlesOn)
                    }

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

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        reporter.playWhenReady(playWhenReady)
                        // The viewer's own pause, not the system's (a call, the audio focus lost, the end of the video).
                        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) livePause(playWhenReady)
                    }

                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            reporter.seek(oldPosition.positionMs, newPosition.positionMs)
                            // The wait until the picture moves again is not a freeze.
                            health.restart()
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        reporter.playbackState(state, exoPlayer.playWhenReady)
                        if (state != Player.STATE_ENDED) return
                        if (request.isLive) {
                            reopenLive()
                        } else if (nextEpisode != null && !upNextDeclined) {
                            // The end of an episode: the next one plays.
                            playNextEpisode()
                        } else {
                            // The end of a movie or an episode: it counts as watched, back to the app.
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
     * The buffer chosen in Settings, no larger than what the app's memory has left ([playerLoadControl]). Automatic
     * keeps Media3's own durations, and waits longer before playing again once the provider has stopped twice.
     */
    private fun loadControl(): LoadControl {
        val (targetBytes, usedBytes) = bufferBudget
        reporter.note("buffer up to ${targetBytes / MEGABYTE} MB (app memory ${usedBytes / MEGABYTE} of ${Runtime.getRuntime().maxMemory() / MEGABYTE} MB used)")
        val main = Handler(Looper.getMainLooper())
        return playerLoadControl(request.bufferSeconds, request.isLive, targetBytes) { raisedMs ->
            // From the player's thread: the reporter lives on the main one.
            main.post { reporter.note("second rebuffering: waits ${raisedMs / 1_000} s of video before playing again") }
        }
    }

    /**
     * The buffer's size in bytes and the app memory in use, measured once, before the first player: measured again at a
     * channel change, the memory of the player just released (not collected yet) would shrink each new buffer.
     */
    private val bufferBudget: Pair<Int, Long> by lazy {
        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        PlaybackBuffer.targetBytes(runtime.maxMemory(), usedBytes) to usedBytes
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
        switchTo(next)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val position = player?.takeIf { !request.isLive }?.currentPosition ?: 0
        val current = if (position > 0) request.copy(startPositionMs = position) else request
        outState.putString(EXTRA_REQUEST, Json.encodeToString(PlayRequest.serializer(), current))
    }

    /** Plays [next] instead of what plays now (the progress of the current one is saved first). */
    private fun switchTo(next: PlayRequest) {
        saveProgress()
        request = next
        // The channel zapped to is the one to open again from the recent apps.
        setIntent(PlayerActivity.intent(this, next))
        urlIndex = 0
        liveRecovery.reset()
        livePausedAt = null
        nextEpisode = repository.nextEpisode(next)
        upNext = null
        upNextDeclined = false
        declineUpNextOnBack.isEnabled = false
        errorMessage = null
        shownReport = null
        // The controls and their panels belong to the player that goes: the channel's banner shows instead.
        controls.hide()
        player?.release()
        session?.release()
        reporter = PlaybackReporter(request, SystemClock::elapsedRealtime)
        resetHealth()
        val exoPlayer = buildPlayer()
        player = exoPlayer
        playerView?.player = exoPlayer
        play(0, request.startPositionMs)
        openSession(exoPlayer)
    }

    /**
     * The media session: system media controls, remotes and voice commands. On a TV it lives only while the player is
     * on screen: a video app offers no system controls once the user has left it, the video being paused (TV-NP).
     */
    private fun openSession(exoPlayer: ExoPlayer) {
        session?.release()
        // Media3 refuses two sessions of one ID: a player opened just after another was closed is created before
        // Android destroys the closed one, which still holds its session then.
        session = MediaSession.Builder(this, exoPlayer).setId("player-${++sessionCount}").build()
    }

    /** Whether the media session is open (tests). */
    @VisibleForTesting
    internal val hasMediaSession: Boolean get() = session != null

    override fun onStart() {
        super.onStart()
        if (television && session == null) player?.let(::openSession)
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
        val exoPlayer = player ?: return false
        // Up next on a TV: OK plays the next episode now.
        if (television && upNext != null && !controls.shown && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
            playNextEpisode()
            handledKeys += event.keyCode
            return true
        }
        // Each key keeps the controls a while longer.
        controls.touch()
        // A replay keeps its channel: the channel keys go on from it.
        val zapping = request.channelId != null
        val action = RemoteKeys.action(event.keyCode, controlsShown, zapping, exoPlayer.isCurrentMediaItemSeekable)
            ?: return television && showsControlsFor(event)
        // A stream that failed: OK presses Try Again; the channel keys still lead to another channel.
        val changesChannel = action is RemoteAction.Digit || action == RemoteAction.NextChannel ||
            action == RemoteAction.PreviousChannel || action == RemoteAction.LastChannel
        if (errorMessage != null && !changesChannel) return false
        // A key held down repeats: seeking goes on, the rest happens once per press (each channel builds a new player).
        val seeks = action == RemoteAction.SeekBack || action == RemoteAction.SeekForward
        if (event.repeatCount > 0 && !seeks) return true
        when (action) {
            RemoteAction.TogglePlay -> {
                Util.handlePlayPauseButtonAction(exoPlayer)
                showControls()
            }
            RemoteAction.Play -> Util.handlePlayButtonAction(exoPlayer)
            RemoteAction.Pause -> Util.handlePauseButtonAction(exoPlayer)
            // 10 s, where the video lands shown a moment on a TV (the controls stay hidden: the next press skips again).
            RemoteAction.SeekBack -> controls.skip(exoPlayer, -PlayerControls.SKIP_MS)
            RemoteAction.SeekForward -> controls.skip(exoPlayer, PlayerControls.SKIP_MS)
            RemoteAction.NextChannel -> zap(1)
            RemoteAction.PreviousChannel -> zap(-1)
            RemoteAction.LastChannel -> backToLastChannel()
            RemoteAction.ShowControls -> showControls()
            RemoteAction.HideControls -> hideControls()
            RemoteAction.Subtitles -> showControls(PlayerPanel.SUBTITLES)
            RemoteAction.Audio -> showControls(PlayerPanel.AUDIO)
            is RemoteAction.Digit -> typeDigit(action.value)
        }
        handledKeys += event.keyCode
        return true
    }

    /**
     * On a TV with the controls hidden, a key with nothing else to do shows them; not the system's keys (Back, the
     * volume…), which do what they do.
     */
    private fun showsControlsFor(event: KeyEvent): Boolean {
        if (controlsShown || errorMessage != null || event.isSystem || KeyEvent.isModifierKey(event.keyCode) || event.repeatCount > 0) return false
        showControls()
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

    /** The Channels list of a live channel's controls: the channels zapped through, with the programme on now. */
    private val channelList = ChannelList(
        load = {
            val state = repository.state.value
            val now = System.currentTimeMillis()
            UiMappers.zappingChannels(state).map { channel ->
                ChannelChoice(channel.id, channel.number, channel.name, state.guide?.current(channel, now)?.title, playing = channel.id == request.channelId)
            }
        },
        onChoose = { id -> UiMappers.zappingChannels(repository.state.value).firstOrNull { it.id == id }?.let(::playChannel) },
    )

    private fun playChannel(channel: Channel) {
        // The channel on now: nothing to change. From its replay, it plays live again.
        if (channel.id == request.channelId && request.isLive) return
        val next = repository.playRequest(channel) ?: return
        // Last channel goes back into a replay where it was.
        lastChannel = if (request.isReplay) request.copy(startPositionMs = player?.currentPosition ?: 0) else request
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
        liveRecovery.reset()
        play(0, if (request.isLive) 0 else player?.currentPosition ?: 0)
    }

    private val hasNextAddress: Boolean get() = urlIndex + 1 < request.urls.size

    /** Another address of the title (format or version of the channel): its tries start again. */
    private fun playNextAddress() {
        urlIndex += 1
        liveRecovery.reset()
        play(urlIndex, player?.currentPosition ?: 0)
    }

    /**
     * A live stream has no end: its server closed the connection ([LiveRecovery]). It is opened again from its address,
     * not sought in what the player kept: an HLS playlist that got an end mark would only replay its last segments.
     */
    private fun reopenLive() {
        when (liveRecovery.ended(SystemClock.elapsedRealtime(), hasNextAddress)) {
            LiveRecovery.Step.OPEN_AGAIN -> {
                reporter.note("live stream ended: opened again")
                play(urlIndex, 0)
            }
            LiveRecovery.Step.NEXT_ADDRESS -> {
                reporter.note("live stream ended again: next address")
                playNextAddress()
            }
            else -> {
                reporter.note("live stream ended again: error shown")
                showError(getString(R.string.error_generic))
            }
        }
    }

    private fun handleError(error: PlaybackException) {
        // A live stream fell behind its window: back to the live edge, a few times in a row at most.
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            when (liveRecovery.behindWindow(SystemClock.elapsedRealtime(), hasNextAddress)) {
                LiveRecovery.Step.BACK_TO_LIVE_EDGE -> {
                    reporter.note("behind the live window: back to the live edge")
                    player?.apply {
                        seekToDefaultPosition()
                        prepare()
                    }
                    return
                }
                LiveRecovery.Step.NEXT_ADDRESS -> {
                    reporter.note("behind the live window again: next address")
                    playNextAddress()
                    return
                }
                else -> {
                    reporter.note("behind the live window again: error shown")
                    showError(getString(R.string.error_generic))
                    return
                }
            }
        }
        // Another address (format or version of the channel) before giving up.
        if (hasNextAddress) {
            reporter.note("fallback to the next address")
            playNextAddress()
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
        showError(getString(message))
    }

    /** The error and Try Again, alone on screen: the controls go. */
    private fun showError(message: String) {
        controls.hide()
        errorMessage = message
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

    /** The Picture-in-Picture button of the phone's controls. */
    private fun enterPictureInPicture() {
        controls.hide()
        runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
    }

    /**
     * The swipes' brightness (this window's, the system's until the first swipe) and volume (the media stream's): the
     * window's brightness goes back to the system's when the player closes.
     */
    private val levels: PlayerLevels by lazy {
        object : PlayerLevels {
            private val audio = getSystemService(AudioManager::class.java)

            override val brightness: Float
                get() = window.attributes.screenBrightness.takeIf { it >= 0f }
                    ?: (Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, SYSTEM_BRIGHTNESS_MAX / 2) / SYSTEM_BRIGHTNESS_MAX.toFloat())

            override fun setBrightness(level: Float) {
                window.attributes = window.attributes.apply { screenBrightness = level.coerceIn(MIN_BRIGHTNESS, 1f) }
            }

            override val volume: Float
                get() = audio?.let { it.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / it.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) } ?: 0f

            override fun setVolume(level: Float) {
                val manager = audio ?: return
                val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt().coerceIn(0, max), 0)
            }
        }
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
        if (isInPictureInPictureMode) controls.hide()
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
        if (television) {
            session?.release()
            session = null
        }
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

        /** Sessions opened by the players of this process, for their IDs (main thread). */
        private var sessionCount = 0

        private const val MEGABYTE = 1024 * 1024

        /** Where the subtitles sit while the TV's controls show: above them (a fraction of the video's height). */
        private const val RAISED_SUBTITLES = 0.3f

        /** Narrower than this (in dp, the shorter side), a phone. */
        private const val TABLET_WIDTH_DP = 600

        /** The system's brightness setting goes up to this. */
        private const val SYSTEM_BRIGHTNESS_MAX = 255

        /** The darkest the swipe makes the screen: not black, the video stays visible. */
        private const val MIN_BRIGHTNESS = 0.01f

        /** The durations Media3 uses on its own (automatic), for the report. */
        private val MEDIA3_BUFFER = PlaybackBuffer.Durations(
            minBufferMs = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            maxBufferMs = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            bufferForPlaybackMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            bufferForPlaybackAfterRebufferMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )

        fun intent(context: Context, request: PlayRequest): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_REQUEST, Json.encodeToString(PlayRequest.serializer(), request))

        private fun Intent.playRequest(): PlayRequest? = getStringExtra(EXTRA_REQUEST)?.let(::decodeRequest)

        private fun decodeRequest(json: String): PlayRequest? = runCatching { Json.decodeFromString(PlayRequest.serializer(), json) }.getOrNull()
    }
}
