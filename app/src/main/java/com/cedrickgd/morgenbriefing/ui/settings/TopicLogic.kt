package com.cedrickgd.morgenbriefing.ui.settings

import com.cedrickgd.morgenbriefing.data.model.Topics
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Topic list operations behind Settings → "Themen". Pure (no Android), unit-tested.
 * Orders are lists of [Topics] ids; hidden topics are a set of ids.
 */
internal object TopicList {

    /**
     * The catalogue in the user's order: ids the catalogue does not know are dropped,
     * duplicates removed, and catalogue topics missing from [order] appended in catalogue order.
     */
    fun normalize(order: List<String>, catalogue: List<String> = Topics.DEFAULT_ORDER): List<String> {
        val known = catalogue.toSet()
        val ordered = order.filter { it in known }.distinct()
        return ordered + catalogue.filterNot { it in ordered }
    }

    /** Moves the element at [from] to [to] (both clamped); returns a new list. */
    fun move(order: List<String>, from: Int, to: Int): List<String> {
        if (order.isEmpty()) return order
        val source = from.coerceIn(order.indices)
        val target = to.coerceIn(order.indices)
        if (source == target) return order
        return order.toMutableList().apply { add(target, removeAt(source)) }
    }

    /** Moves [id] by [delta] positions (negative = up). Unknown ids leave the order unchanged. */
    fun moveBy(order: List<String>, id: String, delta: Int): List<String> {
        val index = order.indexOf(id)
        if (index < 0) return order
        return move(order, index, index + delta)
    }

    /** Topics of [order] that are shown. */
    fun visibleCount(order: List<String>, hidden: Set<String>): Int = order.count { it !in hidden }

    /** False only for the last visible topic: at least one topic always stays on. */
    fun canHide(order: List<String>, hidden: Set<String>, id: String): Boolean =
        id in hidden || visibleCount(order, hidden) > 1

    /**
     * New hidden set after switching [id] on or off. Hiding the last visible topic is refused
     * (the set comes back unchanged); unknown ids are ignored. Ids no longer in [order] are
     * dropped from the result, so the stored set does not collect leftovers.
     */
    fun setVisible(order: List<String>, hidden: Set<String>, id: String, visible: Boolean): Set<String> {
        val current = hidden.filterTo(LinkedHashSet()) { it in order }
        if (id !in order) return current
        return when {
            visible -> current - id
            canHide(order, current, id) -> current + id
            else -> current
        }
    }

    /** Whether [order] already is the catalogue order (the reset button is disabled then). */
    fun isDefaultOrder(order: List<String>, catalogue: List<String> = Topics.DEFAULT_ORDER): Boolean =
        normalize(order, catalogue) == catalogue
}

/** The article text size slider (Settings → "Lesen"): 85–140 % in steps of 5 %. */
internal object ReaderScale {
    const val MIN = 0.85f
    const val MAX = 1.4f
    const val DEFAULT = 1f
    private const val STEP_PERCENT = 5

    /** Stops between the ends for a Material slider with one stop per 5 %. */
    const val SLIDER_STEPS = ((140 - 85) / STEP_PERCENT) - 1

    /** Rounds to the nearest 5 % and clamps to [MIN]..[MAX]. NaN becomes [DEFAULT]. */
    fun normalize(value: Float): Float {
        if (value.isNaN()) return DEFAULT
        val steps = (value * 100f / STEP_PERCENT).roundToInt()
        return (steps * STEP_PERCENT / 100f).coerceIn(MIN, MAX)
    }

    /** "115 %" */
    fun label(value: Float): String = String.format(Locale.GERMANY, "%d %%", (normalize(value) * 100).roundToInt())
}
