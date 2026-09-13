package com.aistudio.voicenote.cvtr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.aistudio.voicenote.cvtr.ui.theme.AccentCoral
import com.aistudio.voicenote.cvtr.ui.theme.AccentRoyalBlue
import com.aistudio.voicenote.cvtr.ui.theme.CardSurfaceWhite
import com.aistudio.voicenote.cvtr.ui.theme.DeepNavyDisplay
import com.aistudio.voicenote.cvtr.ui.theme.MutedInputBackground
import com.aistudio.voicenote.cvtr.ui.theme.SubtitleSlate

@Composable
internal fun BatchQueuePanel(
    items: List<BatchQueueItem>,
    message: String?,
    onRetry: (BatchQueueItem) -> Unit,
    onCancel: (BatchQueueItem) -> Unit,
    onDismissMessage: () -> Unit
) {
    SoftCard(containerColor = CardSurfaceWhite) {
        Text(
            text = "Antrean batch",
            style = MaterialTheme.typography.titleMedium,
            color = DeepNavyDisplay
        )
        message?.let {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = it,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentRoyalBlue
                )
                TextButton(onClick = onDismissMessage) { Text("Tutup") }
            }
        }
        items.forEachIndexed { index, item ->
            if (index > 0 || message != null) Spacer(Modifier.height(10.dp))
            BatchQueueRow(item, onRetry, onCancel)
        }
    }
}

@Composable
private fun BatchQueueRow(
    item: BatchQueueItem,
    onRetry: (BatchQueueItem) -> Unit,
    onCancel: (BatchQueueItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = item.sourceFileName,
            style = MaterialTheme.typography.bodyMedium,
            color = DeepNavyDisplay,
            maxLines = 1
        )
        Text(
            text = "→ ${item.outputFileName}",
            style = MaterialTheme.typography.bodySmall,
            color = SubtitleSlate,
            maxLines = 1
        )
        Spacer(Modifier.height(6.dp))
        when (item.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.RUNNING -> {
                LinearProgressIndicator(
                    progress = { item.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = AccentCoral,
                    trackColor = MutedInputBackground
                )
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (item.state == WorkInfo.State.BLOCKED) {
                            "Menunggu ruang penyimpanan"
                        } else {
                            "Mengonversi ${item.progress}%"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = SubtitleSlate
                    )
                    TextButton(onClick = { onCancel(item) }) { Text("Batal") }
                }
            }
            WorkInfo.State.SUCCEEDED -> Text(
                text = "Selesai",
                style = MaterialTheme.typography.bodySmall,
                color = AccentRoyalBlue
            )
            WorkInfo.State.FAILED,
            WorkInfo.State.CANCELLED -> {
                Text(
                    text = item.errorMessage ?: if (item.state == WorkInfo.State.CANCELLED) {
                        "Dibatalkan"
                    } else {
                        "Konversi gagal"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { onRetry(item) }) {
                    Text("Coba lagi")
                }
            }
        }
    }
}
