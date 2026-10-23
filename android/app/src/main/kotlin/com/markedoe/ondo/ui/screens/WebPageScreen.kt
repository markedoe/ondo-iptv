package com.markedoe.ondo.ui.screens

import android.content.Context
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
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
import com.markedoe.ondo.R

/**
 * One of Ondo's own pages (privacy policy, support) inside the app, for a TV: Google wants no browser opened there
 * (TV-WB). Only [allowed] addresses open, the anchors of the page included: no link leads elsewhere (the website's
 * home page carries the donation links, which never show inside the apps). Back goes back within the page first.
 * The remote's arrows scroll it.
 */
@Composable
fun WebPageScreen(url: String, allowed: Set<String>, onBack: () -> Unit, makeWebView: (Context) -> WebView = ::WebView) {
    var page by remember { mutableStateOf<WebView?>(null) }
    BackHandler {
        val view = page
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }
    AndroidView(
        factory = { context ->
            // A device without a WebView (some TVs) throws here: the page's address is shown instead.
            val web = try {
                makeWebView(context)
            } catch (_: RuntimeException) {
                return@AndroidView TextView(context).apply {
                    text = context.getString(R.string.web_page_unavailable, url)
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    setPadding(64, 64, 64, 64)
                }
            }
            web.apply {
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
        onRelease = { (it as? WebView)?.destroy() },
        modifier = Modifier.fillMaxSize().testTag("webPage"),
    )
}
