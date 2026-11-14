package com.markedoe.ondo

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.ui.Labels
import com.markedoe.ondo.ui.OndoApp
import com.markedoe.ondo.ui.OndoViewModel
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.player.PictureSupportProbe
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.theme.OndoTheme

class MainActivity : ComponentActivity() {
    /**
     * When Ondo was last opened from the TV's home screen while running ([TvLauncherActivity]; the device's uptime, 0:
     * not since this screen was made): each time, Home again, at its top.
     */
    private var launch by mutableLongStateOf(0L)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (opensHome(intent)) launch = SystemClock.uptimeMillis()
    }

    override fun onStart() {
        super.onStart()
        // Back in front: a playlist older than the viewer's choice is downloaded again.
        (application as OndoApplication).repository.refreshIfStale()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val repository = (application as OndoApplication).repository
        // Another app language (Settings, App language) recreates the activity, not the repository.
        repository.setPlaceholderNames(resources.placeholderNames())
        // The version of a film shown first suits this screen (4K, HDR, Dolby Vision).
        UiMappers.pictureSupport = PictureSupportProbe.of(this)
        (application as OndoApplication).localSync.start()
        // The splash screen stays until the saved playlists are read (no welcome screen flash), and the saved
        // catalogue too for a moment (Home appears with its content, see keepsSplash).
        val start = SystemClock.uptimeMillis()
        // Home kept from the last start, drawn under the splash screen while the catalogue is read: shown at once.
        var model: OndoViewModel? = null
        splash.setKeepOnScreenCondition { keepsSplash(repository.state.value, SystemClock.uptimeMillis() - start, model?.homeShown == true) }
        enableEdgeToEdge()
        setContent {
            val viewModel: OndoViewModel = viewModel {
                OndoViewModel(
                    repository,
                    appLabels(
                        getString(R.string.kind_movie), getString(R.string.kind_series), getString(R.string.hero_new_episode),
                        resources.configuration.locales[0], resources.versionWords(),
                    ),
                )
            }
            SideEffect { model = viewModel }
            // Another app language (Settings, App language) recreates the activity, not the view model.
            val movie = stringResource(R.string.kind_movie)
            val series = stringResource(R.string.kind_series)
            val newEpisode = stringResource(R.string.hero_new_episode)
            val locale = LocalConfiguration.current.locales[0]
            val versionWords = LocalContext.current.resources.versionWords()
            LaunchedEffect(movie, series, newEpisode, locale, versionWords) {
                viewModel.setLabels(appLabels(movie, series, newEpisode, locale, versionWords))
            }
            // Always dark: light icons on the transparent system bars.
            DisposableEffect(Unit) {
                val style = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            // No automatic carousel when the user turned animations off (read again when the app comes back).
            var animations by remember { mutableStateOf(animationsOn()) }
            LifecycleResumeEffect(Unit) {
                animations = animationsOn()
                onPauseOrDispose {}
            }
            OndoTheme {
                CompositionLocalProvider(LocalCarouselAutoAdvance provides animations) {
                    OndoApp(viewModel, launch = launch)
                }
            }
        }
    }

    private fun animationsOn(): Boolean = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    companion object {
        /** Set by [TvLauncherActivity]: Home, at its top, whatever page was left open. */
        const val EXTRA_OPEN_HOME = "com.markedoe.ondo.OPEN_HOME"

        /** Whether [intent] asks for Home ([EXTRA_OPEN_HOME]): the TV's home screen. */
        fun opensHome(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_OPEN_HOME, false) == true

        /** Longest wait for the saved catalogue behind the splash screen: Home then appears without it (never as empty). */
        const val SPLASH_RESTORE_LIMIT_MS = 2_000L

        /**
         * Whether the splash screen stays, [elapsedMs] after the app started: until the saved playlists are read, then
         * while the saved catalogue is read, [SPLASH_RESTORE_LIMIT_MS] at most, unless Home can be drawn with its carousel
         * meanwhile ([homeShown]: Home kept from the last start).
         */
        fun keepsSplash(state: OndoState, elapsedMs: Long, homeShown: Boolean = false): Boolean =
            !state.isReady || (state.isRestoring && !homeShown && elapsedMs < SPLASH_RESTORE_LIMIT_MS)
    }
}

/** The mappers' words in the app's language, with a day and a month as the viewer's language writes them ("3 Nov", "Nov 3"). */
internal fun appLabels(
    movie: String,
    series: String,
    newEpisode: String,
    locale: java.util.Locale,
    versionWords: com.markedoe.ondo.core.library.VersionWords = com.markedoe.ondo.core.library.VersionWords(),
) = Labels(
    movie, series, locale, versionWords = versionWords, newEpisode = newEpisode,
    dayMonth = android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"),
)
