package com.aistudio.voicenote.cvtr.editor.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.voicenote.cvtr.data.repository.ConversionHistoryRepository
import com.aistudio.voicenote.cvtr.editor.engine.EditorAudioEngine
import com.aistudio.voicenote.cvtr.editor.engine.EditorExporter
import com.aistudio.voicenote.cvtr.editor.engine.EditorPcmDecoder
import com.aistudio.voicenote.cvtr.editor.model.AudioTrackClip
import com.aistudio.voicenote.cvtr.editor.model.EditorTimelineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class EditorViewModel(
    application: Application,
    private val historyRepository: ConversionHistoryRepository
) : AndroidViewModel(application) {

    private val _timelineState = MutableStateFlow(EditorTimelineState())
    val timelineState: StateFlow<EditorTimelineState> = _timelineState.asStateFlow()

    private val audioEngine = EditorAudioEngine(
        onPlayheadUpdated = { posMs ->
            _timelineState.update { it.copy(playheadPositionMs = posMs) }
        },
        onPlaybackFinished = {
            _timelineState.update { it.copy(isPlaying = false, playheadPositionMs = 0L) }
        }
    )

    fun addTrackFromUri(slotIndex: Int, uri: Uri, displayName: String) {
        if (slotIndex !in 0..2) return

        viewModelScope.launch {
            _timelineState.update { it.copy(isLoadingSource = true, errorMessage = null) }
            try {
                val clipId = UUID.randomUUID().toString()
                val pcmFile = File(getApplication<Application>().cacheDir, "editor_pcm_.pcm")

                val decoded = withContext(Dispatchers.IO) {
                    EditorPcmDecoder.decodeToPcm(getApplication(), uri, pcmFile)
                }

                val clip = AudioTrackClip(
                    id = clipId,
                    sourceUri = uri,
                    displayName = displayName,
                    pcmCacheFile = decoded.pcmFile,
                    durationMs = decoded.durationMs,
                    waveformPoints = decoded.waveformPoints,
                    trimEndMs = decoded.durationMs
                )

                _timelineState.update { current ->
                    val newTracks = current.tracks.toMutableList()
                    // Delete old pcm file if replacing slot
                    newTracks[slotIndex]?.pcmCacheFile?.delete()
                    newTracks[slotIndex] = clip
                    current.copy(
                        tracks = newTracks,
                        selectedTrackIndex = slotIndex,
                        isLoadingSource = false
                    )
                }
            } catch (e: Exception) {
                _timelineState.update {
                    it.copy(
                        isLoadingSource = false,
                        errorMessage = "Gagal memproses audio: "
                    )
                }
            }
        }
    }

    fun removeTrack(slotIndex: Int) {
        if (slotIndex !in 0..2) return
        stopPlayback()

        _timelineState.update { current ->
            val newTracks = current.tracks.toMutableList()
            newTracks[slotIndex]?.pcmCacheFile?.delete()
            newTracks[slotIndex] = null

            val newSelected = if (current.selectedTrackIndex == slotIndex) null else current.selectedTrackIndex
            current.copy(
                tracks = newTracks,
                selectedTrackIndex = newSelected
            )
        }
    }

    fun swapTracks(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in 0..2 || toIndex !in 0..2 || fromIndex == toIndex) return

        _timelineState.update { current ->
            val newTracks = current.tracks.toMutableList()
            val temp = newTracks[fromIndex]
            newTracks[fromIndex] = newTracks[toIndex]
            newTracks[toIndex] = temp

            val newSelected = when (current.selectedTrackIndex) {
                fromIndex -> toIndex
                toIndex -> fromIndex
                else -> current.selectedTrackIndex
            }

            current.copy(
                tracks = newTracks,
                selectedTrackIndex = newSelected
            )
        }
    }

    fun updateClipTrim(slotIndex: Int, trimStartMs: Long, trimEndMs: Long) {
        if (slotIndex !in 0..2) return
        _timelineState.update { current ->
            val track = current.tracks[slotIndex] ?: return@update current
            val clampedStart = trimStartMs.coerceIn(0L, track.durationMs)
            val clampedEnd = trimEndMs.coerceIn(clampedStart, track.durationMs)
            val updatedClip = track.copy(trimStartMs = clampedStart, trimEndMs = clampedEnd)

            val newTracks = current.tracks.toMutableList()
            newTracks[slotIndex] = updatedClip
            current.copy(tracks = newTracks)
        }
    }

    fun updateClipOffset(slotIndex: Int, startOffsetMs: Long) {
        if (slotIndex !in 0..2) return
        _timelineState.update { current ->
            val track = current.tracks[slotIndex] ?: return@update current
            val updatedClip = track.copy(startOffsetMs = startOffsetMs.coerceAtLeast(0L))

            val newTracks = current.tracks.toMutableList()
            newTracks[slotIndex] = updatedClip
            current.copy(tracks = newTracks)
        }
    }

    fun updateClipPitch(slotIndex: Int, semitones: Float) {
        if (slotIndex !in 0..2) return
        _timelineState.update { current ->
            val track = current.tracks[slotIndex] ?: return@update current
            val updatedClip = track.copy(pitchSemitones = semitones.coerceIn(-12f, 12f))

            val newTracks = current.tracks.toMutableList()
            newTracks[slotIndex] = updatedClip
            current.copy(tracks = newTracks)
        }
    }

    fun updateClipVolume(slotIndex: Int, volumeGain: Float) {
        if (slotIndex !in 0..2) return
        _timelineState.update { current ->
            val track = current.tracks[slotIndex] ?: return@update current
            val updatedClip = track.copy(volumeGain = volumeGain.coerceIn(0.0f, 2.0f))

            val newTracks = current.tracks.toMutableList()
            newTracks[slotIndex] = updatedClip
            current.copy(tracks = newTracks)
        }
    }

    fun selectTrack(slotIndex: Int?) {
        _timelineState.update { it.copy(selectedTrackIndex = slotIndex) }
    }

    fun togglePlayPause() {
        val state = _timelineState.value
        if (state.isPlaying) {
            audioEngine.stopPlayback()
            _timelineState.update { it.copy(isPlaying = false) }
        } else {
            val activeClips = state.tracks.filterNotNull()
            if (activeClips.isEmpty() || state.totalDurationMs <= 0) return

            _timelineState.update { it.copy(isPlaying = true) }
            audioEngine.startPlayback(
                coroutineScope = viewModelScope,
                tracks = activeClips,
                startPositionMs = state.playheadPositionMs,
                totalDurationMs = state.totalDurationMs
            )
        }
    }

    fun seekTo(positionMs: Long) {
        val total = _timelineState.value.totalDurationMs
        val target = positionMs.coerceIn(0L, total)
        _timelineState.update { it.copy(playheadPositionMs = target) }
        if (_timelineState.value.isPlaying) {
            togglePlayPause() // stop and restart at target
            togglePlayPause()
        }
    }

    fun stopPlayback() {
        audioEngine.stopPlayback()
        _timelineState.update { it.copy(isPlaying = false) }
    }

    fun resetTimeline() {
        stopPlayback()
        _timelineState.value.tracks.forEach { it?.pcmCacheFile?.delete() }
        _timelineState.value = EditorTimelineState()
    }

    fun exportTimeline(onSuccess: (Uri) -> Unit = {}) {
        val state = _timelineState.value
        if (!state.hasActiveTracks || state.isExporting) return

        stopPlayback()
        viewModelScope.launch {
            _timelineState.update { it.copy(isExporting = true, exportProgress = 0f, errorMessage = null) }

            val result = EditorExporter.exportTimeline(
                context = getApplication(),
                state = state,
                historyRepository = historyRepository,
                onProgress = { prog ->
                    _timelineState.update { it.copy(exportProgress = prog) }
                }
            )

            result.fold(
                onSuccess = { uri ->
                    _timelineState.update { it.copy(isExporting = false, exportProgress = 1f, exportedFileUri = uri) }
                    onSuccess(uri)
                },
                onFailure = { error ->
                    _timelineState.update {
                        it.copy(
                            isExporting = false,
                            errorMessage = "Export gagal: "
                        )
                    }
                }
            )
        }
    }

    fun clearExportResult() {
        _timelineState.update { it.copy(exportedFileUri = null) }
    }

    fun setTrackForTesting(slotIndex: Int, clip: AudioTrackClip?) {
        val newTracks = _timelineState.value.tracks.toMutableList()
        newTracks[slotIndex] = clip
        _timelineState.update { it.copy(tracks = newTracks) }
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.release()
        _timelineState.value.tracks.forEach { it?.pcmCacheFile?.delete() }
    }
}