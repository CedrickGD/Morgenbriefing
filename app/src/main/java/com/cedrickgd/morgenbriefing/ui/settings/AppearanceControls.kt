package com.cedrickgd.morgenbriefing.ui.settings

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import com.cedrickgd.morgenbriefing.ui.components.SkyKind
import com.cedrickgd.morgenbriefing.ui.components.skyPalette
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark
import com.cedrickgd.morgenbriefing.ui.theme.AccentScheme
import com.cedrickgd.morgenbriefing.ui.theme.WarmNeutrals

/** Settings section 3 "Darstellung": theme previews and the accent pearls. */
@Composable
internal fun AppearanceCard(
    themeMode: ThemeMode,
    accent: AccentId,
    onThemeMode: (ThemeMode) -> Unit,
    onAccent: (AccentId) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Overline("Modus")
        Spacer(Modifier.height(12.dp))
        ThemePreviewRow(selected = themeMode, accent = accent, onSelect = onThemeMode)
        Spacer(Modifier.height(24.dp))
        Overline("Akzentfarbe")
        Spacer(Modifier.height(8.dp))
        AccentOrbs(selected = accent, onSelect = onAccent)
    }
}

private data class ModeOption(val mode: ThemeMode, val label: String)

private val ModeOptions = listOf(
    ModeOption(ThemeMode.SYSTEM, "System"),
    ModeOption(ThemeMode.LIGHT, "Hell"),
    ModeOption(ThemeMode.DARK, "Dunkel"),
)

/** System / Hell / Dunkel as three tiles with a tiny drawn phone each. */
@Composable
private fun ThemePreviewRow(selected: ThemeMode, accent: AccentId, onSelect: (ThemeMode) -> Unit) {
    val haptic = rememberToggleHaptics()
    val currentPrimary = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val lightPhone = remember(accent, currentPrimary) { miniPhoneColors(context, accent, dark = false, fallback = currentPrimary) }
    val darkPhone = remember(accent, currentPrimary) { miniPhoneColors(context, accent, dark = true, fallback = currentPrimary) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ModeOptions.forEach { option ->
            val isSelected = option.mode == selected
            val ring by animateDpAsState(if (isSelected) 2.dp else 0.dp, label = "modeRing")
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0.96f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                label = "modeScale",
            )
            val primary = MaterialTheme.colorScheme.primary
            Column(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .lamina(GlassShapes.tile, strong = isSelected)
                    .then(if (ring > 0.dp) Modifier.border(ring, primary, GlassShapes.tile) else Modifier)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = {
                            if (!isSelected) haptic(true)
                            onSelect(option.mode)
                        },
                    )
                    .padding(top = 12.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(
                    Modifier
                        .size(width = 58.dp, height = 108.dp)
                        .clearAndSetSemantics { },
                ) {
                    when (option.mode) {
                        ThemeMode.LIGHT -> drawMiniPhone(lightPhone)
                        ThemeMode.DARK -> drawMiniPhone(darkPhone)
                        ThemeMode.SYSTEM -> {
                            drawMiniPhone(lightPhone)
                            val diagonal = Path().apply {
                                moveTo(size.width, 0f)
                                lineTo(size.width, size.height)
                                lineTo(0f, size.height)
                                close()
                            }
                            clipPath(diagonal) { drawMiniPhone(darkPhone) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Colors of one mini phone: the app's sky, neutrals and accent in light or dark. */
private data class MiniPhoneColors(
    val frame: Color,
    val skyTop: Color,
    val skyMid: Color,
    val skyLow: Color,
    val aurora: Color,
    val sun: Color,
    val ink: Color,
    val glass: Color,
    val glassEdge: Color,
    val accent: Color,
    val dark: Boolean,
)

private fun miniPhoneColors(context: Context, accent: AccentId, dark: Boolean, fallback: Color): MiniPhoneColors {
    val neutrals = WarmNeutrals.of(dark)
    val primary = when {
        accent == AccentId.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicPrimary(context, dark)
        else -> AccentScheme.build(Color(effectiveAccent(accent).seed), dark).primary
    }.takeIf { it.alpha > 0f } ?: fallback
    val sky = if (dark) skyPalette(SkyKind.CLEAR, isDay = false, dark = true) else skyPalette(SkyKind.FEW_CLOUDS, isDay = true, dark = false)
    return MiniPhoneColors(
        frame = if (dark) Color(0xFF2E2C28) else Color(0xFFE6E2D9),
        skyTop = sky.top,
        skyMid = sky.mid,
        skyLow = lerp(neutrals.background, primary, if (dark) 0.16f else 0.14f),
        aurora = primary.copy(alpha = if (dark) 0.30f else 0.24f),
        sun = if (dark) Color(0xFFF2EEDC) else Color(0xFFFFC861),
        ink = neutrals.onSurface,
        glass = Color.White.copy(alpha = if (dark) 0.12f else 0.58f),
        glassEdge = Color.White.copy(alpha = if (dark) 0.18f else 0.85f),
        accent = primary,
        dark = dark,
    )
}

@RequiresApi(Build.VERSION_CODES.S)
private fun dynamicPrimary(context: Context, dark: Boolean): Color =
    if (dark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary

/** A tiny phone showing the app: sky, aurora, sun or moon, a glass card and the indicator. */
private fun DrawScope.drawMiniPhone(c: MiniPhoneColors) {
    val w = size.width
    val h = size.height
    val frameRadius = CornerRadius(w * 0.2f)
    drawRoundRect(color = c.frame, cornerRadius = frameRadius)
    drawRoundRect(
        color = if (c.dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.06f),
        cornerRadius = frameRadius,
        style = Stroke(width = w * 0.018f),
    )
    val inset = w * 0.045f
    val sw = w - inset * 2
    val sh = h - inset * 2
    val screenRadius = CornerRadius(w * 0.16f)
    val screen = Path().apply {
        addRoundRect(RoundRect(inset, inset, inset + sw, inset + sh, screenRadius))
    }
    clipPath(screen) {
        drawRect(
            Brush.verticalGradient(
                0f to c.skyTop,
                0.42f to c.skyMid,
                1f to c.skyLow,
                startY = inset,
                endY = inset + sh,
            ),
        )
        drawCircle(
            Brush.radialGradient(listOf(c.aurora, Color.Transparent), center = Offset(inset + sw * 0.85f, inset + sh * 0.62f), radius = sw * 0.75f),
            radius = sw * 0.75f,
            center = Offset(inset + sw * 0.85f, inset + sh * 0.62f),
        )
        // Sun (light) or moon with a few stars (dark).
        val sunCenter = Offset(inset + sw * 0.76f, inset + sh * 0.12f)
        drawCircle(c.sun.copy(alpha = 0.35f), radius = sw * 0.16f, center = sunCenter)
        drawCircle(c.sun, radius = sw * 0.085f, center = sunCenter)
        if (c.dark) {
            listOf(0.18f to 0.07f, 0.36f to 0.15f, 0.55f to 0.05f, 0.22f to 0.24f).forEach { (x, y) ->
                drawCircle(Color.White.copy(alpha = 0.8f), radius = w * 0.012f, center = Offset(inset + sw * x, inset + sh * y))
            }
        }
        // Headline lines on the sky.
        val line = h * 0.022f
        drawRoundRect(c.ink.copy(alpha = 0.55f), Offset(inset + sw * 0.12f, inset + sh * 0.25f), Size(sw * 0.56f, line), CornerRadius(line))
        drawRoundRect(c.ink.copy(alpha = 0.3f), Offset(inset + sw * 0.12f, inset + sh * 0.30f), Size(sw * 0.36f, line), CornerRadius(line))
        // Glass card with a temperature and two lines.
        val cardTop = inset + sh * 0.39f
        val cardSize = Size(sw * 0.78f, sh * 0.25f)
        val cardOrigin = Offset(inset + sw * 0.11f, cardTop)
        val cardRadius = CornerRadius(w * 0.09f)
        drawRoundRect(c.glass, cardOrigin, cardSize, cardRadius)
        drawRoundRect(c.glassEdge, cardOrigin, cardSize, cardRadius, style = Stroke(width = w * 0.012f))
        drawRoundRect(c.accent, Offset(cardOrigin.x + sw * 0.08f, cardTop + sh * 0.05f), Size(sw * 0.22f, sh * 0.07f), CornerRadius(line * 1.4f))
        drawRoundRect(c.ink.copy(alpha = 0.4f), Offset(cardOrigin.x + sw * 0.08f, cardTop + sh * 0.155f), Size(sw * 0.5f, line), CornerRadius(line))
        // A second, smaller card.
        val card2 = Offset(inset + sw * 0.11f, inset + sh * 0.68f)
        drawRoundRect(c.glass, card2, Size(sw * 0.78f, sh * 0.14f), cardRadius)
        drawRoundRect(c.ink.copy(alpha = 0.3f), Offset(card2.x + sw * 0.08f, card2.y + sh * 0.055f), Size(sw * 0.42f, line), CornerRadius(line))
        // Floating page indicator with the accent capsule.
        val pill = Size(sw * 0.4f, sh * 0.05f)
        val pillOrigin = Offset(inset + (sw - pill.width) / 2f, inset + sh * 0.9f)
        drawRoundRect(c.glass, pillOrigin, pill, CornerRadius(pill.height))
        drawRoundRect(c.accent, Offset(pillOrigin.x + pill.width * 0.36f, pillOrigin.y), Size(pill.width * 0.28f, pill.height), CornerRadius(pill.height))
    }
}

/**
 * The accent colors as large pearls in a grid; a ring glides to the chosen one and its name
 * appears below. "Material You" (Android 12+) shows the wallpaper color with a palette mark.
 */
@Composable
private fun AccentOrbs(selected: AccentId, onSelect: (AccentId) -> Unit) {
    val context = LocalContext.current
    val dark = isGlassDark()
    val accents = remember { availableAccents() }
    val current = effectiveAccent(selected)
    val dynamicColor = remember(context, dark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicPrimary(context, dark) else null
    }
    val haptic = rememberToggleHaptics()
    val density = LocalDensity.current
    val ringColor = MaterialTheme.colorScheme.onSurface

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ORB_COLUMNS
        val cell = maxWidth / columns
        val rows = (accents.size + columns - 1) / columns
        val index = accents.indexOf(current).coerceAtLeast(0)
        val target = with(density) {
            IntOffset(
                x = (cell * (index % columns) + (cell - RING_SIZE) / 2).roundToPx(),
                y = (ROW_HEIGHT * (index / columns) + (ROW_HEIGHT - RING_SIZE) / 2).roundToPx(),
            )
        }
        val ringOffset by animateIntOffsetAsState(
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1)),
            label = "accentRing",
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT * rows)
                .selectableGroup(),
        ) {
            accents.forEachIndexed { i, accent ->
                val color = if (accent == AccentId.DYNAMIC && dynamicColor != null) dynamicColor else Color(accent.seed)
                val isSelected = accent == current
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0.86f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
                    label = "orbScale",
                )
                Box(
                    modifier = Modifier
                        .offset(x = cell * (i % columns) + (cell - TOUCH_SIZE) / 2, y = ROW_HEIGHT * (i / columns) + (ROW_HEIGHT - TOUCH_SIZE) / 2)
                        .size(TOUCH_SIZE)
                        .clip(CircleShape)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = {
                                if (!isSelected) haptic(true)
                                onSelect(accent)
                            },
                        )
                        .semantics { contentDescription = accent.label },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(
                        Modifier
                            .size(ORB_SIZE)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                    ) { drawPearl(color) }
                    if (accent == AccentId.DYNAMIC) {
                        Icon(
                            Icons.Rounded.Palette,
                            contentDescription = null,
                            tint = if (color.luminance() > 0.5f) Color.Black.copy(alpha = 0.7f) else Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            // The gliding selection ring (offset read in the layout phase only).
            Box(
                Modifier
                    .offset { ringOffset }
                    .size(RING_SIZE)
                    .border(2.5.dp, ringColor.copy(alpha = 0.85f), CircleShape),
            )
        }
    }
    AnimatedContent(
        targetState = current.label,
        transitionSpec = {
            (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        contentAlignment = Alignment.Center,
        label = "accentName",
    ) { name ->
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = MaterialTheme.typography.headlineSmall.fontFamily),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

/** A glossy pearl: light from the top left, deeper color at the rim, a small highlight. */
private fun DrawScope.drawPearl(color: Color) {
    val r = size.minDimension / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    drawCircle(color.copy(alpha = 0.22f), radius = r, center = center.copy(y = center.y + r * 0.08f))
    drawCircle(
        Brush.radialGradient(
            0f to lerp(color, Color.White, 0.42f),
            0.55f to color,
            1f to lerp(color, Color.Black, 0.28f),
            center = Offset(center.x - r * 0.35f, center.y - r * 0.4f),
            radius = r * 1.5f,
        ),
        radius = r * 0.94f,
        center = center,
    )
    drawCircle(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0f)),
            center = Offset(center.x - r * 0.32f, center.y - r * 0.38f),
            radius = r * 0.36f,
        ),
        radius = r * 0.36f,
        center = Offset(center.x - r * 0.32f, center.y - r * 0.38f),
    )
}

private const val ORB_COLUMNS = 4
private val ORB_SIZE = 46.dp
private val RING_SIZE = 58.dp
private val TOUCH_SIZE = 56.dp
private val ROW_HEIGHT = 66.dp
