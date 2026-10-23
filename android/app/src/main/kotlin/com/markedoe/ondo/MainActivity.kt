package com.markedoe.ondo

import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.LaunchedEffect
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.ui.Labels
import com.markedoe.ondo.ui.OndoApp
import com.markedoe.ondo.ui.OndoViewModel
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.theme.Appearance
import com.markedoe.ondo.ui.theme.OndoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val repository = (application as OndoApplication).repository
        (application as OndoApplication).localSync.start()
        // The splash screen stays until the saved playlists are read (no welcome screen flash), and the saved
        // catalogue too for a moment (Home appears with its content, see keepsSplash).
        val start = SystemClock.uptimeMillis()
        splash.setKeepOnScreenCondition { keepsSplash(repository.state.value, SystemClock.uptimeMillis() - start) }
        enableEdgeToEdge()
        setContent {
            val viewModel: OndoViewModel = viewModel {
                OndoViewModel(repository, Labels(getString(R.string.kind_movie), getString(R.string.kind_series)))
            }
            // Another app language (Settings, App language) recreates the activity, not the view model.
            val movie = stringResource(R.string.kind_movie)
            val series = stringResource(R.string.kind_series)
            val locale = LocalConfiguration.current.locales[0]
            LaunchedEffect(movie, series, locale) { viewModel.setLabels(Labels(movie, series, locale)) }
            val appearance by viewModel.appearance.collectAsStateWithLifecycle()
            val dark = when (appearance) {
                Appearance.SYSTEM -> isSystemInDarkTheme()
                Appearance.DARK -> true
                Appearance.LIGHT -> false
            }
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            // No automatic carousel when the user turned animations off (read again when the app comes back).
            var animations by remember { mutableStateOf(animationsOn()) }
            LifecycleResumeEffect(Unit) {
                animations = animationsOn()
                onPauseOrDispose {}
            }
            OndoTheme(appearance) {
                CompositionLocalProvider(LocalCarouselAutoAdvance provides animations) {
                    OndoApp(viewModel)
                }
            }
        }
    }

    private fun animationsOn(): Boolean = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    companion object {
        /** Longest wait for the saved catalogue behind the splash screen: Home then appears without it (never as empty). */
        const val SPLASH_RESTORE_LIMIT_MS = 2_000L

        /**
         * Whether the splash screen stays, [elapsedMs] after the app started: until the saved playlists are read, then
         * while the saved catalogue is read, [SPLASH_RESTORE_LIMIT_MS] at most.
         */
        fun keepsSplash(state: OndoState, elapsedMs: Long): Boolean =
            !state.isReady || (state.isRestoring && elapsedMs < SPLASH_RESTORE_LIMIT_MS)
    }
}
