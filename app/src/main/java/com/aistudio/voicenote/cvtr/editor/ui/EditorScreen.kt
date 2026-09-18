package com.aistudio.voicenote.cvtr.editor.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.voicenote.cvtr.data.local.ConversionHistory
import com.aistudio.voicenote.cvtr.telegram.TelegramSender
import com.aistudio.voicenote.cvtr.ui.theme.AccentCoral
import com.aistudio.voicenote.cvtr.ui.theme.AccentRoyalBlue
import com.aistudio.voicenote.cvtr.ui.theme.AppCanvasBackground
import com.aistudio.voicenote.cvtr.ui.theme.CardSurfaceWhite
import com.aistudio.voicenote.cvtr.ui.theme.DarkNavyHeadline
import com.aistudio.voicenote.cvtr.ui.theme.DeepNavyDisplay
import com.aistudio.voicenote.cvtr.ui.theme.LightSlateCaption
import com.aistudio.voicenote.cvtr.ui.theme.PastelMintCardBg
import com.aistudio.voicenote.cvtr.ui.theme.PastelPeachCardBg
import com.aistudio.voicenote.cvtr.ui.theme.SubtleBorder
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    historyList: List<ConversionHistory> = emptyList(),
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state by viewModel.timelineState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pickerSlotIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = AppCanvasBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Audio Editor & Mixer",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = DeepNavyDisplay
                    )
                },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = DeepNavyDisplay)
                        }
                    }
                },
                actions = {
                    if (state.hasActiveTracks) {
                        TextButton(onClick = { viewModel.resetTimeline() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = LightSlateCaption)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reset", color = LightSlateCaption, fontSize = 13.sp)
                        }

                        Button(
                            onClick = { viewModel.exportTimeline() },
                            enabled = !state.isExporting,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentRoyalBlue),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export OGG", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppCanvasBackground)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Transport Bar (Play/Pause, Rewind, Timer Display)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, SubtleBorder, RoundedCornerShape(16.dp)),
                color = CardSurfaceWhite,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconButton(
                            onClick = { viewModel.seekTo(0L) },
                            enabled = state.hasActiveTracks,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(PastelPeachCardBg)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastRewind,
                                contentDescription = "Rewind",
                                tint = DeepNavyDisplay
                            )
                        }

                        IconButton(
                            onClick = { viewModel.togglePlayPause() },
                            enabled = state.hasActiveTracks && !state.isLoadingSource,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (state.isPlaying) AccentCoral else AccentRoyalBlue)
                        ) {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (state.isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Time Display
                    val currentSec = state.playheadPositionMs / 1000f
                    val totalSec = state.totalDurationMs / 1000f
                    val currentFormatted = String.format(Locale.US, "%02d:%04.1f", (currentSec / 60).toInt(), currentSec % 60)
                    val totalFormatted = String.format(Locale.US, "%02d:%04.1f", (totalSec / 60).toInt(), totalSec % 60)

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = " / ",
                            color = DeepNavyDisplay,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (state.isPlaying) "Memutar..." else "Siap",
                            color = LightSlateCaption,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // 2. Loading Indicator for Audio Import
            if (state.isLoadingSource) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PastelMintCardBg)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = AccentRoyalBlue
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Mendekode audio ke PCM 48kHz mono...",
                        color = DeepNavyDisplay,
                        fontSize = 13.sp
                    )
                }
            }

            // 3. Main Timeline Canvas
            TimelineCanvas(
                state = state,
                onTrackSelected = { slotIndex -> viewModel.selectTrack(slotIndex) },
                onAddTrackClicked = { slotIndex -> pickerSlotIndex = slotIndex },
                onClipOffsetChanged = { slotIndex, newOffset -> viewModel.updateClipOffset(slotIndex, newOffset) },
                onClipTrimChanged = { slotIndex, trimStart, trimEnd -> viewModel.updateClipTrim(slotIndex, trimStart, trimEnd) },
                onSeekRequested = { posMs -> viewModel.seekTo(posMs) }
            )

            // 4. Selected Track Controls Toolbar
            val selectedIndex = state.selectedTrackIndex
            val selectedClip = selectedIndex?.let { state.tracks.getOrNull(it) }

            if (selectedIndex != null && selectedClip != null) {
                EditorToolbarSheet(
                    slotIndex = selectedIndex,
                    clip = selectedClip,
                    onPitchChanged = { semitones -> viewModel.updateClipPitch(selectedIndex, semitones) },
                    onVolumeChanged = { gain -> viewModel.updateClipVolume(selectedIndex, gain) },
                    onSwapTrack = { toIndex -> viewModel.swapTracks(selectedIndex, toIndex) },
                    onReplaceAudio = { pickerSlotIndex = selectedIndex },
                    onRemoveTrack = { viewModel.removeTrack(selectedIndex) }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Modal: Audio Source Picker
    pickerSlotIndex?.let { slotIndex ->
        AudioSourcePickerSheet(
            targetSlotIndex = slotIndex,
            historyList = historyList,
            onDismiss = { pickerSlotIndex = null },
            onSourceSelected = { uri, name ->
                viewModel.addTrackFromUri(slotIndex, uri, name)
            }
        )
    }

    // Dialog: Exporting Progress
    if (state.isExporting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Mengekspor Audio...", color = DeepNavyDisplay) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Menggabungkan track dan meng-encode ke OGG Opus Telegram...",
                        fontSize = 13.sp,
                        color = DarkNavyHeadline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { state.exportProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = AccentRoyalBlue
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "%",
                        color = LightSlateCaption,
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            },
            confirmButton = {}
        )
    }

    // Dialog: Export Success
    state.exportedFileUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { viewModel.clearExportResult() },
            icon = {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentRoyalBlue, modifier = Modifier.size(36.dp))
            },
            title = { Text("Export Berhasil!", color = DeepNavyDisplay, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Audio hasil mixing telah tersimpan dalam format OGG Opus standar Telegram Voice Note.",
                    color = DarkNavyHeadline,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val sender = TelegramSender()
                        sender.sendVoiceNoteViaTelegramApp(context, uri)
                        viewModel.clearExportResult()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRoyalBlue)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Kirim ke Telegram")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.clearExportResult() }) {
                    Text("Tutup")
                }
            }
        )
    }
}