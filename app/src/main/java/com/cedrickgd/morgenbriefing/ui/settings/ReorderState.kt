package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drag-to-reorder for a contiguous run of items inside a [LazyColumn][androidx.compose.foundation.lazy.LazyColumn]
 * (the topic rows of the settings list). Items are identified by their lazy-list key; only keys
 * accepted by [isReorderable] take part, everything else (headers, other cards) stays put.
 *
 * While dragging, the dragged row follows the finger via [offsetOf] (a graphics-layer
 * translation, no relayout), the others make room through `Modifier.animateItem()`, and the list
 * auto-scrolls when the row reaches the top or bottom edge. When the finger lifts, the row
 * settles into its slot with a spring (instantly with reduced motion).
 */
@Stable
internal class ReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val edgeZonePx: Float,
    private val maxScrollPerFramePx: Float,
    private val isReorderable: (Any) -> Boolean,
    /** Whether the row [key] has a reorderable neighbour below ([down]) or above it. */
    private val canMove: (key: Any, down: Boolean) -> Boolean,
    private val onMove: (fromKey: Any, toKey: Any) -> Unit,
    private val onSwap: () -> Unit,
    private val onDrop: () -> Unit,
    private val reduceMotion: () -> Boolean,
) {
    /** Key of the row under the finger, or null. */
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    /** Key of the row that is springing back into its slot after a drop. */
    var settlingKey by mutableStateOf<Any?>(null)
        private set

    private var initialOffset by mutableIntStateOf(0)
    private var dragDelta by mutableFloatStateOf(0f)
    private val settle = Animatable(0f)

    /** List index the dragged row will have once the last move is laid out. */
    private var awaitingIndex: Int? = null

    val isDragging: Boolean get() = draggingKey != null

    private val draggingItem: LazyListItemInfo?
        get() = draggingKey?.let { key -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } }

    /**
     * Translation of the item with [key]: follows the finger, or the settle animation. The first
     * and last row cannot be pulled beyond their slot, so the dragged row never leaves the group.
     */
    fun offsetOf(key: Any): Float = when (key) {
        draggingKey -> draggingItem?.let { item ->
            val raw = initialOffset + dragDelta - item.offset
            when {
                raw > 0f && !canMove(key, true) -> 0f
                raw < 0f && !canMove(key, false) -> 0f
                else -> raw
            }
        } ?: 0f
        settlingKey -> settle.value
        else -> 0f
    }

    fun onDragStart(key: Any) {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        scope.launch { settle.snapTo(0f) }
        settlingKey = null
        draggingKey = key
        initialOffset = item.offset
        dragDelta = 0f
        awaitingIndex = null
    }

    fun onDrag(deltaY: Float) {
        if (draggingKey == null) return
        dragDelta += deltaY
        swapIfNeeded()
    }

    fun onDragEnd() {
        val key = draggingKey ?: return
        val remaining = offsetOf(key)
        draggingKey = null
        dragDelta = 0f
        awaitingIndex = null
        onDrop()
        if (reduceMotion() || remaining == 0f) return
        settlingKey = key
        scope.launch {
            settle.snapTo(remaining)
            settle.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            if (settlingKey == key) settlingKey = null
        }
    }

    /**
     * Pixels to scroll this frame so the dragged row stays reachable: positive near the bottom
     * edge, negative near the top, 0 elsewhere — and 0 once the row is first/last in its group,
     * so the list does not run away from it. Faster the deeper the row reaches into the edge.
     */
    fun autoScrollStep(): Float {
        val item = draggingItem ?: return 0f
        val info = listState.layoutInfo
        val top = item.offset + offsetOf(item.key)
        val bottom = top + item.size
        val viewportTop = info.viewportStartOffset + info.beforeContentPadding
        val viewportBottom = info.viewportEndOffset - info.afterContentPadding
        val intoBottom = bottom - (viewportBottom - edgeZonePx)
        val intoTop = (viewportTop + edgeZonePx) - top
        return when {
            dragDelta > 0f && intoBottom > 0f && canMove(item.key, true) ->
                maxScrollPerFramePx * (intoBottom / edgeZonePx).coerceIn(0.1f, 1f)
            dragDelta < 0f && intoTop > 0f && canMove(item.key, false) ->
                -maxScrollPerFramePx * (intoTop / edgeZonePx).coerceIn(0.1f, 1f)
            else -> 0f
        }
    }

    /** After the list scrolled under the finger, the row may now hover over a new neighbour. */
    fun afterAutoScroll() = swapIfNeeded()

    /**
     * Moves the dragged row past a neighbour once its leading edge crosses that neighbour's
     * middle (bottom edge when moving down, top edge when moving up). Comparing edges with the
     * neighbour's middle is stable for rows of different heights: right after a swap the
     * opposite condition cannot hold, so rows never flip back and forth.
     */
    private fun swapIfNeeded() {
        val dragging = draggingItem ?: return
        val expected = awaitingIndex
        if (expected != null) {
            // The previous move is not laid out yet; layout info would still show the old order.
            if (dragging.index != expected) return
            awaitingIndex = null
        }
        val top = dragging.offset + offsetOf(dragging.key)
        val bottom = top + dragging.size
        val candidates = listState.layoutInfo.visibleItemsInfo.filter { it.key != dragging.key && isReorderable(it.key) }
        val target = candidates
            .filter { it.index > dragging.index && bottom > it.offset + it.size / 2f }
            .maxByOrNull { it.index }
            ?: candidates
                .filter { it.index < dragging.index && top < it.offset + it.size / 2f }
                .minByOrNull { it.index }
            ?: return

        val first = listState.firstVisibleItemIndex
        if (dragging.index == first || target.index == first) {
            // The lazy list keeps its first visible item anchored by key; re-anchor so the swap
            // does not scroll the list.
            scope.launch {
                listState.scrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }
        }
        awaitingIndex = target.index
        onMove(dragging.key, target.key)
        onSwap()
    }
}

/**
 * Remembers a [ReorderState] for [listState] and runs the edge auto-scroll while dragging.
 * Callbacks may change between recompositions; the latest ones are used.
 */
@Composable
internal fun rememberReorderState(
    listState: LazyListState,
    reduceMotion: Boolean,
    isReorderable: (Any) -> Boolean,
    canMove: (key: Any, down: Boolean) -> Boolean,
    onMove: (fromKey: Any, toKey: Any) -> Unit,
    onSwap: () -> Unit,
    onDrop: () -> Unit,
): ReorderState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val latestIsReorderable by rememberUpdatedState(isReorderable)
    val latestCanMove by rememberUpdatedState(canMove)
    val latestOnMove by rememberUpdatedState(onMove)
    val latestOnSwap by rememberUpdatedState(onSwap)
    val latestOnDrop by rememberUpdatedState(onDrop)
    val latestReduceMotion by rememberUpdatedState(reduceMotion)
    val state = remember(listState, scope, density) {
        ReorderState(
            listState = listState,
            scope = scope,
            edgeZonePx = with(density) { 72.dp.toPx() },
            maxScrollPerFramePx = with(density) { 14.dp.toPx() },
            isReorderable = { latestIsReorderable(it) },
            canMove = { key, down -> latestCanMove(key, down) },
            onMove = { from, to -> latestOnMove(from, to) },
            onSwap = { latestOnSwap() },
            onDrop = { latestOnDrop() },
            reduceMotion = { latestReduceMotion },
        )
    }
    val dragging = state.isDragging
    LaunchedEffect(state, dragging) {
        if (!dragging) return@LaunchedEffect
        while (isActive) {
            val step = state.autoScrollStep()
            if (step != 0f) {
                listState.scrollBy(step)
                state.afterAutoScroll()
            }
            withFrameNanos { }
        }
    }
    return state
}
