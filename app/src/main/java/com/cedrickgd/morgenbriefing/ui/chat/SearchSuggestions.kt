package com.cedrickgd.morgenbriefing.ui.chat

import android.content.Context
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import com.cedrickgd.morgenbriefing.R
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark

/**
 * Google's Search Suggestions under an answer Gemini grounded with Google Search (GE §6.4, §11,
 * docs/AI-PROVIDERS.md D4): Google's own HTML and CSS, shown exactly as delivered — never
 * restyled, as wide as the answer, whenever the answer is shown (also from history). A plain
 * [WebView] without JavaScript, file or content access and with a transparent background; every
 * tap on a suggestion goes straight to Google's results page via [onOpenLink] (a Custom Tab), no
 * interstitial.
 *
 * Light and dark come from the HTML's own `prefers-color-scheme` rules. The WebView reports the
 * scheme of the theme it was created with (`isLightTheme`, Android 10+), so it is created with a
 * light or dark theme that follows the app's look — not the system's — and recreated when that
 * changes.
 */
@Composable
internal fun SearchSuggestions(html: String, onOpenLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val dark = isGlassDark()
    val open by rememberUpdatedState(onOpenLink)
    key(dark) {
        AndroidView(
            factory = { context ->
                val themed = ContextThemeWrapper(context, if (dark) R.style.Theme_Morgenbriefing_WebDark else R.style.Theme_Morgenbriefing_WebLight)
                suggestionsWebView(themed) { url -> open(url) }
            },
            update = { view ->
                if (view.tag != html) {
                    view.tag = html
                    view.loadDataWithBaseURL(GOOGLE_BASE_URL, html, "text/html", "utf-8", null)
                }
            },
            onRelease = { view -> view.destroy() },
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Google-Suchvorschläge zu dieser Antwort" },
        )
    }
}

/** Base URL of the suggestions document: relative links and assets resolve against Google. */
private const val GOOGLE_BASE_URL = "https://www.google.com"

private fun suggestionsWebView(context: Context, onLink: (String) -> Unit): WebView =
    WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                onLink(request.url.toString())
                return true
            }

            // Old WebView builds may still call the String overload; both open the link outside.
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                onLink(url)
                return true
            }
        }
    }
