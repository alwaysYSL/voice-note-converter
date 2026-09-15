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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aistudio.voicenote.cvtr.ui.theme.AppCanvasBackground
import com.aistudio.voicenote.cvtr.ui.theme.DeepNavyDisplay
import com.aistudio.voicenote.cvtr.ui.theme.LightSlateCaption
import com.aistudio.voicenote.cvtr.editor.model.ExportPreset

/** Layout A: a calm stacked timeline with controls kept reachable at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorScreen(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
    onPickTrack: () -> Unit = {},
    onReplaceSource: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    var showExitDialog by remember { mutableStateOf(false) }
    fun requestBack() {
        if (state.session.dirty) showExitDialog = true else onBack()
    }
    BackHandler { requestBack() }
    LaunchedEffect(state.draft.status, state.draft.exitAfterSave) {
        if (state.draft.status == EditorDraftSaveStatus.SUCCEEDED && state.draft.exitAfterSave) {
            onBack()
        }
    }
    val selectedClip = state.session.tracks
        .asSequence()
        .flatMap { it.clips.asSequence() }
        .firstOrNull { it.id == state.session.selectedClipId && it.id !in state.offlineClipIds }

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
                IconButton(onClick = ::requestBack, modifier = Modifier.testTag("editor_back")) {
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
            if (state.offlineClipIds.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text = state.sourceError ?: "Draft source is unavailable",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.offlineClipIds.forEach { clipId ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Clip $clipId offline", modifier = Modifier.weight(1f))
                            Button(onClick = { onReplaceSource(clipId) }) { Text("Ganti file") }
                        }
                    }
                }
            }
            if (state.effectRecoveryClipIds.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        "Efek perlu diterapkan ulang",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("editor_effect_recovery"),
                    )
                    state.effectRecoveryClipIds.forEach { clipId ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Clip $clipId", modifier = Modifier.weight(1f))
                            Button(onClick = { onIntent(EditorIntent.ReapplyCleanup(clipId)) }) {
                                Text("Terapkan ulang")
                            }
                        }
                    }
                }
            }
            state.activeSheet?.let { sheet ->
                selectedClip?.let { clip ->
                    EditorToolSheet(
                        sheet = sheet,
                        clip = clip,
                        onIntent = onIntent,
                        cleanupState = state.cleanup,
                    )
                }
            }
            TimelineCanvas(
                state = state,
                onIntent = onIntent,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("editor_timeline"),
            )
            if (state.export.sheetOpen) {
                EditorExportSheet(state.export, state.effectRecoveryClipIds.isEmpty(), onIntent)
            }
            state.message?.let { message ->
                Text(
                    text = editorMessageText(message),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when (state.draft.status) {
                EditorDraftSaveStatus.SAVING -> Text(
                    "Menyimpan draft… ${(state.draft.progress * 100f).toInt()}%",
                    modifier = Modifier.testTag("editor_draft_progress"),
                    style = MaterialTheme.typography.bodySmall,
                )
                EditorDraftSaveStatus.SUCCEEDED -> Text(
                    "Draft tersimpan",
                    modifier = Modifier.testTag("editor_draft_success"),
                    style = MaterialTheme.typography.bodySmall,
                )
                EditorDraftSaveStatus.FAILED -> state.draft.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("editor_draft_error"))
                }
                EditorDraftSaveStatus.IDLE -> Unit
            }
            EditorBottomActions(
                session = state.session,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                exportState = state.export,
                draftState = state.draft,
                cleanupInspectionPending = state.cleanupInspectionPending,
                onIntent = onIntent,
                onPickTrack = onPickTrack,
            )
        }
    }
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Perubahan belum disimpan") },
            text = { Text("Simpan perubahan sebelum keluar dari editor?") },
            confirmButton = {
                Button(onClick = {
                    showExitDialog = false
                    onIntent(EditorIntent.SaveDraft(exitAfterSave = true))
                }) { Text("Simpan sebagai draft") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showExitDialog = false; onBack() }) { Text("Buang sesi") }
                    Button(onClick = { showExitDialog = false }) { Text("Batal") }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorExportSheet(
    state: EditorExportUiState,
    effectsReady: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = { onIntent(EditorIntent.ShowExportSheet(false)) }) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Export edited timeline", style = MaterialTheme.typography.titleLarge, color = DeepNavyDisplay)
            OutlinedTextField(
                value = state.outputName,
                onValueChange = { onIntent(EditorIntent.SetExportName(it)) },
                label = { Text("File name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth().testTag("editor_export_name"),
            )
            Text("Preset", color = LightSlateCaption)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.preset == ExportPreset.VOICE_NOTE_32,
                    onClick = { onIntent(EditorIntent.SetExportPreset(ExportPreset.VOICE_NOTE_32)) },
                    label = { Text("Voice note · 32 kbps") },
                    modifier = Modifier.testTag("editor_export_32"),
                )
                FilterChip(
                    selected = state.preset == ExportPreset.HIGH_QUALITY_64,
                    onClick = { onIntent(EditorIntent.SetExportPreset(ExportPreset.HIGH_QUALITY_64)) },
                    label = { Text("High quality · 64 kbps") },
                    modifier = Modifier.testTag("editor_export_64"),
                )
            }
            Text("Estimated size: ${formatBytes(state.estimatedSizeBytes)}", color = LightSlateCaption)
            when (state.status) {
                EditorExportStatus.QUEUED, EditorExportStatus.RUNNING -> {
                    Text("Exporting ${(state.progress * 100f).toInt().coerceIn(0, 100)}%", modifier = Modifier.testTag("editor_export_progress"))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { onIntent(EditorIntent.CancelExport) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel export")
                    }
                }
                EditorExportStatus.FAILED, EditorExportStatus.CANCELLED -> {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("editor_export_error")) }
                    Button(
                        onClick = { onIntent(EditorIntent.RetryExport) },
                        enabled = state.canRetry || state.status == EditorExportStatus.CANCELLED,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Retry export") }
                }
                EditorExportStatus.SUCCEEDED -> {
                    Text("Export complete", color = DeepNavyDisplay, modifier = Modifier.testTag("editor_export_result"))
                    state.cleanupWarning?.let { Text("Cleanup warning: $it", color = LightSlateCaption) }
                    Button(onClick = { onIntent(EditorIntent.ShowExportSheet(false)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Done")
                    }
                }
                EditorExportStatus.IDLE -> {
                    Button(
                        onClick = { onIntent(EditorIntent.StartExport) },
                        enabled = state.outputName.isNotBlank() && effectsReady,
                        modifier = Modifier.fillMaxWidth().testTag("editor_export_start"),
                    ) { Text("Export") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000f)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000f)
    else -> "$bytes B"
}

private fun editorMessageText(message: EditorMessage): String = when (message) {
    EditorMessage.TIMELINE_LIMIT -> "This track would exceed the five-minute timeline limit."
    EditorMessage.TRACK_LIMIT -> "The editor supports up to five tracks."
    EditorMessage.HISTORY_NOT_FOUND -> "This history item is no longer available."
    EditorMessage.IMPORT_FAILED -> "The track could not be imported."
    EditorMessage.PROCESSED_AUDIO_UNAVAILABLE -> "Processed cleanup audio is unavailable; the original source is playing."
    EditorMessage.DRAFT_SAVE_FAILED -> "Draft gagal disimpan. Perubahan tetap ada di editor."
    EditorMessage.DRAFT_NOT_FOUND -> "Draft tidak ditemukan."
    EditorMessage.SOURCE_REPLACEMENT_INVALID -> "File pengganti tidak dapat dibaca atau terlalu pendek."
    EditorMessage.SOURCE_REPLACEMENT_FAILED -> "File pengganti gagal disimpan; sumber lama dipertahankan."
}
