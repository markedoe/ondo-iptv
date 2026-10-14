package com.markedoe.ondo.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.myListState
import kotlinx.coroutines.delay

/** Off when the system's animations are turned off (accessibility) and in screenshots. */
val LocalCarouselAutoAdvance = staticCompositionLocalOf { true }

/** What the carousel shows for one title (details may still be loading). */
data class HeroPage(
    val item: ShowcaseItem,
    val meta: String,
    val inMyList: Boolean,
    val resumable: Boolean,
)

/**
 * Full-width carousel of featured movies and series: blurred artwork behind the centred poster,
 * title, facts, Play and My List. Advances every 7 s unless [autoAdvance] is off (tests, screenshots).
 */
@Composable
fun HeroCarousel(
    pages: List<HeroPage>,
    onOpen: (ShowcaseItem) -> Unit,
    onPlay: (ShowcaseItem) -> Unit,
    onToggleMyList: (ShowcaseItem) -> Unit,
    modifier: Modifier = Modifier,
    autoAdvance: Boolean = true,
    topBar: @Composable () -> Unit = {},
) {
    val pager = rememberPagerState { pages.size }
    if (autoAdvance && pages.size > 1) {
        LaunchedEffect(pager.currentPage, pages.size) {
            delay(7_000)
            pager.animateScrollToPage((pager.currentPage + 1) % pages.size)
        }
    }
    // Most of the screen, leaving the next shelf in sight on phones.
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val heroHeight = (screenHeight * 0.78f).coerceIn(440f, 600f).dp
    val posterWidth = heroHeight * 0.33f
    Box(modifier.fillMaxWidth().testTag("hero")) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { index ->
            val page = pages[index]
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                Artwork(
                    url = page.item.posterUrl,
                    title = page.item.title,
                    modifier = Modifier.fillMaxSize().blur(40.dp),
                    showsTitleFallback = false,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.6f),
                                0.3f to Color.Black.copy(alpha = 0.25f),
                                0.55f to Color.Black.copy(alpha = 0.3f),
                                1f to MaterialTheme.colorScheme.background,
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 64.dp, bottom = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Poster(
                        url = page.item.posterUrl,
                        title = page.item.title,
                        modifier = Modifier
                            .width(posterWidth)
                            .clip(PosterShape)
                            // TalkBack reads the title and offers to open it.
                            .clickable(onClickLabel = page.item.title) { onOpen(page.item) }
                            .semantics { contentDescription = page.item.title },
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = page.item.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Text(
                        text = page.meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { onPlay(page.item) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp),
                            modifier = Modifier.testTag(if (index == pager.currentPage) "heroPlay" else "heroPlay$index"),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (page.resumable) R.string.resume else R.string.play), fontWeight = FontWeight.SemiBold)
                        }
                        val listState = myListState(page.inMyList)
                        FilledTonalButton(
                            onClick = { onToggleMyList(page.item) },
                            modifier = Modifier.semantics { stateDescription = listState },
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.16f), contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Icon(if (page.inMyList) Icons.Rounded.Check else Icons.Rounded.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.my_list), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars)) { topBar() }
        if (pages.size > 1) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(pages.size) { index ->
                    val selected = index == pager.currentPage
                    val width = animateDpAsState(if (selected) 18.dp else 6.dp, label = "dot").value
                    Box(
                        Modifier
                            .size(width = width, height = 6.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (selected) 0.95f else 0.35f)),
                    )
                }
            }
        }
    }
}
