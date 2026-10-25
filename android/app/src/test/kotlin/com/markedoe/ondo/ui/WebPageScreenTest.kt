package com.markedoe.ondo.ui

import android.content.Context
import android.util.AndroidRuntimeException
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.ui.screens.WebPageScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Ondo's own pages (privacy, support) inside the app. */
@RunWith(AndroidJUnit4::class)
class WebPageScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private class Page(context: Context) : WebView(context) {
        var downs = 0
        var ups = 0

        override fun pageDown(bottom: Boolean): Boolean {
            downs++
            return true
        }

        override fun pageUp(top: Boolean): Boolean {
            ups++
            return true
        }
    }

    /**
     * On a TV the page keeps the focus in Compose, never in the web view: Android would hand the focus on as the view
     * leaves, away from the row of Settings that opened the page. The remote's Up and Down scroll it.
     */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun theRemoteScrollsThePageWhileTheFocusStaysInCompose() {
        val url = "https://www.example.com/privacy.html"
        var page: Page? = null
        compose.setContent {
            WebPageScreen(url, allowed = setOf(url), onBack = {}, makeWebView = { Page(it).also { made -> page = made } })
        }
        compose.waitForIdle()
        compose.onNodeWithTag("webPage").assertIsFocused()
        compose.onNodeWithTag("webPage").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionUp)
        }
        compose.waitForIdle()
        val view = checkNotNull(page)
        assertEquals(2, view.downs)
        assertEquals(1, view.ups)
        assertFalse(view.isFocusable)
        assertFalse(view.hasFocus())
    }

    /** A device without a WebView makes `WebView(context)` throw (MissingWebViewPackageException): the address shows. */
    @Test
    fun aDeviceWithoutAWebViewShowsTheAddress() {
        val url = "https://www.example.com/privacy.html"
        compose.setContent {
            WebPageScreen(url, allowed = setOf(url), onBack = {}, makeWebView = { throw AndroidRuntimeException("No WebView installed") })
        }
        compose.waitForIdle()
        val shown = texts(compose.activity.window.decorView)
        assertEquals(listOf("This page cannot open on this device. Visit $url from a phone or a computer."), shown)
    }
}
