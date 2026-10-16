package com.markedoe.ondo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlin.math.PI
import kotlin.math.sin

val PosterShape = RoundedCornerShape(12.dp)

/** Pixels a blurred copy of a poster is decoded at: the blur hides any more detail. */
private const val BLURRED_DECODE_SIZE = 96

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
    /** At most this many pixels wide and high, rather than the size on screen (blurred copies). */
    decodeSize: Int? = null,
) {
    if (url.isNullOrBlank()) {
        GeneratedCover(title, modifier, showsTitleFallback)
        return
    }
    var failed by remember(url) { mutableStateOf(false) }
    val context = LocalPlatformContext.current
    val model: Any = remember(url, decodeSize) { if (decodeSize == null) url else ImageRequest.Builder(context).data(url).size(decodeSize).build() }
    // The cover is drawn behind the picture: it shows while the picture loads and fades in, and stays if it fails.
    // No subcomposition per item, unlike a loading slot (lists scroll more smoothly).
    Box(modifier.generatedCover(title), contentAlignment = Alignment.BottomCenter) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { failed = false },
            onError = { failed = true },
        )
        if (failed && showsTitleFallback) CoverTitle(title)
    }
}

/** A poster as a blurred background (carousel, pages without a backdrop): decoded small, then blurred. */
@Composable
fun BlurredPoster(url: String?, title: String, modifier: Modifier = Modifier) {
    Artwork(url = url, title = title, modifier = modifier.blur(40.dp), showsTitleFallback = false, decodeSize = BLURRED_DECODE_SIZE)
}

/**
 * A wide background picture (movie and series pages). While it loads, without one, or when its address leads
 * nowhere, the poster fills the space, blurred, rather than an empty frame.
 */
@Composable
fun Backdrop(url: String?, posterUrl: String?, title: String, modifier: Modifier = Modifier) {
    Box(modifier) {
        BlurredPoster(url = posterUrl, title = title, modifier = Modifier.fillMaxSize())
        if (!url.isNullOrBlank()) {
            // Over the blurred poster, which stays visible until the backdrop arrives (or for good if it fails).
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * A poster drawn from the title: a night sky whose hue comes from the title, a moon and two rows of hills,
 * with the title at the bottom (the same idea as the iPhone app's placeholder artwork).
 */
@Composable
fun GeneratedCover(title: String, modifier: Modifier = Modifier, showsTitle: Boolean = true) {
    Box(modifier.generatedCover(title), contentAlignment = Alignment.BottomCenter) {
        if (showsTitle) CoverTitle(title)
    }
}

@Composable
private fun CoverTitle(title: String) {
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

/** Draws [GeneratedCover]'s picture behind the content. Its gradients and hills are built once per size, not at every frame. */
private fun Modifier.generatedCover(title: String): Modifier {
    val seed = title.hashCode()
    val hue = ((seed.toLong() and 0xffff) % 360).toFloat()
    val moonX = 0.3f + ((seed ushr 8) and 0xff) / 255f * 0.4f
    val phase = ((seed ushr 16) and 0xff) / 255f * (2 * PI).toFloat()
    return drawWithCache {
        val sky = Brush.verticalGradient(listOf(Color.hsv(hue, 0.5f, 0.5f), Color.hsv((hue + 25f) % 360f, 0.65f, 0.13f)))
        val moon = Offset(size.width * moonX, size.height * 0.3f)
        val radius = size.minDimension * 0.17f
        val glow = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent), center = moon, radius = radius * 2.8f)
        val far = hills(size, level = 0.6f, amplitude = 0.05f, waves = 1.6f, phase = phase)
        val near = hills(size, level = 0.72f, amplitude = 0.04f, waves = 2.3f, phase = phase + 1.7f)
        onDrawBehind {
            drawRect(sky)
            drawCircle(glow, radius = radius * 2.8f, center = moon)
            drawCircle(Color(0xFFF4F1E6).copy(alpha = 0.92f), radius = radius, center = moon)
            drawPath(far, Color.hsv(hue, 0.55f, 0.24f))
            drawPath(near, Color.hsv((hue + 15f) % 360f, 0.6f, 0.12f))
        }
    }
}

private fun hills(size: Size, level: Float, amplitude: Float, waves: Float, phase: Float): Path {
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
    return path
}

/** A channel logo, or its initials when the provider gives none (always fully visible). */
@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier) {
    if (url.isNullOrBlank()) {
        Initials(name, modifier.fillMaxSize())
        return
    }
    var loaded by remember(url) { mutableStateOf(false) }
    // The logo's frame is filled from the start: the initials show there until the logo arrives (or if it fails).
    Box(modifier.fillMaxSize()) {
        Initials(name, Modifier.fillMaxSize(), shown = { !loaded })
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onLoading = { loaded = false },
            onSuccess = { loaded = true },
            onError = { loaded = false },
        )
    }
}

/** Two letters of the name, half as tall as the space, drawn rather than laid out (one per row of the Live list). */
@Composable
private fun Initials(name: String, modifier: Modifier = Modifier, shown: () -> Boolean = { true }) {
    val letters = remember(name) { name.split(' ', '-', '_', '.').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() } }
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.copy(fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(
        modifier.drawWithCache {
            if (size.minDimension <= 0f) return@drawWithCache onDrawBehind {}
            val fontSize = (size.minDimension * 0.5f).toSp()
            val layout = measurer.measure(
                letters,
                style.copy(fontSize = fontSize, lineHeight = fontSize),
                overflow = TextOverflow.Clip,
                maxLines = 1,
                constraints = Constraints(maxWidth = size.width.toInt().coerceAtLeast(0)),
            )
            val topLeft = Offset((size.width - layout.size.width) / 2f, (size.height - layout.size.height) / 2f)
            onDrawBehind { if (shown()) drawText(layout, topLeft = topLeft) }
        },
    )
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
