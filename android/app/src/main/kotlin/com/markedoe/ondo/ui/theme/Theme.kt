package com.markedoe.ondo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.markedoe.ondo.ui.tv.ProvideFocusRipple

/** Ondo palette: lime accent on deep green-black (always dark). */
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

private val OndoTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
)

@Composable
fun OndoTheme(content: @Composable () -> Unit) {
    // Ondo is always dark, on a phone as on a TV.
    MaterialTheme(colorScheme = Dark, typography = OndoTypography) {
        // A focus that shows from the sofa (remote, keyboard).
        ProvideFocusRipple(content)
    }
}
