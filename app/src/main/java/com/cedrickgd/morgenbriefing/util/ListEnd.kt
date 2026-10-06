package com.cedrickgd.morgenbriefing.util

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * Keeps the end of a list readable when the floating chrome grows (the mini player appears
 * while the user is at the very end): the list scrolls up by the extra [bottom] room instead of
 * letting the player cover the last lines. Away from the end nothing moves.
 */
@Composable
fun KeepListEndAboveChrome(listState: LazyListState, bottom: Dp) {
    val density = LocalDensity.current
    val bottomPx by rememberUpdatedState(with(density) { bottom.toPx() })
    LaunchedEffect(listState) {
        var previous = bottomPx
        snapshotFlow { bottomPx }.collect { current ->
            val grew = current - previous
            previous = current
            val atEnd = listState.isAtEnd(slackPx = grew)
            if (grew > 0f && atEnd) {
                withFrameNanos { } // let the list measure the larger padding first
                listState.animateScrollBy(grew)
            }
        }
    }
}

/**
 * True when the last item is fully in view, allowing [slackPx] of extra room below it — so the
 * answer is the same whether or not the list has already laid out with a grown bottom padding.
 */
private fun LazyListState.isAtEnd(slackPx: Float): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return false
    if (last.index != info.totalItemsCount - 1) return false
    val distance = last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
    return distance <= slackPx + 1f
}
