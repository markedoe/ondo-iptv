package com.markedoe.ondo.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The time left on a movie or an episode: minutes under an hour, hours and minutes from an hour on. */
@RunWith(RobolectricTestRunner::class)
class TimeLeftTest {
    private fun resources(tag: String): Resources {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(configuration).resources
    }

    @Test
    fun underAnHourInMinutes() {
        assertEquals("45 min left", timeLeft(resources("en"), 45))
        assertEquals("59 min left", timeLeft(resources("en"), 59))
        assertEquals("45 min restantes", timeLeft(resources("fr"), 45))
    }

    @Test
    fun fromAnHourOnInHoursAndMinutes() {
        val en = resources("en")
        assertEquals("1 h left", timeLeft(en, 60))
        assertEquals("1 h 23 min left", timeLeft(en, 83))
        assertEquals("2 h 5 min left", timeLeft(en, 125))
        assertEquals("1 h 23 min restantes", timeLeft(resources("fr"), 83))
        assertEquals("1 h restante", timeLeft(resources("fr"), 60))
        assertEquals("2 h restantes", timeLeft(resources("fr"), 120))
        assertEquals("Quedan 1 h 23 min", timeLeft(resources("es"), 83))
        assertEquals("Queda 1 h", timeLeft(resources("es"), 60))
        assertEquals("1 h 23 min restantes", timeLeft(resources("pt-BR"), 83))
        assertEquals("剩余 1 小时 23 分钟", timeLeft(resources("zh-CN"), 83))
        assertEquals("1 घंटा 23 मिनट बाकी", timeLeft(resources("hi"), 83))
        assertEquals("2 घंटे बाकी", timeLeft(resources("hi"), 120))
        // Arabic may write its own digits: the words only.
        val ar = resources("ar")
        assertTrue(timeLeft(ar, 83).let { it.startsWith("متبقٍ ساعة و") && it.endsWith(" دقيقة") })
        assertTrue(timeLeft(ar, 185).contains(" ساعات و"))
    }
}
