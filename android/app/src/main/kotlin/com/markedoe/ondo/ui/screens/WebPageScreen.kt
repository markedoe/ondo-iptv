package com.markedoe.ondo.ui.screens

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView

/**
 * One of Ondo's own pages (privacy policy, support) inside the app, for a TV: Google wants no browser opened there
 * (TV-WB). Only [allowed] addresses open, the anchors of the page included: no link leads elsewhere (the website's
 * home page carries the donation links, which never show inside the apps). Back goes back within the page first.
 * The remote's arrows scroll it.
 */
@Composable
fun WebPageScreen(url: String, allowed: Set<String>, onBack: () -> Unit) {
    var page by remember { mutableStateOf<WebView?>(null) }
    BackHandler {
        val view = page
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                // Static pages: no script, no files.
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.buildUpon().fragment(null).build().toString() !in allowed
                }
                isFocusable = true
                isFocusableInTouchMode = true
                loadUrl(url)
                requestFocus()
            }.also { page = it }
        },
        onRelease = { it.destroy() },
        modifier = Modifier.fillMaxSize().testTag("webPage"),
    )
}
