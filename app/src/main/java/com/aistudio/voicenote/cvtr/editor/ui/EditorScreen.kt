package com.aistudio.voicenote.cvtr.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aistudio.voicenote.cvtr.ui.theme.AppCanvasBackground
import com.aistudio.voicenote.cvtr.ui.theme.DeepNavyDisplay
import com.aistudio.voicenote.cvtr.ui.theme.LightSlateCaption

/** Layout A: a calm stacked timeline with controls kept reachable at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorScreen(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
    onPickTrack: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val selectedClip = state.session.tracks
        .asSequence()
        .flatMap { it.clips.asSequence() }
        .firstOrNull { it.id == state.session.selectedClipId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppCanvasBackground)
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = {
                Column {
                    Text("Audio editor", color = DeepNavyDisplay)
                    if (state.session.dirty) {
                        Text("Unsaved changes", color = LightSlateCaption, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            EditorTransport(
                session = state.session,
                playback = state.playback,
                onIntent = onIntent,
            )
            ContextualEditorToolbar(
                session = state.session,
                selectedClip = selectedClip,
                onIntent = onIntent,
            )
            TimelineCanvas(
                state = state,
                onIntent = onIntent,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("editor_timeline"),
            )
            state.activeSheet?.let { sheet ->
                selectedClip?.let { clip ->
                    EditorToolSheet(sheet = sheet, clip = clip, onIntent = onIntent)
                }
            }
            state.message?.let { message ->
                Text(
                    text = editorMessageText(message),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            EditorBottomActions(
                session = state.session,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onIntent = onIntent,
                onPickTrack = onPickTrack,
            )
        }
    }
}

private fun editorMessageText(message: EditorMessage): String = when (message) {
    EditorMessage.TIMELINE_LIMIT -> "This track would exceed the five-minute timeline limit."
    EditorMessage.TRACK_LIMIT -> "The editor supports up to five tracks."
    EditorMessage.HISTORY_NOT_FOUND -> "This history item is no longer available."
    EditorMessage.IMPORT_FAILED -> "The track could not be imported."
}
