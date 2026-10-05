package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing
import java.time.LocalDate

/**
 * Overline date + greeting + serif headline.
 *
 * Sits directly on the AmbientSky (docs/DESIGN.md §7), so the overline uses a slightly deepened
 * `onSurfaceVariant` to stay readable on the pastel/dusky sky tones. Fades and slides in once.
 */
@Composable
fun TodayHeader(date: LocalDate, greeting: String, headline: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val entrance = rememberEntrance(durationMillis = 640)

    Column(modifier.entrance(entrance, distance = 20.dp)) {
        Text(
            text = Fmt.dateLine(date),
            style = typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
            color = headerOverlineColor(colors.onSurfaceVariant, colors.onSurface),
        )
        Spacer(Modifier.height(10.dp))
        if (greeting.isNotBlank()) {
            Text(
                text = greeting,
                style = typography.titleMedium,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(2.dp))
        }
        Text(
            text = headline,
            style = typography.headlineMedium,
            color = colors.onSurface,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** onSurfaceVariant pulled 45 % towards onSurface: same family, enough contrast on the sky. */
internal fun headerOverlineColor(onSurfaceVariant: Color, onSurface: Color): Color =
    lerp(onSurfaceVariant, onSurface, 0.45f)
