package com.aistudio.voicenote.cvtr.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aistudio.voicenote.cvtr.ui.theme.AccentCoral
import com.aistudio.voicenote.cvtr.ui.theme.SubtleBorder
import kotlin.math.abs
import kotlin.math.floor

@Composable
fun WaveformVisualizer(
    waveform: List<Int>,
    progress: Float,
    modifier: Modifier = Modifier,
    activeColor: Color = AccentCoral,
    inactiveColor: Color = SubtleBorder,
    playheadColor: Color = AccentCoral,
    barWidth: Dp = 3.dp,
    barCornerRadius: Dp = 2.dp,
    minBarHeight: Dp = 2.dp,
    height: Dp = 48.dp,
    onSeek: ((Float) -> Unit)? = null,
    trimRange: ClosedFloatingPointRange<Float>? = null,
    onTrimRangeChange: ((ClosedFloatingPointRange<Float>) -> Unit)? = null,
    showPlayhead: Boolean = true,
    showTrimHandles: Boolean = false,
    isInteractive: Boolean = true
) {
    val sourceBars = remember(waveform) {
        waveform.ifEmpty { List(38) { 1 } }
    }
    val currentOnSeek = rememberUpdatedState(onSeek)
    val currentTrimRange = rememberUpdatedState(trimRange)
    val currentOnTrimRangeChange = rememberUpdatedState(onTrimRangeChange)
    val gestureModifier = if (!isInteractive) {
        Modifier
    } else {
        Modifier
            .then(if (onSeek != null) Modifier.pointerInput(Unit) {
                detectTapGestures { offset ->
                    currentOnSeek.value?.invoke((offset.x / size.width).coerceIn(0f, 1f))
                }
            } else Modifier)
            .then(
                if (showTrimHandles && trimRange != null && onTrimRangeChange != null) {
                    Modifier.pointerInput(showTrimHandles) {
                        var draggingStart = true
                        val minGap = 0.02f
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                val range = currentTrimRange.value ?: return@detectHorizontalDragGestures
                                val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                                draggingStart = abs(fraction - range.start) <
                                    abs(fraction - range.endInclusive)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val range = currentTrimRange.value
                                    ?: return@detectHorizontalDragGestures
                                val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                if (draggingStart) {
                                    val normalizedEnd = range.endInclusive.coerceAtLeast(minGap)
                                    val maxStart = normalizedEnd - minGap
                                    currentOnTrimRangeChange.value?.invoke(
                                        fraction.coerceIn(0f, maxStart)..normalizedEnd
                                    )
                                } else {
                                    val normalizedStart = range.start.coerceAtMost(1f - minGap)
                                    val minEnd = normalizedStart + minGap
                                    currentOnTrimRangeChange.value?.invoke(
                                        normalizedStart..fraction.coerceIn(minEnd, 1f)
                                    )
                                }
                            }
                        )
                    }
                } else if (onSeek != null) {
                    Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            currentOnSeek.value?.invoke(
                                (change.position.x / size.width).coerceIn(0f, 1f)
                            )
                        }
                    }
                } else Modifier
            )
    }

    Canvas(modifier.fillMaxWidth().height(height).then(gestureModifier)) {
        val barWidthPx = barWidth.toPx()
        val maxBars = floor(size.width / (barWidthPx + 1f)).toInt().coerceAtLeast(1)
        val bars = if (sourceBars.size <= maxBars) sourceBars else List(maxBars) { index ->
            sourceBars[index * sourceBars.size / maxBars]
        }
        val spacing = if (bars.size > 1) {
            ((size.width - bars.size * barWidthPx) / (bars.size - 1)).coerceAtLeast(0f)
        } else 0f
        val safeProgress = progress.coerceIn(0f, 1f)
        val minHeightPx = minBarHeight.toPx()

        bars.forEachIndexed { index, amplitude ->
            val fraction = if (bars.size > 1) index.toFloat() / (bars.size - 1) else 0f
            val insideTrim = trimRange == null || fraction in trimRange
            val alpha = if (insideTrim) 1f else .3f
            val barHeight = minHeightPx + amplitude.coerceIn(0, 31) / 31f * (size.height - minHeightPx)
            drawRoundRect(
                color = (if (fraction <= safeProgress) activeColor else inactiveColor).copy(alpha = alpha),
                topLeft = Offset(index * (barWidthPx + spacing), (size.height - barHeight) / 2f),
                size = Size(barWidthPx, barHeight),
                cornerRadius = CornerRadius(barCornerRadius.toPx())
            )
        }
        if (showPlayhead) {
            val x = safeProgress * size.width
            drawLine(playheadColor, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
        }
        if (showTrimHandles && trimRange != null) {
            listOf(trimRange.start, trimRange.endInclusive).forEach { fraction ->
                drawRoundRect(
                    AccentCoral,
                    Offset(fraction * size.width - 2.dp.toPx(), 0f),
                    Size(4.dp.toPx(), size.height),
                    CornerRadius(2.dp.toPx())
                )
            }
        }
    }
}
