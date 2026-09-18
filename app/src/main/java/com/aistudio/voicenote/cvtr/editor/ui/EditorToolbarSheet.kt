package com.aistudio.voicenote.cvtr.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.voicenote.cvtr.editor.model.AudioTrackClip
import com.aistudio.voicenote.cvtr.ui.theme.AccentCoral
import com.aistudio.voicenote.cvtr.ui.theme.AccentRoyalBlue
import com.aistudio.voicenote.cvtr.ui.theme.CardSurfaceWhite
import com.aistudio.voicenote.cvtr.ui.theme.DarkNavyHeadline
import com.aistudio.voicenote.cvtr.ui.theme.DeepNavyDisplay
import com.aistudio.voicenote.cvtr.ui.theme.LightSlateCaption
import com.aistudio.voicenote.cvtr.ui.theme.PastelPeachCardBg
import com.aistudio.voicenote.cvtr.ui.theme.SubtleBorder
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun EditorToolbarSheet(
    slotIndex: Int,
    clip: AudioTrackClip,
    onPitchChanged: (Float) -> Unit,
    onVolumeChanged: (Float) -> Unit,
    onSwapTrack: (toIndex: Int) -> Unit,
    onReplaceAudio: () -> Unit,
    onRemoveTrack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .border(1.dp, SubtleBorder, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
        color = CardSurfaceWhite,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Track Label & Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Track ${slotIndex + 1}: ${clip.displayName}",
                        color = DeepNavyDisplay,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = "Atur pitch, volume & posisi track",
                        color = LightSlateCaption,
                        fontSize = 12.sp
                    )
                }

                Row {
                    IconButton(
                        onClick = onRemoveTrack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Hapus Track",
                            tint = AccentCoral
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 1. Pitch Shifter Slider (-12 s.d. +12)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format(Locale.US, "Pitch: %+.1f semitones", clip.pitchSemitones),
                    color = DarkNavyHeadline,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                if (clip.pitchSemitones != 0f) {
                    IconButton(
                        onClick = { onPitchChanged(0f) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Reset Pitch",
                            tint = AccentRoyalBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Slider(
                value = clip.pitchSemitones,
                onValueChange = onPitchChanged,
                valueRange = -12f..12f,
                steps = 47,
                colors = SliderDefaults.colors(
                    thumbColor = AccentRoyalBlue,
                    activeTrackColor = AccentRoyalBlue
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 2. Volume Gain Slider (0% s.d. 200%)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = DarkNavyHeadline,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Volume: ${(clip.volumeGain * 100).roundToInt()}%",
                        color = DarkNavyHeadline,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (clip.volumeGain != 1.0f) {
                    IconButton(
                        onClick = { onVolumeChanged(1.0f) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Reset Volume",
                            tint = AccentRoyalBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Slider(
                value = clip.volumeGain,
                onValueChange = onVolumeChanged,
                valueRange = 0.0f..2.0f,
                colors = SliderDefaults.colors(
                    thumbColor = AccentRoyalBlue,
                    activeTrackColor = AccentRoyalBlue
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Move Track & Replace Audio Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (targetIndex in 0..2) {
                    if (targetIndex != slotIndex) {
                        OutlinedButton(
                            onClick = { onSwapTrack(targetIndex) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Ke Track ${targetIndex + 1}",
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                }

                FilledTonalButton(
                    onClick = onReplaceAudio,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = PastelPeachCardBg,
                        contentColor = DeepNavyDisplay
                    )
                ) {
                    Text(
                        text = "Ganti Audio",
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}