package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.domain.Condition
import com.cedrickgd.morgenbriefing.ui.theme.ColorMath
import com.cedrickgd.morgenbriefing.ui.theme.MIN_TEXT_CONTRAST
import com.cedrickgd.morgenbriefing.ui.theme.accentColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TodayHeader sits directly on the sky: its text must stay readable on every sky. */
class SkyPaletteTest {

    @Test
    fun everyConditionMapsToASky() {
        for (condition in Condition.entries) SkyKind.of(condition) // exhaustive, must not throw
    }

    @Test
    fun headerTextIsReadableOnEverySky() {
        for (dark in listOf(false, true)) {
            val scheme = accentColorScheme(Color(AccentId.MORGENROT.seed), dark)
            val overline = headerOverlineColor(scheme.onSurfaceVariant, scheme.onSurface)
            for (kind in SkyKind.entries) {
                for (isDay in listOf(true, false)) {
                    val p = skyPalette(kind, isDay, dark)
                    val name = "$kind/${if (isDay) "day" else "night"}/${if (dark) "dark" else "light"}"
                    val backgrounds = listOf(p.top, p.mid, p.glow.compositeOver(p.top), p.fog.compositeOver(p.top))
                    for (bg in backgrounds) {
                        val headline = ColorMath.contrastRatio(scheme.onSurface, bg)
                        val small = ColorMath.contrastRatio(overline, bg)
                        assertTrue("$name: headline contrast $headline on $bg", headline >= 7.0)
                        assertTrue("$name: overline contrast $small on $bg", small >= MIN_TEXT_CONTRAST)
                    }
                }
            }
        }
    }

    @Test
    fun skiesMatchTheThemeBrightness() {
        for (kind in SkyKind.entries) {
            for (isDay in listOf(true, false)) {
                val light = skyPalette(kind, isDay, dark = false)
                val dark = skyPalette(kind, isDay, dark = true)
                assertTrue("$kind light sky too dark", ColorMath.relativeLuminance(light.top) > 0.4)
                assertTrue("$kind dark sky too bright", ColorMath.relativeLuminance(dark.top) < 0.08)
            }
        }
    }
}
