package com.cedrickgd.morgenbriefing.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.util.statusBarTop
import kotlin.math.roundToInt

/**
 * Page title right under the status bar (docs/DESIGN.md §4): small overline + large serif title.
 * Use it as the first item of a page's scrolling content.
 */
@Composable
fun PageTitle(
    overline: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = statusBarTop() + 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = overline.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
        trailing()
    }
}

/**
 * The slim frosted bar that slides in once the [PageTitle] has scrolled away: status bar
 * height + 52 dp, title in small serif. Place it last inside the page's root [Box].
 *
 * It is floating glass: it blurs the content gliding beneath it, takes every touch inside its
 * bounds (nothing below is triggered by accident) and lets the content fade out softly under its
 * lower edge. [onTap] (e.g. "scroll to the top") makes the bar itself tappable.
 *
 * [bottom] adds a second row inside the same glass surface (the feed's chip row once it
 * sticks): it appears in place at once while the glass grows down behind it with a spring, so
 * the bar seems to take the row in — one surface, one edge.
 */
@Composable
fun BoxScope.TopGlassBar(
    title: String,
    visible: Boolean,
    onTap: (() -> Unit)? = null,
    bottom: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { -it / 3 },
        exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)) +
            slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { -it / 3 },
    ) {
        val statusTop = statusBarTop()
        val density = LocalDensity.current
        var contentHeight by remember { mutableIntStateOf(0) }
        // The glass follows the content's height with a spring; the first height is taken as is,
        // so the bar does not "grow" every time it slides in. Read in the layout phase only.
        val glassAnim = remember { Animatable(0f) }
        LaunchedEffect(contentHeight) {
            val target = contentHeight.toFloat()
            when {
                target <= 0f -> Unit
                glassAnim.value <= 0f -> glassAnim.snapTo(target)
                else -> glassAnim.animateTo(target, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
            }
        }
        val glassHeight: () -> Int = { glassAnim.value.roundToInt() }
        val fadeColor = glassTint().copy(alpha = if (isGlassDark()) 0.5f else 0.6f)
        Box(Modifier.fillMaxWidth()) {
            // Content softly fading out under the bar's lower edge (behind the glass itself).
            Box(
                Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val lip = BarCorner.roundToPx()
                        val height = lip + BarFade.roundToPx()
                        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                        layout(placeable.width, 0) { placeable.place(0, glassHeight() - lip) }
                    }
                    .drawBehind {
                        drawRect(
                            Brush.verticalGradient(
                                0f to fadeColor,
                                (BarCorner.toPx() / size.height) to fadeColor,
                                1f to Color.Transparent,
                            ),
                        )
                    },
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        // One pixel above the screen, so the glass edge never draws a line along
                        // the very top of the display.
                        val lift = with(density) { 1.dp.roundToPx() }
                        val height = (glassHeight() + lift).coerceAtLeast(0)
                        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                        layout(placeable.width, 0) { placeable.place(0, -lift) }
                    }
                    .glass(
                        shape = RoundedCornerShape(bottomStart = BarCorner, bottomEnd = BarCorner),
                        level = GlassLevel.Thick,
                        floating = true,
                    ),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { contentHeight = it.height }
                    .blockTouchesBehind()
                    .then(
                        if (onTap != null) {
                            Modifier.clickable(
                                interactionSource = null,
                                indication = null,
                                onClickLabel = "Nach oben scrollen",
                                onClick = onTap,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .padding(top = statusTop),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = MaterialTheme.typography.displaySmall.fontFamily),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                    )
                    actions()
                }
                bottom?.invoke()
            }
        }
    }
}

/** Corner radius of the bar's lower edge. */
private val BarCorner = 22.dp

/** How far the content fades out below the bar. */
private val BarFade = 18.dp
