package com.cedrickgd.morgenbriefing.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.cedrickgd.morgenbriefing.data.model.NewsItem

/** Opening and sharing links. Brief URLs come from a JSON file, so only http(s) is ever opened. */
internal object Links {
    const val OPEN_METEO = "https://open-meteo.com/"
    const val SOURCE_CODE = "https://github.com/CedrickGD/Morgenbriefing"
    const val NEW_GITHUB_TOKEN = "https://github.com/settings/personal-access-tokens/new"

    /** True for an absolute `http(s)://host…` URL without whitespace. Pure, unit-tested. */
    fun isWebUrl(url: String): Boolean {
        val text = url.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return false
        val scheme = text.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "https" && scheme != "http") return false
        val host = text.substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
            .substringBefore(':')
        return host.isNotBlank()
    }

    /**
     * Opens [url] in a Custom Tab tinted with [toolbarColor] (share button on). Falls back to any
     * browser via ACTION_VIEW and finally tells the user that nothing could open it.
     */
    fun openInApp(context: Context, url: String, @ColorInt toolbarColor: Int, darkTheme: Boolean) {
        val uri = webUriOrNull(url) ?: return toast(context, "Dieser Link lässt sich nicht öffnen.")
        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(toolbarColor)
            .setNavigationBarColor(toolbarColor)
            .build()
        val tab = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(colors)
            .setColorScheme(if (darkTheme) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .build()
        if (context.findActivity() == null) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            tab.launchUrl(context, uri)
            return
        } catch (e: ActivityNotFoundException) {
            // No browser that understands Custom Tabs — try a plain VIEW intent.
        } catch (e: SecurityException) {
            // Misbehaving default browser; try a plain VIEW intent.
        }
        openExternal(context, uri)
    }

    /** Opens [url] in the default browser. */
    fun openExternal(context: Context, url: String) {
        val uri = webUriOrNull(url) ?: return toast(context, "Dieser Link lässt sich nicht öffnen.")
        openExternal(context, uri)
    }

    /** System share sheet with "headline\nurl" as plain text. */
    fun share(context: Context, headline: String, url: String) {
        val text = listOf(headline.trim(), url.trim()).filter { it.isNotEmpty() }.joinToString("\n")
        if (text.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (headline.isNotBlank()) putExtra(Intent.EXTRA_TITLE, headline.trim())
        }
        val chooser = Intent.createChooser(send, "Meldung teilen")
        if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            toast(context, "Teilen ist auf diesem Gerät gerade nicht möglich.")
        }
    }

    private fun openExternal(context: Context, uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        if (context.findActivity() == null) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            toast(context, "Kein Browser gefunden, der den Link öffnen kann.")
        } catch (e: SecurityException) {
            toast(context, "Kein Browser gefunden, der den Link öffnen kann.")
        }
    }

    private fun webUriOrNull(url: String): Uri? = if (isWebUrl(url)) url.trim().toUri() else null

    private fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

/** Link handling bound to the current theme, for screens and news cards. */
@Stable
class LinkActions internal constructor(
    private val context: Context,
    @param:ColorInt private val toolbarColor: Int,
    private val darkTheme: Boolean,
) {
    /** In-app browser (Custom Tab). */
    fun open(url: String) = Links.openInApp(context, url, toolbarColor, darkTheme)

    fun openArticle(item: NewsItem) = open(item.url)

    fun shareArticle(item: NewsItem) = Links.share(context, item.headline, item.url)
}

/** [LinkActions] whose Custom Tab toolbar matches the current theme's surface color. */
@Composable
fun rememberLinkActions(): LinkActions {
    val context = LocalContext.current
    val surface = MaterialTheme.colorScheme.surface
    val toolbarColor = surface.toArgb()
    val darkTheme = surface.luminance() < 0.5f
    return remember(context, toolbarColor, darkTheme) { LinkActions(context, toolbarColor, darkTheme) }
}
