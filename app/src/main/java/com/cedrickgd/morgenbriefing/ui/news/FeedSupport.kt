package com.cedrickgd.morgenbriefing.ui.news

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cedrickgd.morgenbriefing.MorgenbriefingApp
import com.cedrickgd.morgenbriefing.listen.ListenController
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.glass
import com.cedrickgd.morgenbriefing.util.rememberLinkActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "Feed"

/** The app's "Zuhören" controller, or null in previews (no MorgenbriefingApp there). */
@Composable
internal fun rememberListenController(): ListenController? {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as? MorgenbriefingApp)?.listen }
}

/**
 * `NewsItem.key` of the article being read aloud (or paused) right now, else null. Changes
 * only when that article changes — the reading progress does not recompose the feed.
 */
@Composable
internal fun rememberListeningKey(listen: ListenController?): State<String?> {
    val flow: Flow<String?> = remember(listen) {
        val state = try {
            listen?.state
        } catch (e: Exception) {
            Log.w(TAG, "Listen state unavailable", e)
            null
        }
        state?.map { s -> s.articleKey.takeIf { s.speaking || s.paused } }?.distinctUntilChanged() ?: flowOf(null)
    }
    return flow.collectAsStateWithLifecycle(initialValue = null)
}

/**
 * [FeedActions] for a screen: share, listen and copy are handled here; opening, sources,
 * hiding and asking are the screen's. Null [onHideTopic]/[onAsk] hide those menu items.
 *
 * @param onMessage shows a short message (snackbar), e.g. "Link kopiert".
 */
@Composable
internal fun rememberFeedActions(
    onOpen: (FeedArticle) -> Unit,
    onShowSources: (FeedArticle) -> Unit,
    onMessage: (String) -> Unit,
    onHideTopic: ((FeedArticle) -> Unit)?,
    onAsk: ((FeedArticle) -> Unit)?,
): FeedActions {
    val context = LocalContext.current
    val links = rememberLinkActions()
    val listen = rememberListenController()
    val open by rememberUpdatedState(onOpen)
    val sources by rememberUpdatedState(onShowSources)
    val message by rememberUpdatedState(onMessage)
    val hide by rememberUpdatedState(onHideTopic)
    val ask by rememberUpdatedState(onAsk)
    val canHide = onHideTopic != null
    val canAsk = onAsk != null
    return remember(context, links, listen, canHide, canAsk) {
        FeedActions(
            onOpen = { open(it) },
            onListen = { article ->
                try {
                    listen?.play(article.item) ?: message("Vorlesen ist hier gerade nicht verfügbar.")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not start reading aloud", e)
                    message("Vorlesen klappt gerade nicht.")
                }
            },
            onStopListening = {
                try {
                    listen?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not stop reading aloud", e)
                }
            },
            onShare = { links.shareArticle(it.item) },
            onShowSources = { sources(it) },
            onCopyLink = { article ->
                val copied = copyLink(context, article.item.headline, article.item.url)
                // Android 13+ confirms copying itself; only older versions get our message.
                if (!copied) {
                    message("Kopieren hat nicht geklappt.")
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    message("Link kopiert")
                }
            },
            onHideTopic = if (canHide) { article -> hide?.invoke(article) } else null,
            onAsk = if (canAsk) { article -> ask?.invoke(article) } else null,
        )
    }
}

/** Puts [url] on the clipboard; false when there is nothing to copy or the system refused. */
internal fun copyLink(context: Context, label: String, url: String): Boolean {
    if (url.isBlank()) return false
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    return try {
        clipboard.setPrimaryClip(ClipData.newPlainText(label.ifBlank { "Link" }, url.trim()))
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "Clipboard refused", e)
        false
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Clipboard unavailable", e)
        false
    }
}

/** Shows [text] as the only snackbar; runs [onAction] when its action is tapped. */
internal fun SnackbarHostState.say(scope: CoroutineScope, text: String, action: String? = null, onAction: () -> Unit = {}) {
    scope.launch {
        currentSnackbarData?.dismiss()
        val result = showSnackbar(
            message = text,
            actionLabel = action,
            withDismissAction = false,
            duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onAction()
    }
}

/**
 * Content of the feed's sticky header: parks below the TopGlassBar ([stickBelowBar]) and grows a
 * thick frosted strip behind itself while parked, so the chips sit on one calm glass band
 * together with the bar.
 */
@Composable
internal fun ParkedChipHeader(
    listState: LazyListState,
    key: Any,
    barHeightPx: Int,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val barHeight = with(density) { barHeightPx.toDp() }
    val fadePx = with(density) { 28.dp.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .stickBelowBar(listState, key, barHeightPx),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = stuckFraction(listState, key, barHeightPx, fadePx) }
                .glass(shape = ChipStripShape, level = GlassLevel.Thick),
        )
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(barHeight))
            content()
            Spacer(Modifier.height(6.dp))
        }
    }
}
