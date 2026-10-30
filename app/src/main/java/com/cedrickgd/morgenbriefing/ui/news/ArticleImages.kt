package com.cedrickgd.morgenbriefing.ui.news

import com.cedrickgd.morgenbriefing.data.model.ArticleImage
import java.net.URI
import java.net.URISyntaxException

private const val MAX_URL_LENGTH = 2_048
private const val HTTPS_PORT = 443

/**
 * The picture's URL when the app may load it: `https` only (newsroom CDNs), no user info, no
 * unusual port, no path tricks. Anything else is null — then the app draws a cover instead.
 */
fun ArticleImage.safeUrl(): String? = safeImageUrl(url)

/** See [safeUrl]; pure, unit-tested. */
internal fun safeImageUrl(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text.length > MAX_URL_LENGTH) return null
    val uri = try {
        URI(text)
    } catch (_: URISyntaxException) {
        return null
    }
    if (!uri.isAbsolute || uri.isOpaque) return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.host.isNullOrBlank()) return null
    if (uri.rawUserInfo != null) return null
    if (uri.port != -1 && uri.port != HTTPS_PORT) return null
    val path = uri.normalize().rawPath ?: return null
    if (path.contains("/../")) return null
    return text
}

/** True for an https request; checked for every hop of a redirect in the image loader. */
internal fun isAllowedImageScheme(scheme: String): Boolean = scheme.equals("https", ignoreCase = true)
