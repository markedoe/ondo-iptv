package com.markedoe.ondo.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.myListState
import com.markedoe.ondo.ui.theme.OndoColors
import com.markedoe.ondo.ui.tv.focusFrame
import com.markedoe.ondo.ui.tv.focusMemory
import com.markedoe.ondo.ui.tv.isTelevision
import com.markedoe.ondo.ui.tv.startToEndGradient
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
    /** A wide picture from the title's details, once they are known (the TV shows it full screen). */
    val backdropUrl: String? = null,
    val plot: String? = null,
)

/** The featured titles' frame: rounded, inset from the screen's edges like the shelves below it. */
private val HeroShape = RoundedCornerShape(16.dp)

/** The hero's buttons: as on a TV, a little rounded rather than pills. */
private val HeroButtonShape = RoundedCornerShape(8.dp)

/**
 * The featured movies and series in a rounded frame under [topBar]: the title's artwork, with its title, facts, Play
 * and My List at the bottom over a dark shade, and the page dots inside. Advances every 7 s unless [autoAdvance] is
 * off (tests, screenshots) or the focus is on it (remote, keyboard).
 * Upright screens fill the frame with the poster; landscape ones (a tablet, a phone on its side) show the wide
 * picture, or the blurred poster with the poster at the end, leaving the next shelf in sight. With a remote, Right
 * past the last item and Left past Play turn the page.
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
    val screenWidth = configuration.screenWidthDp
    val wide = screenWidth > screenHeight
    val gutter = rememberShelfMetrics().gutter
    // Upright: a little taller than wide, the next shelf's title in sight below. Landscape: about 60 % of the height.
    val heroHeight = if (wide) {
        (screenHeight * 0.6f).coerceIn(240f, 420f).dp
    } else {
        minOf((screenWidth - 2 * gutter.value) * 1.25f, screenHeight * 0.6f).coerceAtLeast(320f).dp
    }
    val forward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight
    val backward = if (forward == Key.DirectionRight) Key.DirectionLeft else Key.DirectionRight
    fun turn(to: Int) {
        focusPage = to
        scope.launch { pager.animateScrollToPage(to) }
    }
    Column(modifier.fillMaxWidth().testTag("hero").onFocusChanged { focused = it.hasFocus }) {
        Box(Modifier.windowInsetsPadding(WindowInsets.statusBars)) { topBar() }
        Box(
            Modifier
                .padding(horizontal = gutter)
                .fillMaxWidth()
                .height(heroHeight)
                .clip(HeroShape)
                .background(OndoColors.Surface),
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
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
                // Landscape, the poster stands beside the words unless the wide picture loads.
                var backdropFailed by remember(page.backdropUrl) { mutableStateOf(false) }
                val showsPoster = wide && (page.backdropUrl.isNullOrBlank() || backdropFailed)
                // With a remote, the focused button is the bright one (no grey state layer over the white): Play
                // otherwise, as on a touch screen.
                val television = isTelevision()
                val playBright = !television || playFocused || !listFocused
                val buttons: @Composable () -> Unit = {
                    CompositionLocalProvider(LocalRippleConfiguration provides if (television) null else LocalRippleConfiguration.current) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { onPlay(page.item) },
                                shape = HeroButtonShape,
                                colors = heroButtonColors(playBright),
                                contentPadding = PaddingValues(start = 18.dp, end = 22.dp, top = 10.dp, bottom = 10.dp),
                                modifier = Modifier
                                    .focusFrame(HeroButtonShape, grow = 1.06f, frame = false)
                                    .onFocusChanged { playFocused = it.isFocused }
                                    .focusRequester(playFocus)
                                    .focusMemory("hero-play", initial = index == pager.currentPage)
                                    .testTag(if (index == pager.currentPage) "heroPlay" else "heroPlay$index"),
                            ) {
                                PlayIcon(page.item.key == preparingKey, Modifier.size(22.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(if (page.resumable) R.string.resume else R.string.play), fontWeight = FontWeight.SemiBold)
                            }
                            val listState = myListState(page.inMyList)
                            Button(
                                onClick = { onToggleMyList(page.item) },
                                shape = HeroButtonShape,
                                modifier = Modifier
                                    .focusFrame(HeroButtonShape, grow = 1.06f, frame = false)
                                    .onFocusChanged { listFocused = it.isFocused }
                                    .focusMemory("hero-list")
                                    .semantics { stateDescription = listState },
                                colors = heroButtonColors(television && listFocused),
                                contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
                            ) {
                                Icon(if (page.inMyList) Icons.Rounded.Check else Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.my_list), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                // Right past the last item (My List, or the poster beside the buttons) and Left past Play turn the page.
                val turnKeys = Modifier.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || pages.size < 2) return@onPreviewKeyEvent false
                    val last = if (showsPoster) posterFocused else listFocused
                    when {
                        event.key == forward && last -> { turn((index + 1) % pages.size); true }
                        event.key == backward && playFocused && index > 0 -> { turn(index - 1); true }
                        else -> false
                    }
                }
                Box(Modifier.fillMaxSize().then(turnKeys)) {
                    if (wide) {
                        WidePage(page, showsPoster, onOpen, buttons, onPosterFocus = { posterFocused = it }, onBackdropFailed = { backdropFailed = true })
                    } else {
                        TallPage(page, dots = pages.size > 1, onOpen, buttons)
                    }
                }
            }
            if (pages.size > 1) {
                PageDots(
                    pages.size,
                    pager.currentPage,
                    Modifier
                        .align(if (wide) Alignment.BottomEnd else Alignment.BottomCenter)
                        .padding(end = if (wide) 24.dp else 0.dp, bottom = if (wide) 22.dp else 14.dp),
                )
            }
        }
    }
}

/** Which of the featured titles is shown: a dash for the current one, dots for the others. */
@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            val selected = index == current
            val width = animateDpAsState(if (selected) 18.dp else 6.dp, label = "dot").value
            Box(
                Modifier
                    .size(width = width, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (selected) 1f else 0.35f)),
            )
        }
    }
}

/** White with black text when [bright] (the focused or main button), else see-through white on the artwork. */
@Composable
private fun heroButtonColors(bright: Boolean) = if (bright) {
    ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
} else {
    ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White)
}

/** The title, its facts and [buttons], at the bottom start of the frame. */
@Composable
private fun BoxScope.Words(page: HeroPage, titleStyle: androidx.compose.ui.text.TextStyle, bottom: Dp, maxWidth: Dp, buttons: @Composable () -> Unit) {
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .padding(start = 20.dp, end = 20.dp, bottom = bottom)
            .widthIn(max = maxWidth),
    ) {
        Text(
            text = page.item.title,
            style = titleStyle,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = page.meta,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(14.dp))
        buttons()
    }
}

/** An upright screen: the poster fills the frame, the words over a shade at its bottom. */
@Composable
private fun BoxScope.TallPage(page: HeroPage, dots: Boolean, onOpen: (ShowcaseItem) -> Unit, buttons: @Composable () -> Unit) {
    Artwork(
        url = page.item.posterUrl,
        title = page.item.title,
        showsTitleFallback = false,
        modifier = Modifier
            .fillMaxSize()
            // TalkBack reads the title and offers to open it.
            .clickable(onClickLabel = page.item.title) { onOpen(page.item) }
            .semantics { contentDescription = page.item.title },
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0.4f to Color.Transparent, 0.7f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.9f))),
    )
    Words(page, MaterialTheme.typography.headlineMedium, bottom = if (dots) 34.dp else 20.dp, maxWidth = 560.dp, buttons)
}

/**
 * A landscape screen: the wide picture, or the blurred poster with the poster at the end ([showsPoster]); the words
 * at the bottom start over a shade on their side.
 */
@Composable
private fun BoxScope.WidePage(
    page: HeroPage,
    showsPoster: Boolean,
    onOpen: (ShowcaseItem) -> Unit,
    buttons: @Composable () -> Unit,
    onPosterFocus: (Boolean) -> Unit,
    onBackdropFailed: () -> Unit,
) {
    Backdrop(
        url = page.backdropUrl,
        posterUrl = page.item.posterUrl,
        title = page.item.title,
        modifier = Modifier.fillMaxSize().then(
            if (showsPoster) Modifier else Modifier.clickable(onClickLabel = page.item.title) { onOpen(page.item) }.semantics { contentDescription = page.item.title },
        ),
        onFailed = { onBackdropFailed() },
    )
    val ink = Color.Black
    Box(Modifier.fillMaxSize().background(startToEndGradient(0f to ink.copy(alpha = 0.72f), 0.45f to ink.copy(alpha = 0.35f), 0.75f to Color.Transparent)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to ink.copy(alpha = 0.55f))))
    if (showsPoster) {
        Poster(
            url = page.item.posterUrl,
            title = page.item.title,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(top = 20.dp, bottom = 44.dp, end = 24.dp)
                .fillMaxHeight()
                .focusFrame(PosterShape, grow = 1.04f)
                .onFocusChanged { onPosterFocus(it.isFocused) }
                .focusMemory("hero-poster")
                .clip(PosterShape)
                .clickable(onClickLabel = page.item.title) { onOpen(page.item) }
                .semantics { contentDescription = page.item.title },
        )
    }
    Words(page, MaterialTheme.typography.headlineLarge, bottom = 22.dp, maxWidth = 480.dp, buttons)
}
