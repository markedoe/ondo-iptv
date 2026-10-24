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
 * On a TV, the controls are drawn for the remote ([TvPlayerOverlay], [TvControls]): Media3's view only shows the video.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    /** The player of what plays now (a new one for each channel): the TV's controls follow it. */
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

    /** The TV's controls: shown, their panel, what the player does. */
    private val tvControls: TvControls by lazy {
        TvControls(lifecycleScope) { shown ->
            hideControlsOnBack.isEnabled = shown
            // The subtitles rise above the controls while they show.
            playerView?.subtitleView?.setBottomPaddingFraction(if (shown) RAISED_SUBTITLES else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        }.apply { playing = { player?.playWhenReady == true } }
    }

    /** Media3's controls are on screen, on a phone or a tablet. */
    private var phoneControlsShown = false

    /** The player's controls are on screen (the remote's arrows then move through them). */
    private val controlsShown: Boolean get() = if (television) tvControls.shown else phoneControlsShown

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

    /**
     * On a TV, Back closes a panel, then hides the controls, before it leaves (the system's back: the key itself no longer
     * reaches the app). Enabled while the controls show.
     */
    private val hideControlsOnBack: OnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (tvControls.back()) return
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

    /** Freezes and lost pictures of this playback, explained; shown by More, Stream Statistics. */
    private var health = PlaybackHealth()
    private var healthStart = SystemClock.elapsedRealtime()
    private var showsStatistics by mutableStateOf(false)
    private var statistics by mutableStateOf<StreamStatistics?>(null)

    /**
     * The More menu (Aspect Ratio, Sleep Timer, Stream Statistics, Report a Problem) is open, on a phone or a tablet,
     * on [menuPage] (null for its first list); a TV has its More panel.
     */
    private var menuOpen by mutableStateOf(false)
    private var menuPage by mutableStateOf<TvPanel?>(null)

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

        reporter = PlaybackReporter(request, SystemClock::elapsedRealtime)
        // The channel list for the remote's channel keys, when the player was opened again alone (after Android
        // stopped the app in the background).
        repository.bootstrap()
        onBackPressedDispatcher.addCallback(this, hideControlsOnBack)
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
                                setShowSubtitleButton(true)
                                setShowNextButton(false)
                                setShowPreviousButton(false)
                                // The spinner while the stream starts, after a seek and when it waits for data.
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                // The screen stays on while the video plays only: paused, it may dim and a TV may
                                // show its screen saver (TV-BU, TV-BY).
                                keepScreenOn = exoPlayer.isPlaying
                                // A TV draws its own controls for the remote over this view (TvPlayerOverlay): Media3's
                                // keep drawing the picture, the subtitles and the spinner.
                                useController = !television
                                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { phoneControlsShown = it == View.VISIBLE })
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
                    val minute by remember { derivedStateOf { tvControls.clockMs / 60_000 } }
                    if (television && !inPictureInPicture && shownPlayer != null && errorMessage == null) {
                        OndoTvTheme {
                            TvPlayerOverlay(
                                player = shownPlayer,
                                controls = tvControls,
                                // Looked up once a minute and for each channel, not on each frame of the controls.
                                info = remember(request, minute) { tvPlayerInfo(minute * 60_000) },
                                statisticsShown = showsStatistics,
                                onToggleStatistics = ::toggleStatistics,
                                onReportProblem = { shownReport = problemReport() },
                                scale = scale,
                                onScale = repository::setVideoScale,
                                sleep = sleepTimer,
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
                    // On a TV, the remote's Back closes (no Close button for the focus to wander to) and More is one
                    // of the player's own buttons, with its own panel.
                    if (!inPictureInPicture && !television) {
                        IconButton(
                            onClick = { finish() },
                            modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                        }
                        Box(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
                            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("playerMore")) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more), tint = Color.White)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = ::closeMenu) {
                                when (menuPage) {
                                    TvPanel.ASPECT -> VideoScale.entries.forEach { option ->
                                        MenuChoice(stringResource(option.label), selected = option == scale, tag = "scale-${option.name}") {
                                            closeMenu()
                                            repository.setVideoScale(option)
                                        }
                                    }
                                    TvPanel.SLEEP -> (listOf(null) + SleepTimer.CHOICES).forEach { minutes ->
                                        val name = if (minutes == null) stringResource(R.string.off) else stringResource(R.string.sleep_minutes, minutes)
                                        MenuChoice(name, selected = sleepTimer.minutes == minutes, tag = "sleep-${minutes ?: "off"}") {
                                            closeMenu()
                                            sleepTimer.set(minutes)
                                        }
                                    }
                                    else -> {
                                        MenuRow(stringResource(R.string.aspect_ratio), stringResource(scale.label), tag = "menuAspect") { menuPage = TvPanel.ASPECT }
                                        val sleepValue = sleepTimer.endsAt?.let { stringResource(R.string.sleep_ends_at, DateFormat.getTimeFormat(this@PlayerActivity).format(Date(it))) }
                                        MenuRow(stringResource(R.string.sleep_timer), sleepValue ?: stringResource(R.string.off), tag = "menuSleep") { menuPage = TvPanel.SLEEP }
                                        MenuChoice(stringResource(R.string.stream_statistics), selected = showsStatistics, tag = "statisticsToggle") {
                                            closeMenu()
                                            toggleStatistics()
                                        }
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.report_problem)) },
                                            onClick = {
                                                closeMenu()
                                                shownReport = problemReport()
                                            },
                                        )
                                    }
                                }
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
        if (television) {
            // The title and where the video starts, a moment, without taking the remote's keys.
            tvControls.peek()
            lifecycleScope.launch {
                while (isActive) {
                    delay(500)
                    // Where the video is, while the controls show it.
                    if (tvControls.shown || tvControls.peeking) player?.let(tvControls::refresh)
                }
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

    /** What the TV's controls say at the top: the title and its line, or a live channel's number, name and programme. */
    private fun tvPlayerInfo(now: Long): TvPlayerInfo {
        val current = request
        val state = repository.state.value
        val channel = current.channelId?.takeIf { current.isLive }?.let { id -> state.content.channels.firstOrNull { it.id == id } }
        val programme = channel?.let { state.guide?.current(it, now) }
        return TvPlayerInfo(
            title = channel?.let { listOfNotNull(it.number?.toString(), it.name).joinToString("  ") } ?: current.title,
            subtitle = programme?.title ?: current.subtitle,
            live = current.isLive,
            programmeStart = programme?.start,
            programmeEnd = programme?.end,
        )
    }

    private fun closeMenu() {
        menuOpen = false
        menuPage = null
    }

    /** A row of the phone's More menu that opens a list of choices: what it sets, its value beneath. */
    @Composable
    private fun MenuRow(title: String, value: String, tag: String, onClick: () -> Unit) {
        DropdownMenuItem(
            text = {
                Column {
                    Text(title)
                    Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            onClick = onClick,
            trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
            modifier = Modifier.testTag(tag),
        )
    }

    /** A choice of the phone's More menu, ticked when in use. */
    @Composable
    private fun MenuChoice(name: String, selected: Boolean, tag: String, onClick: () -> Unit) {
        DropdownMenuItem(
            text = { Text(name) },
            onClick = onClick,
            trailingIcon = { if (selected) Icon(Icons.Rounded.Check, contentDescription = null) },
            modifier = Modifier.testTag(tag).semantics { this.selected = selected },
        )
    }

    private fun toggleStatistics() {
        showsStatistics = !showsStatistics
        statistics = if (showsStatistics) currentStatistics() else null
    }

    /** The controls, with where the video is now (the TV's are kept up to date only while they show). */
    private fun showControls(panel: TvPanel? = null) {
        if (!television) {
            playerView?.showController()
            return
        }
        player?.let(tvControls::refresh)
        tvControls.show(panel)
    }

    private fun hideControls() {
        if (television) tvControls.hide() else playerView?.hideController()
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
                        if (state != Player.STATE_ENDED) return
                        if (request.isLive) {
                            reopenLive()
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
        errorMessage = null
        shownReport = null
        // The controls and their panels belong to the player that goes: the channel's banner shows instead.
        if (television) tvControls.hide()
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
        val view = playerView ?: return false
        val exoPlayer = player ?: return false
        // On a TV, each key keeps the controls a while longer.
        if (television) tvControls.touch()
        val zapping = request.isLive && request.channelId != null
        val action = RemoteKeys.action(event.keyCode, controlsShown, zapping, exoPlayer.isCurrentMediaItemSeekable)
            ?: return television && showsControlsFor(event)
        // A stream that failed: OK presses Try Again; the channel keys still lead to another channel.
        val changesChannel = action is RemoteAction.Digit || action == RemoteAction.NextChannel ||
            action == RemoteAction.PreviousChannel || action == RemoteAction.LastChannel
        if (errorMessage != null && !changesChannel) return false
        // A phone has no panels: its subtitles are in Media3's settings.
        if (!television && (action == RemoteAction.Subtitles || action == RemoteAction.Audio)) return false
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
            // On a TV, 10 s, where the video lands shown a moment (the controls stay hidden: the next press skips again).
            RemoteAction.SeekBack -> if (television) {
                tvControls.skip(exoPlayer, -TvControls.SKIP_MS)
            } else {
                exoPlayer.seekBack()
                view.showController()
            }
            RemoteAction.SeekForward -> if (television) {
                tvControls.skip(exoPlayer, TvControls.SKIP_MS)
            } else {
                exoPlayer.seekForward()
                view.showController()
            }
            RemoteAction.NextChannel -> zap(1)
            RemoteAction.PreviousChannel -> zap(-1)
            RemoteAction.LastChannel -> backToLastChannel()
            RemoteAction.ShowControls -> showControls()
            RemoteAction.HideControls -> hideControls()
            RemoteAction.Subtitles -> showControls(TvPanel.SUBTITLES)
            RemoteAction.Audio -> showControls(TvPanel.AUDIO)
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

    /** The error and Try Again, alone on screen: the TV's controls go. */
    private fun showError(message: String) {
        if (television) tvControls.hide()
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && supportsPictureInPicture && player?.isPlaying == true) {
            runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        playerView?.useController = !isInPictureInPictureMode && !television
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
