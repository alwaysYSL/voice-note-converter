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
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(label)
    }
}

@Composable
internal fun EditorToolSheet(
    sheet: EditorSheet,
    clip: AudioClip,
    onIntent: (EditorIntent) -> Unit,
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
                        EditorSheet.CLEANUP -> "Delete mode"
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
                    Text("Choose whether later clips close the deleted gap.", color = LightSlateCaption)
                }
            }
        }
    }
}

@Composable
internal fun EditorTransport(
    session: EditorSession,
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
            onClick = {},
            enabled = false,
            modifier = Modifier
                .size(48.dp)
                .testTag("editor_play_disabled")
                .semantics {
                    contentDescription = "Play disabled until Phase 2 audio renderer is connected"
                    stateDescription = "Disabled until Phase 2"
                },
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
        }
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = "Audio playback available in Phase 2",
            tint = AccentCoral,
        )
        Text(
            text = "${formatTransportTime(session.playheadMs)} / timeline",
            style = MaterialTheme.typography.labelLarge,
            color = DeepNavyDisplay,
        )
        Text(
            text = "Playback connects in Phase 2",
            style = MaterialTheme.typography.bodySmall,
            color = LightSlateCaption,
        )
    }
}

@Composable
internal fun EditorBottomActions(
    session: EditorSession,
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
            enabled = session.dirty,
            modifier = Modifier
                .size(48.dp)
                .testTag("undo"),
        ) {
            Icon(Icons.Filled.Undo, contentDescription = "Undo")
        }
        IconButton(
            onClick = { onIntent(EditorIntent.Redo) },
            enabled = false,
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
            onClick = {},
            enabled = false,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("editor_export_disabled")
                .semantics {
                    contentDescription = "Export disabled until Phase 2 audio rendering is connected"
                    stateDescription = "Disabled until Phase 2"
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
