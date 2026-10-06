package com.cedrickgd.morgenbriefing.ui.settings

import android.os.Build
import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SliderState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.LocalGlassState
import com.cedrickgd.morgenbriefing.ui.glass.glass
import com.cedrickgd.morgenbriefing.ui.glass.glassEdgeBrush
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark
import kotlinx.coroutines.delay
import java.util.Locale

/*
 * Building blocks of the "Morgenglas" settings and history pages (docs/DESIGN.md §2, §5, §6):
 * section headings, glass pill buttons, a lamina for elements that sit *on* a glass card, glass
 * switches with haptics, the frosted dialog and snackbar, status colors and the staggered
 * entrance of page content.
 */

/** Overline + serif title above a section (docs/DESIGN.md §5: every section has both). */
@Composable
internal fun SectionHeading(overline: String, title: String, modifier: Modifier = Modifier, top: Dp = 36.dp) {
    Column(modifier.padding(start = 4.dp, end = 4.dp, top = top, bottom = 14.dp)) {
        Overline(overline)
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** Small tracked uppercase label (inside cards and above titles). */
@Composable
internal fun Overline(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text.uppercase(Locale.GERMAN),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * A lighter pane for elements that sit on top of a glass card (tiles, previews, pills inside a
 * card). Not a second blur — glass on glass would show the sky through the card.
 */
internal fun Modifier.lamina(shape: Shape = GlassShapes.tile, strong: Boolean = false): Modifier = composed {
    val dark = isGlassDark()
    val fill = when {
        dark && strong -> Color.White.copy(alpha = 0.10f)
        dark -> Color.White.copy(alpha = 0.06f)
        strong -> Color.White.copy(alpha = 0.62f)
        else -> Color.White.copy(alpha = 0.42f)
    }
    val edge = Color.White.copy(alpha = if (dark) 0.08f else 0.55f)
    clip(shape).background(fill).border(1.dp, edge, shape)
}

/** Visual weight of a [GlassPillButton]. */
internal enum class PillStyle {
    /** Accent fill, for the main action of a card. */
    Filled,

    /** Glass with accent text, the default. */
    Tonal,

    /** Just text on a faint lamina, for secondary actions. */
    Quiet,
}

/**
 * Fully rounded glass button, at least 48 dp high. [onGlass] = it sits on a glass card (uses a
 * lamina instead of a second blur).
 */
@Composable
internal fun GlassPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: PillStyle = PillStyle.Tonal,
    enabled: Boolean = true,
    onGlass: Boolean = true,
    contentDescription: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "pillPress",
    )
    val content = when (style) {
        PillStyle.Filled -> colors.onPrimary
        PillStyle.Tonal -> colors.primary
        PillStyle.Quiet -> colors.onSurface
    }
    val surface = when (style) {
        PillStyle.Filled -> Modifier
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.92f), colors.primary)))
        PillStyle.Tonal, PillStyle.Quiet -> if (onGlass) {
            Modifier.lamina(CircleShape, strong = style == PillStyle.Tonal)
        } else {
            Modifier.glass(shape = CircleShape, level = GlassLevel.Thin)
        }
    }
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else DISABLED_ALPHA
            }
            .heightIn(min = 48.dp)
            .then(surface)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .padding(start = if (icon != null) 16.dp else 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Slider track without tick marks: accent up to the thumb, a faint pane after it, a small gap
 * around the thumb. Calmer than the dotted default on glass.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalmSliderTrack(state: SliderState) {
    val inactive = if (isGlassDark()) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val primary = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(10.dp),
    ) {
        val span = (state.valueRange.endInclusive - state.valueRange.start).coerceAtLeast(0.0001f)
        val fraction = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
        val x = size.width * fraction
        val gap = 6.dp.toPx()
        val radius = CornerRadius(size.height / 2f)
        if (x - gap > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(primary.copy(alpha = 0.7f), primary), endX = x),
                size = Size(x - gap, size.height),
                cornerRadius = radius,
            )
        }
        if (x + gap < size.width) {
            drawRoundRect(inactive, topLeft = Offset(x + gap, 0f), size = Size(size.width - x - gap, size.height), cornerRadius = radius)
        }
    }
}

/**
 * The one slider knob of the settings: a round accent disc with a white rim and a soft shadow —
 * the same family as the sun knobs of the day window.
 */
@Composable
internal fun CalmSliderThumb() {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(SliderKnobSize)
            .shadow(4.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
            .background(Brush.linearGradient(listOf(lerp(primary, Color.White, 0.25f), primary)), CircleShape)
            .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape),
    )
}

/** Size of every slider knob in the settings. */
internal val SliderKnobSize = 26.dp

/** Haptic tick for switches and toggles (on/off feel different). */
@Composable
internal fun rememberToggleHaptics(): (Boolean) -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) {
        { on -> haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff) }
    }
}

/** Material switch with a check in the thumb, readable on glass. Decorative: the row toggles. */
@Composable
internal fun GlassSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val dark = isGlassDark()
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        modifier = modifier,
        thumbContent = if (checked) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else {
            null
        },
        colors = SwitchDefaults.colors(
            uncheckedTrackColor = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.55f),
            uncheckedBorderColor = colors.outline.copy(alpha = 0.7f),
            uncheckedThumbColor = colors.outline,
        ),
    )
}

/**
 * A whole row that toggles [checked] (48 dp+), with title, optional subtitle and a [GlassSwitch].
 * Plays the toggle haptic.
 */
@Composable
internal fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val haptic = rememberToggleHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = { value ->
                    haptic(value)
                    onCheckedChange(value)
                },
            )
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        GlassSwitch(checked = checked)
    }
}

/** Round badge with an icon on a soft tinted lamina (leading element of rows). */
@Composable
internal fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 40.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = if (isGlassDark()) 0.20f else 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Green/amber of the status pills; text colors keep ≥ 4.5:1 on glass. */
internal object StatusPalette {
    @Composable
    fun ok(): Color = if (isGlassDark()) Color(0xFF7BD8A0) else Color(0xFF2E7D4F)

    @Composable
    fun okFill(): Color = if (isGlassDark()) Color(0xFF4FBF82) else Color(0xFF34A065)

    @Composable
    fun attention(): Color = if (isGlassDark()) Color(0xFFF4C25B) else Color(0xFF9A5B00)

    @Composable
    fun attentionFill(): Color = if (isGlassDark()) Color(0xFFE9B23F) else Color(0xFFE5A50A)
}

/**
 * Frosted dialog: a translucent pane with the glass edge and sheen. The dialog lives in its own
 * window where Haze cannot see the sky, so it uses a denser tint — and on Android 12+ asks the
 * system to blur everything behind the window (where the device allows cross-window blur).
 */
@Composable
internal fun GlassDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    dismissOnClickOutside: Boolean = true,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = dismissOnClickOutside,
            // Edge-to-edge window: the pane keeps clear of system bars and the keyboard itself,
            // so the buttons stay reachable while typing (the body scrolls instead).
            decorFitsSystemWindows = false,
        ),
    ) {
        val blurred = rememberWindowBlurBehind()
        val dark = isGlassDark()
        // Behind a blurred window the light page stays light (dim 0.16): dark bar icons there.
        // Otherwise the dim darkens the page and the default white icons stay readable.
        FullScreenDialogWindow(lightBars = blurred && !dark)
        val colors = MaterialTheme.colorScheme
        val shape = GlassShapes.card
        val fill = if (dark) {
            colors.surfaceContainer.copy(alpha = if (blurred) 0.80f else 1f)
        } else {
            // Without the system blur behind the window the pane is fully opaque: nothing of the
            // page may ghost through a text field or the buttons.
            colors.surface.copy(alpha = if (blurred) 0.80f else 1f)
        }
        val scroll = rememberScrollState()
        val fadeColor = if (dark) colors.surfaceContainer else colors.surface
        val sheen = Color.White.copy(alpha = if (dark) 0.06f else 0.5f)
        CompositionLocalProvider(LocalGlassState provides null, LocalContentColor provides colors.onSurface) {
            // The window covers the whole screen, so a tap beside the pane is no longer "outside"
            // for the platform: the pane is centred here and such a tap closes it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (dismissOnClickOutside) {
                            Modifier.pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = modifier
                        .safeDrawingPadding()
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .clip(shape)
                        // Taps on the pane itself never count as "beside" it.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .background(fill)
                        .drawBehind {
                            drawRect(Brush.verticalGradient(0f to sheen, (28.dp.toPx() / size.height).coerceIn(0.01f, 1f) to Color.Transparent))
                        }
                        .border(1.dp, glassEdgeBrush(), shape)
                        .padding(top = 24.dp, bottom = 12.dp),
                ) {
                    if (icon != null) {
                        IconBadge(icon, Modifier.padding(start = 24.dp, bottom = 14.dp), size = 44.dp)
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .semantics { heading() },
                    )
                    Box(Modifier.weight(1f, fill = false)) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(scroll)
                                .padding(horizontal = 24.dp)
                                .padding(top = 12.dp, bottom = 8.dp),
                            content = content,
                        )
                        // Text scrolling up under the fixed title fades out instead of being cut in half
                        // (e.g. when the keyboard pushes the dialog up).
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(14.dp)
                                .graphicsLayer { alpha = (scroll.value / 24f).coerceIn(0f, 1f) }
                                .background(Brush.verticalGradient(listOf(fadeColor, fadeColor.copy(alpha = 0f)))),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        content = buttons,
                    )
                }
            }
        }
    }
}

/** Enables the system blur behind the current dialog window (API 31+, if the device allows). */
@Composable
private fun rememberWindowBlurBehind(): Boolean {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val context = LocalContext.current
    val density = LocalDensity.current
    val enabled = remember(window, context) {
        window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true
    }
    DisposableEffect(window, enabled) {
        if (window != null && enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyBlurBehind(window, with(density) { BLUR_BEHIND.roundToPx() })
        }
        onDispose { }
    }
    return enabled
}

/**
 * Lets the dialog window cover the whole screen and sets its bar icons ([lightBars]: dark icons).
 * The system takes the status-bar icon colour of a dimming window only from a window that
 * reaches under the status bar; a dialog sized to its pane always got white icons, which nearly
 * vanish over the light, blurred page.
 */
@Composable
private fun FullScreenDialogWindow(lightBars: Boolean) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    DisposableEffect(window, view, lightBars) {
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        // Transparent bars: the blurred, dimmed page shows behind them as everywhere else.
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
        onDispose { }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun applyBlurBehind(window: Window, radiusPx: Int) {
    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
    window.attributes = window.attributes.apply { blurBehindRadius = radiusPx }
    window.setDimAmount(BLUR_DIM)
}

private val BLUR_BEHIND = 28.dp
private const val BLUR_DIM = 0.16f

/** Text button for [GlassDialog] rows. */
@Composable
internal fun DialogButton(text: String, onClick: () -> Unit, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.primary) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text, color = if (enabled) color else color.copy(alpha = DISABLED_ALPHA), style = MaterialTheme.typography.labelLarge)
    }
}

/** Snackbars as a frosted pill above the floating chrome. */
@Composable
internal fun GlassSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        val shape = RoundedCornerShape(22.dp)
        val outline = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isGlassDark()) 0.16f else 0.10f)
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                // A floating element: very soft ambient shadow and a defined rim, so it reads
                // clearly even above another glass card. The host scales snackbars in, which a
                // live blur cannot follow, so this uses a dense frosted fill instead.
                .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f))
                .border(1.dp, outline, shape)
                .heightIn(min = 52.dp)
                .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            data.visuals.actionLabel?.let { label ->
                TextButton(onClick = data::performAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
            }
        }
    }
}

/**
 * Staggered entrance (docs/DESIGN.md §6): the first items of a page fade and rise in one after
 * another, once. Items that scroll into view later — or come back — simply appear.
 */
@Stable
internal class Stagger(private val enabled: Boolean) {
    private val seen = HashSet<Any>()
    private val startedAt = SystemClock.uptimeMillis()

    fun claim(key: Any): Boolean {
        if (!enabled) return false
        val first = seen.add(key)
        return first && SystemClock.uptimeMillis() - startedAt < WINDOW_MS
    }

    private companion object {
        const val WINDOW_MS = 900L
    }
}

@Composable
internal fun rememberStagger(enabled: Boolean): Stagger = remember { Stagger(enabled) }

internal fun Modifier.staggerIn(stagger: Stagger, key: Any, index: Int): Modifier = composed {
    val animate = remember(key) { stagger.claim(key) }
    if (!animate) return@composed this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        delay(index.coerceIn(0, MAX_STAGGERED) * STAGGER_MS)
        progress.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 28.dp.toPx()
    }
}

private const val STAGGER_MS = 50L
private const val MAX_STAGGERED = 8
internal const val DISABLED_ALPHA = 0.45f
