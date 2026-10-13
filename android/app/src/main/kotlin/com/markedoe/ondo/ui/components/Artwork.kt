package com.markedoe.ondo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import kotlin.math.PI
import kotlin.math.sin

val PosterShape = RoundedCornerShape(12.dp)

/**
 * Artwork from the provider, or — when there is none or it fails — a generated cover: a gradient whose hue
 * comes from the title, with the title on it. Lists stay good-looking with incomplete catalogues.
 */
@Composable
fun Artwork(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    showsTitleFallback: Boolean = true,
) {
    if (url.isNullOrBlank()) {
        GeneratedCover(title, modifier, showsTitleFallback)
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
        loading = { GeneratedCover(title, Modifier.fillMaxSize(), showsTitle = false) },
        error = { GeneratedCover(title, Modifier.fillMaxSize(), showsTitleFallback) },
    )
}

/**
 * A poster drawn from the title: a night sky whose hue comes from the title, a moon and two rows of hills,
 * with the title at the bottom (the same idea as the iPhone app's placeholder artwork).
 */
@Composable
fun GeneratedCover(title: String, modifier: Modifier = Modifier, showsTitle: Boolean = true) {
    val seed = title.hashCode()
    val hue = ((seed.toLong() and 0xffff) % 360).toFloat()
    val moonX = 0.3f + ((seed ushr 8) and 0xff) / 255f * 0.4f
    val phase = ((seed ushr 16) and 0xff) / 255f * (2 * PI).toFloat()
    Box(
        modifier = modifier.drawBehind {
            drawRect(Brush.verticalGradient(listOf(Color.hsv(hue, 0.5f, 0.5f), Color.hsv((hue + 25f) % 360f, 0.65f, 0.13f))))
            val moon = Offset(size.width * moonX, size.height * 0.3f)
            val radius = size.minDimension * 0.17f
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent), center = moon, radius = radius * 2.8f),
                radius = radius * 2.8f,
                center = moon,
            )
            drawCircle(Color(0xFFF4F1E6).copy(alpha = 0.92f), radius = radius, center = moon)
            hills(level = 0.6f, amplitude = 0.05f, waves = 1.6f, phase = phase, color = Color.hsv(hue, 0.55f, 0.24f))
            hills(level = 0.72f, amplitude = 0.04f, waves = 2.3f, phase = phase + 1.7f, color = Color.hsv((hue + 15f) % 360f, 0.6f, 0.12f))
        },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (showsTitle) {
            Text(
                text = title.uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium.copy(shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 8f)),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
            )
        }
    }
}

private fun DrawScope.hills(level: Float, amplitude: Float, waves: Float, phase: Float, color: Color) {
    val path = Path()
    path.moveTo(0f, size.height)
    val steps = 48
    for (step in 0..steps) {
        val x = size.width * step / steps
        val y = size.height * (level + amplitude * sin(phase + waves * 2 * PI * step / steps).toFloat())
        path.lineTo(x, y)
    }
    path.lineTo(size.width, size.height)
    path.close()
    drawPath(path, color)
}

/** A channel logo, or its initials when the provider gives none (always fully visible). */
@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier) {
    if (url.isNullOrBlank()) {
        Initials(name, modifier)
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        loading = { Initials(name, Modifier.fillMaxSize()) },
        error = { Initials(name, Modifier.fillMaxSize()) },
    )
}

@Composable
private fun Initials(name: String, modifier: Modifier = Modifier) {
    val letters = name.split(' ', '-', '_', '.').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fontSize = with(LocalDensity.current) { (minOf(maxWidth, maxHeight) * 0.5f).toSp() }
        Text(
            text = letters,
            fontSize = fontSize,
            lineHeight = fontSize,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 2:3 poster with soft corners, a hairline border and an optional progress bar. */
@Composable
fun Poster(url: String?, title: String, modifier: Modifier = Modifier, progress: Float? = null) {
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(PosterShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), PosterShape),
    ) {
        Artwork(url = url, title = title, modifier = Modifier.fillMaxSize())
        if (progress != null && progress > 0f) {
            ProgressBar(
                value = progress,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp),
            )
        }
    }
}

/** Thin rounded progress bar drawn over artwork. */
@Composable
fun ProgressBar(value: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.3f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(value.coerceIn(0f, 1f))
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
