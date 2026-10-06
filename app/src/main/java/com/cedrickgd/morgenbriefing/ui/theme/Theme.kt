package com.cedrickgd.morgenbriefing.ui.theme

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.ThemeMode

/**
 * App theme: [ThemeMode] decides light/dark, [AccentId] the palette (DYNAMIC = Material You on
 * API 31+, else MORGENROT). Warm neutral surfaces in light (bg #FCFCFB), night blue in dark (bg #0F1320),
 * serif display typography for headlines, sets status/navigation bar icon contrast.
 *
 * Switching accent or mode cross-fades the colors (skipped when the system "remove animations"
 * setting is on). The reduce-motion flag is resolved once here and shared with every
 * [rememberReduceMotion] call below.
 */
@Composable
fun MorgenbriefingTheme(
    themeMode: ThemeMode,
    accent: AccentId,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val target = remember(context, accent, dark) { resolveColorScheme(context, accent, dark) }

    val reduceMotion = observeReduceMotion()
    val animateColors = !reduceMotion && !LocalInspectionMode.current
    val colorScheme = animateColorScheme(target, animateColors)

    SystemBarAppearance(dark)

    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MorgenbriefingTypography,
            shapes = MorgenbriefingShapes,
            content = content,
        )
    }
}

/** Full M3 scheme derived from one seed color (used for every non-dynamic accent). */
fun accentColorScheme(seed: Color, dark: Boolean): ColorScheme = AccentScheme.build(seed, dark)

/** True when the system "remove animations" setting is on — skip decorative motion. */
@Composable
fun rememberReduceMotion(): Boolean {
    // Inside MorgenbriefingTheme the value is observed once and shared; outside of it
    // (e.g. a stray preview) fall back to observing the setting directly.
    LocalReduceMotion.current?.let { return it }
    return observeReduceMotion()
}

/** Provided by [MorgenbriefingTheme]; `null` outside of it. */
private val LocalReduceMotion = staticCompositionLocalOf<Boolean?> { null }

private fun resolveColorScheme(context: Context, accent: AccentId, dark: Boolean): ColorScheme =
    if (accent == AccentId.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Keep Material You's own (wallpaper-tinted) surfaces as they are.
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val seed = if (accent == AccentId.DYNAMIC) AccentId.MORGENROT.seed else accent.seed
        accentColorScheme(Color(seed), dark)
    }

/**
 * Observes `Settings.Global.ANIMATOR_DURATION_SCALE` (the "remove animations" switch) and updates
 * live when the user flips it while the app is open.
 */
@Composable
private fun observeReduceMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val resolver = LocalContext.current.applicationContext.contentResolver
    var reduce by remember(resolver) { mutableStateOf(readAnimationsRemoved(resolver)) }

    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = readAnimationsRemoved(resolver)
            }
        }
        val registered = runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
        }.isSuccess
        // The setting may have changed between the first read and the registration.
        reduce = readAnimationsRemoved(resolver)
        onDispose {
            if (registered) runCatching { resolver.unregisterContentObserver(observer) }
        }
    }
    return reduce
}

private fun readAnimationsRemoved(resolver: ContentResolver): Boolean =
    try {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
        false
    }

/** Dark status/navigation bar icons on light themes and vice versa (edge-to-edge bars). */
@Composable
private fun SystemBarAppearance(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val context = LocalContext.current
    SideEffect {
        val window = context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val ColorTransition: AnimationSpec<Color> = tween(durationMillis = 450, easing = FastOutSlowInEasing)

/** Smoothly morphs between schemes when the accent or the light/dark mode changes. */
@Composable
private fun animateColorScheme(target: ColorScheme, enabled: Boolean): ColorScheme {
    if (!enabled) return target

    @Composable
    fun animate(color: Color): Color =
        animateColorAsState(color, ColorTransition, label = "colorScheme").value

    return target.copy(
        primary = animate(target.primary),
        onPrimary = animate(target.onPrimary),
        primaryContainer = animate(target.primaryContainer),
        onPrimaryContainer = animate(target.onPrimaryContainer),
        inversePrimary = animate(target.inversePrimary),
        secondary = animate(target.secondary),
        onSecondary = animate(target.onSecondary),
        secondaryContainer = animate(target.secondaryContainer),
        onSecondaryContainer = animate(target.onSecondaryContainer),
        tertiary = animate(target.tertiary),
        onTertiary = animate(target.onTertiary),
        tertiaryContainer = animate(target.tertiaryContainer),
        onTertiaryContainer = animate(target.onTertiaryContainer),
        background = animate(target.background),
        onBackground = animate(target.onBackground),
        surface = animate(target.surface),
        onSurface = animate(target.onSurface),
        surfaceVariant = animate(target.surfaceVariant),
        onSurfaceVariant = animate(target.onSurfaceVariant),
        surfaceTint = animate(target.surfaceTint),
        inverseSurface = animate(target.inverseSurface),
        inverseOnSurface = animate(target.inverseOnSurface),
        outline = animate(target.outline),
        outlineVariant = animate(target.outlineVariant),
        surfaceBright = animate(target.surfaceBright),
        surfaceDim = animate(target.surfaceDim),
        surfaceContainer = animate(target.surfaceContainer),
        surfaceContainerHigh = animate(target.surfaceContainerHigh),
        surfaceContainerHighest = animate(target.surfaceContainerHighest),
        surfaceContainerLow = animate(target.surfaceContainerLow),
        surfaceContainerLowest = animate(target.surfaceContainerLowest),
    )
}
