package com.markedoe.ondo

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
        // The splash screen stays until the saved playlists are read (no welcome screen flash).
        splash.setKeepOnScreenCondition { !repository.state.value.isReady }
        enableEdgeToEdge()
        setContent {
            val viewModel: OndoViewModel = viewModel {
                OndoViewModel(repository, Labels(getString(R.string.kind_movie), getString(R.string.kind_series)))
            }
            val state by viewModel.state.collectAsStateWithLifecycle()
            val dark = when (state.settings.appearance) {
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
            // No automatic carousel when the user turned animations off.
            val animations = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            OndoTheme(state.settings.appearance) {
                CompositionLocalProvider(LocalCarouselAutoAdvance provides animations) {
                    OndoApp(viewModel)
                }
            }
        }
    }
}
