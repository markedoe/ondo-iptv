package com.markedoe.ondo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.markedoe.ondo.ui.tv.ProvideFocusRipple
import com.markedoe.ondo.ui.tv.isTelevision

/** Ondo palette: lime accent on deep green-black (dark, the default) or teal on white (light). */
object OndoColors {
    val Lime = Color(0xFFCEF941)
    val Teal = Color(0xFF127A61)
    val Cream = Color(0xFFFFF6DE)
    val Ink = Color(0xFF050807)
    val Surface = Color(0xFF111614)
    val SurfaceHigh = Color(0xFF1A211E)
}

private val Dark: ColorScheme = darkColorScheme(
    primary = OndoColors.Lime,
    onPrimary = Color.Black,
    secondary = OndoColors.Cream,
    onSecondary = Color.Black,
    background = OndoColors.Ink,
    onBackground = Color.White,
    surface = OndoColors.Ink,
    onSurface = Color.White,
    surfaceVariant = OndoColors.SurfaceHigh,
    onSurfaceVariant = Color(0xFFB8C2BD),
    surfaceContainer = OndoColors.Surface,
    surfaceContainerHigh = OndoColors.SurfaceHigh,
    outline = Color(0xFF3A4440),
    // Selected chips, the tab bar's pill: Ondo's lime on dark, not Material's default purple.
    primaryContainer = Color(0xFF34421A),
    onPrimaryContainer = OndoColors.Lime,
    secondaryContainer = Color(0xFF2C3622),
    onSecondaryContainer = Color(0xFFE6F7B8),
)

private val Light: ColorScheme = lightColorScheme(
    primary = OndoColors.Teal,
    onPrimary = Color.White,
    secondary = Color(0xFF0B1F1A),
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFEFF3F1),
    onSurfaceVariant = Color(0xFF4A5652),
    primaryContainer = Color(0xFFCDEBE2),
    onPrimaryContainer = Color(0xFF0B1F1A),
    secondaryContainer = Color(0xFFDDEBE6),
    onSecondaryContainer = Color(0xFF0B1F1A),
)

private val OndoTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
)

enum class Appearance { SYSTEM, LIGHT, DARK }

@Composable
fun OndoTheme(appearance: Appearance = Appearance.DARK, content: @Composable () -> Unit) {
    // A TV is always dark (Google's TV guidelines; Compose for TV's pages are dark only).
    val dark = LocalConfiguration.current.isTelevision || when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = OndoTypography) {
        // A focus that shows from the sofa (remote, keyboard).
        ProvideFocusRipple(content)
    }
}
