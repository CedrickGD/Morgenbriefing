package com.cedrickgd.morgenbriefing.ui.settings

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.ThemeMode

private data class ThemeOption(val mode: ThemeMode, val label: String, val icon: ImageVector)

private val ThemeOptions = listOf(
    ThemeOption(ThemeMode.SYSTEM, "System", Icons.Rounded.BrightnessAuto),
    ThemeOption(ThemeMode.LIGHT, "Hell", Icons.Rounded.LightMode),
    ThemeOption(ThemeMode.DARK, "Dunkel", Icons.Rounded.DarkMode),
)

/** System / Hell / Dunkel as a segmented button row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemeModeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        ThemeOptions.forEachIndexed { index, option ->
            val isSelected = option.mode == selected
            SegmentedButton(
                selected = isSelected,
                onClick = { onSelect(option.mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeOptions.size),
                icon = {
                    SegmentedButtonDefaults.Icon(
                        active = isSelected,
                        inactiveContent = {
                            Icon(
                                imageVector = option.icon,
                                contentDescription = null,
                                modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                            )
                        },
                    )
                },
            ) {
                Text(option.label, maxLines = 1)
            }
        }
    }
}

/**
 * Row of round color swatches. The selected one gets a ring and a check mark; "Material You"
 * shows the wallpaper color with a palette symbol. Applies immediately.
 */
@Composable
internal fun AccentPicker(
    selected: AccentId,
    darkTheme: Boolean,
    onSelect: (AccentId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val accents = remember { availableAccents() }
    val current = effectiveAccent(selected)
    val dynamicColor = remember(context, darkTheme) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicPrimary(context, darkTheme) else null
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            accents.forEach { accent ->
                val isDynamic = accent == AccentId.DYNAMIC
                AccentSwatch(
                    color = if (isDynamic && dynamicColor != null) dynamicColor else Color(accent.seed),
                    label = accent.label,
                    selected = accent == current,
                    showPalette = isDynamic,
                    onClick = { onSelect(accent) },
                )
            }
        }
        Text(
            text = "Akzent: ${current.label}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun dynamicPrimary(context: Context, darkTheme: Boolean): Color =
    if (darkTheme) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary

@Composable
private fun AccentSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    showPalette: Boolean,
    onClick: () -> Unit,
) {
    val onColor = if (color.luminance() > 0.5f) Color.Black else Color.White
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(46.dp)
                    .border(width = 2.dp, color = ring, shape = CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(if (selected) 34.dp else 38.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            when {
                selected -> Icon(Icons.Rounded.Check, contentDescription = null, tint = onColor, modifier = Modifier.size(20.dp))
                showPalette -> Icon(Icons.Rounded.Palette, contentDescription = null, tint = onColor, modifier = Modifier.size(18.dp))
            }
        }
    }
}
