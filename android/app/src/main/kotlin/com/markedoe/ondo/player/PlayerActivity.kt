package com.markedoe.ondo.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.markedoe.ondo.OndoApplication
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.theme.OndoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Full-screen player (Media3 / ExoPlayer): HLS, MPEG-TS, MP4, MKV; fallback addresses, resume position,
 * Picture-in-Picture, media session (headset and remote buttons). Progress is saved every 10 s.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private lateinit var request: PlayRequest
    private var urlIndex = 0
    private var errorMessage by mutableStateOf<String?>(null)
    private var playerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = requireNotNull(intent.playRequest()) { "PlayerActivity needs a play request" }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val exoPlayer = buildPlayer()
        player = exoPlayer
        session = MediaSession.Builder(this, exoPlayer).build()
        play(urlIndex, request.startPositionMs)

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
                                keepScreenOn = true
                            }.also { playerView = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    IconButton(
                        onClick = { finish() },
                        modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                    }
                    errorMessage?.let { message ->
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
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(request.headers)
        request.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.let { http.setUserAgent(it.value) }
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) = handleError(error)

                    override fun onPlaybackStateChanged(state: Int) {
                        // The end of a movie or an episode: it counts as watched, back to the app.
                        if (state == Player.STATE_ENDED && !request.isLive) {
                            saveProgress(ended = true)
                            finish()
                        }
                    }
                })
                playWhenReady = true
            }
    }

    private fun play(index: Int, startMs: Long) {
        val url = request.urls.getOrNull(index) ?: return
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
        player?.release()
        session?.release()
        val exoPlayer = buildPlayer()
        player = exoPlayer
        session = MediaSession.Builder(this, exoPlayer).build()
        playerView?.player = exoPlayer
        play(0, request.startPositionMs)
    }

    private fun retry() {
        errorMessage = null
        urlIndex = 0
        play(0, if (request.isLive) 0 else player?.currentPosition ?: 0)
    }

    private fun handleError(error: PlaybackException) {
        // A live stream fell behind its window: back to the live edge.
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player?.apply {
                seekToDefaultPosition()
                prepare()
            }
            return
        }
        // Another address (format or version of the channel) before giving up.
        if (urlIndex + 1 < request.urls.size) {
            urlIndex += 1
            play(urlIndex, player?.currentPosition ?: 0)
            return
        }
        errorMessage = getString(
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> R.string.error_provider_refused
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> R.string.error_network
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> R.string.error_format
                else -> R.string.error_generic
            },
        )
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            player?.pause()
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

        fun intent(context: Context, request: PlayRequest): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_REQUEST, Json.encodeToString(PlayRequest.serializer(), request))

        private fun Intent.playRequest(): PlayRequest? =
            getStringExtra(EXTRA_REQUEST)?.let { runCatching { Json.decodeFromString(PlayRequest.serializer(), it) }.getOrNull() }
    }
}
