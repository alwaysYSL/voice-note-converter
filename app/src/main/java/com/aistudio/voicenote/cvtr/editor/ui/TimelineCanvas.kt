package com.aistudio.voicenote.cvtr.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.voicenote.cvtr.editor.model.AudioTrackClip
import com.aistudio.voicenote.cvtr.editor.model.EditorTimelineState
import com.aistudio.voicenote.cvtr.ui.theme.AccentCoral
import com.aistudio.voicenote.cvtr.ui.theme.AccentPeriwinkle
import com.aistudio.voicenote.cvtr.ui.theme.AccentRoyalBlue
import com.aistudio.voicenote.cvtr.ui.theme.CardSurfaceWhite
import com.aistudio.voicenote.cvtr.ui.theme.DarkNavyHeadline
import com.aistudio.voicenote.cvtr.ui.theme.DeepNavyDisplay
import com.aistudio.voicenote.cvtr.ui.theme.LightSlateCaption
import com.aistudio.voicenote.cvtr.ui.theme.PastelMintCardBg
import com.aistudio.voicenote.cvtr.ui.theme.PastelPeriwinkleCardBg
import com.aistudio.voicenote.cvtr.ui.theme.SubtleBorder
import java.util.Locale

private const val PIXELS_PER_SECOND = 60f // 60dp per second

@Composable
fun TimelineCanvas(
    state: EditorTimelineState,
    onTrackSelected: (Int?) -> Unit,
    onAddTrackClicked: (Int) -> Unit,
    onClipOffsetChanged: (slotIndex: Int, newOffsetMs: Long) -> Unit,
    onClipTrimChanged: (slotIndex: Int, trimStartMs: Long, trimEndMs: Long) -> Unit,
    onSeekRequested: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val totalSeconds = maxOf(10, ((state.totalDurationMs + 2000L) / 1000L).toInt())
    val canvasWidthDp = (totalSeconds * PIXELS_PER_SECOND).dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardSurfaceWhite)
            .border(1.dp, SubtleBorder, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp)
    ) {
        // Scrollable Timeline Content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
        ) {
            Column(
                modifier = Modifier
                    .width(canvasWidthDp)
                    .padding(horizontal = 16.dp)
            ) {
                // 1. Time Ruler Header
                TimeRuler(
                    totalSeconds = totalSeconds,
                    onSeek = onSeekRequested
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 2. Three Track Lanes
                for (slotIndex in 0..2) {
                    val track = state.tracks.getOrNull(slotIndex)
                    val isSelected = state.selectedTrackIndex == slotIndex

                    TrackLane(
                        slotIndex = slotIndex,
                        clip = track,
                        isSelected = isSelected,
                        onSelect = { onTrackSelected(slotIndex) },
                        onAdd = { onAddTrackClicked(slotIndex) },
                        onOffsetChange = { offsetMs -> onClipOffsetChanged(slotIndex, offsetMs) },
                        onTrimChange = { startMs, endMs -> onClipTrimChanged(slotIndex, startMs, endMs) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .padding(vertical = 4.dp)
                    )
                }
            }

            // 3. Vertical Playhead Line
            val playheadOffsetDp = ((state.playheadPositionMs / 1000f) * PIXELS_PER_SECOND + 16f).dp
            Box(
                modifier = Modifier
                    .offset(x = playheadOffsetDp)
                    .width(2.dp)
                    .height(236.dp)
                    .background(AccentCoral)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .offset(x = (-4).dp, y = 0.dp)
                        .clip(CircleShape)
                        .background(AccentCoral)
                )
            }
        }
    }
}

@Composable
private fun TimeRuler(
    totalSeconds: Int,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(totalSeconds) {
                detectDragGestures { change, _ ->
                    val x = change.position.x
                    val second = (x / (PIXELS_PER_SECOND * density)).coerceAtLeast(0f)
                    onSeek((second * 1000).toLong())
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pxPerSec = PIXELS_PER_SECOND * density
            for (sec in 0..totalSeconds) {
                val x = sec * pxPerSec
                drawLine(
                    color = LightSlateCaption.copy(alpha = 0.5f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            for (sec in 0..totalSeconds step 2) {
                Box(
                    modifier = Modifier
                        .width((PIXELS_PER_SECOND * 2).dp)
                ) {
                    Text(
                        text = "${sec}s",
                        color = LightSlateCaption,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackLane(
    slotIndex: Int,
    clip: AudioTrackClip?,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onAdd: () -> Unit,
    onOffsetChange: (Long) -> Unit,
    onTrimChange: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackLabel = "Track ${slotIndex + 1}"

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PastelPeriwinkleCardBg.copy(alpha = 0.4f))
            .border(
                width = if (isSelected) 1.5.dp else 0.5.dp,
                color = if (isSelected) AccentRoyalBlue else SubtleBorder,
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        if (clip == null) {
            // Empty Slot Button
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onAdd() }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = AccentRoyalBlue,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "+ $trackLabel (Kosong)",
                    color = AccentRoyalBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            // Filled Clip with Handles
            val clipOffsetDp = ((clip.startOffsetMs / 1000f) * PIXELS_PER_SECOND).dp
            val clipWidthDp = maxOf(40f, (clip.activeDurationMs / 1000f) * PIXELS_PER_SECOND).dp

            Box(
                modifier = Modifier
                    .offset(x = clipOffsetDp)
                    .width(clipWidthDp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) PastelMintCardBg else CardSurfaceWhite)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) AccentRoyalBlue else AccentPeriwinkle.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect() }
            ) {
                // Waveform background in clip
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    val points = clip.waveformPoints
                    if (points.isNotEmpty()) {
                        val barWidth = size.width / points.size
                        for (i in points.indices) {
                            val barHeight = points[i] * size.height
                            val x = i * barWidth
                            val y = (size.height - barHeight) / 2f
                            drawLine(
                                color = if (isSelected) AccentRoyalBlue.copy(alpha = 0.5f) else LightSlateCaption.copy(alpha = 0.4f),
                                start = Offset(x, y),
                                end = Offset(x, y + barHeight),
                                strokeWidth = maxOf(1f, barWidth - 1f),
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }

                // Middle Content: Drag to Move Offset
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp)
                        .pointerInput(clip.id) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val deltaMs = ((dragAmount.x / (PIXELS_PER_SECOND * density)) * 1000L).toLong()
                                val newOffset = (clip.startOffsetMs + deltaMs).coerceAtLeast(0L)
                                onOffsetChange(newOffset)
                            }
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column {
                        Text(
                            text = clip.displayName,
                            color = DeepNavyDisplay,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        val durationSec = clip.activeDurationMs / 1000f
                        Text(
                            text = String.format(Locale.US, "%.1fs", durationSec),
                            color = DarkNavyHeadline,
                            fontSize = 10.sp
                        )
                    }
                }

                // Left Trim Handle (In-Point)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(12.dp)
                        .fillMaxHeight()
                        .background(AccentRoyalBlue.copy(alpha = 0.85f))
                        .pointerInput(clip.id) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val deltaMs = ((dragAmount.x / (PIXELS_PER_SECOND * density)) * 1000L).toLong()
                                val newTrimStart = (clip.trimStartMs + deltaMs).coerceIn(0L, clip.trimEndMs - 500L)
                                onTrimChange(newTrimStart, clip.trimEndMs)
                            }
                        }
                )

                // Right Trim Handle (Out-Point)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(12.dp)
                        .fillMaxHeight()
                        .background(AccentRoyalBlue.copy(alpha = 0.85f))
                        .pointerInput(clip.id) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val deltaMs = ((dragAmount.x / (PIXELS_PER_SECOND * density)) * 1000L).toLong()
                                val newTrimEnd = (clip.trimEndMs + deltaMs).coerceIn(clip.trimStartMs + 500L, clip.durationMs)
                                onTrimChange(clip.trimStartMs, newTrimEnd)
                            }
                        }
                )
            }
        }
    }
}