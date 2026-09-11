package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio.PlaybackState
import com.example.data.local.ConversionHistory
import com.example.ui.components.WaveformVisualizer
import com.example.ui.theme.AccentCoral
import com.example.ui.theme.AccentRoyalBlue
import com.example.ui.theme.AppCanvasBackground
import com.example.ui.theme.CardSurfaceWhite
import com.example.ui.theme.DeepNavyDisplay
import com.example.ui.theme.PastelMintCardBg
import com.example.ui.theme.PastelMintText
import com.example.ui.theme.PastelPeachCardBg
import com.example.ui.theme.PastelPeriwinkleCardBg
import com.example.ui.theme.SubtitleSlate
import com.example.ui.theme.SubtleBorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, modifier: Modifier = Modifier) {
    val historyItems by viewModel.historyItems.collectAsStateWithLifecycle()
    val currentlyPlayingId by viewModel.currentlyPlayingId.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ConversionHistory?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.pausePlayback()
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Hapus voice note?") },
            text = { Text("${item.outputFileName} akan dihapus dari penyimpanan dan riwayat.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteItem(item); pendingDelete = null }) {
                    Text("Hapus", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Batal") } }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppCanvasBackground)
    ) {
        if (historyItems.isEmpty()) {
            EmptyHistoryState(Modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    top = 24.dp,
                    end = 20.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    HistoryHeader(total = historyItems.size)
                }
                items(historyItems, key = { it.id }) { item ->
                    HistoryItemCard(
                        item = item,
                        isCurrent = currentlyPlayingId == item.id,
                        playbackState = playbackState,
                        onPlayPause = { viewModel.playPause(item) },
                        onShare = { viewModel.shareItem(item) },
                        onDelete = { pendingDelete = item }
                    )
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun HistoryHeader(total: Int) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Riwayat voice note",
                style = MaterialTheme.typography.headlineLarge,
                color = DeepNavyDisplay,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = PastelPeriwinkleCardBg
            ) {
                Text(
                    text = "$total file",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentRoyalBlue
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            "Putar kembali, kirim ulang, atau hapus voice note yang tersimpan.",
            style = MaterialTheme.typography.bodyMedium,
            color = SubtitleSlate
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HistoryItemCard(
    item: ConversionHistory,
    isCurrent: Boolean,
    playbackState: PlaybackState,
    onPlayPause: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val background by animateColorAsState(
                targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                    PastelPeachCardBg else AppCanvasBackground,
                label = "history-delete-background"
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(26.dp))
                    .background(background)
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Default.Delete, "Hapus", tint = MaterialTheme.colorScheme.error)
            }
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(2.dp, RoundedCornerShape(26.dp)),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(PastelPeachCardBg, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = AccentCoral)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.outputFileName,
                            style = MaterialTheme.typography.titleMedium,
                            color = DeepNavyDisplay,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            item.originalFileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = SubtitleSlate,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    HistoryStatusBadge(sent = item.sentTo != null)
                }

                val date = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(item.createdAt))
                val duration = "%02d:%02d".format(item.durationSeconds / 60, item.durationSeconds % 60)
                val size = if (item.fileSizeBytes < 1024 * 1024) "%.1f KB".format(item.fileSizeBytes / 1024f)
                else "%.1f MB".format(item.fileSizeBytes / (1024f * 1024f))
                Text(
                    "$duration  •  $size  •  $date",
                    style = MaterialTheme.typography.labelSmall,
                    color = SubtitleSlate,
                    modifier = Modifier.padding(top = 14.dp)
                )
                item.sentTo?.let {
                    Text(
                        "Dibuka di Telegram untuk $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = PastelMintText,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .size(42.dp)
                            .background(PastelPeriwinkleCardBg, CircleShape)
                    ) {
                        Icon(
                            if (isCurrent && playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isCurrent && playbackState.isPlaying) "Pause" else "Putar",
                            tint = AccentRoyalBlue
                        )
                    }
                    WaveformVisualizer(
                        waveform = parseWaveform(item.waveform),
                        progress = if (isCurrent) playbackState.progress else 0f,
                        modifier = Modifier.weight(1f),
                        activeColor = AccentRoyalBlue,
                        inactiveColor = SubtleBorder,
                        playheadColor = AccentCoral,
                        height = 34.dp,
                        showPlayhead = isCurrent,
                        isInteractive = false
                    )
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier
                            .size(42.dp)
                            .background(PastelMintCardBg, CircleShape)
                    ) {
                        Icon(Icons.Default.Share, "Kirim ulang", tint = PastelMintText)
                    }
                }
                if (isCurrent) {
                    playbackState.errorMessage?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryStatusBadge(sent: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (sent) PastelMintCardBg else PastelPeriwinkleCardBg
    ) {
        Text(
            text = if (sent) "Terkirim" else "Tersimpan",
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (sent) PastelMintText else AccentRoyalBlue
        )
    }
}

private fun parseWaveform(value: String): List<Int> = value.removeSurrounding("[", "]")
    .split(',').mapNotNull { it.trim().toIntOrNull() }

@Composable
private fun EmptyHistoryState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .background(PastelMintCardBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(46.dp),
                tint = PastelMintText
            )
        }
        Spacer(Modifier.size(18.dp))
        Text(
            "Belum ada konversi",
            style = MaterialTheme.typography.headlineMedium,
            color = DeepNavyDisplay
        )
        Spacer(Modifier.size(6.dp))
        Text(
            "Mulai convert voice note pertama Anda.",
            style = MaterialTheme.typography.bodyMedium,
            color = SubtitleSlate
        )
    }
}
