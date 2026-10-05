package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.ui.components.motionDisabled

/** What the picture slot of an article shows right now. */
enum class ImageStatus {
    /** No (allowed) picture: the cover is shown. */
    NONE,
    LOADING,
    SHOWN,

    /** The picture could not be loaded; the cover stays visible. */
    FAILED,
}

/** The seed for an article's generated cover. */
internal fun coverKey(item: NewsItem): String = item.key.ifBlank { item.headline }

/**
 * The article's free picture (Wikimedia Commons only, see [safeUrl]) or its generated
 * [CoverArt]. The cover is drawn underneath while the picture loads, so the picture fades in
 * over it and a failed download simply leaves the cover. Size it with [modifier].
 *
 * @param contentDescription null for decorative use (cards: the headline says it all).
 * @param onStatus reports whether the picture is shown — used to show its credit only then.
 */
@Composable
fun ArticleVisual(
    item: NewsItem,
    topic: Topic,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    emojiAlignment: Alignment = Alignment.TopEnd,
    emojiFraction: Float = 0.34f,
    onStatus: ((ImageStatus) -> Unit)? = null,
) {
    val url = remember(item.image) { item.image?.safeUrl() }
    val latestOnStatus by rememberUpdatedState(onStatus)

    Box(modifier) {
        CoverArt(
            topic = topic,
            seedKey = coverKey(item),
            modifier = Modifier.matchParentSize(),
            emojiAlignment = emojiAlignment,
            emojiFraction = emojiFraction,
        )
        if (url != null) {
            val context = LocalContext.current
            val animate = !motionDisabled()
            val request = remember(context, url, animate) {
                ImageRequest.Builder(context)
                    .data(url)
                    .crossfade(animate)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                onState = { state ->
                    val status = when (state) {
                        is AsyncImagePainter.State.Success -> ImageStatus.SHOWN
                        is AsyncImagePainter.State.Error -> ImageStatus.FAILED
                        is AsyncImagePainter.State.Loading -> ImageStatus.LOADING
                        AsyncImagePainter.State.Empty -> ImageStatus.LOADING
                    }
                    latestOnStatus?.invoke(status)
                },
            )
        }
    }

    LaunchedEffect(url) {
        if (url == null) latestOnStatus?.invoke(ImageStatus.NONE)
    }
}
