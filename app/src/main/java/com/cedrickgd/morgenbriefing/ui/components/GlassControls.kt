package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.glass
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark

/** Scales an element to 0.97 while it is pressed (docs/DESIGN.md §6), springy, layer only. */
@Composable
internal fun Modifier.pressScale(interaction: MutableInteractionSource, pressedScale: Float = 0.97f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * A pill-shaped glass button, at least 48 dp high. [filled] paints it in the accent color for
 * the one primary action of a block; otherwise it is frosted glass with accent-colored text.
 */
@Composable
fun GlassPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    emoji: String? = null,
    filled: Boolean = false,
    enabled: Boolean = true,
    contentColor: Color = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
) {
    val interaction = remember { MutableInteractionSource() }
    val base = Modifier
        .pressScale(interaction)
        .then(
            if (filled) {
                Modifier
                    .shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            } else {
                Modifier.glass(shape = GlassShapes.pill, level = GlassLevel.Thin)
            },
        )
        .clickable(
            interactionSource = interaction,
            indication = ripple(),
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .then(base)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .padding(start = if (icon != null || emoji != null) 16.dp else 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            when {
                icon != null -> {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                emoji != null -> {
                    Emoji(emoji, size = 16.sp)
                    Spacer(Modifier.width(8.dp))
                }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A round frosted-glass icon button ([size] ≥ 48 dp keeps the touch target accessible). */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    level: GlassLevel = GlassLevel.Thin,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .pressScale(interaction, pressedScale = 0.92f)
            .shadow(10.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))
            .glass(shape = GlassShapes.pill, level = level)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Snackbars as dense frosted pills, placed by the caller (above the floating chrome). */
@Composable
fun GlassSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data -> GlassSnackbar(data) }
}

@Composable
private fun GlassSnackbar(data: SnackbarData) {
    val colors = MaterialTheme.colorScheme
    val outline = colors.onSurface.copy(alpha = if (isGlassDark()) 0.16f else 0.10f)
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .shadow(16.dp, GlassShapes.tile, ambientColor = Color.Black.copy(alpha = 0.16f), spotColor = Color.Black.copy(alpha = 0.16f))
            // The host scales snackbars in, which a live blur cannot follow (part of the pill
            // stayed clear, the feed sharp beneath it): a dense frosted fill instead, as in the
            // settings.
            .clip(GlassShapes.tile)
            .background(colors.surfaceContainerHigh.copy(alpha = 0.97f))
            .border(1.dp, outline, GlassShapes.tile)
            .heightIn(min = 56.dp)
            .padding(start = 20.dp, end = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = data.visuals.message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 14.dp),
        )
        val action = data.visuals.actionLabel
        if (action != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = data::performAction)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = action, style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * A bottom sheet made of thick frosted glass (docs/DESIGN.md §2: sheets = `Thick`). It never
 * rises under the status bar; the glass reaches under the navigation bar while its content is
 * padded above it. Without real blur (Android < 12) the glass is practically opaque, so the page
 * never ghosts through a modal. On Android 7, which cannot draw dark navigation buttons, a dark
 * band behind them keeps the white buttons visible on the light sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSheet(onDismissRequest, rememberModalBottomSheetState(skipPartiallyExpanded = true), content)
}

/** [GlassSheet] with the caller's [sheetState], to animate it out before acting (`hide()`). */
@ExperimentalMaterial3Api
@Composable
fun GlassSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = isGlassDark()
    val lightButtonsImpossible = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = SheetShape,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        dragHandle = null,
        // Room for the status bar above a fully expanded sheet (transparent, so a short sheet
        // simply has a little invisible headroom).
        contentWindowInsets = { WindowInsets.statusBars.only(WindowInsetsSides.Top) },
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = !dark,
            isAppearanceLightNavigationBars = !dark && !lightButtonsImpossible,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .glass(shape = SheetShape, level = GlassLevel.Thick, floating = true),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape),
            )
            content()
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                    .then(if (lightButtonsImpossible && !dark) Modifier.background(Color.Black.copy(alpha = 0.55f)) else Modifier),
            )
        }
    }
}
