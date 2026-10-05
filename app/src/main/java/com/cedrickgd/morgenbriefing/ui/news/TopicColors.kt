package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.ui.theme.ColorMath
import kotlin.math.min

/**
 * Colors derived from a topic's own seed color (independent of the app accent), tuned per
 * light/dark so chips, buttons and covers stay readable in both.
 *
 * @property container tonal background for chips, the header circle and the "+ Mehr" button.
 * @property onContainer text/icons on [container] (≥ 4.5:1 contrast, unit-tested).
 * @property coverStart / [coverEnd] gradient of the generated cover; always dark enough for
 *   white headlines on top.
 * @property coverGlow a lighter, warmer tone for the soft shapes on the cover.
 * @property strong deep tone for the selected topic chip; white text on it is ≥ 4.5:1.
 */
@Immutable
data class TopicColors(
    val container: Color,
    val onContainer: Color,
    val coverStart: Color,
    val coverEnd: Color,
    val coverGlow: Color,
    val strong: Color,
)

/** Pure: perceptual (OkLCH) derivation from the seed, so every hue looks equally calm. */
internal fun topicColors(seed: Long, dark: Boolean): TopicColors {
    val lch = ColorMath.toOkLch(Color(seed))
    val h = lch.h
    val c = lch.c
    return if (dark) {
        TopicColors(
            container = ColorMath.fromOkLch(0.33, min(c * 0.45, 0.07), h),
            onContainer = ColorMath.fromOkLch(0.90, min(c * 0.40, 0.06), h),
            coverStart = ColorMath.fromOkLch(0.50, min(c * 0.90, 0.13), h - 14),
            coverEnd = ColorMath.fromOkLch(0.29, min(c * 0.80, 0.10), h + 24),
            coverGlow = ColorMath.fromOkLch(0.72, min(c * 0.70, 0.12), h - 30),
            strong = ColorMath.fromOkLch(0.47, min(c * 0.90, 0.13), h),
        )
    } else {
        TopicColors(
            container = ColorMath.fromOkLch(0.93, min(c * 0.35, 0.045), h),
            onContainer = ColorMath.fromOkLch(0.36, min(c * 0.80, 0.12), h),
            coverStart = ColorMath.fromOkLch(0.66, min(c * 1.00, 0.16), h - 14),
            coverEnd = ColorMath.fromOkLch(0.44, min(c * 1.00, 0.15), h + 24),
            coverGlow = ColorMath.fromOkLch(0.86, min(c * 0.60, 0.11), h - 30),
            strong = ColorMath.fromOkLch(0.45, min(c * 1.00, 0.15), h),
        )
    }
}

/** True when the current theme is dark (the in-app theme may differ from the system's). */
@Composable
internal fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** [topicColors] for the current light/dark theme, cached per topic. */
@Composable
internal fun rememberTopicColors(topic: Topic): TopicColors {
    val dark = isDarkTheme()
    return remember(topic.seed, dark) { topicColors(topic.seed, dark) }
}
