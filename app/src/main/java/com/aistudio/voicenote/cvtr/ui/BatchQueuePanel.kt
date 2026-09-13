package com.aistudio.voicenote.cvtr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.aistudio.voicenote.cvtr.ui.theme.AccentCoral
import com.aistudio.voicenote.cvtr.ui.theme.AccentRoyalBlue
import com.aistudio.voicenote.cvtr.ui.theme.CardSurfaceWhite
import com.aistudio.voicenote.cvtr.ui.theme.DeepNavyDisplay
import com.aistudio.voicenote.cvtr.ui.theme.MutedInputBackground
import com.aistudio.voicenote.cvtr.ui.theme.SubtitleSlate

private const val MAX_VISIBLE_BATCH_ITEMS = 4

@Composable
internal fun BatchQueuePanel(
    items: List<BatchQueueItem>,
    message: String?,
    onRetry: (BatchQueueItem) -> Unit,
    onCancel: (BatchQueueItem) -> Unit,
    onDismissItem: (BatchQueueItem) -> Unit,
    onDismissAll: () -> Unit,
    onDismissMessage: () -> Unit
) {
    SoftCard(containerColor = CardSurfaceWhite) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Antrean batch",
                    style = MaterialTheme.typography.titleMedium,
                    color = DeepNavyDisplay
                )
                Text(
                    text = batchSummary(items),
                    style = MaterialTheme.typography.bodySmall,
                    color = SubtitleSlate
                )
            }
            TextButton(onClick = onDismissAll) { Text("Tutup semua") }
        }

        message?.let {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MutedInputBackground, RoundedCornerShape(10.dp))
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = it,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentRoyalBlue
                )
                IconButton(onClick = onDismissMessage) {
                    Icon(Icons.Rounded.Close, contentDescription = "Tutup pesan")
                }
            }
        }

        items.take(MAX_VISIBLE_BATCH_ITEMS).forEach { item ->
            Spacer(Modifier.height(8.dp))
            BatchQueueRow(item, onRetry, onCancel, onDismissItem)
        }
        if (items.size > MAX_VISIBLE_BATCH_ITEMS) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "+${items.size - MAX_VISIBLE_BATCH_ITEMS} item lain diproses berurutan",
                style = MaterialTheme.typography.bodySmall,
                color = SubtitleSlate
            )
        }
    }
}

@Composable
private fun BatchQueueRow(
    item: BatchQueueItem,
    onRetry: (BatchQueueItem) -> Unit,
    onCancel: (BatchQueueItem) -> Unit,
    onDismiss: (BatchQueueItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MutedInputBackground, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.sourceFileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DeepNavyDisplay,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "→ ${item.outputFileName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SubtitleSlate,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { onDismiss(item) }) {
                Icon(Icons.Rounded.Close, contentDescription = "Tutup ${item.sourceFileName}")
            }
        }

        when (item.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.RUNNING -> {
                LinearProgressIndicator(
                    progress = { item.progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    color = AccentCoral,
                    trackColor = CardSurfaceWhite
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (item.state) {
                            WorkInfo.State.BLOCKED -> "Menunggu giliran atau ruang penyimpanan"
                            WorkInfo.State.ENQUEUED -> "Menunggu giliran"
                            else -> "Mengonversi ${item.progress}%"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = SubtitleSlate
                    )
                    TextButton(onClick = { onCancel(item) }) { Text("Batal semua") }
                }
            }
            WorkInfo.State.SUCCEEDED -> StatusText("Selesai", AccentRoyalBlue)
            WorkInfo.State.FAILED,
            WorkInfo.State.CANCELLED -> {
                Text(
                    text = item.errorMessage ?: if (item.state == WorkInfo.State.CANCELLED) {
                        "Dibatalkan"
                    } else {
                        "Konversi gagal"
                    },
                    modifier = Modifier.padding(end = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                if (item.canRetry) {
                    TextButton(onClick = { onRetry(item) }) { Text("Coba lagi") }
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(1.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

private fun batchSummary(items: List<BatchQueueItem>): String {
    val active = items.count { !it.isTerminal }
    val failed = items.count { it.state == WorkInfo.State.FAILED }
    val completed = items.count { it.state == WorkInfo.State.SUCCEEDED }
    return listOfNotNull(
        active.takeIf { it > 0 }?.let { "$it aktif" },
        completed.takeIf { it > 0 }?.let { "$it selesai" },
        failed.takeIf { it > 0 }?.let { "$it gagal" }
    ).joinToString(" · ").ifBlank { "Tidak ada item aktif" }
}
