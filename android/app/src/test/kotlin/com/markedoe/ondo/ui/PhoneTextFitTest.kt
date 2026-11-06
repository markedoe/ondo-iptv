package com.markedoe.ondo.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlin.math.ceil
import androidx.test.core.app.ApplicationProvider
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.R
import com.markedoe.ondo.appLabels
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeScreen
import com.markedoe.ondo.ui.screens.LiveActions
import com.markedoe.ondo.ui.screens.LiveScreen
import com.markedoe.ondo.ui.screens.PersonScreen
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.screenshots.ShowcaseCatalogue
import com.markedoe.ondo.screenshots.ShowcaseImages
import java.util.Locale
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * On a 360 dp phone, in each language of the app, the short words of every surface stay whole on one line: the tab
 * titles (Movies, Series, Live TV), the person page's section title and its My List and Collection buttons, and Home's
 * Play and My List. Arabic and Hindi titles used to break onto a second line, even mid-word (a letter-spaced title is
 * measured narrower than it is laid out), the Collection button was squeezed into a column of letters, and the hero's
 * My List wrapped in Portuguese.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xxhdpi")
class PhoneTextFitTest(private val language: String) {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    // The activity starts once the language is set, as the app's does: its configuration is the language's.
    @get:Rule val compose = createEmptyComposeRule()

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages(): List<Array<Any>> = listOf("en", "fr", "es", "pt-rBR", "zh-rCN", "ar-ldrtl", "hi").map { arrayOf<Any>(it) }
    }

    private enum class Surface { MOVIES, SERIES, LIVE, PERSON, HOME }

    private val tag get() = language.substringBefore("-ldrtl").replace("-r", "-")
    private val locale: Locale get() = Locale.forLanguageTag(tag)
    private val defaultLocale = Locale.getDefault()

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers("+$language")
        Locale.setDefault(locale)
        ShowcaseImages.install(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        ShowcaseImages.reset()
        Locale.setDefault(defaultLocale)
    }

    private val strings: Context
        get() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
        }

    /**
     * How many lines each [text] on screen takes, from the height it is drawn at: the layout semantics give is made
     * anew at the widest width allowed, not at the width the text was laid out at.
     */
    private fun lines(text: String): List<Int> =
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.singleOrNull()?.let { layout ->
                val lineHeight = layout.size.height.toFloat() / layout.lineCount
                maxOf(layout.lineCount, (node.size.height / lineHeight).roundToInt())
            }
        }

    /**
     * A title as wide as its text sits on one line: Compose sizes a title to the width it measures for it, then lays
     * it out at that width, so a title measured narrower than it is laid out breaks onto a second line. This happened
     * to letter-spaced Arabic and Hindi ("الأفلا / م"). Every large style of the theme, as the app's language sets it.
     */
    @Test
    fun titlesFitTheWidthTheyAreMeasuredAt() {
        lateinit var styles: Map<String, TextStyle>
        lateinit var density: Density
        lateinit var fonts: FontFamily.Resolver
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity -> activity.setContent {
            OndoTheme {
                val type = MaterialTheme.typography
                styles = mapOf(
                    "headlineSmall" to type.headlineSmall,
                    "headlineSmall bold" to type.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    "headlineMedium" to type.headlineMedium,
                    "headlineLarge" to type.headlineLarge,
                    "displaySmall" to type.displaySmall,
                )
                density = LocalDensity.current
                fonts = LocalFontFamilyResolver.current
            }
        } }
        compose.waitForIdle()
        scenario.close()
        val problems = mutableListOf<String>()
        for (id in listOf(R.string.movies, R.string.series, R.string.live_tv, R.string.my_list)) {
            val text = strings.getString(id)
            for ((name, style) in styles) {
                val localized = style.copy(localeList = LocaleList(tag))
                val intrinsics = ParagraphIntrinsics(text, localized, annotations = emptyList(), density = density, fontFamilyResolver = fonts)
                val width = ceil(intrinsics.maxIntrinsicWidth).toInt()
                val paragraph = Paragraph(intrinsics, Constraints(maxWidth = width), maxLines = Int.MAX_VALUE)
                if (paragraph.lineCount != 1) problems += "\"$text\" in $name: ${paragraph.lineCount} lines at its own width ($width px)"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun shortWordsStayOnOneLineOnEverySurface() {
        val catalogue = ShowcaseCatalogue.load(tag.substringBefore('-'))
        val state = catalogue.state()
        val now = ShowcaseCatalogue.NOW
        val words = strings
        val labels = appLabels(words.getString(R.string.kind_movie), words.getString(R.string.kind_series), words.getString(R.string.hero_new_episode), locale)
        var surface by mutableStateOf(Surface.MOVIES)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity -> activity.setContent {
            OndoTheme {
                CompositionLocalProvider(LocalCarouselAutoAdvance provides false) {
                    TelevisionFrame {
                        when (surface) {
                            Surface.MOVIES -> VodScreen(UiMappers.vod(state, series = false, title = words.getString(R.string.movies), now = now), VodActions())
                            Surface.SERIES -> VodScreen(UiMappers.vod(state, series = true, title = words.getString(R.string.series), now = now), VodActions())
                            Surface.LIVE -> LiveScreen(UiMappers.live(state, null, false, now), LiveActions())
                            Surface.PERSON -> PersonScreen(UiMappers.person(state, catalogue.screens.person), onOpen = {}, onBack = {})
                            Surface.HOME -> HomeScreen(UiMappers.home(state, labels, now, catalogue.kept()).copy(channels = emptyList()), HomeActions())
                        }
                    }
                }
            }
        } }
        val checked = mapOf(
            Surface.MOVIES to listOf(R.string.movies),
            Surface.SERIES to listOf(R.string.series),
            Surface.LIVE to listOf(R.string.live_tv),
            Surface.PERSON to listOf(R.string.movies, R.string.my_list, R.string.collection),
            Surface.HOME to listOf(R.string.play, R.string.my_list),
        )
        val problems = mutableListOf<String>()
        for ((shown, texts) in checked) {
            surface = shown
            compose.waitForIdle()
            for (id in texts) {
                val text = words.getString(id)
                val found = lines(text)
                if (found.isEmpty()) problems += "$shown: \"$text\" not shown"
                found.filter { it != 1 }.forEach { problems += "$shown: \"$text\" on $it lines" }
            }
        }
        scenario.close()
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
