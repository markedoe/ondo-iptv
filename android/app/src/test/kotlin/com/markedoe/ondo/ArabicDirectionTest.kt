package com.markedoe.ondo

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * In right-to-left text, a colon between a Latin word and an Arabic one takes the Arabic direction and is drawn on the
 * word's left: the category code "FR:" that the Arabic help quotes would read ":FR", unlike the category names it
 * describes. The code keeps its colon on its right.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArabicDirectionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val arabic = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("ar")) })
    private val measurer = TextMeasurer(createFontFamilyResolver(context), Density(context), LayoutDirection.Rtl)

    @Test
    fun aCategoryCodeKeepsItsColonOnItsRight() {
        val texts = R.string::class.java.fields.associate { it.name to arabic.resources.getString(it.getInt(null)) }
        val quoting = texts.filterValues { "FR:" in it }
        assertTrue("the Arabic help quotes the code FR:", quoting.isNotEmpty())
        val problems = quoting.mapNotNull { (name, text) ->
            val layout = measurer.measure(AnnotatedString(text), constraints = Constraints(maxWidth = 100_000))
            val code = text.indexOf("FR:")
            val letter = layout.getBoundingBox(code + 1)
            val colon = layout.getBoundingBox(code + 2)
            if (colon.left >= letter.right - 0.5f) null else "$name: the colon is drawn left of FR (x ${colon.left} < ${letter.right})"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
