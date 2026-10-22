package com.markedoe.ondo.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.myListState
import com.markedoe.ondo.ui.tv.focusFrame
import com.markedoe.ondo.ui.tv.focusMemory
import com.markedoe.ondo.ui.tv.isTelevision
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * Full-width carousel of featured movies and series: blurred artwork behind the poster, title, facts, Play and My
 * List. Advances every 7 s unless [autoAdvance] is off (tests, screenshots) or the focus is on it (remote, keyboard).
 * Tall screens centre the poster above the title; short landscape ones (a TV, a phone on its side) put the title and
 * buttons beside it, leaving the next shelf in sight. With a remote, Right past My List and Left past Play turn the
 * page.
 */
@Composable
fun HeroCarousel(
    pages: List<HeroPage>,
    onOpen: (ShowcaseItem) -> Unit,
    onPlay: (ShowcaseItem) -> Unit,
    onToggleMyList: (ShowcaseItem) -> Unit,
    modifier: Modifier = Modifier,
    autoAdvance: Boolean = true,
    /** The title whose Play is getting ready: its button shows progress. */
    preparingKey: String? = null,
    topBar: @Composable () -> Unit = {},
) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    /** The page whose Play takes the focus once it is shown (the remote turned the page). */
    var focusPage by remember { mutableStateOf<Int?>(null) }
    if (autoAdvance && pages.size > 1 && !focused) {
        LaunchedEffect(pager.currentPage, pages.size) {
            delay(7_000)
            pager.animateScrollToPage((pager.currentPage + 1) % pages.size)
        }
    }
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp
    val wide = screenHeight < 600 && configuration.screenWidthDp > screenHeight
    // Most of the screen on a tall one, leaving the next shelf in sight; about 60 % of a short one.
    val heroHeight = if (wide) (screenHeight * 0.6f).coerceIn(240f, 400f).dp else (screenHeight * 0.78f).coerceIn(440f, 600f).dp
    val forward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight
    val backward = if (forward == Key.DirectionRight) Key.DirectionLeft else Key.DirectionRight
    fun turn(to: Int) {
        focusPage = to
        scope.launch { pager.animateScrollToPage(to) }
    }
    Box(modifier.fillMaxWidth().testTag("hero").onFocusChanged { focused = it.hasFocus }) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { index ->
            val page = pages[index]
            val playFocus = remember { FocusRequester() }
            LaunchedEffect(focusPage, pager.currentPage) {
                if (focusPage == index && pager.currentPage == index) {
                    runCatching { playFocus.requestFocus() }
                    focusPage = null
                }
            }
            var listFocused by remember { mutableStateOf(false) }
            var playFocused by remember { mutableStateOf(false) }
            var posterFocused by remember { mutableStateOf(false) }
            // With a remote, the focused button is the bright one (no grey state layer over the white): Play
            // otherwise, as on a touch screen.
            val television = isTelevision()
            val playBright = !television || playFocused || !listFocused
            val buttons: @Composable () -> Unit = {
                CompositionLocalProvider(LocalRippleConfiguration provides if (television) null else LocalRippleConfiguration.current) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { onPlay(page.item) },
                            colors = heroButtonColors(playBright),
                            contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp),
                            modifier = Modifier
                                .focusFrame(CircleShape, grow = 1.06f, frame = false)
                                .onFocusChanged { playFocused = it.isFocused }
                                .focusRequester(playFocus)
                                .focusMemory("hero-play", initial = index == pager.currentPage)
                                .testTag(if (index == pager.currentPage) "heroPlay" else "heroPlay$index"),
                        ) {
                            PlayIcon(page.item.key == preparingKey, Modifier.size(24.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (page.resumable) R.string.resume else R.string.play), fontWeight = FontWeight.SemiBold)
                        }
                        val listState = myListState(page.inMyList)
                        Button(
                            onClick = { onToggleMyList(page.item) },
                            modifier = Modifier
                                .focusFrame(CircleShape, grow = 1.06f, frame = false)
                                .onFocusChanged { listFocused = it.isFocused }
                                .focusMemory("hero-list")
                                .semantics { stateDescription = listState },
                            colors = heroButtonColors(television && listFocused),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Icon(if (page.inMyList) Icons.Rounded.Check else Icons.Rounded.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.my_list), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            // Right past the last item (My List, or the poster beside the buttons) and Left past Play turn the page.
            val turnKeys = Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || pages.size < 2) return@onPreviewKeyEvent false
                val last = if (wide) posterFocused else listFocused
                when {
                    event.key == forward && last -> { turn((index + 1) % pages.size); true }
                    event.key == backward && playFocused && index > 0 -> { turn(index - 1); true }
                    else -> false
                }
            }
            Box(Modifier.fillMaxWidth().height(heroHeight).then(turnKeys)) {
                BlurredPoster(url = page.item.posterUrl, title = page.item.title, modifier = Modifier.fillMaxSize())
                if (wide) {
                    WidePage(page, onOpen, buttons, onPosterFocus = { posterFocused = it })
                } else {
                    TallPage(page, heroHeight * 0.33f, onOpen, buttons)
                }
            }
        }
        Box(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars)) { topBar() }
        if (pages.size > 1) {
            Row(
                modifier = Modifier.align(if (wide) Alignment.BottomStart else Alignment.BottomCenter).padding(start = if (wide) 24.dp else 0.dp, bottom = 12.dp),
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

/** White with black text when [bright] (the focused or main button), else see-through white on the artwork. */
@Composable
private fun heroButtonColors(bright: Boolean) = if (bright) {
    ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
} else {
    ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.16f), contentColor = Color.White)
}

/** Poster centred above the title, facts and buttons (phones and tablets held upright). */
@Composable
private fun TallPage(page: HeroPage, posterWidth: Dp, onOpen: (ShowcaseItem) -> Unit, buttons: @Composable () -> Unit) {
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
        buttons()
    }
}

/** Title, facts and buttons beside the poster (a TV, a phone on its side, a short landscape tablet). */
@Composable
private fun WidePage(page: HeroPage, onOpen: (ShowcaseItem) -> Unit, buttons: @Composable () -> Unit, onPosterFocus: (Boolean) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.75f),
                    0.55f to Color.Black.copy(alpha = 0.35f),
                    1f to Color.Black.copy(alpha = 0.1f),
                ),
            )
            .background(Brush.verticalGradient(0.7f to Color.Transparent, 1f to MaterialTheme.colorScheme.background)),
    )
    Row(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 24.dp)) {
            Text(
                text = page.item.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = page.meta,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(20.dp))
            buttons()
        }
        Poster(
            url = page.item.posterUrl,
            title = page.item.title,
            modifier = Modifier
                .fillMaxHeight()
                .focusFrame(PosterShape, grow = 1.04f)
                .onFocusChanged { onPosterFocus(it.isFocused) }
                .focusMemory("hero-poster")
                .clip(PosterShape)
                .clickable(onClickLabel = page.item.title) { onOpen(page.item) }
                .semantics { contentDescription = page.item.title },
        )
    }
}
