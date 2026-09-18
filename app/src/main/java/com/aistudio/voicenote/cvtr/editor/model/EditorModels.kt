package com.aistudio.voicenote.cvtr.editor.model

import android.net.Uri
import java.io.File
import java.util.UUID

data class AudioTrackClip(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: Uri,
    val displayName: String,
    val pcmCacheFile: File,
    val durationMs: Long,
    val waveformPoints: List<Float> = emptyList(),
    val startOffsetMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = durationMs,
    val pitchSemitones: Float = 0f,
    val volumeGain: Float = 1.0f
) {
    val activeDurationMs: Long 
        get() = (trimEndMs - trimStartMs).coerceAtLeast(0L)
        
    val timelineEndMs: Long 
        get() = startOffsetMs + activeDurationMs
}

data class EditorTimelineState(
    val tracks: List<AudioTrackClip?> = listOf(null, null, null),
    val selectedTrackIndex: Int? = null,
    val playheadPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isLoadingSource: Boolean = false,
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val exportedFileUri: Uri? = null,
    val errorMessage: String? = null
) {
    val totalDurationMs: Long 
        get() = tracks.filterNotNull().maxOfOrNull { it.timelineEndMs } ?: 0L
        
    val hasActiveTracks: Boolean 
        get() = tracks.any { it != null }
}