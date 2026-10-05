package com.cedrickgd.morgenbriefing.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.util.statusBarTop

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
 */
@Composable
fun BoxScope.TopGlassBar(title: String, visible: Boolean, actions: @Composable RowScope.() -> Unit = {}) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { -it / 3 },
        exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)) +
            slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { -it / 3 },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .glass(
                    shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp),
                    level = GlassLevel.Thick,
                )
                .padding(top = statusBarTop())
                .height(52.dp),
        ) {
            Row(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = MaterialTheme.typography.displaySmall.fontFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                actions()
            }
        }
    }
}
