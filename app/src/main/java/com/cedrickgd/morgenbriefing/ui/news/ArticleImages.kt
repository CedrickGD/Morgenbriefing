package com.cedrickgd.morgenbriefing.ui.news

import com.cedrickgd.morgenbriefing.data.model.ArticleImage
import java.net.URI
import java.net.URISyntaxException

/** The only host the app loads pictures from: Wikimedia's file server. */
internal const val COMMONS_HOST = "upload.wikimedia.org"

/** Files of Wikimedia Commons (free licenses); other wikis on the same host may host non-free files. */
private const val COMMONS_PATH = "/wikipedia/commons/"

private const val MAX_URL_LENGTH = 2_048

/**
 * The picture's URL when the app may load it: `https`, host exactly `upload.wikimedia.org`,
 * a Wikimedia Commons file path, no user info and no unusual port. Anything else is null —
 * then the app draws a cover instead. We never load press photos or other hosts.
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
    if (!COMMONS_HOST.equals(uri.host, ignoreCase = true)) return null
    if (uri.rawUserInfo != null) return null
    if (uri.port != -1 && uri.port != HTTPS_PORT) return null
    val path = uri.normalize().rawPath ?: return null
    if (!path.startsWith(COMMONS_PATH) || path.contains("/../")) return null
    return text
}

/** True for an https request to Wikimedia's file server; checked for every hop of a redirect. */
internal fun isAllowedImageHost(scheme: String, host: String): Boolean =
    scheme.equals("https", ignoreCase = true) && host.equals(COMMONS_HOST, ignoreCase = true)

/**
 * Attribution shown next to a large picture: "Bild: Raimond Spekking · CC BY-SA 4.0".
 * Falls back to "Wikimedia Commons" when the author is unknown.
 */
internal fun ArticleImage.creditLine(): String {
    val author = credit.trim().ifEmpty { "Wikimedia Commons" }
    val licence = license.trim()
    return if (licence.isEmpty()) "Bild: $author" else "Bild: $author · $licence"
}

private const val HTTPS_PORT = 443
