package com.markedoe.ondo.ui

import android.util.AndroidRuntimeException
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.ui.screens.WebPageScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Ondo's own pages (privacy, support) inside the app. */
@RunWith(AndroidJUnit4::class)
class WebPageScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
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
