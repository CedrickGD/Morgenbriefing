package com.cedrickgd.morgenbriefing.ui.reader

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.components.motionDisabled
import com.cedrickgd.morgenbriefing.ui.theme.Reading
import com.cedrickgd.morgenbriefing.util.horizontalSafeDrawing
import com.cedrickgd.morgenbriefing.util.statusBarTop

/**
 * While the article loads: the shape of a page (picture, chip, headline, lead, text) with a
 * slow light sweep — calmer than a spinner and no layout jump when the article arrives.
 */
@Composable
internal fun ReaderSkeleton(heroHeight: Dp, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val base = colors.surfaceContainerHigh
    val shine = colors.surfaceContainerLowest.copy(alpha = 0.7f)
    val sweep: State<Float>? = if (motionDisabled()) {
        null
    } else {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = -0.4f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
            label = "skeletonSweep",
        )
    }

    fun Modifier.bone(shape: Shape): Modifier = this
        .clip(shape)
        .background(base)
        .drawWithContent {
            val p = sweep?.value ?: return@drawWithContent
            val center = size.width * p
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to shine,
                    1f to Color.Transparent,
                    startX = center - size.width * 0.3f,
                    endX = center + size.width * 0.3f,
                ),
            )
        }

    Box(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Artikel wird geladen" },
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(heroHeight - 28.dp)
                    .bone(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)),
            )
            // Same rhythm as the loaded page: the topic chip starts where the picture ends.
            Column(Modifier.readingColumn().padding(top = 28.dp)) {
                Box(Modifier.width(110.dp).height(28.dp).bone(CircleShape))
                Spacer(Modifier.height(22.dp))
                Box(Modifier.fillMaxWidth().height(30.dp).bone(RoundedCornerShape(8.dp)))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth(0.72f).height(30.dp).bone(RoundedCornerShape(8.dp)))
                Spacer(Modifier.height(22.dp))
                repeat(3) { i ->
                    Box(Modifier.fillMaxWidth(if (i == 2) 0.55f else 1f).height(16.dp).bone(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(18.dp))
                repeat(4) { i ->
                    Box(Modifier.fillMaxWidth(if (i == 3) 0.4f else 1f).height(14.dp).bone(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        FloatingBack(onBack)
    }
}

/** A friendly full-screen message (article gone, offline) with one way forward. */
@Composable
internal fun ReaderMessage(
    emoji: String,
    title: String,
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .horizontalSafeDrawing()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Emoji(emoji, size = 48.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = Reading.articleSubheading(),
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
        FloatingBack(onBack)
    }
}

@Composable
private fun FloatingBack(onBack: () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Row(
            Modifier
                .padding(top = statusBarTop())
                .horizontalSafeDrawing()
                .height(ReaderBarHeight)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Zurück",
                onClick = onBack,
            )
        }
    }
}
