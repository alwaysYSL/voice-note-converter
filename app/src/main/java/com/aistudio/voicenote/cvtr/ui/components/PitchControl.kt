package com.aistudio.voicenote.cvtr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.voicenote.cvtr.ui.theme.AccentCoral
import com.aistudio.voicenote.cvtr.ui.theme.AccentRoyalBlue
import com.aistudio.voicenote.cvtr.ui.theme.DeepNavyDisplay
import com.aistudio.voicenote.cvtr.ui.theme.SubtitleSlate
import com.aistudio.voicenote.cvtr.ui.theme.SubtleBorder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pitch adjustment slider with a zero-centered range of four semitones.
 */
@Composable
fun PitchControl(
    pitchSemitones: Float,
    onPitchChange: (Float) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🎵  Pitch",
                style = MaterialTheme.typography.labelLarge,
                color = DeepNavyDisplay
            )
            Text(
                text = formatPitch(pitchSemitones),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (pitchSemitones == 0f) SubtitleSlate else AccentRoyalBlue
            )
        }

        Slider(
            value = pitchSemitones.coerceIn(-4f, 4f),
            onValueChange = { rawValue ->
                val rounded = (rawValue * 10f).roundToInt() / 10f
                onPitchChange(if (abs(rounded) < 0.15f) 0f else rounded)
            },
            valueRange = -4f..4f,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = AccentCoral,
                activeTrackColor = AccentCoral,
                inactiveTrackColor = SubtleBorder
            )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("-4 st", style = MaterialTheme.typography.labelSmall, color = SubtitleSlate)
            Text("0", style = MaterialTheme.typography.labelSmall, color = SubtitleSlate)
            Text("+4 st", style = MaterialTheme.typography.labelSmall, color = SubtitleSlate)
        }
    }
}

internal fun formatPitch(semitones: Float): String = when {
    semitones == 0f -> "0 st"
    semitones > 0f -> String.format(Locale.US, "+%.1f st", semitones)
    else -> String.format(Locale.US, "%.1f st", semitones)
}
