package com.markedoe.ondo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage

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

@Composable
fun GeneratedCover(title: String, modifier: Modifier = Modifier, showsTitle: Boolean = true) {
    val hue = ((title.hashCode().toLong() and 0xffff) % 360).toFloat()
    val top = Color.hsv(hue, 0.55f, 0.55f)
    val bottom = Color.hsv((hue + 30f) % 360f, 0.7f, 0.16f)
    Box(
        modifier = modifier.background(Brush.verticalGradient(listOf(top, bottom))),
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
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
            )
        }
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
