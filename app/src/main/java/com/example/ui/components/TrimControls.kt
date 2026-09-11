package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AccentCoral
import com.example.ui.theme.AccentRoyalBlue
import com.example.ui.theme.DeepNavyDisplay
import com.example.ui.theme.PastelPeachCardBg
import com.example.ui.theme.SubtitleSlate

data class TrimState(
    val startMs: Long = 0,
    val endMs: Long = 0,
    val totalDurationMs: Long = 0,
    val isActive: Boolean = false
) {
    val startFraction: Float
        get() = if (totalDurationMs > 0) startMs.toFloat() / totalDurationMs else 0f
    val endFraction: Float
        get() = if (totalDurationMs > 0) endMs.toFloat() / totalDurationMs else 1f
    val trimmedDurationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0)
    val trimRange get() = startFraction..endFraction
}

private fun time(ms: Long) = "%02d:%02d".format(ms / 60_000, (ms / 1_000) % 60)

@Composable
fun TrimControls(
    trimState: TrimState,
    onToggleTrim: () -> Unit,
    onPreviewTrim: () -> Unit,
    onResetTrim: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        FilterChip(
            selected = trimState.isActive,
            onClick = onToggleTrim,
            label = { Text("Potong bagian audio") },
            leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = PastelPeachCardBg,
                selectedLabelColor = DeepNavyDisplay,
                selectedLeadingIconColor = AccentCoral,
                iconColor = AccentRoyalBlue,
                labelColor = DeepNavyDisplay
            )
        )
        if (trimState.isActive) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${time(trimState.startMs)} sampai ${time(trimState.endMs)}", color = DeepNavyDisplay)
                Text("Durasi ${time(trimState.trimmedDurationMs)}", color = SubtitleSlate)
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onPreviewTrim, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Preview")
                }
                OutlinedButton(onClick = onResetTrim, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("Reset")
                }
            }
        }
    }
}
