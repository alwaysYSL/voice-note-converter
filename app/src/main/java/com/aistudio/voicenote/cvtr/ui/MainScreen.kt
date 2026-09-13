package com.aistudio.voicenote.cvtr.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.voicenote.cvtr.ui.components.PitchControl
import com.aistudio.voicenote.cvtr.ui.components.TrimControls
import com.aistudio.voicenote.cvtr.ui.components.TrimState
import com.aistudio.voicenote.cvtr.ui.components.WaveformVisualizer
import com.aistudio.voicenote.cvtr.ui.theme.AccentCoral
import com.aistudio.voicenote.cvtr.ui.theme.AccentRoyalBlue
import com.aistudio.voicenote.cvtr.ui.theme.AppCanvasBackground
import com.aistudio.voicenote.cvtr.ui.theme.CardSurfaceWhite
import com.aistudio.voicenote.cvtr.ui.theme.DeepNavyDisplay
import com.aistudio.voicenote.cvtr.ui.theme.MutedInputBackground
import com.aistudio.voicenote.cvtr.ui.theme.PastelMintText
import com.aistudio.voicenote.cvtr.ui.theme.PastelPeachCardBg
import com.aistudio.voicenote.cvtr.ui.theme.PastelPeriwinkleCardBg
import com.aistudio.voicenote.cvtr.ui.theme.SubtitleSlate
import com.aistudio.voicenote.cvtr.ui.theme.SubtleBorder

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    bottomOverlayClearance: Dp = 0.dp,
    statusBarInset: Dp? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val batchItems by viewModel.batchItems.collectAsStateWithLifecycle()
    val batchMessage by viewModel.batchMessage.collectAsStateWithLifecycle()
    val hasConvertedResult = state.convertedUri != null
    val safeTopInset = statusBarInset
        ?: WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val sourceSelectionEnabled = state.processStatus !in setOf(
        ProcessStatus.ANALYZING,
        ProcessStatus.CONVERTING,
        ProcessStatus.SENDING
    )
    val context = LocalContext.current
    var pendingBatchUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val batchStoragePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.enqueueBatch(pendingBatchUris)
        pendingBatchUris = emptyList()
    }
    val enqueueBatchWithPermission: (List<Uri>) -> Unit = { uris ->
        val needsLegacyPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needsLegacyPermission) {
            pendingBatchUris = uris
            batchStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.enqueueBatch(uris)
        }
    }
    val batchPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        enqueueBatchWithPermission(uris)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::handleIncomingUri)
    }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startConversion()
    }
    val startConversionWithPermission = {
        val needsLegacyPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needsLegacyPermission) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.startConversion()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.pausePlayback()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppCanvasBackground),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp + safeTopInset, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ConverterHeader()
            SourcePickerCard(
                hasSelection = state.selectedFileUri != null,
                selectedFileName = state.fileName,
                enabled = sourceSelectionEnabled,
                onPick = { picker.launch(arrayOf("audio/*", "video/*")) },
                onPickBatch = { batchPicker.launch(arrayOf("audio/*", "video/*")) }
            )
            if (batchItems.isNotEmpty() || batchMessage != null) {
                BatchQueuePanel(
                    items = batchItems,
                    message = batchMessage,
                    onRetry = viewModel::retryBatch,
                    onCancel = viewModel::cancelBatch,
                    onDismissItem = viewModel::dismissBatch,
                    onDismissAll = viewModel::dismissAllBatch,
                    onDismissMessage = viewModel::clearBatchMessage
                )
            }
            state.fileName?.let { fileName ->
                val previewingConverted = state.previewSource == PreviewSource.CONVERTED &&
                    state.convertedUri != null
                PreviewCard(
                    fileName = if (previewingConverted && state.outputFileName.isNotBlank()) {
                        state.outputFileName
                    } else {
                        fileName
                    },
                    hasConvertedResult = hasConvertedResult,
                    waveform = state.previewWaveform(),
                    progress = playback.progress,
                    isPlaying = playback.isPlaying,
                    currentPositionMs = playback.currentPositionMs,
                    fallbackDurationSec = if (previewingConverted) {
                        (state.convertedDurationMs / 1_000L).toInt()
                    } else {
                        state.fileDurationSec
                    },
                    trimState = state.trimState,
                    processStatus = state.processStatus,
                    onTogglePlayback = viewModel::togglePlayback,
                    onSeek = viewModel::seekToFraction,
                    onTrimRangeChange = { viewModel.updateTrimRange(it.start, it.endInclusive) },
                    onToggleTrim = viewModel::toggleTrim,
                    onPreviewTrim = viewModel::previewTrim,
                    onResetTrim = viewModel::resetTrim,
                    pitchSemitones = state.pitchSemitones,
                    onPitchChange = viewModel::updatePitch,
                    outputFileName = state.outputFileName,
                    onOutputFileNameChange = viewModel::updateOutputFileName,
                    previewSource = state.previewSource,
                    onPreviewSourceChange = viewModel::setPreviewSource,
                    normalizeAudio = state.normalizeAudio,
                    onNormalizeAudioChange = viewModel::setNormalizeAudio,
                    trimSilence = state.trimSilence,
                    onTrimSilenceChange = viewModel::setTrimSilence,
                    compatibilitySummary = state.compatibilitySummary,
                    compatibilityWarning = state.compatibilityWarning,
                    playbackError = playback.errorMessage
                )
            }

        }

        if (state.selectedFileUri != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = bottomOverlayClearance)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("converter_action_bar"),
                    shape = RoundedCornerShape(20.dp),
                    color = CardSurfaceWhite,
                    tonalElevation = 1.dp,
                    shadowElevation = 6.dp
                ) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        ProcessActionArea(
                            state = state,
                            hasConvertedResult = hasConvertedResult,
                            onConvert = startConversionWithPermission,
                            onSend = viewModel::sendConverted,
                            onConfirmSent = viewModel::confirmConvertedSent,
                            onReset = viewModel::resetForNewFile
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConverterHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Voice note studio",
                style = MaterialTheme.typography.labelLarge,
                color = AccentCoral,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Ubah jadi voice note.",
                style = MaterialTheme.typography.displaySmall,
                color = DeepNavyDisplay
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Audio atau video menjadi OGG Opus yang siap dibuka di Telegram.",
                style = MaterialTheme.typography.bodyMedium,
                color = SubtitleSlate
            )
        }
        IconBubble(
            icon = { Icon(Icons.Default.Mic, contentDescription = null) },
            backgroundColor = PastelPeachCardBg,
            iconColor = AccentCoral,
            size = 54.dp
        )
    }
}

@Composable
private fun SourcePickerCard(
    hasSelection: Boolean,
    selectedFileName: String?,
    enabled: Boolean,
    onPick: () -> Unit,
    onPickBatch: () -> Unit = {}
) {
    SoftCard(
        containerColor = CardSurfaceWhite,
        modifier = if (hasSelection) Modifier.testTag("source_summary") else Modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            IconBubble(
                icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                backgroundColor = PastelPeriwinkleCardBg,
                iconColor = AccentRoyalBlue
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (hasSelection) "Sumber audio" else "Pilih file sumber",
                    style = MaterialTheme.typography.titleMedium,
                    color = DeepNavyDisplay
                )
                Text(
                    text = when {
                        selectedFileName != null -> selectedFileName
                        hasSelection -> "Menyiapkan preview audio..."
                        else -> "Audio atau video dari perangkat Anda"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SubtitleSlate,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hasSelection) {
                    Row {
                        TextButton(onClick = onPick, enabled = enabled) {
                            Text("Ganti")
                        }
                        TextButton(onClick = onPickBatch, enabled = enabled) {
                            Text("Pilih beberapa")
                        }
                    }
                }
            }
        }
        if (!hasSelection) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onPick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = DeepNavyDisplay
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, SubtleBorder)
            ) {
                Text("Pilih audio atau video")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onPickBatch,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = DeepNavyDisplay
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, SubtleBorder)
            ) {
                Text("Pilih beberapa file")
            }
        }
    }
}

@Composable
internal fun PreviewCard(
    fileName: String,
    hasConvertedResult: Boolean,
    waveform: List<Int>,
    progress: Float,
    isPlaying: Boolean,
    currentPositionMs: Long,
    fallbackDurationSec: Int,
    trimState: TrimState,
    processStatus: ProcessStatus,
    onTogglePlayback: () -> Unit,
    onSeek: (Float) -> Unit,
    onTrimRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onToggleTrim: () -> Unit,
    onPreviewTrim: () -> Unit,
    onResetTrim: () -> Unit,
    pitchSemitones: Float = 0f,
    onPitchChange: (Float) -> Unit = {},
    outputFileName: String = "",
    onOutputFileNameChange: (String) -> Unit = {},
    previewSource: PreviewSource = PreviewSource.ORIGINAL,
    onPreviewSourceChange: (PreviewSource) -> Unit = {},
    normalizeAudio: Boolean = false,
    onNormalizeAudioChange: (Boolean) -> Unit = {},
    trimSilence: Boolean = false,
    onTrimSilenceChange: (Boolean) -> Unit = {},
    compatibilitySummary: String? = null,
    compatibilityWarning: String? = null,
    playbackError: String? = null
) {
    var showAudioEditor by remember(fileName) { mutableStateOf(false) }
    val previewingConverted = hasConvertedResult && previewSource == PreviewSource.CONVERTED

    SoftCard(containerColor = CardSurfaceWhite) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconBubble(
                icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                backgroundColor = if (previewingConverted) PastelPeriwinkleCardBg else PastelPeachCardBg,
                iconColor = if (previewingConverted) AccentRoyalBlue else AccentCoral
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (previewingConverted) "Hasil voice note" else "Preview audio asli",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (previewingConverted) PastelMintText else AccentCoral
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = DeepNavyDisplay,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (hasConvertedResult) {
            Spacer(Modifier.height(12.dp))
            PreviewSourceToggle(
                previewSource = previewSource,
                onPreviewSourceChange = onPreviewSourceChange
            )
            compatibilitySummary?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = SubtitleSlate
                )
            }
            compatibilityWarning?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (!hasConvertedResult &&
            processStatus != ProcessStatus.ANALYZING &&
            processStatus != ProcessStatus.CONVERTING &&
            processStatus != ProcessStatus.SENDING
        ) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = outputFileName,
                onValueChange = onOutputFileNameChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("output_file_name"),
                label = { Text("Nama file hasil") },
                supportingText = { Text("Akhiran .ogg akan ditambahkan otomatis bila perlu.") },
                singleLine = true
            )
        }
        Spacer(Modifier.height(18.dp))
        WaveformVisualizer(
            modifier = Modifier.fillMaxWidth().testTag("preview_waveform"),
            waveform = waveform,
            progress = progress,
            activeColor = if (previewingConverted) AccentRoyalBlue else AccentCoral,
            inactiveColor = SubtleBorder,
            playheadColor = AccentCoral,
            height = 58.dp,
            onSeek = onSeek,
            trimRange = trimState.takeIf { it.isActive && !hasConvertedResult }?.trimRange,
            onTrimRangeChange = onTrimRangeChange,
            showTrimHandles = trimState.isActive && !hasConvertedResult,
            isInteractive = processStatus != ProcessStatus.CONVERTING &&
                processStatus != ProcessStatus.SENDING &&
                processStatus != ProcessStatus.ANALYZING
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(
                onClick = onTogglePlayback,
                modifier = Modifier
                    .size(42.dp)
                    .background(AccentCoral, CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Putar",
                    tint = Color.White
                )
            }
            Text(
                text = MainViewModel.formatDuration(
                    (currentPositionMs / 1000).toInt()
                ) + " / " + MainViewModel.formatDuration(fallbackDurationSec),
                style = MaterialTheme.typography.bodySmall,
                color = SubtitleSlate
            )
        }
        playbackError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (!hasConvertedResult && processStatus == ProcessStatus.IDLE) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { showAudioEditor = !showAudioEditor },
                modifier = Modifier.testTag("audio_editor_toggle")
            ) {
                Icon(
                    imageVector = if (showAudioEditor) Icons.Default.ExpandLess else Icons.Default.Tune,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text("Edit audio")
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (showAudioEditor) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            if (showAudioEditor) {
                HorizontalDivider(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .testTag("preview_tools_divider"),
                    color = SubtleBorder,
                    thickness = 0.5.dp
                )
                Spacer(Modifier.height(8.dp))
                TrimControls(
                    trimState = trimState,
                    onToggleTrim = onToggleTrim,
                    onPreviewTrim = onPreviewTrim,
                    onResetTrim = onResetTrim
                )
                Spacer(Modifier.height(12.dp))
                PitchControl(
                    pitchSemitones = pitchSemitones,
                    onPitchChange = onPitchChange,
                    enabled = true
                )
                Spacer(Modifier.height(12.dp))
                AudioEffectsControls(
                    normalizeAudio = normalizeAudio,
                    onNormalizeAudioChange = onNormalizeAudioChange,
                    trimSilence = trimSilence,
                    onTrimSilenceChange = onTrimSilenceChange
                )
            }
        }
    }
}

@Composable
private fun PreviewSourceToggle(
    previewSource: PreviewSource,
    onPreviewSourceChange: (PreviewSource) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (previewSource == PreviewSource.ORIGINAL) {
            Button(
                onClick = { onPreviewSourceChange(PreviewSource.ORIGINAL) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCoral)
            ) {
                Text("Asli")
            }
        } else {
            OutlinedButton(
                onClick = { onPreviewSourceChange(PreviewSource.ORIGINAL) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Asli")
            }
        }
        if (previewSource == PreviewSource.CONVERTED) {
            Button(
                onClick = { onPreviewSourceChange(PreviewSource.CONVERTED) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentRoyalBlue)
            ) {
                Text("Hasil")
            }
        } else {
            OutlinedButton(
                onClick = { onPreviewSourceChange(PreviewSource.CONVERTED) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Hasil")
            }
        }
    }
}

@Composable
private fun AudioEffectsControls(
    normalizeAudio: Boolean,
    onNormalizeAudioChange: (Boolean) -> Unit,
    trimSilence: Boolean,
    onTrimSilenceChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Pemrosesan hasil",
            style = MaterialTheme.typography.labelLarge,
            color = DeepNavyDisplay
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Normalisasi volume", color = DeepNavyDisplay)
                Text(
                    "Naikkan level tanpa melewati peak aman.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SubtitleSlate
                )
            }
            Switch(
                checked = normalizeAudio,
                onCheckedChange = onNormalizeAudioChange
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Potong hening tepi", color = DeepNavyDisplay)
                Text(
                    "Hapus hening di awal dan akhir hasil.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SubtitleSlate
                )
            }
            Switch(
                checked = trimSilence,
                onCheckedChange = onTrimSilenceChange
            )
        }
    }
}

@Composable
private fun ProcessActionArea(
    state: MainUiState,
    hasConvertedResult: Boolean,
    onConvert: () -> Unit,
    onSend: () -> Unit,
    onConfirmSent: () -> Unit,
    onReset: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (state.processStatus) {
            ProcessStatus.ANALYZING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = AccentRoyalBlue,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(12.dp))
                Text("Menyiapkan preview audio", color = DeepNavyDisplay)
            }
            ProcessStatus.CONVERTING, ProcessStatus.SENDING -> {
                Text(
                    text = if (state.processStatus == ProcessStatus.CONVERTING) "Mengubah menjadi voice note" else "Membuka Telegram",
                    style = MaterialTheme.typography.titleSmall,
                    color = DeepNavyDisplay
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = AccentCoral,
                    trackColor = MutedInputBackground
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (state.processStatus == ProcessStatus.CONVERTING) "${(state.progress * 100).toInt()}% selesai" else "Menyiapkan pengiriman",
                    style = MaterialTheme.typography.bodySmall,
                    color = SubtitleSlate
                )
            }
            ProcessStatus.CONVERTED -> PrimaryActionButton(
                text = "Kirim ke Telegram",
                icon = Icons.AutoMirrored.Filled.Send,
                onClick = onSend
            )
            ProcessStatus.SHARE_OPENED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = PastelMintText)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "Telegram sudah dibuka",
                            style = MaterialTheme.typography.titleSmall,
                            color = DeepNavyDisplay
                        )
                        Text(
                            state.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = PastelMintText
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onConfirmSent,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Tandai sudah dikirim") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Konversi baru") }
            }
            ProcessStatus.CONFIRMED_SENT -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = PastelMintText)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "Pengiriman dikonfirmasi",
                            style = MaterialTheme.typography.titleSmall,
                            color = DeepNavyDisplay
                        )
                        Text(
                            state.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = PastelMintText
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Konversi baru") }
            }
            ProcessStatus.FAILED -> {
                Text(
                    state.errorMessage ?: "Terjadi kesalahan.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onReset,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("Batal") }
                    if (state.canRetry && state.selectedFileUri != null) {
                        Button(
                            onClick = if (hasConvertedResult) onSend else onConvert,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text("Coba lagi") }
                    }
                }
            }
            ProcessStatus.IDLE -> if (state.selectedFileUri != null) {
                PrimaryActionButton(
                    text = "Konversi ke voice note",
                    icon = Icons.Default.ContentCut,
                    onClick = onConvert
                )
            }
        }
    }
}

@Composable
private fun PrimaryActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AccentCoral,
            contentColor = Color.White
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun SoftCard(
    containerColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
private fun IconBubble(
    icon: @Composable () -> Unit,
    backgroundColor: Color,
    iconColor: Color,
    size: Dp = 46.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(backgroundColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides iconColor,
            content = icon
        )
    }
}
