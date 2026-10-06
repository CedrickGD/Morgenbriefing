package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.data.model.DayWeather
import com.cedrickgd.morgenbriefing.data.model.HourPoint
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import com.cedrickgd.morgenbriefing.domain.Condition
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.domain.WeatherInsights
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/** Rain bars get a percentage label from this probability on. */
private const val POP_LABEL_FROM = 30

/** The curve never exaggerates small swings: at least this many degrees span the chart. */
private const val MIN_TEMP_RANGE = 6.0

/**
 * "Leipzig · 10–18 Uhr": hand-drawn temperature curve + rain bars + day summary, on a glass card.
 *
 * The chart is one Canvas: a smooth, non-overshooting temperature line with a soft fill, hour
 * dots with labels, condition emojis, rain-probability bars and a dashed "jetzt" marker while
 * the window is running. The line draws itself in once (skipped with reduced motion).
 */
@Composable
fun DayCurveCard(snapshot: WeatherSnapshot, modifier: Modifier = Modifier) {
    val day = snapshot.day
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val hours = remember(day.hours) { day.hours.sortedBy { it.time } }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 20.dp, bottom = 20.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dayCurveTitle(day),
                    style = typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
                    color = colors.onSurfaceVariant,
                    // Large font sizes: a second line instead of losing "UHR" silently.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${Fmt.temp(day.minTemp)} – ${Fmt.temp(day.maxTemp)}",
                    style = typography.labelLarge,
                    color = colors.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))

            if (hours.size >= 2) {
                DayCurveChart(
                    day = day,
                    hours = hours,
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .fillMaxWidth(),
                )
            } else {
                Text(
                    text = "Für dieses Zeitfenster gibt es gerade keine Stundenwerte.",
                    style = typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            val summary = WeatherInsights.daySummary(snapshot)
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = summary,
                    style = typography.bodyMedium,
                    color = colors.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }
}

private class ChartLabels(
    val temps: List<TextLayoutResult>,
    val hours: List<TextLayoutResult>,
    val emojis: List<TextLayoutResult>,
    val pops: List<TextLayoutResult?>,
    val now: TextLayoutResult,
)

private class RainColors(val bar: Color, val text: Color)

/**
 * Vertical layout of the chart in px. At the default font size it is the original fixed one
 * (emoji row 22 dp, curve from 48 dp, 46 dp for the rain bars below it, 20 dp hour axis, 190 dp
 * in all); with larger fonts each band grows with its labels, and the chart with them, so the
 * temperature labels never run into the emoji row.
 */
private class ChartGeometry(val emojiRow: Float, val curveTop: Float, val belowCurve: Float, val axis: Float, val height: Dp) {
    companion object {
        fun of(labels: ChartLabels, density: Density): ChartGeometry = with(density) {
            val emojiRow = max(22.dp.toPx(), labels.emojis.maxOf { it.size.height } + 2.dp.toPx())
            val tempHeight = labels.temps.maxOf { it.size.height }
            val curveTop = max(48.dp.toPx(), emojiRow + tempHeight + DOT_RADIUS.toPx() + 4.dp.toPx())
            val popHeight = labels.pops.maxOfOrNull { it?.size?.height ?: 0 } ?: 0
            val belowCurve = max(46.dp.toPx(), BAR_MAX.toPx() + popHeight)
            val axis = max(20.dp.toPx(), labels.hours.maxOf { it.size.height } + 2.dp.toPx())
            val extra = (curveTop - 48.dp.toPx()) + (belowCurve - 46.dp.toPx()) + (axis - 20.dp.toPx())
            ChartGeometry(emojiRow, curveTop, belowCurve, axis, 190.dp + extra.toDp())
        }
    }
}

private val DOT_RADIUS = 4.5.dp
private val BAR_MAX = 30.dp

@Composable
private fun DayCurveChart(day: DayWeather, hours: List<HourPoint>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val dark = colors.background.luminance() < 0.5f
    val primary = colors.primary
    // Hollow dots are filled with the glass tint so the line seems to pass behind them.
    val cardColor = colors.surface
    val rain = if (dark) {
        RainColors(bar = Color(0xFF6EA0F0).copy(alpha = 0.55f), text = Color(0xFF9CC0FA))
    } else {
        RainColors(bar = Color(0xFF4C84D9).copy(alpha = 0.5f), text = Color(0xFF2F5FA8))
    }

    val measurer = rememberTextMeasurer(cacheSize = 48)
    val tempStyle = typography.labelMedium.copy(color = colors.onSurface, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    val hourStyle = typography.labelSmall.copy(color = colors.onSurfaceVariant, letterSpacing = 0.sp)
    val popStyle = typography.labelSmall.copy(color = rain.text, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    val nowStyle = typography.labelSmall.copy(color = colors.onPrimary, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
    val emojiStyle = TextStyle(fontSize = 15.sp)

    val labels = remember(hours, tempStyle, hourStyle, popStyle, nowStyle, measurer) {
        ChartLabels(
            temps = hours.map { measurer.measure(Fmt.temp(it.temp), tempStyle) },
            hours = hours.map { measurer.measure(it.hour.toString(), hourStyle) },
            emojis = hours.map { measurer.measure(Condition.fromCode(it.code).emoji(it.isDay), emojiStyle) },
            pops = hours.map { if (it.pop >= POP_LABEL_FROM) measurer.measure("${it.pop} %", popStyle) else null },
            now = measurer.measure("jetzt", nowStyle),
        )
    }

    val density = LocalDensity.current
    val geometry = remember(labels, density) { ChartGeometry.of(labels, density) }

    val now by rememberBerlinMinute()
    val nowIndex = remember(day, hours, now) { nowFraction(day, hours, now) }
    val description = remember(day, hours) { chartDescription(day, hours) }

    // Draw-in once per day window; remembered across recompositions and list recycling.
    val skip = motionDisabled()
    val drawKey = "${day.place.name}|${day.date}|${day.isTomorrow}"
    var played by rememberSaveable(drawKey) { mutableStateOf(false) }
    val reveal = remember(drawKey) { Animatable(if (played || skip) 1f else 0f) }
    LaunchedEffect(drawKey) {
        if (reveal.value < 1f) reveal.animateTo(1f, tween(durationMillis = 900, easing = Emphasized))
        played = true
    }

    Spacer(
        modifier
            .height(geometry.height)
            .semantics { contentDescription = description }
            .drawWithCache {
                val n = hours.size
                val inset = 18.dp.toPx()
                val left = inset
                val right = size.width - inset
                val step = (right - left) / (n - 1)
                val xs = FloatArray(n) { left + it * step }

                val emojiRow = geometry.emojiRow
                val curveTop = geometry.curveTop
                val plotBottom = size.height - geometry.axis
                val curveBottom = plotBottom - geometry.belowCurve
                val barMax = BAR_MAX.toPx()

                val minT = hours.minOf { it.temp }
                val maxT = hours.maxOf { it.temp }
                val range = max(maxT - minT, MIN_TEMP_RANGE)
                val low = (minT + maxT) / 2.0 - range / 2.0
                val ys = FloatArray(n) { i ->
                    (curveBottom - ((hours[i].temp - low) / range) * (curveBottom - curveTop)).toFloat()
                }
                val tangents = CurveMath.monotoneTangents(xs, ys)
                val line = CurveMath.smoothPath(xs, ys, tangents)
                val fill = Path().apply {
                    addPath(line)
                    lineTo(xs.last(), plotBottom)
                    lineTo(xs.first(), plotBottom)
                    close()
                }
                val fillBrush = Brush.verticalGradient(
                    listOf(primary.copy(alpha = 0.2f), primary.copy(alpha = 0f)),
                    startY = curveTop,
                    endY = plotBottom,
                )
                val measure = PathMeasure().apply { setPath(line, false) }
                val lineLength = measure.length
                val segment = Path()
                val lineStroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val ringStroke = Stroke(width = 2.dp.toPx())
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                val dotRadius = DOT_RADIUS.toPx()
                val revealSoftness = 12.dp.toPx()

                val tempEvery = if (step >= 30.dp.toPx()) 1 else 2
                val emojiEvery = if (step >= 24.dp.toPx()) 1 else 2
                val hourEvery = if (step >= 22.dp.toPx()) 1 else 2
                val barWidth = min(step * 0.42f, 14.dp.toPx())
                val barCorner = CornerRadius(4.dp.toPx())

                val nowX = nowIndex?.let { left + it * step }
                val nowY = nowX?.let { CurveMath.valueAt(it, xs, ys, tangents) }
                val pillPadH = 6.dp.toPx()
                val pillPadV = 2.dp.toPx()
                val pillSize = Size(labels.now.size.width + pillPadH * 2, labels.now.size.height + pillPadV * 2)
                val pillRect = nowX?.let {
                    Rect(
                        offset = Offset(
                            (it - pillSize.width / 2f).coerceIn(0f, size.width - pillSize.width),
                            size.height - pillSize.height,
                        ),
                        size = pillSize,
                    )
                }

                onDrawBehind {
                    val p = reveal.value
                    val revealX = left + (right - left) * p
                    fun appear(x: Float) = ((revealX - x) / revealSoftness + 1f).coerceIn(0f, 1f)

                    // Rain probability bars along the bottom.
                    for (i in 0 until n) {
                        val pop = hours[i].pop.coerceIn(0, 100)
                        if (pop <= 0) continue
                        val a = appear(xs[i])
                        val barHeight = max(barMax * pop / 100f, 3.dp.toPx())
                        drawRoundRect(
                            color = rain.bar,
                            topLeft = Offset(xs[i] - barWidth / 2f, plotBottom - barHeight),
                            size = Size(barWidth, barHeight),
                            cornerRadius = barCorner,
                            alpha = a,
                        )
                        labels.pops[i]?.let { label ->
                            drawText(
                                label,
                                topLeft = Offset(
                                    (xs[i] - label.size.width / 2f).coerceIn(0f, size.width - label.size.width),
                                    plotBottom - barHeight - label.size.height - 2.dp.toPx(),
                                ),
                                alpha = a,
                            )
                        }
                    }

                    // Soft fill under the curve, revealed left to right.
                    clipRect(right = revealX) { drawPath(fill, fillBrush) }

                    // "jetzt" marker behind the line, fading in at the end of the draw-in.
                    val nowAlpha = ((p - 0.8f) / 0.2f).coerceIn(0f, 1f)
                    if (nowX != null && nowAlpha > 0f) {
                        drawLine(
                            color = primary.copy(alpha = 0.6f * nowAlpha),
                            start = Offset(nowX, curveTop - 14.dp.toPx()),
                            end = Offset(nowX, plotBottom),
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = dash,
                        )
                    }

                    // The temperature line.
                    if (p >= 1f) {
                        drawPath(line, primary, style = lineStroke)
                    } else if (p > 0f) {
                        segment.reset()
                        measure.getSegment(0f, lineLength * p, segment, true)
                        drawPath(segment, primary, style = lineStroke)
                    }

                    // Hour dots, temperature labels and condition emojis.
                    for (i in 0 until n) {
                        val a = appear(xs[i])
                        if (a <= 0f) continue
                        val center = Offset(xs[i], ys[i])
                        drawCircle(cardColor, radius = dotRadius, center = center, alpha = a)
                        drawCircle(primary, radius = dotRadius, center = center, style = ringStroke, alpha = a)
                        if (i % tempEvery == 0) {
                            val label = labels.temps[i]
                            drawText(
                                label,
                                topLeft = Offset(
                                    (xs[i] - label.size.width / 2f).coerceIn(0f, size.width - label.size.width),
                                    ys[i] - dotRadius - 4.dp.toPx() - label.size.height,
                                ),
                                alpha = a,
                            )
                        }
                        if (i % emojiEvery == 0) {
                            val emoji = labels.emojis[i]
                            drawText(
                                emoji,
                                topLeft = Offset(xs[i] - emoji.size.width / 2f, (emojiRow - emoji.size.height) / 2f),
                                alpha = a,
                            )
                        }
                    }

                    // Hour axis (always visible); labels under the "jetzt" pill step aside.
                    for (i in 0 until n) {
                        if (i % hourEvery != 0) continue
                        val label = labels.hours[i]
                        val topLeft = Offset(xs[i] - label.size.width / 2f, size.height - label.size.height)
                        val bounds = Rect(topLeft, Size(label.size.width.toFloat(), label.size.height.toFloat()))
                        if (pillRect != null && nowAlpha > 0f && bounds.overlaps(pillRect.inflate(2.dp.toPx()))) continue
                        drawText(label, topLeft = topLeft)
                    }

                    if (nowX != null && nowY != null && pillRect != null && nowAlpha > 0f) {
                        drawCircle(cardColor, radius = 7.dp.toPx(), center = Offset(nowX, nowY), alpha = nowAlpha)
                        drawCircle(primary, radius = 5.dp.toPx(), center = Offset(nowX, nowY), alpha = nowAlpha)
                        drawRoundRect(
                            color = primary,
                            topLeft = pillRect.topLeft,
                            size = pillRect.size,
                            cornerRadius = CornerRadius(pillRect.height / 2f),
                            alpha = nowAlpha,
                        )
                        drawText(
                            labels.now,
                            topLeft = Offset(pillRect.left + pillPadH, pillRect.top + pillPadV),
                            alpha = nowAlpha,
                        )
                    }
                }
            },
    )
}

/** "LEIPZIG · 10–18 UHR", with "MORGEN · " in front for tomorrow's window. */
private fun dayCurveTitle(day: DayWeather): String {
    val place = day.place.name.uppercase(Locale.GERMAN)
    val title = "$place · ${day.startHour}–${day.endHour} UHR"
    return if (day.isTomorrow) "MORGEN · $title" else title
}

/** Spoken summary of the chart for screen readers. */
private fun chartDescription(day: DayWeather, hours: List<HourPoint>): String = buildString {
    append("Temperaturverlauf ")
    if (day.isTomorrow) append("morgen ")
    append("in ${day.place.name} von ${day.startHour} bis ${day.endHour} Uhr: ")
    append(
        hours.joinToString(", ") { hour ->
            val rain = if (hour.pop >= POP_LABEL_FROM) ", Regen ${hour.pop} Prozent" else ""
            "${hour.hour} Uhr ${Fmt.temp(hour.temp)}$rain"
        },
    )
    append('.')
}

/**
 * Fractional index into [hours] for the current Berlin time, or null when the window is not
 * running right now (tomorrow's window, another day, before the start or after the end).
 */
private fun nowFraction(day: DayWeather, hours: List<HourPoint>, now: ZonedDateTime): Float? {
    if (day.isTomorrow) return null
    val date = runCatching { LocalDate.parse(day.date.trim()) }.getOrNull() ?: return null
    if (date != now.toLocalDate()) return null
    val index = hours.indexOfFirst { point ->
        val time = runCatching { LocalDateTime.parse(point.time.trim()) }.getOrNull()
        if (time != null) time.toLocalDate() == date && time.hour == now.hour else point.hour == now.hour
    }
    if (index < 0) return null
    val fraction = index + now.minute / 60f
    return if (fraction > hours.lastIndex) null else fraction
}

/** Current Berlin time, refreshed at every full minute while on screen. */
@Composable
private fun rememberBerlinMinute(): State<ZonedDateTime> =
    produceState(initialValue = ZonedDateTime.now(BERLIN)) {
        while (true) {
            val current = ZonedDateTime.now(BERLIN)
            value = current
            delay(((60 - current.second) * 1_000L).coerceAtLeast(1_000L))
        }
    }
