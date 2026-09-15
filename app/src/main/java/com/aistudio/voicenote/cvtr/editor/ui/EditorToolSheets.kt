package com.aistudio.voicenote.cvtr.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.audio.EditorPlaybackState
import com.aistudio.voicenote.cvtr.ui.theme.AccentCoral
import com.aistudio.voicenote.cvtr.ui.theme.CardSurfaceWhite
import com.aistudio.voicenote.cvtr.ui.theme.DeepNavyDisplay
import com.aistudio.voicenote.cvtr.ui.theme.LightSlateCaption
import com.aistudio.voicenote.cvtr.ui.theme.PastelPeachCardBg

@Composable
internal fun ContextualEditorToolbar(
    session: EditorSession,
    selectedClip: AudioClip?,
    onIntent: (EditorIntent) -> Unit,
) {
    val hasSelection = selectedClip != null
    var deleteMenuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorToolButton(
            label = "Split",
            enabled = hasSelection,
            onClick = { onIntent(EditorIntent.Split(session.playheadMs)) },
        )
        EditorToolButton(
            label = "Trim",
            enabled = hasSelection,
            onClick = {
                selectedClip?.let { clip ->
                    onIntent(EditorIntent.Trim(clip.sourceStartMs, clip.sourceEndMs))
                }
            },
        )
        EditorToolButton(
            label = "Fade",
            enabled = hasSelection,
            onClick = { onIntent(EditorIntent.ShowSheet(EditorSheet.FADE)) },
        )
        EditorToolButton(
            label = "Pitch",
            enabled = hasSelection,
            onClick = { onIntent(EditorIntent.ShowSheet(EditorSheet.PITCH)) },
        )
        EditorToolButton(
            label = "Speed",
            enabled = hasSelection,
            onClick = { onIntent(EditorIntent.ShowSheet(EditorSheet.SPEED)) },
        )
        EditorToolButton(
            label = "Cleanup",
            enabled = hasSelection,
            onClick = { onIntent(EditorIntent.ShowSheet(EditorSheet.CLEANUP)) },
            modifier = Modifier.testTag("cleanup_toolbar"),
        )
        androidx.compose.foundation.layout.Box {
            OutlinedButton(
                onClick = { deleteMenuExpanded = true },
                enabled = hasSelection,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Text("Delete")
            }
            DropdownMenu(
                expanded = deleteMenuExpanded,
                onDismissRequest = { deleteMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Delete and ripple") },
                    onClick = {
                        deleteMenuExpanded = false
                        onIntent(EditorIntent.Delete(ripple = true))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete, keep gap") },
                    onClick = {
                        deleteMenuExpanded = false
                        onIntent(EditorIntent.Delete(ripple = false))
                    },
                )
            }
        }
    }
}

@Composable
private fun EditorToolButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(label)
    }
}

@Composable
internal fun EditorToolSheet(
    sheet: EditorSheet,
    clip: AudioClip,
    onIntent: (EditorIntent) -> Unit,
    cleanupState: EditorCleanupUiState = EditorCleanupUiState(),
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = CardSurfaceWhite,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = when (sheet) {
                        EditorSheet.FADE -> "Fade"
                        EditorSheet.PITCH -> "Pitch"
                        EditorSheet.SPEED -> "Speed"
                        EditorSheet.CLEANUP -> "Cleanup / Normalize"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = DeepNavyDisplay,
                )
                OutlinedButton(
                    onClick = { onIntent(EditorIntent.ShowSheet(null)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Done")
                }
            }
            when (sheet) {
                EditorSheet.FADE -> {
                    Text("Fade in", color = LightSlateCaption)
                    Slider(
                        value = clip.effects.fadeInMs.toFloat(),
                        onValueChange = {
                            onIntent(
                                EditorIntent.SetFade(
                                    fadeInMs = it.toLong(),
                                    fadeOutMs = clip.effects.fadeOutMs,
                                )
                            )
                        },
                        valueRange = 0f..5_000f,
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    Text("Fade out", color = LightSlateCaption)
                    Slider(
                        value = clip.effects.fadeOutMs.toFloat(),
                        onValueChange = {
                            onIntent(
                                EditorIntent.SetFade(
                                    fadeInMs = clip.effects.fadeInMs,
                                    fadeOutMs = it.toLong(),
                                )
                            )
                        },
                        valueRange = 0f..5_000f,
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
                EditorSheet.PITCH -> {
                    Text("Pitch: ${clip.effects.pitchSemitones} semitones", color = LightSlateCaption)
                    Slider(
                        value = clip.effects.pitchSemitones,
                        onValueChange = { onIntent(EditorIntent.SetPitch(it)) },
                        valueRange = -4f..4f,
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
                EditorSheet.SPEED -> {
                    Text("Speed: ${"%.2f".format(clip.effects.speed)}×", color = LightSlateCaption)
                    Slider(
                        value = clip.effects.speed,
                        onValueChange = { onIntent(EditorIntent.SetSpeed(it)) },
                        valueRange = .5f..2f,
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
                EditorSheet.CLEANUP -> {
                    var normalize by remember { mutableStateOf(false) }
                    var strength by remember { mutableStateOf(com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength.OFF) }
                    var applyToTrack by remember { mutableStateOf(false) }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = applyToTrack,
                            onCheckedChange = { applyToTrack = it }
                        )
                        Text("Apply to entire track (default: selected clip)", color = LightSlateCaption)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = normalize,
                            onCheckedChange = { normalize = it }
                        )
                        Text("Normalize (-1 dBFS)", color = LightSlateCaption)
                    }

                    Text("Noise Reduction: ${cleanupStrengthLabel(strength)}", color = LightSlateCaption)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength.values().forEach { option ->
                            FilterChip(
                                selected = strength == option,
                                onClick = { strength = option },
                                label = { Text(cleanupStrengthLabel(option)) },
                                modifier = Modifier.testTag("cleanup_${option.name.lowercase()}"),
                            )
                        }
                    }

                    when (cleanupState.status) {
                        EditorCleanupStatus.QUEUED, EditorCleanupStatus.RUNNING -> {
                            Text("Processing ${(cleanupState.progress * 100f).toInt().coerceIn(0, 100)}%", modifier = Modifier.testTag("cleanup_progress"))
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { cleanupState.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(onClick = { onIntent(EditorIntent.CancelCleanup) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Cancel")
                            }
                        }
                        EditorCleanupStatus.FAILED, EditorCleanupStatus.CANCELLED -> {
                            cleanupState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cleanup_error")) }
                            Button(onClick = { onIntent(EditorIntent.RetryCleanup) }, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
                        }
                        else -> {
                            Button(
                                onClick = {
                                    onIntent(EditorIntent.StartCleanup(clip.id, normalize, strength, applyToTrack))
                                },
                                modifier = Modifier.fillMaxWidth().testTag("cleanup_apply"),
                            ) { Text("Terapkan") }
                        }
                    }
                }
            }
        }
    }
}

private fun cleanupStrengthLabel(strength: com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength): String = when (strength) {
    com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength.OFF -> "OFF"
    com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength.LIGHT -> "Ringan"
    com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength.MEDIUM -> "Sedang"
    com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength.STRONG -> "Kuat"
}

@Composable
internal fun EditorTransport(
    session: EditorSession,
    playback: EditorPlaybackState = EditorPlaybackState(),
    onIntent: (EditorIntent) -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PastelPeachCardBg)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(
            onClick = {
                onIntent(if (playback.playing) EditorIntent.Pause else EditorIntent.Play)
            },
            enabled = playback.durationMs > 0L || session.tracks.any { it.clips.isNotEmpty() },
            modifier = Modifier
                .size(48.dp)
                .testTag(if (playback.playing) "editor_pause" else "editor_play")
                .semantics {
                    contentDescription = if (playback.playing) "Pause audio playback" else "Play audio preview"
                    stateDescription = if (playback.playing) "Playing" else "Paused"
                },
        ) {
            Icon(
                if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
            )
        }
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = "Audio preview",
            tint = AccentCoral,
        )
        Text(
            text = "${formatTransportTime(session.playheadMs)} / timeline",
            style = MaterialTheme.typography.labelLarge,
            color = DeepNavyDisplay,
        )
        Text(
            text = if (playback.error == null) "Preview follows the played head" else "Preview unavailable",
            style = MaterialTheme.typography.bodySmall,
            color = LightSlateCaption,
        )
    }
}

@Composable
internal fun EditorBottomActions(
    session: EditorSession,
    canUndo: Boolean,
    canRedo: Boolean,
    exportState: EditorExportUiState = EditorExportUiState(),
    onIntent: (EditorIntent) -> Unit,
    onPickTrack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurfaceWhite)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = { onIntent(EditorIntent.Undo) },
            enabled = canUndo,
            modifier = Modifier
                .size(48.dp)
                .testTag("undo"),
        ) {
            Icon(Icons.Filled.Undo, contentDescription = "Undo")
        }
        IconButton(
            onClick = { onIntent(EditorIntent.Redo) },
            enabled = canRedo,
            modifier = Modifier
                .size(48.dp)
                .testTag("redo"),
        ) {
            Icon(Icons.Filled.Redo, contentDescription = "Redo")
        }
        Button(
            onClick = onPickTrack,
            enabled = session.tracks.size < 5,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("add_track"),
        ) {
            Text("Add track")
        }
        Button(
            onClick = { onIntent(EditorIntent.ShowExportSheet(true)) },
            enabled = session.tracks.isNotEmpty() &&
                exportState.status != EditorExportStatus.QUEUED &&
                exportState.status != EditorExportStatus.RUNNING,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("editor_export")
                .semantics {
                    contentDescription = "Export edited timeline"
                },
        ) {
            Text("Export")
        }
    }
}

private fun formatTransportTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
