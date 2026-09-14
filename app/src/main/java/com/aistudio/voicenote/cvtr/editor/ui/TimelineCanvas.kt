package com.aistudio.voicenote.cvtr.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import com.aistudio.voicenote.cvtr.editor.model.MAX_TIMELINE_MS
import com.aistudio.voicenote.cvtr.ui.theme.AccentCoral
import com.aistudio.voicenote.cvtr.ui.theme.AccentRoyalBlue
import com.aistudio.voicenote.cvtr.ui.theme.AppCanvasBackground
import com.aistudio.voicenote.cvtr.ui.theme.CardSurfaceWhite
import com.aistudio.voicenote.cvtr.ui.theme.DeepNavyDisplay
import com.aistudio.voicenote.cvtr.ui.theme.LightSlateCaption
import com.aistudio.voicenote.cvtr.ui.theme.PastelAquaCardBg
import com.aistudio.voicenote.cvtr.ui.theme.PastelMintCardBg
import com.aistudio.voicenote.cvtr.ui.theme.PastelMintText
import com.aistudio.voicenote.cvtr.ui.theme.PastelPeachCardBg
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

private val TrackRowHeight = 112.dp
private val TrackHeaderWidth = 136.dp
// Keep adjacent clips reachable on the smallest supported viewport; pinch zoom can
// increase this density when editing a longer recording.
private val BaseDpPerSecond = 18.dp

/**
 * A horizontally scrollable, stacked timeline. Track headers live outside the horizontal
 * scroll container so their controls remain visible while the timeline is panned.
 */
@Composable
internal fun TimelineCanvas(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current.density
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val timelineWidth = (BaseDpPerSecond.value * (MAX_TIMELINE_MS / 1_000f) * zoom).dp
    val dpPerMs = BaseDpPerSecond.value * zoom / 1_000f
    val selectedClipId = state.session.selectedClipId

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(CardSurfaceWhite),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "TRACKS",
                modifier = Modifier
                    .width(TrackHeaderWidth)
                    .padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = LightSlateCaption,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(horizontalScroll),
            ) {
                TimeRuler(
                    timelineWidth = timelineWidth,
                    zoom = zoom,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(AppCanvasBackground),
        ) {
            Column(
                modifier = Modifier
                    .width(TrackHeaderWidth)
                    .fillMaxHeight()
                    .verticalScroll(verticalScroll),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                state.session.tracks.forEach { track ->
                    TrackHeader(
                        track = track,
                        onVolumeChange = { volume ->
                            onIntent(EditorIntent.SetTrackVolume(track.id, volume))
                        },
                        onMuteToggle = {
                            onIntent(EditorIntent.SetTrackMuted(track.id, !track.muted))
                        },
                    )
                }
                if (state.session.tracks.isEmpty()) {
                    Spacer(Modifier.height(TrackRowHeight))
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(horizontalScroll)
                    .verticalScroll(verticalScroll)
                    .pointerInput(horizontalScroll, zoom) {
                        detectTapGestures { offset ->
                            val x = offset.x + horizontalScroll.value
                            val position = (x / (dpPerMs * density)).roundToLong()
                            onIntent(EditorIntent.SelectClip(null))
                            onIntent(EditorIntent.Seek(position))
                        }
                    }
                    .pointerInput(zoom) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            zoom = (zoom * gestureZoom).coerceIn(.65f, 3f)
                            if (pan.x != 0f) {
                                scope.launch {
                                    horizontalScroll.scrollBy(-pan.x)
                                }
                            }
                        }
                    },
            ) {
                Column(
                    modifier = Modifier
                        .width(timelineWidth),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    state.session.tracks.forEach { track ->
                        TrackTimelineRow(
                            track = track,
                            waveformBySource = state.waveformBySource,
                            selectedClipId = selectedClipId,
                            dpPerMs = dpPerMs,
                            onIntent = onIntent,
                        )
                    }
                    if (state.session.tracks.isEmpty()) {
                        Spacer(Modifier.height(TrackRowHeight))
                    }
                }

                // Draw after the rows so the playhead remains visible over waveform content.
                Canvas(
                    modifier = Modifier
                        .width(timelineWidth)
                        .height((state.session.tracks.size * (TrackRowHeight.value + 1f)).dp)
                        .align(Alignment.TopStart)
                        .zIndex(1f)
                        .testTag("editor_playhead"),
                ) {
                    val playheadX = state.session.playheadMs * dpPerMs * density
                    drawLine(
                        color = AccentCoral,
                        start = Offset(playheadX, 0f),
                        end = Offset(playheadX, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeRuler(
    timelineWidth: Dp,
    zoom: Float,
) {
    Row(
        modifier = Modifier
            .width(timelineWidth)
            .fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        (0L..MAX_TIMELINE_MS step 10_000L).forEach { timeMs ->
            Box(
                modifier = Modifier
                    .width((BaseDpPerSecond.value * 10f * zoom).dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = formatTimelineTime(timeMs),
                        color = LightSlateCaption,
                        fontSize = 11.sp,
                    )
                    Spacer(
                        Modifier
                            .padding(top = 2.dp)
                            .width(1.dp)
                            .height(8.dp)
                            .background(LightSlateCaption),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackHeader(
    track: EditorTrack,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(TrackRowHeight)
            .background(CardSurfaceWhite)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag(trackSemanticsTag(track.id)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = track.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
                color = DeepNavyDisplay,
            )
            IconButton(
                onClick = onMuteToggle,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = if (track.muted) Icons.Filled.MicOff else Icons.Filled.VolumeUp,
                    contentDescription = if (track.muted) "Unmute ${track.name}" else "Mute ${track.name}",
                    tint = if (track.muted) AccentCoral else LightSlateCaption,
                )
            }
        }
        Slider(
            value = track.volume.coerceIn(0f, 1.5f),
            onValueChange = onVolumeChange,
            valueRange = 0f..1.5f,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        )
        Text(
            text = "${(track.volume * 100).roundToLong()}%",
            color = LightSlateCaption,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun TrackTimelineRow(
    track: EditorTrack,
    waveformBySource: Map<String, List<Int>>,
    selectedClipId: String?,
    dpPerMs: Float,
    onIntent: (EditorIntent) -> Unit,
) {
    Box(
        modifier = Modifier
            .width((BaseDpPerSecond.value * (MAX_TIMELINE_MS / 1_000f) * (dpPerMs / BaseDpPerSecond.value * 1_000f)).dp)
            .height(TrackRowHeight)
            .background(Color.White.copy(alpha = .55f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        track.clips.forEach { clip ->
            val clipWidth = (clip.timelineDurationMs.coerceAtLeast(400L) * dpPerMs).dp
            val clipOffset = (clip.timelineStartMs * dpPerMs).dp
            TimelineClip(
                clip = clip,
                waveform = waveformBySource[clip.source.uri].orEmpty(),
                selected = selectedClipId == clip.id,
                width = clipWidth,
                offset = clipOffset,
                dpPerMs = dpPerMs,
                onSelect = { onIntent(EditorIntent.SelectClip(clip.id)) },
                onMove = { start -> onIntent(EditorIntent.Move(start)) },
                onTrim = { sourceStart, sourceEnd ->
                    onIntent(EditorIntent.Trim(sourceStart, sourceEnd))
                },
            )
        }
    }
}

@Composable
private fun TimelineClip(
    clip: AudioClip,
    waveform: List<Int>,
    selected: Boolean,
    width: Dp,
    offset: Dp,
    dpPerMs: Float,
    onSelect: () -> Unit,
    onMove: (Long) -> Unit,
    onTrim: (Long, Long) -> Unit,
) {
    var dragOffsetPx by remember(clip.id) { mutableFloatStateOf(0f) }
    var originalSourceStartMs by remember(clip.id) { mutableLongStateOf(clip.sourceStartMs) }
    var originalSourceEndMs by remember(clip.id) { mutableLongStateOf(clip.sourceEndMs) }
    val density = LocalDensity.current.density
    val currentClip by rememberUpdatedState(clip)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnTrim by rememberUpdatedState(onTrim)
    val trimGesturesEnabled = width >= 96.dp
    val interactionWidth = if (width < 48.dp) 48.dp else width
    val interactionPadding = (interactionWidth - width) / 2f
    val interactionOffset = if (offset < interactionPadding) 0.dp else offset - interactionPadding
    val visualOffset = offset - interactionOffset
    Box(
        modifier = Modifier
            .offset(x = interactionOffset)
            .width(interactionWidth)
            .height(88.dp)
            .testTag(clipSemanticsTag(clip.id))
            .semantics {
                this.selected = selected
                contentDescription = buildString {
                    append("Clip ${clip.id}")
                    if (selected) append(", selected")
                    if (!trimGesturesEnabled) append("; drag to move")
                }
            }
            .pointerInput(clip.id, dpPerMs, selected) {
                var dragMode = 0
                var totalTrimDragPx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { startOffset ->
                        dragOffsetPx = 0f
                        totalTrimDragPx = 0f
                        dragMode = when {
                            !currentSelected || !trimGesturesEnabled -> 0
                            startOffset.x <= 48f * density -> 1
                            startOffset.x >= size.width - (48f * density) -> 2
                            else -> 0
                        }
                        if (dragMode != 0) {
                            originalSourceStartMs = currentClip.sourceStartMs
                            originalSourceEndMs = currentClip.sourceEndMs
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        if (dragMode == 0) {
                            dragOffsetPx += dragAmount
                            currentOnMove(
                                (currentClip.timelineStartMs + dragOffsetPx / (dpPerMs * density))
                                    .roundToLong()
                                    .coerceAtLeast(0L)
                            )
                        } else {
                            totalTrimDragPx += dragAmount
                            val timelineDeltaMs = (totalTrimDragPx / (dpPerMs * density)).roundToLong()
                            val deltaMs = (timelineDeltaMs * currentClip.effects.normalizedSpeed).roundToLong()
                            if (dragMode == 1) {
                                currentOnTrim(
                                    (originalSourceStartMs + deltaMs)
                                        .coerceIn(0L, originalSourceEndMs - 1L),
                                    originalSourceEndMs,
                                )
                            } else {
                                currentOnTrim(
                                    originalSourceStartMs,
                                    (originalSourceEndMs + deltaMs)
                                        .coerceIn(originalSourceStartMs + 1L, currentClip.source.sourceDurationOrEnd()),
                                )
                            }
                        }
                    },
                    onDragEnd = {
                        dragOffsetPx = 0f
                        totalTrimDragPx = 0f
                        dragMode = 0
                    },
                    onDragCancel = {
                        dragOffsetPx = 0f
                        totalTrimDragPx = 0f
                        dragMode = 0
                    },
                )
            }
            .clickable(role = Role.Button, onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .offset(x = visualOffset)
                .width(width)
                .height(88.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) PastelMintCardBg else PastelAquaCardBg),
        ) {
            WaveformBars(
                waveform = waveform,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                active = selected,
            )
            Text(
                text = clip.id,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp, top = 7.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) PastelMintText else DeepNavyDisplay,
                maxLines = 1,
            )
            if (selected && trimGesturesEnabled) {
                Canvas(Modifier.matchParentSize()) {
                    drawRoundRect(
                        color = AccentRoyalBlue,
                        style = Stroke(width = 3.dp.toPx()),
                        cornerRadius = CornerRadius(12.dp.toPx()),
                    )
                }
                TrimHandle(
                    modifier = Modifier.align(Alignment.CenterStart),
                    contentDescription = "Trim start of ${clip.id}",
                    onDragStart = {
                        originalSourceStartMs = clip.sourceStartMs
                        originalSourceEndMs = clip.sourceEndMs
                    },
                    onDrag = { deltaPx ->
                        val timelineDeltaMs = (deltaPx / (dpPerMs * density)).roundToLong()
                        val deltaMs = (timelineDeltaMs * clip.effects.normalizedSpeed).roundToLong()
                        onTrim(
                            (originalSourceStartMs + deltaMs)
                                .coerceIn(0L, originalSourceEndMs - 1L),
                            originalSourceEndMs,
                        )
                    },
                )
                TrimHandle(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    contentDescription = "Trim end of ${clip.id}",
                    onDragStart = {
                        originalSourceStartMs = clip.sourceStartMs
                        originalSourceEndMs = clip.sourceEndMs
                    },
                    onDrag = { deltaPx ->
                        val timelineDeltaMs = (deltaPx / (dpPerMs * density)).roundToLong()
                        val deltaMs = (timelineDeltaMs * clip.effects.normalizedSpeed).roundToLong()
                        onTrim(
                            originalSourceStartMs,
                            (originalSourceEndMs + deltaMs)
                                .coerceIn(originalSourceStartMs + 1L, clip.source.sourceDurationOrEnd()),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun TrimHandle(
    modifier: Modifier,
    contentDescription: String,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
) {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 72.dp)
            .background(AccentRoyalBlue.copy(alpha = .9f), RoundedCornerShape(8.dp))
            .semantics {
                this.contentDescription = contentDescription
                this.stateDescription = "Drag horizontally to adjust"
            }
            .pointerInput(contentDescription) {
                var totalDragPx = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        totalDragPx = 0f
                        currentOnDragStart()
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        totalDragPx += amount
                        currentOnDrag(totalDragPx)
                    },
                    onDragEnd = { totalDragPx = 0f },
                    onDragCancel = { totalDragPx = 0f },
                )
            },
    )
}

@Composable
private fun WaveformBars(
    waveform: List<Int>,
    modifier: Modifier,
    active: Boolean,
) {
    Canvas(modifier) {
        val bars = waveform.ifEmpty { List(32) { (it % 7) + 2 } }
        val barWidth = (size.width / (bars.size * 1.8f)).coerceAtLeast(2.dp.toPx())
        val gap = (size.width - bars.size * barWidth) / (bars.size + 1)
        bars.forEachIndexed { index, peak ->
            val height = (peak.coerceIn(1, 32) / 32f * size.height).coerceAtLeast(3.dp.toPx())
            drawRoundRect(
                color = if (active) AccentRoyalBlue else LightSlateCaption,
                topLeft = Offset(gap + index * (barWidth + gap), (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

private fun formatTimelineTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

private fun clipSemanticsTag(id: String): String =
    if (id.startsWith("clip-")) id else "clip-$id"

private fun trackSemanticsTag(id: String): String =
    if (id.startsWith("track-")) id else "track-$id"

private fun com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef.sourceDurationOrEnd(): Long =
    durationMs.takeIf { it >= 0L } ?: Long.MAX_VALUE
