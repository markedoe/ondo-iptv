package com.markedoe.ondo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.tv.ProvideFocusRipple

/**
 * Ondo's palette: neutral greys on pure black, white for what is chosen or focused. The colour on screen comes from
 * the pictures, not from the frame around them.
 */
object OndoColors {
    /** What has the focus or is chosen: white. */
    val Accent = Color.White
    val Ink = Color(0xFF000000)
    val Surface = Color(0xFF1C1C1E)
    val SurfaceHigh = Color(0xFF2C2C2E)
    val Outline = Color(0xFF3A3A3C)

    /** Secondary text: white at 60 % on black. */
    val Secondary = Color(0xFF9A9A9E)
}

/**
 * Inter (SIL Open Font License, `assets/licenses/inter-OFL.txt`), one variable file for every weight. Letters it
 * lacks (Arabic, Devanagari, Chinese) come from the system's fonts.
 */
@OptIn(ExperimentalTextApi::class)
val Inter: FontFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold).map { weight ->
        Font(R.font.inter, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

private val Dark: ColorScheme = darkColorScheme(
    primary = OndoColors.Accent,
    onPrimary = Color.Black,
    secondary = Color.White,
    onSecondary = Color.Black,
    background = OndoColors.Ink,
    onBackground = Color.White,
    surface = OndoColors.Ink,
    onSurface = Color.White,
    surfaceVariant = OndoColors.SurfaceHigh,
    onSurfaceVariant = OndoColors.Secondary,
    surfaceContainer = OndoColors.Surface,
    surfaceContainerHigh = OndoColors.SurfaceHigh,
    outline = OndoColors.Outline,
    // Selected chips, the tab bar's pill: a raised grey, not Material's default purple.
    primaryContainer = OndoColors.SurfaceHigh,
    onPrimaryContainer = Color.White,
    secondaryContainer = OndoColors.SurfaceHigh,
    onSecondaryContainer = Color.White,
)

/**
 * Material's switch in Ondo's colours: on, a white track with a black thumb; off, a grey thumb and outline on a dark
 * track (Material's own off thumb, the outline grey, is lost on black).
 */
@Composable
fun ondoSwitchColors(): SwitchColors = SwitchDefaults.colors(
    uncheckedThumbColor = OndoColors.Secondary,
    uncheckedTrackColor = OndoColors.SurfaceHigh,
    uncheckedBorderColor = OndoColors.Secondary,
)

/** Every style of [base] in Inter; large titles drawn a little tighter, as large type reads best. */
fun Typography.inInter(): Typography = Typography(
    displayLarge = displayLarge.inter(tight = true),
    displayMedium = displayMedium.inter(tight = true),
    displaySmall = displaySmall.inter(tight = true),
    headlineLarge = headlineLarge.inter(tight = true),
    headlineMedium = headlineMedium.inter(tight = true),
    headlineSmall = headlineSmall.inter(tight = true),
    titleLarge = titleLarge.inter(),
    titleMedium = titleMedium.inter(),
    titleSmall = titleSmall.inter(),
    bodyLarge = bodyLarge.inter(),
    bodyMedium = bodyMedium.inter(),
    bodySmall = bodySmall.inter(),
    labelLarge = labelLarge.inter(),
    labelMedium = labelMedium.inter(),
    labelSmall = labelSmall.inter(),
)

fun TextStyle.inter(tight: Boolean = false): TextStyle = copy(fontFamily = Inter, letterSpacing = if (tight) (-0.02).em else letterSpacing)

private val OndoTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
).inInter()

@Composable
fun OndoTheme(content: @Composable () -> Unit) {
    // Ondo is always dark, on a phone as on a TV.
    MaterialTheme(colorScheme = Dark, typography = OndoTypography) {
        // A focus that shows from the sofa (remote, keyboard).
        ProvideFocusRipple(content)
    }
}
