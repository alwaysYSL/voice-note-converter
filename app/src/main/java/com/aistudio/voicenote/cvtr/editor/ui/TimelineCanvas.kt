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
import androidx.compose.ui.draw.alpha
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
import com.aistudio.voicenote.cvtr.editor.model.TrimEdge
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
    val dpPerMs = BaseDpPerSecond.value * zoom / 1_000f

    val maxClipEndMs = state.session.tracks
        .flatMap { it.clips }
        .maxOfOrNull { it.timelineEndMs } ?: 0L
    val effectiveDurationMs = maxOf(maxClipEndMs, state.session.playheadMs, 5_000L)
    val tailPaddingMs = 3_000L
    val dynamicDurationMs = (effectiveDurationMs + tailPaddingMs).coerceAtMost(MAX_TIMELINE_MS)
    val timelineWidth = maxOf(320.dp, (dynamicDurationMs * dpPerMs).dp)
    val selectedClipId = state.session.selectedClipId

    val currentHorizontalScroll by rememberUpdatedState(horizontalScroll)
    val currentOnIntent by rememberUpdatedState(onIntent)
    val currentDensity by rememberUpdatedState(density)

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
                    durationMs = dynamicDurationMs,
                    dpPerMs = dpPerMs,
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
                        enabled = track.clips.none { it.id in state.offlineClipIds },
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
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val currentDpPerMs = BaseDpPerSecond.value * zoom / 1_000f
                            val x = offset.x + currentHorizontalScroll.value
                            val position = (x / (currentDpPerMs * currentDensity)).roundToLong()
                            currentOnIntent(EditorIntent.SelectClip(null))
                            currentOnIntent(EditorIntent.Seek(position))
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                            if (gestureZoom != 1f) {
                                val oldZoom = zoom
                                val newZoom = (oldZoom * gestureZoom).coerceIn(0.5f, 4f)
                                if (newZoom != oldZoom) {
                                    val oldDpPerMs = BaseDpPerSecond.value * oldZoom / 1_000f
                                    val newDpPerMs = BaseDpPerSecond.value * newZoom / 1_000f
                                    val anchorTimeMs = (centroid.x + currentHorizontalScroll.value) / (oldDpPerMs * currentDensity)
                                    zoom = newZoom
                                    val newScrollTarget = (anchorTimeMs * newDpPerMs * currentDensity) - centroid.x
                                    scope.launch {
                                        currentHorizontalScroll.scrollTo(newScrollTarget.roundToLong().toInt().coerceAtLeast(0))
                                    }
                                }
                            }
                            if (pan.x != 0f) {
                                scope.launch {
                                    currentHorizontalScroll.scrollBy(-pan.x)
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
                            offlineClipIds = state.offlineClipIds,
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
    durationMs: Long,
    dpPerMs: Float,
) {
    val stepMs = when {
        dpPerMs >= 0.08f -> 1_000L
        dpPerMs >= 0.035f -> 2_000L
        dpPerMs >= 0.015f -> 5_000L
        else -> 10_000L
    }
    Row(
        modifier = Modifier
            .width(timelineWidth)
            .fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val count = ((durationMs / stepMs) + 1).toInt()
        (0 until count).forEach { i ->
            val timeMs = i * stepMs
            val boxWidth = (stepMs * dpPerMs).dp
            Box(
                modifier = Modifier
                    .width(boxWidth)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = formatTimelineTime(timeMs),
                        color = LightSlateCaption,
                        fontSize = 10.sp,
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
    enabled: Boolean = true,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(TrackRowHeight)
            .background(CardSurfaceWhite)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag(trackSemanticsTag(track.id))
            .alpha(if (enabled) 1f else .55f)
            .semantics {
                stateDescription = when {
                    !enabled -> "Offline; replace source"
                    track.muted -> "Muted"
                    else -> "Active"
                }
            },
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
                enabled = enabled,
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
            enabled = enabled,
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
    offlineClipIds: Set<String> = emptySet(),
    dpPerMs: Float,
    onIntent: (EditorIntent) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TrackRowHeight)
            .background(Color.White.copy(alpha = .55f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        track.clips.forEach { clip ->
            val clipWidth = (clip.timelineDurationMs.coerceAtLeast(400L) * dpPerMs).dp
            val clipOffset = (clip.timelineStartMs * dpPerMs).dp
            val fullPeaks = waveformBySource[clip.source.uri].orEmpty()
            val slicedPeaks = remember(fullPeaks, clip.sourceStartMs, clip.sourceEndMs, clip.source.durationMs) {
                visiblePeaks(
                    allPeaks = fullPeaks,
                    sourceStartMs = clip.sourceStartMs,
                    sourceEndMs = clip.sourceEndMs,
                    sourceDurationMs = clip.source.durationMs,
                )
            }
            TimelineClip(
                clip = clip,
                waveform = slicedPeaks,
                selected = selectedClipId == clip.id,
                enabled = clip.id !in offlineClipIds,
                width = clipWidth,
                offset = clipOffset,
                dpPerMs = dpPerMs,
                onSelect = { onIntent(EditorIntent.SelectClip(clip.id)) },
                onIntent = onIntent,
            )
        }
    }
}

@Composable
private fun TimelineClip(
    clip: AudioClip,
    waveform: List<Int>,
    selected: Boolean,
    enabled: Boolean = true,
    width: Dp,
    offset: Dp,
    dpPerMs: Float,
    onSelect: () -> Unit,
    onIntent: (EditorIntent) -> Unit,
) {
    var originStartMs by remember(clip.id) { mutableLongStateOf(clip.timelineStartMs) }
    var originalSourceStartMs by remember(clip.id) { mutableLongStateOf(clip.sourceStartMs) }
    var originalSourceEndMs by remember(clip.id) { mutableLongStateOf(clip.sourceEndMs) }
    var dragOffsetPx by remember(clip.id) { mutableFloatStateOf(0f) }
    var totalTrimDragPx by remember(clip.id) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current.density
    val currentClip by rememberUpdatedState(clip)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnIntent by rememberUpdatedState(onIntent)
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
            .alpha(if (enabled) 1f else .45f)
            .semantics {
                this.selected = selected
                stateDescription = when {
                    !enabled -> "Offline; replace source"
                    selected -> "Selected"
                    else -> "Available"
                }
                contentDescription = buildString {
                    append("Clip ${clip.id}")
                    if (selected) append(", selected")
                    if (!enabled) append(", offline")
                    if (!trimGesturesEnabled) append("; drag to move")
                }
            }
            .pointerInput(clip.id, dpPerMs, selected, enabled) {
                if (!enabled) return@pointerInput
                var dragMode = 0
                detectHorizontalDragGestures(
                    onDragStart = { startOffset ->
                        dragOffsetPx = 0f
                        totalTrimDragPx = 0f
                        originStartMs = currentClip.timelineStartMs
                        originalSourceStartMs = currentClip.sourceStartMs
                        originalSourceEndMs = currentClip.sourceEndMs
                        dragMode = when {
                            !currentSelected || !trimGesturesEnabled -> 0
                            startOffset.x <= 48f * density -> 1
                            startOffset.x >= size.width - (48f * density) -> 2
                            else -> 0
                        }
                        if (dragMode == 0) {
                            currentOnIntent(EditorIntent.BeginMove(currentClip.id))
                        } else {
                            val edge = if (dragMode == 1) TrimEdge.START else TrimEdge.END
                            currentOnIntent(EditorIntent.BeginTrim(currentClip.id, edge))
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        if (dragMode == 0) {
                            dragOffsetPx += dragAmount
                            val proposedStartMs = (originStartMs + dragOffsetPx / (dpPerMs * density))
                                .roundToLong()
                                .coerceAtLeast(0L)
                            currentOnIntent(EditorIntent.UpdateMove(currentClip.id, proposedStartMs))
                        } else {
                            totalTrimDragPx += dragAmount
                            val timelineDeltaMs = (totalTrimDragPx / (dpPerMs * density)).roundToLong()
                            val deltaMs = (timelineDeltaMs * currentClip.effects.normalizedSpeed).roundToLong()
                            if (dragMode == 1) {
                                val startMs = (originalSourceStartMs + deltaMs)
                                    .coerceIn(0L, originalSourceEndMs - 1L)
                                currentOnIntent(EditorIntent.UpdateTrim(currentClip.id, startMs, originalSourceEndMs))
                            } else {
                                val endMs = (originalSourceEndMs + deltaMs)
                                    .coerceIn(originalSourceStartMs + 1L, currentClip.source.sourceDurationOrEnd())
                                currentOnIntent(EditorIntent.UpdateTrim(currentClip.id, originalSourceStartMs, endMs))
                            }
                        }
                    },
                    onDragEnd = {
                        if (dragMode == 0) {
                            val proposedStartMs = (originStartMs + dragOffsetPx / (dpPerMs * density))
                                .roundToLong()
                                .coerceAtLeast(0L)
                            currentOnIntent(EditorIntent.Move(timelineStartMs = proposedStartMs, clipId = currentClip.id))
                            currentOnIntent(EditorIntent.CommitMove(currentClip.id))
                        } else if (dragMode == 1 || dragMode == 2) {
                            val timelineDeltaMs = (totalTrimDragPx / (dpPerMs * density)).roundToLong()
                            val deltaMs = (timelineDeltaMs * currentClip.effects.normalizedSpeed).roundToLong()
                            if (dragMode == 1) {
                                val startMs = (originalSourceStartMs + deltaMs)
                                    .coerceIn(0L, originalSourceEndMs - 1L)
                                currentOnIntent(EditorIntent.Trim(sourceStartMs = startMs, sourceEndMs = originalSourceEndMs, clipId = currentClip.id))
                            } else {
                                val endMs = (originalSourceEndMs + deltaMs)
                                    .coerceIn(originalSourceStartMs + 1L, currentClip.source.sourceDurationOrEnd())
                                currentOnIntent(EditorIntent.Trim(sourceStartMs = originalSourceStartMs, sourceEndMs = endMs, clipId = currentClip.id))
                            }
                            currentOnIntent(EditorIntent.CommitTrim(currentClip.id))
                        }
                        dragOffsetPx = 0f
                        totalTrimDragPx = 0f
                        dragMode = 0
                    },
                    onDragCancel = {
                        currentOnIntent(EditorIntent.CancelGesture)
                        dragOffsetPx = 0f
                        totalTrimDragPx = 0f
                        dragMode = 0
                    },
                )
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onSelect),
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
                var lastCalculatedStartMs = clip.sourceStartMs
                var lastCalculatedEndMs = clip.sourceEndMs
                TrimHandle(
                    modifier = Modifier.align(Alignment.CenterStart),
                    contentDescription = "Trim start of ${clip.id}",
                    onDragStart = {
                        originalSourceStartMs = clip.sourceStartMs
                        originalSourceEndMs = clip.sourceEndMs
                        lastCalculatedStartMs = clip.sourceStartMs
                        currentOnIntent(EditorIntent.BeginTrim(clip.id, TrimEdge.START))
                    },
                    onDrag = { deltaPx ->
                        val timelineDeltaMs = (deltaPx / (dpPerMs * density)).roundToLong()
                        val deltaMs = (timelineDeltaMs * clip.effects.normalizedSpeed).roundToLong()
                        val newStart = (originalSourceStartMs + deltaMs).coerceIn(0L, originalSourceEndMs - 1L)
                        lastCalculatedStartMs = newStart
                        currentOnIntent(EditorIntent.UpdateTrim(clip.id, newStart, originalSourceEndMs))
                    },
                    onDragEnd = {
                        currentOnIntent(EditorIntent.Trim(sourceStartMs = lastCalculatedStartMs, sourceEndMs = originalSourceEndMs, clipId = clip.id))
                        currentOnIntent(EditorIntent.CommitTrim(clip.id))
                    },
                    onDragCancel = {
                        currentOnIntent(EditorIntent.CancelGesture)
                    },
                )
                TrimHandle(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    contentDescription = "Trim end of ${clip.id}",
                    onDragStart = {
                        originalSourceStartMs = clip.sourceStartMs
                        originalSourceEndMs = clip.sourceEndMs
                        lastCalculatedEndMs = clip.sourceEndMs
                        currentOnIntent(EditorIntent.BeginTrim(clip.id, TrimEdge.END))
                    },
                    onDrag = { deltaPx ->
                        val timelineDeltaMs = (deltaPx / (dpPerMs * density)).roundToLong()
                        val deltaMs = (timelineDeltaMs * clip.effects.normalizedSpeed).roundToLong()
                        val newEnd = (originalSourceEndMs + deltaMs)
                            .coerceIn(originalSourceStartMs + 1L, clip.source.sourceDurationOrEnd())
                        lastCalculatedEndMs = newEnd
                        currentOnIntent(EditorIntent.UpdateTrim(clip.id, originalSourceStartMs, newEnd))
                    },
                    onDragEnd = {
                        currentOnIntent(EditorIntent.Trim(sourceStartMs = originalSourceStartMs, sourceEndMs = lastCalculatedEndMs, clipId = clip.id))
                        currentOnIntent(EditorIntent.CommitTrim(clip.id))
                    },
                    onDragCancel = {
                        currentOnIntent(EditorIntent.CancelGesture)
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
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
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
                    onDragEnd = {
                        totalDragPx = 0f
                        currentOnDragEnd()
                    },
                    onDragCancel = {
                        totalDragPx = 0f
                        currentOnDragCancel()
                    },
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

internal fun visiblePeaks(
    allPeaks: List<Int>,
    sourceStartMs: Long,
    sourceEndMs: Long,
    sourceDurationMs: Long,
): List<Int> {
    if (allPeaks.isEmpty() || sourceDurationMs <= 0L || sourceEndMs <= sourceStartMs) {
        return emptyList()
    }
    val totalPeaks = allPeaks.size
    val startIndex = ((sourceStartMs.toDouble() / sourceDurationMs) * totalPeaks)
        .toInt()
        .coerceIn(0, totalPeaks - 1)
    val endIndex = ((sourceEndMs.toDouble() / sourceDurationMs) * totalPeaks)
        .toInt()
        .coerceIn(startIndex + 1, totalPeaks)
    return allPeaks.subList(startIndex, endIndex)
}
